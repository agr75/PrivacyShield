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
 * Example 07 — drain everything pending for the account.
 *
 * <p><b>What it does</b><br>
 * Calls the account-wide download repeatedly until it answers 204, saving each
 * batch to ./downloads.
 *
 * <p>This endpoint returns at most 10 documents per call, from any job, and
 * always as a ZIP. Documents are marked as downloaded atomically before the
 * response is sent, so each call returns the next batch and several workers can
 * drain the same queue without getting duplicates.
 *
 * <p>Use it as a safety net: it collects results nobody claimed, which is what
 * protects you from the 5-day retention window.
 *
 * <p><b>What you need configured</b>
 * <pre>
 *   PRIVACYSHIELD_ACCOUNT_ID   your account identifier
 *   PRIVACYSHIELD_API_KEY      your account's API key
 *   PRIVACYSHIELD_BASE_URL     optional, defaults to production
 * </pre>
 *
 * <p><b>How to run</b>
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.DownloadPending \
 *       -Dexec.args="5"
 * </pre>
 */
public final class DownloadPending {

    private static final int DEFAULT_MAX_BATCHES = 5;
    private static final int DOCUMENTS_PER_CALL = 10;

    private DownloadPending() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (PrivacyShieldException error) {
            System.exit(PrivacyShieldClient.reportFailure(error));
        }
    }

    private static int run(String[] args) {
        int maxBatches = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_MAX_BATCHES;
        String baseUrl = Config.baseUrl();
        Path outputDir = Config.outputDir();
        String accessToken = PrivacyShieldClient.authenticateOnce(baseUrl);

        System.out.println("Draining documents pending download for account "
                + Config.accountId() + " ...");
        System.out.println();

        int collected = 0;
        int batches = 0;
        boolean exhaustedBatches = true;

        for (int batch = 1; batch <= maxBatches; batch++) {
            HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/accounts/documents/pending/download"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Access-Token", accessToken)
                    .GET()
                    .build());

            List<JsonNode> metadata = PrivacyShieldClient.parseDocumentsMetadata(
                    response.headers().firstValue("X-Documents-Metadata").orElse(null));
            PrivacyShieldClient.raiseForStatus(response);

            if (response.statusCode() == 204) {
                // An empty array here is the definitive "nothing waiting anywhere".
                System.out.println("Batch " + batch + ": HTTP 204 - nothing left.");
                exhaustedBatches = false;
                break;
            }

            batches = batch;
            collected += metadata.size();
            System.out.println("Batch " + batch + ": " + metadata.size() + " document(s)");
            for (JsonNode document : metadata) {
                JsonNode pages = document.get("pageCount");
                // jobId only appears in the account-wide download. Without it you
                // cannot tell which submission a file in the ZIP answers.
                System.out.printf(Locale.ROOT, "  %-30s job %-8s %-8s %s page(s)%n",
                        document.path("documentName").asText(),
                        document.path("jobId").asText(),
                        document.path("status").asText(),
                        pages == null || pages.isNull() ? "-" : pages.asText());
            }

            String filename = PrivacyShieldClient.filenameFromContentDisposition(
                    response.headers().firstValue("Content-Disposition").orElse(null),
                    "documents.zip");
            Path target;
            try {
                Files.createDirectories(outputDir);
                // Never overwrite: a batch this code clobbers cannot be downloaded again.
                target = PrivacyShieldClient.uniquePath(outputDir, filename);
                Files.write(target, response.body());
            } catch (IOException error) {
                throw new PrivacyShieldException("Could not save the download: "
                        + error.getMessage(), error);
            }
            System.out.println("  Saved to " + target + " ("
                    + PrivacyShieldClient.formatBytes(response.body().length) + ")");

            if (metadata.size() < DOCUMENTS_PER_CALL) {
                System.out.println("Batch " + batch + " returned fewer than " + DOCUMENTS_PER_CALL
                        + " documents, so the queue is empty.");
                exhaustedBatches = false;
                break;
            }
        }

        if (exhaustedBatches) {
            System.out.println();
            System.out.println("Stopped after " + maxBatches + " batch(es). More documents may "
                    + "still be pending - run again to continue.");
        }

        System.out.println();
        System.out.println("Collected " + collected + " document(s) in " + batches + " batch(es).");
        if (collected > 0) {
            System.out.println("Those documents are now marked as downloaded and will not be "
                    + "returned again.");
        }
        return 0;
    }
}
