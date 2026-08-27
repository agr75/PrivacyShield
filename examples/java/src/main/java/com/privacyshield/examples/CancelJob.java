package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Example 06 — cancel queued work.
 *
 * <p><b>What it does</b><br>
 * Cancels one job, or every active job of a template with {@code --template}.
 *
 * <p>Only documents that have not started processing are cancelled. Documents
 * already in the pipeline finish normally and their pages are charged, so
 * cancelling is a race worth losing early rather than late.
 *
 * <p>An {@code abortedDocuments} of 0 is a success: it means everything had
 * already started.
 *
 * <p><b>What you need configured</b>
 * <pre>
 *   PRIVACYSHIELD_ACCOUNT_ID   your account identifier
 *   PRIVACYSHIELD_API_KEY      your account's API key
 *   PRIVACYSHIELD_TEMPLATE_ID      the template the job belongs to
 *   PRIVACYSHIELD_JOB_ID       the job to cancel, unless passed as an argument
 *   PRIVACYSHIELD_BASE_URL     optional, defaults to production
 * </pre>
 *
 * <p><b>How to run</b>
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.CancelJob \
 *       -Dexec.args="12345"
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.CancelJob \
 *       -Dexec.args="--template"
 * </pre>
 */
public final class CancelJob {

    private CancelJob() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (PrivacyShieldException error) {
            System.exit(PrivacyShieldClient.reportFailure(error));
        }
    }

    private static int run(String[] args) {
        String baseUrl = Config.baseUrl();
        long templateId = Config.templateId();
        String accessToken = PrivacyShieldClient.authenticateOnce(baseUrl);

        if (args.length > 0 && "--template".equals(args[0])) {
            cancelWholeTemplate(baseUrl, accessToken, templateId);
        } else {
            long jobId = args.length > 0 ? Long.parseLong(args[0]) : Config.jobId();
            cancelOneJob(baseUrl, accessToken, templateId, jobId);
        }

        System.out.println();
        System.out.println("Cancelled documents consume no pages. Finished documents stay "
                + "downloadable - check DownloadResult before writing the job off.");
        return 0;
    }

    private static void cancelOneJob(String baseUrl, String accessToken, long templateId, long jobId) {
        System.out.println("Cancelling job " + jobId + " of template " + templateId + " ...");
        HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/" + templateId + "/job/" + jobId + "/abort"))
                .timeout(Duration.ofSeconds(30))
                .header("Access-Token", accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())   // no body on this endpoint
                .build());
        PrivacyShieldClient.raiseForStatus(response);

        int aborted = PrivacyShieldClient.parseJson(response).get("abortedDocuments").asInt();
        System.out.println(aborted + " document(s) moved to aborted.");
        if (aborted > 0) {
            System.out.println("Documents that had already started processing are unaffected "
                    + "and will finish.");
        } else {
            System.out.println("Every document had already started processing, so there was "
                    + "nothing to cancel. This is a success, not an error.");
        }
    }

    private static void cancelWholeTemplate(String baseUrl, String accessToken, long templateId) {
        // This also cancels jobs your process did not create, including
        // submissions made from the web interface.
        System.out.println("Cancelling every active job of template " + templateId + " ...");
        HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/flow/" + templateId + "/abort"))
                .timeout(Duration.ofSeconds(30))
                .header("Access-Token", accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        PrivacyShieldClient.raiseForStatus(response);

        JsonNode body = PrivacyShieldClient.parseJson(response);
        System.out.println(body.get("abortedJobs").asInt() + " job(s) affected:");
        JsonNode jobs = body.get("jobs");
        if (jobs != null) {
            for (JsonNode job : jobs) {
                System.out.println("  job " + job.get("jobId").asLong() + ": "
                        + job.get("abortedDocuments").asInt() + " document(s) cancelled");
            }
        }
    }
}
