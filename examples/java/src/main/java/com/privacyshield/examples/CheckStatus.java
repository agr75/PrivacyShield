package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Example 03 — list your jobs and their status.
 *
 * <p><b>What it does</b><br>
 * Fetches one page of the job listing and prints it as a table. This is the
 * endpoint you poll while waiting: it changes nothing on the server, unlike the
 * download endpoints.
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
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.CheckStatus \
 *       -Dexec.args="0"
 * </pre>
 */
public final class CheckStatus {

    private CheckStatus() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (PrivacyShieldException error) {
            System.exit(PrivacyShieldClient.reportFailure(error));
        }
    }

    private static int run(String[] args) {
        int page = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String baseUrl = Config.baseUrl();
        String accessToken = PrivacyShieldClient.authenticateOnce(baseUrl);

        HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/jobs/status?page=" + page))
                .timeout(Duration.ofSeconds(30))
                .header("Access-Token", accessToken)
                .GET()
                .build());

        PrivacyShieldClient.raiseForStatus(response);
        JsonNode body = PrivacyShieldClient.parseJson(response);

        int total = body.get("total").asInt();
        int limit = body.get("limit").asInt();
        int pages = limit > 0 ? Math.max(1, (int) Math.ceil((double) total / limit)) : 1;

        System.out.println(total + " job(s) in the account - page " + body.get("page").asInt()
                + " of " + (pages - 1) + " - " + limit + " per page");
        System.out.println();

        JsonNode jobs = body.get("jobs");
        if (jobs == null || jobs.size() == 0) {
            System.out.println("No jobs on this page.");
            System.out.println();
            System.out.println("The listing excludes deleted jobs and jobs that failed completely, "
                    + "so a job you cannot find here will not reappear.");
            return 0;
        }

        System.out.printf(Locale.ROOT, "%8s  %8s  %-12s  CREATED%n", "JOB", "TEMPLATE", "STATUS");
        for (JsonNode job : jobs) {
            System.out.printf(Locale.ROOT, "%8d  %8d  %-12s  %s%n",
                    job.get("jobId").asLong(),
                    job.get("templateId").asLong(),
                    job.get("status").asText(),
                    job.get("createdAt").asText());
        }
        System.out.println();
        System.out.println("new/processing = still working - completed = ready to download - "
                + "downloaded = already collected - cancelled = aborted");
        return 0;
    }
}
