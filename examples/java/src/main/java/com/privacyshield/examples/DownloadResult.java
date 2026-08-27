package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Example 04 — download the results of one job.
 *
 * <p><b>What it does</b><br>
 * Requests the finished documents of a job, prints the per-document report from
 * the X-Documents-Metadata header, and saves the body to ./downloads.
 *
 * <p>Two outcomes are normal: 200 with a PDF or ZIP, and 204 when nothing is
 * ready yet. Both carry the metadata header.
 *
 * <p>Downloading marks the returned documents as downloaded. They will not be
 * sent again, so this example writes the file before printing anything else and
 * never overwrites an existing one.
 *
 * <p><b>What you need configured</b>
 * <pre>
 *   PRIVACYSHIELD_ACCOUNT_ID   your account identifier
 *   PRIVACYSHIELD_API_KEY      your account's API key
 *   PRIVACYSHIELD_JOB_ID       the job to download, unless passed as an argument
 *   PRIVACYSHIELD_BASE_URL     optional, defaults to production
 * </pre>
 *
 * <p><b>How to run</b>
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.DownloadResult \
 *       -Dexec.args="12345"
 * </pre>
 */
public final class DownloadResult {

    private DownloadResult() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (PrivacyShieldException error) {
            System.exit(PrivacyShieldClient.reportFailure(error));
        }
    }

    private static int run(String[] args) {
        long jobId = args.length > 0 ? Long.parseLong(args[0]) : Config.jobId();
        String baseUrl = Config.baseUrl();
        Path outputDir = Config.outputDir();
        String accessToken = PrivacyShieldClient.authenticateOnce(baseUrl);

        System.out.println("Requesting documents for job " + jobId + " ...");
        HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jobs/" + jobId + "/documents/download"))
                .timeout(Duration.ofSeconds(300))
                .header("Access-Token", accessToken)
                .GET()
                .build());

        // Read the metadata before anything else: it is present on 204 as well,
        // and a 410 here means the job passed the 5-day retention window.
        List<JsonNode> metadata = PrivacyShieldClient.parseDocumentsMetadata(
                response.headers().firstValue("X-Documents-Metadata").orElse(null));
        PrivacyShieldClient.raiseForStatus(response);
        System.out.println();
        printMetadata(jobId, metadata);

        List<JsonNode> pending = PrivacyShieldClient.documentsInProgress(metadata);

        if (response.statusCode() == 204) {
            System.out.println("HTTP 204 - nothing is ready to download yet. This is not an error.");
            if (!pending.isEmpty()) {
                System.out.println(pending.size()
                        + " document(s) still pending or processing. Try again later.");
            } else if (!metadata.isEmpty()) {
                System.out.println("Every document is in a terminal state, so there is nothing "
                        + "left to collect from this job.");
            }
            return 0;
        }

        String filename = PrivacyShieldClient.filenameFromContentDisposition(
                response.headers().firstValue("Content-Disposition").orElse(null),
                "job_" + jobId + "_documents");
        Path target;
        try {
            Files.createDirectories(outputDir);
            target = PrivacyShieldClient.uniquePath(outputDir, filename);
            Files.write(target, response.body());
        } catch (IOException error) {
            throw new PrivacyShieldException("Could not save the download: " + error.getMessage(),
                    error);
        }

        System.out.println("Saved " + response.headers().firstValue("Content-Type").orElse(null)
                + " to " + target + " ("
                + PrivacyShieldClient.formatBytes(response.body().length) + ")");
        System.out.println("Those documents are now marked as downloaded and will not be sent again.");
        if (!pending.isEmpty()) {
            System.out.println(pending.size() + " document(s) in this job are still in progress - "
                    + "run this example again later to collect them.");
        }
        return 0;
    }

    /**
     * Prints the per-document report. This is the only place the API tells you
     * what happened to each document, and it arrives on 204 responses too.
     */
    private static void printMetadata(long jobId, List<JsonNode> metadata) {
        if (metadata.isEmpty()) {
            System.out.println("The response carried no X-Documents-Metadata header.");
            return;
        }
        System.out.println("Documents in job " + jobId + ":");
        System.out.printf(Locale.ROOT, "  %-30s %-11s %5s  COMPLETED%n",
                "DOCUMENT", "STATUS", "PAGES");
        for (JsonNode document : metadata) {
            JsonNode pages = document.get("pageCount");
            JsonNode completed = document.get("completedAt");
            System.out.printf(Locale.ROOT, "  %-30s %-11s %5s  %s%n",
                    document.path("documentName").asText(),
                    document.path("status").asText(),
                    pages == null || pages.isNull() ? "-" : pages.asText(),
                    completed == null || completed.isNull() ? "-" : completed.asText());
        }
        System.out.println();
    }
}
