package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.BadRequestException;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.Download;
import com.privacyshield.examples.PrivacyShieldClient.GoneException;
import com.privacyshield.examples.PrivacyShieldClient.JobVanishedException;
import com.privacyshield.examples.PrivacyShieldClient.PollTimeoutException;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Example 08 — process a whole folder, with retries and a summary.
 *
 * <p><b>What it does</b><br>
 * Sends every supported file in a folder as its own job, waits for all of them,
 * downloads each result, and prints a summary. One job per file rather than one
 * job for everything, so a single bad file cannot fail the batch and each result
 * can be traced back to its source.
 *
 * <p>Failure handling is per file:
 * <ul>
 *   <li>400 (unsupported type, bad name, quota) — recorded, never retried
 *   <li>401 — token refreshed and the request retried once, inside the client
 *   <li>403/404/410 — recorded as configuration or retention problems
 *   <li>5xx and network errors — retried with exponential backoff
 * </ul>
 *
 * <p>Exits 1 if any file failed, so it can be used from a scheduler.
 *
 * <p><b>What you need configured</b>
 * <pre>
 *   PRIVACYSHIELD_ACCOUNT_ID   your account identifier
 *   PRIVACYSHIELD_API_KEY      your account's API key
 *   PRIVACYSHIELD_TEMPLATE_ID      the template to send the documents to
 *   PRIVACYSHIELD_BASE_URL     optional, defaults to production
 * </pre>
 *
 * <p><b>How to run</b>
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.BatchProcessing \
 *       -Dexec.args="./inbox 1800"
 * </pre>
 */
public final class BatchProcessing {

    private static final double DEFAULT_TIMEOUT_SECONDS = 30 * 60;

    private BatchProcessing() {
    }

    /** One file and whatever happened to it. */
    private static final class Item {
        final Path path;
        Long jobId;
        int documents;
        Path savedTo;
        long sizeBytes;
        String error;

        Item(Path path) {
            this.path = path;
        }

        String name() {
            return path.getFileName().toString();
        }
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (PrivacyShieldException error) {
            System.exit(PrivacyShieldClient.reportFailure(error));
        }
    }

    private static int run(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: BatchProcessing <folder> [timeout-seconds]");
            return 1;
        }

        Path folder = Path.of(args[0]);
        double timeout = args.length > 1 ? Double.parseDouble(args[1]) : DEFAULT_TIMEOUT_SECONDS;
        List<Path> paths = discover(folder);

        String baseUrl = Config.baseUrl();
        long templateId = Config.templateId();
        Path outputDir = Config.outputDir();

        System.out.println("PrivacyShield batch processing");
        System.out.println("  folder   : " + folder);
        System.out.println("  template : " + templateId);
        System.out.println("  found    : " + paths.size() + " supported file(s)");
        System.out.println();

        if (paths.isEmpty()) {
            System.out.println("Nothing to do. Supported extensions: "
                    + String.join(", ", PrivacyShieldClient.SUPPORTED_EXTENSIONS));
            return 0;
        }

        List<Item> items = new ArrayList<>();
        for (Path path : paths) {
            items.add(new Item(path));
        }

        long deadline = System.nanoTime() + (long) (timeout * 1_000_000_000L);
        try (PrivacyShieldClient client =
                     new PrivacyShieldClient(Config.accountId(), Config.apiKey(), baseUrl)) {
            uploadAll(client, templateId, items);
            waitAll(client, items, deadline);
            downloadAll(client, items, outputDir);
        }

        return summarize(paths.size(), items, outputDir);
    }

    /** Returns the supported files of a folder, sorted, non-recursive. */
    private static List<Path> discover(Path folder) {
        if (!Files.isDirectory(folder)) {
            throw new PrivacyShieldException("Not a folder: " + folder);
        }
        try (Stream<Path> entries = Files.list(folder)) {
            List<Path> found = new ArrayList<>();
            entries.filter(Files::isRegularFile)
                    .filter(path -> PrivacyShieldClient.isSupportedFile(
                            path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(found::add);
            return found;
        } catch (IOException error) {
            throw new PrivacyShieldException("Could not list " + folder + ": "
                    + error.getMessage(), error);
        }
    }

    /** Turns an exception into one line, with the reason it will not be retried. */
    private static String describe(PrivacyShieldException error) {
        if (error instanceof BadRequestException) {
            return "rejected: " + error.getApiMessage() + " (400, not retried)";
        }
        if (error instanceof GoneException) {
            return "gone: " + error.getApiMessage() + " (410, past the retention window)";
        }
        if (error instanceof PollTimeoutException) {
            return "still processing when the timeout elapsed (collect it later)";
        }
        if (error instanceof JobVanishedException) {
            return "disappeared from the job listing (failed completely or was deleted)";
        }
        if (error.getStatusCode() != null) {
            return "HTTP " + error.getStatusCode() + ": " + error.getApiMessage();
        }
        return error.getMessage();
    }

    private static void uploadAll(PrivacyShieldClient client, long templateId, List<Item> items) {
        System.out.println("Uploading ...");
        int index = 0;
        for (Item item : items) {
            index++;
            String label = String.format(Locale.ROOT, "  [%d/%d] %-30s",
                    index, items.size(), item.name());
            try {
                JsonNode body = client.uploadDocuments(templateId, List.of(item.path));
                item.jobId = body.get("job").asLong();
                item.documents = body.get("docs").asInt();
                System.out.println(label + " -> job " + item.jobId + " (" + item.documents + " doc)");
            } catch (PrivacyShieldException error) {
                item.error = describe(error);
                System.out.println(label + " failed: " + item.error);
            }
        }
    }

    private static void waitAll(PrivacyShieldClient client, List<Item> items, long deadline) {
        List<Item> pending = new ArrayList<>();
        for (Item item : items) {
            if (item.jobId != null) {
                pending.add(item);
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        System.out.println("Waiting for " + pending.size() + " job(s) ...");
        for (Item item : pending) {
            // A single shared deadline: the batch as a whole gets the timeout,
            // not each job, so a slow first job cannot push the last one past it.
            double remaining = (deadline - System.nanoTime()) / 1_000_000_000.0;
            if (remaining <= 0) {
                item.error = "not waited for: the batch timeout had already elapsed";
                System.out.println("  job " + item.jobId + " skipped: batch timeout elapsed");
                continue;
            }
            long startedAt = System.nanoTime();
            try {
                JsonNode job = client.waitForJob(item.jobId, remaining, null);
                System.out.printf(Locale.ROOT, "  job %d %s after %.0fs%n",
                        item.jobId, job.get("status").asText(),
                        (System.nanoTime() - startedAt) / 1_000_000_000.0);
            } catch (PrivacyShieldException error) {
                item.error = describe(error);
                System.out.println("  job " + item.jobId + " failed: " + item.error);
            }
        }
    }

    private static void downloadAll(PrivacyShieldClient client, List<Item> items, Path outputDir) {
        List<Item> ready = new ArrayList<>();
        for (Item item : items) {
            if (item.jobId != null && item.error == null) {
                ready.add(item);
            }
        }
        if (ready.isEmpty()) {
            return;
        }

        System.out.println("Downloading ...");
        for (Item item : ready) {
            try {
                Download download = client.downloadJobDocuments(item.jobId, outputDir);
                if (download.isEmpty()) {
                    // 204 after a terminal status means every document errored.
                    StringBuilder statuses = new StringBuilder();
                    for (JsonNode document : download.metadata) {
                        if (statuses.length() > 0) {
                            statuses.append(", ");
                        }
                        statuses.append(document.path("status").asText());
                    }
                    item.error = "nothing to download (document status: "
                            + (statuses.length() == 0 ? "unknown" : statuses) + ")";
                    System.out.println("  job " + item.jobId + " -> nothing to download");
                    continue;
                }
                item.savedTo = download.path;
                item.sizeBytes = download.sizeBytes;
                System.out.println("  job " + item.jobId + " -> " + download.path + " ("
                        + PrivacyShieldClient.formatBytes(download.sizeBytes) + ")");
            } catch (PrivacyShieldException error) {
                item.error = describe(error);
                System.out.println("  job " + item.jobId + " failed: " + item.error);
            }
        }
    }

    private static int summarize(int found, List<Item> items, Path outputDir) {
        int uploaded = 0;
        int downloaded = 0;
        List<Item> failed = new ArrayList<>();
        for (Item item : items) {
            if (item.jobId != null) {
                uploaded++;
            }
            if (item.savedTo != null) {
                downloaded++;
            }
            if (item.error != null) {
                failed.add(item);
            }
        }

        System.out.println();
        System.out.println("Summary");
        System.out.println("  files found : " + found);
        System.out.println("  uploaded    : " + uploaded);
        System.out.println("  downloaded  : " + downloaded);
        System.out.println("  failed      : " + failed.size());
        for (Item item : failed) {
            System.out.println("      " + item.name() + ": " + item.error);
        }

        if (downloaded > 0) {
            System.out.println();
            System.out.println("Results are in " + outputDir + "/ and are marked as downloaded.");
        }
        return failed.isEmpty() ? 0 : 1;
    }
}
