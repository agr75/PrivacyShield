package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.Download;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Example 05 — the complete flow, end to end.
 *
 * <p><b>What it does</b><br>
 * Authenticate, upload, wait for the job to finish, download the results. This
 * is the example to read first if you are integrating PrivacyShield.
 *
 * <p>It uses {@link PrivacyShieldClient}, which handles the parts you would
 * otherwise have to write yourself:
 *
 * <ul>
 *   <li>the job it downloads is the one the upload returned, not a constant
 *   <li>polling with exponential backoff and a global timeout
 *   <li>the access token renews itself during the wait, so a job that outlives
 *       the 30-minute token lifetime still works
 *   <li>401 retried once, 5xx retried with backoff, 4xx not retried at all
 *   <li>204 treated as "not ready yet" rather than an error
 * </ul>
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
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.EndToEnd \
 *       -Dexec.args="contract.pdf"
 * </pre>
 */
public final class EndToEnd {

    private static final double POLL_TIMEOUT_SECONDS = 15 * 60;

    private EndToEnd() {
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
            System.err.println("Usage: EndToEnd <file> [more-files ...]");
            System.err.println("Supported extensions: "
                    + String.join(", ", PrivacyShieldClient.SUPPORTED_EXTENSIONS));
            return 1;
        }

        List<Path> files = new ArrayList<>();
        for (String argument : args) {
            Path file = Path.of(argument);
            if (!Files.isRegularFile(file)) {
                System.err.println("File not found: " + file);
                return 1;
            }
            if (!PrivacyShieldClient.isSupportedFile(file.getFileName().toString())) {
                System.err.println("Unsupported file type: " + file);
                System.err.println("Supported extensions: "
                        + String.join(", ", PrivacyShieldClient.SUPPORTED_EXTENSIONS));
                return 1;
            }
            files.add(file);
        }

        String baseUrl = Config.baseUrl();
        long accountId = Config.accountId();
        long templateId = Config.templateId();
        Path outputDir = Config.outputDir();
        final long startedAt = System.nanoTime();

        System.out.println("PrivacyShield end-to-end example");
        System.out.println("  base URL : " + baseUrl);
        System.out.println("  account  : " + accountId);
        System.out.println("  template : " + templateId);
        System.out.println("  files    : " + files.size());
        System.out.println();

        JsonNode job;
        long jobId;
        try (PrivacyShieldClient client =
                     new PrivacyShieldClient(accountId, Config.apiKey(), baseUrl)) {

            System.out.println("1) Authenticating ...");
            client.authenticate();
            System.out.println("   Access token obtained.");

            System.out.println("2) Uploading " + files.size() + " file(s) ...");
            JsonNode upload = client.uploadDocuments(templateId, files);
            jobId = upload.get("job").asLong();
            System.out.println("   Job " + jobId + " created, " + upload.get("docs").asInt()
                    + " document(s) accepted.");

            System.out.printf(Locale.ROOT, "3) Waiting for job %d (timeout %.0fs) ...%n",
                    jobId, POLL_TIMEOUT_SECONDS);

            job = client.waitForJob(jobId, POLL_TIMEOUT_SECONDS, (polled, sleepFor) -> {
                double elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0;
                String suffix = sleepFor <= 0
                        ? ""
                        : String.format(Locale.ROOT, "  - next check in %.0fs", sleepFor);
                // stripTrailing: the status column is padded, and the terminal
                // status line has no suffix to fill it.
                System.out.println(String.format(Locale.ROOT, "   [%4.0fs] %-12s%s",
                        elapsed, polled.get("status").asText(), suffix).stripTrailing());
            });

            System.out.println("4) Downloading results ...");
            Download download = client.downloadJobDocuments(jobId, outputDir);
            printDocuments(download.metadata);

            if (download.isEmpty()) {
                // Reachable: a job whose documents all failed reports 'completed'
                // via its other documents, or everything was already collected.
                System.out.println("   HTTP 204 - the server had nothing to send.");
            } else {
                System.out.println("   Saved " + download.contentType + " to " + download.path
                        + " (" + PrivacyShieldClient.formatBytes(download.sizeBytes) + ")");
                System.out.println("   Those documents are now marked as downloaded.");
            }

            List<JsonNode> pending = PrivacyShieldClient.documentsInProgress(download.metadata);
            if (!pending.isEmpty()) {
                System.out.println("   " + pending.size() + " document(s) have not finished yet - "
                        + "run DownloadResult later to collect them.");
            }
        }

        double elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.println();
        System.out.printf(Locale.ROOT, "Done in %.0fs. Job %d finished with status '%s'.%n",
                elapsed, jobId, job.get("status").asText());
        return 0;
    }

    private static void printDocuments(List<JsonNode> metadata) {
        if (metadata.isEmpty()) {
            return;
        }
        System.out.printf(Locale.ROOT, "   %-30s %-11s %5s  COMPLETED%n",
                "DOCUMENT", "STATUS", "PAGES");
        for (JsonNode document : metadata) {
            JsonNode pages = document.get("pageCount");
            JsonNode completed = document.get("completedAt");
            System.out.printf(Locale.ROOT, "   %-30s %-11s %5s  %s%n",
                    document.path("documentName").asText(),
                    document.path("status").asText(),
                    pages == null || pages.isNull() ? "-" : pages.asText(),
                    completed == null || completed.isNull() ? "-" : completed.asText());
        }
    }
}
