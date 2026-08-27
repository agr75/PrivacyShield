package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Example 02 — send documents to a template.
 *
 * <p><b>What it does</b><br>
 * Uploads one or more files in a single multipart request and prints the job
 * identifier the API returns. The files do not have to be the same type: PDFs,
 * images and ZIP archives can travel together.
 *
 * <p>Nothing is anonymized when this returns. The documents are queued.
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
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.UploadDocument \
 *       -Dexec.args="contract.pdf scan.png"
 * </pre>
 */
public final class UploadDocument {

    private UploadDocument() {
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
            System.err.println("Usage: UploadDocument <file> [more-files ...]");
            System.err.println("Supported extensions: "
                    + String.join(", ", PrivacyShieldClient.SUPPORTED_EXTENSIONS));
            return 1;
        }

        List<Path> files = new ArrayList<>();
        for (String argument : args) {
            files.add(Path.of(argument));
        }
        // Fail before uploading rather than after a 400 from the server.
        for (Path file : files) {
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
        }

        String baseUrl = Config.baseUrl();
        long templateId = Config.templateId();
        String accessToken = PrivacyShieldClient.authenticateOnce(baseUrl);

        System.out.println("Uploading " + files.size() + " file(s) to template " + templateId + ":");
        for (Path file : files) {
            long size;
            try {
                size = Files.size(file);
            } catch (java.io.IOException error) {
                throw new PrivacyShieldException("Could not read " + file, error);
            }
            System.out.println("  " + file.getFileName() + " ("
                    + PrivacyShieldClient.formatBytes(size) + ")");
        }

        // One multipart part per file, all named "file".
        String boundary = "PrivacyShieldBoundary" + UUID.randomUUID();
        byte[] body = PrivacyShieldClient.buildMultipartBody(files, boundary);

        HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/" + templateId))
                .timeout(java.time.Duration.ofSeconds(300))  // uploads are large
                .header("Access-Token", accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build());

        PrivacyShieldClient.raiseForStatus(response);
        JsonNode parsed = PrivacyShieldClient.parseJson(response);
        long jobId = parsed.get("job").asLong();

        System.out.println("Job " + jobId + " created, " + parsed.get("docs").asInt()
                + " document(s) accepted.");
        System.out.println();
        System.out.println("Nothing is anonymized yet - processing is asynchronous.");
        System.out.println("Save the job id, it is your only handle on this submission:");
        System.out.println("    export PRIVACYSHIELD_JOB_ID=" + jobId);
        System.out.println("Next: CheckStatus to watch it, or EndToEnd for the full flow.");
        return 0;
    }
}
