package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacyshield.examples.PrivacyShieldClient.Config;
import com.privacyshield.examples.PrivacyShieldClient.PrivacyShieldException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Example 01 — obtain an access token.
 *
 * <p><b>What it does</b><br>
 * Exchanges your API key for an Access-Token JWT and prints how long it lasts.
 * This is the first call of every integration: the token it returns is what
 * authorizes all the others.
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
 *   mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.Authenticate
 * </pre>
 */
public final class Authenticate {

    private static final int TOKEN_LIFETIME_MINUTES = 30;

    private Authenticate() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run());
        } catch (PrivacyShieldException error) {
            System.exit(PrivacyShieldClient.reportFailure(error));
        }
    }

    private static int run() {
        String baseUrl = Config.baseUrl();
        long accountId = Config.accountId();

        System.out.println("Authenticating account " + accountId + " at " + baseUrl + " ...");

        String body = "{\"api_key\":\"" + Config.apiKey() + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/auth/" + accountId))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response = PrivacyShieldClient.sendOnce(request);

        // 403 means the key is wrong; 404 means the account is wrong. Both look
        // identical from the outside, so the message matters here.
        PrivacyShieldClient.raiseForStatus(response);

        JsonNode parsed = PrivacyShieldClient.parseJson(response);
        String token = parsed.get("Access-Token").asText();
        LocalTime expiresAt = LocalTime.now().plusMinutes(TOKEN_LIFETIME_MINUTES);

        System.out.println("Access token obtained: " + token.substring(0, Math.min(20, token.length())) + "...");
        System.out.println("Valid for " + TOKEN_LIFETIME_MINUTES + " minutes, until about "
                + expiresAt.format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " local time.");
        System.out.println("Send it as the 'Access-Token' header on every other request.");
        return 0;
    }

}
