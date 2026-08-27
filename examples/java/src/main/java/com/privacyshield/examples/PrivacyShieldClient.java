package com.privacyshield.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable client for the PrivacyShield REST API.
 *
 * <p>This is the one file here meant to be copied into your own project. The
 * other classes show the raw HTTP calls so you can see what goes over the wire;
 * this one wraps them with the behaviour a real integration needs:
 *
 * <ul>
 *   <li>an access token that renews itself before the 30-minute lifetime is up
 *   <li>exactly one retry on 401, in case a token was invalidated early
 *   <li>exponential backoff on 5xx, and no retry at all on 4xx
 *   <li>204 treated as a normal answer rather than an error
 *   <li>polling with backoff, a global timeout, and a terminal outcome when a
 *       job disappears from the listing
 *   <li>downloads that never overwrite an existing file, because a download
 *       cannot be repeated: the server marks documents as downloaded when it
 *       sends them
 * </ul>
 *
 * <p>Requires JDK 11 or newer. Failures are reported as unchecked
 * {@link PrivacyShieldException}s so the examples stay readable; the checked
 * {@link IOException} and {@link InterruptedException} that {@link HttpClient}
 * throws are wrapped in {@link ServerException}.
 */
public class PrivacyShieldClient implements AutoCloseable {

    public static final String DEFAULT_BASE_URL =
            "https://api.privacyshield.fundamentia.com/api/v1";

    /**
     * The API issues tokens with a 30-minute lifetime. Renewing a few minutes
     * early costs one extra call per half hour and removes a whole class of
     * failure that otherwise only shows up in the middle of a long wait.
     */
    static final long TOKEN_LIFETIME_SECONDS = 30 * 60;
    static final long TOKEN_REFRESH_MARGIN_SECONDS = 5 * 60;

    /** 5xx and network failures are the only retryable conditions. */
    static final int MAX_ATTEMPTS = 4;
    static final double INITIAL_BACKOFF_SECONDS = 2.0;
    static final double MAX_BACKOFF_SECONDS = 30.0;

    /**
     * Polling defaults. The cap matters more than the initial delay: without it,
     * a job that takes an hour is polled with hour-long gaps.
     */
    public static final double DEFAULT_POLL_TIMEOUT_SECONDS = 15 * 60;
    static final double INITIAL_POLL_DELAY_SECONDS = 2.0;
    static final double MAX_POLL_DELAY_SECONDS = 30.0;

    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final Duration UPLOAD_REQUEST_TIMEOUT = Duration.ofSeconds(300);

    /** A job in one of these states will not change on its own. */
    static final Set<String> TERMINAL_JOB_STATUSES =
            Set.of("completed", "downloaded", "cancelled");

    /** A document in one of these states will not change on its own. */
    static final Set<String> TERMINAL_DOCUMENT_STATUSES =
            Set.of("done", "aborted", "error");

    public static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".zip");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long accountId;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    private String token;
    private long tokenObtainedAtNanos;

    public PrivacyShieldClient(long accountId, String apiKey) {
        this(accountId, apiKey, DEFAULT_BASE_URL);
    }

    public PrivacyShieldClient(long accountId, String apiKey, String baseUrl) {
        this.accountId = accountId;
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public long getAccountId() {
        return accountId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        // HttpClient has no close() before JDK 21; nothing to release here.
    }

    // -----------------------------------------------------------------
    // Errors
    // -----------------------------------------------------------------

    /** Base class for every failure this client reports. */
    public static class PrivacyShieldException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Integer statusCode;
        private final String apiMessage;

        public PrivacyShieldException(String message) {
            this(message, null, null);
        }

        public PrivacyShieldException(String message, Integer statusCode, String apiMessage) {
            super(message);
            this.statusCode = statusCode;
            this.apiMessage = apiMessage;
        }

        public PrivacyShieldException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
            this.apiMessage = null;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getApiMessage() {
            return apiMessage;
        }
    }

    /** 400 — the request is wrong, or the quota cannot cover it. Never retry. */
    public static class BadRequestException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public BadRequestException(String m, Integer s, String a) { super(m, s, a); }
    }

    /** 401 — missing or invalid token. Retried once automatically. */
    public static class AuthenticationException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public AuthenticationException(String m, Integer s, String a) { super(m, s, a); }
    }

    /** 403 — the resource belongs to another account. A configuration problem. */
    public static class AccessDeniedException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public AccessDeniedException(String m, Integer s, String a) { super(m, s, a); }
    }

    /** 404 — no such account, template or job. */
    public static class NotFoundException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public NotFoundException(String m, Integer s, String a) { super(m, s, a); }
    }

    /**
     * 410 — the job was deleted. Results are unrecoverable, normally because
     * more than 5 days passed since the documents finished.
     */
    public static class GoneException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public GoneException(String m, Integer s, String a) { super(m, s, a); }
    }

    /** 5xx or a network failure. Retried with backoff before it reaches you. */
    public static class ServerException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public ServerException(String m, Integer s, String a) { super(m, s, a); }
        public ServerException(String m, Throwable cause) { super(m, cause); }
    }

    /** The global polling timeout elapsed. The job keeps running on the server. */
    public static class PollTimeoutException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public PollTimeoutException(String m) { super(m); }
    }

    /**
     * The job stopped appearing in the listing, which excludes deleted jobs and
     * jobs that failed completely. Waiting longer will not bring it back.
     */
    public static class JobVanishedException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public JobVanishedException(String m) { super(m); }
    }

    /** A required environment variable is missing or not a number. */
    public static class MissingConfigurationException extends PrivacyShieldException {
        private static final long serialVersionUID = 1L;
        public MissingConfigurationException(String m) { super(m); }
    }

    // -----------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------

    /**
     * Reads the examples' configuration from the environment. Nothing is ever
     * hardcoded: a credential in a source file is a credential that gets
     * committed.
     */
    public static final class Config {

        private Config() {
        }

        /** The API base URL, including the /api/v1 prefix and no trailing slash. */
        public static String baseUrl() {
            String value = System.getenv("PRIVACYSHIELD_BASE_URL");
            if (value == null || value.trim().isEmpty()) {
                return DEFAULT_BASE_URL;
            }
            return stripTrailingSlash(value.trim());
        }

        public static long accountId() {
            return requireLong("PRIVACYSHIELD_ACCOUNT_ID");
        }

        public static String apiKey() {
            return require("PRIVACYSHIELD_API_KEY");
        }

        /**
         * Identifier of the template documents are sent to.
         *
         * <p>Named after the API literal {@code templateId}, which is what the
         * endpoint path uses. The web interface calls the same thing a template.
         */
        public static long templateId() {
            return requireLong("PRIVACYSHIELD_TEMPLATE_ID");
        }

        /** Identifier of a pre-existing job, for the examples that act on one. */
        public static long jobId() {
            return requireLong("PRIVACYSHIELD_JOB_ID");
        }

        /** Where the examples write downloaded documents. */
        public static Path outputDir() {
            return Path.of("./downloads");
        }

        public static String require(String name) {
            String value = System.getenv(name);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
            throw new MissingConfigurationException(
                    "Missing configuration: " + name + "\n"
                            + "\n"
                            + "  export " + name + "=...\n"
                            + "\n"
                            + "Where to find it: " + whereToFind(name) + ".");
        }

        public static long requireLong(String name) {
            String value = require(name);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException error) {
                throw new MissingConfigurationException("Invalid configuration: " + name
                        + " must be a number, got '" + value + "'.");
            }
        }

        private static String whereToFind(String name) {
            switch (name) {
                case "PRIVACYSHIELD_ACCOUNT_ID":
                    return "the My Account section of "
                            + "https://privacyshield.fundamentia.com/administration";
                case "PRIVACYSHIELD_API_KEY":
                    return "the Api Key section of "
                            + "https://privacyshield.fundamentia.com/administration?tab=apikey";
                case "PRIVACYSHIELD_TEMPLATE_ID":
                    return "the template list in https://privacyshield.fundamentia.com - "
                            + "templates are created in the web interface, not through the API";
                case "PRIVACYSHIELD_JOB_ID":
                    return "the 'job' field returned when you upload a document "
                            + "(see UploadDocument), or the listing from CheckStatus";
                default:
                    return "your account administrator";
            }
        }
    }

    /**
     * Prints a failure the way every example does, and returns the exit code.
     *
     * @param error the failure to report
     * @return 1, so a caller can {@code System.exit(reportFailure(error))}
     */
    public static int reportFailure(PrivacyShieldException error) {
        if (error instanceof MissingConfigurationException) {
            System.err.println(error.getMessage());
        } else {
            System.err.println();
            System.err.println("Failed: " + error.getMessage());
        }
        return 1;
    }

    // -----------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------

    /**
     * Seconds since the current token was issued, or -1 if there is no token.
     *
     * @return the token's age in seconds
     */
    public double getTokenAgeSeconds() {
        if (token == null) {
            return -1;
        }
        return (System.nanoTime() - tokenObtainedAtNanos) / 1_000_000_000.0;
    }

    /**
     * Exchanges the API key for a new access token.
     *
     * <p>Called automatically when needed. Call it directly only to force a
     * renewal. Its own failures are final: there is no token to refresh.
     *
     * @return the new token
     */
    public String authenticate() {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(MAPPER.createObjectNode().put("api_key", apiKey));
        } catch (IOException error) {
            throw new PrivacyShieldException("Could not build the authentication body", error);
        }

        HttpResponse<byte[]> response = sendWithBackoff(() -> httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/auth/" + accountId))
                        .timeout(DEFAULT_REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()));

        raiseForStatus(response);
        JsonNode parsed = parseJson(response);
        JsonNode tokenNode = parsed.get("Access-Token");
        if (tokenNode == null || tokenNode.asText().isEmpty()) {
            throw new PrivacyShieldException(
                    "The authentication response contains no Access-Token field: " + parsed);
        }
        this.token = tokenNode.asText();
        this.tokenObtainedAtNanos = System.nanoTime();
        return this.token;
    }

    // -----------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------

    /**
     * Sends one or more documents to a template.
     *
     * <p>Repeat parts named {@code file} carry the documents; they do not have
     * to be the same type. The response's {@code job} field is the only handle
     * you get on the submission, so persist it before doing anything else.
     *
     * @param templateId identifier of the destination template
     * @param files  local files to upload
     * @return the parsed response body
     */
    public JsonNode uploadDocuments(long templateId, List<Path> files) {
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                throw new PrivacyShieldException("File not found: " + file);
            }
        }
        String boundary = "PrivacyShieldBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartBody(files, boundary);

        // The body is a byte array, so a retried upload re-sends it as-is. A
        // streaming implementation would have to rewind its sources instead.
        HttpResponse<byte[]> response = authenticatedRequest("POST", "/flow/" + templateId, body,
                "multipart/form-data; boundary=" + boundary, UPLOAD_REQUEST_TIMEOUT);
        return parseJson(response);
    }

    /**
     * Returns one page of the job listing, newest first, 40 per page.
     *
     * @param page zero-based page number
     * @return the parsed response body
     */
    public JsonNode getJobsStatus(int page) {
        HttpResponse<byte[]> response = authenticatedRequest("GET",
                "/jobs/status?page=" + page, null, null, DEFAULT_REQUEST_TIMEOUT);
        return parseJson(response);
    }

    /**
     * Locates a job in the paginated listing.
     *
     * @param jobId the job to find
     * @return the job entry, or null when it is not there — a terminal outcome,
     *         since the listing excludes deleted jobs and jobs that failed
     *         completely
     */
    public JsonNode findJob(long jobId) {
        return findJob(jobId, 25);
    }

    public JsonNode findJob(long jobId, int maxPages) {
        int page = 0;
        while (page < maxPages) {
            JsonNode body = getJobsStatus(page);
            JsonNode jobs = body.get("jobs");
            if (jobs != null) {
                for (JsonNode job : jobs) {
                    if (job.get("jobId").asLong() == jobId) {
                        return job;
                    }
                }
            }
            int limit = body.hasNonNull("limit") ? body.get("limit").asInt() : 40;
            int total = body.hasNonNull("total") ? body.get("total").asInt() : 0;
            if ((long) (page + 1) * limit >= total) {
                return null;
            }
            page++;
        }
        return null;
    }

    /**
     * Cancels the queued documents of one job.
     *
     * <p>{@code abortedDocuments} can be 0: that means every document had
     * already started processing, which is a success, not an error.
     *
     * @param templateId template the job belongs to
     * @param jobId  job to cancel
     * @return the parsed response body
     */
    public JsonNode abortJob(long templateId, long jobId) {
        HttpResponse<byte[]> response = authenticatedRequest("POST",
                "/flow/" + templateId + "/job/" + jobId + "/abort", null, null,
                DEFAULT_REQUEST_TIMEOUT);
        return parseJson(response);
    }

    /**
     * Cancels the queued documents of every active job of a template.
     *
     * <p>This affects jobs your process did not create, including submissions
     * made from the web interface.
     *
     * @param templateId the template
     * @return the parsed response body
     */
    public JsonNode abortTemplate(long templateId) {
        HttpResponse<byte[]> response = authenticatedRequest("POST",
                "/flow/" + templateId + "/abort", null, null, DEFAULT_REQUEST_TIMEOUT);
        return parseJson(response);
    }

    /**
     * Downloads the finished documents of one job.
     *
     * <p>One document comes back as a PDF, several as a ZIP — check
     * {@link Download#contentType} instead of assuming. The returned metadata
     * describes every document in the job, including the ones not sent.
     *
     * @param jobId     the job
     * @param outputDir folder to save into, created if missing
     * @return the outcome; {@link Download#isEmpty()} when the server had
     *         nothing ready (204)
     */
    public Download downloadJobDocuments(long jobId, Path outputDir) {
        HttpResponse<byte[]> response = authenticatedRequest("GET",
                "/jobs/" + jobId + "/documents/download", null, null, UPLOAD_REQUEST_TIMEOUT);
        return asDownload(response, outputDir, "job_" + jobId + "_documents");
    }

    /**
     * Downloads up to 10 finished, not-yet-downloaded documents of the account.
     *
     * <p>Always a ZIP. The metadata lists only what this call returned, and each
     * entry carries an extra {@code jobId} telling you which job it came from.
     *
     * @param outputDir folder to save into, created if missing
     * @return the outcome; {@link Download#isEmpty()} when nothing was pending
     */
    public Download downloadPendingDocuments(Path outputDir) {
        HttpResponse<byte[]> response = authenticatedRequest("GET",
                "/accounts/documents/pending/download", null, null, UPLOAD_REQUEST_TIMEOUT);
        return asDownload(response, outputDir, "documents.zip");
    }

    // -----------------------------------------------------------------
    // Waiting
    // -----------------------------------------------------------------

    /** Called on every poll with the job entry and the seconds about to be slept. */
    @FunctionalInterface
    public interface PollListener {
        void onPoll(JsonNode job, double sleepForSeconds);
    }

    public JsonNode waitForJob(long jobId) {
        return waitForJob(jobId, DEFAULT_POLL_TIMEOUT_SECONDS, null);
    }

    /**
     * Polls the job listing until the job reaches a terminal status.
     *
     * <p>The token renews itself inside the loop, so a wait longer than the
     * 30-minute token lifetime works without any action from the caller.
     *
     * @param jobId          the job to wait for
     * @param timeoutSeconds how long to wait in total
     * @param listener       progress callback, may be null
     * @return the job entry in its terminal state
     */
    public JsonNode waitForJob(long jobId, double timeoutSeconds, PollListener listener) {
        long deadline = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
        double delay = INITIAL_POLL_DELAY_SECONDS;

        while (true) {
            JsonNode job = findJob(jobId);
            if (job == null) {
                throw new JobVanishedException("Job " + jobId + " is no longer in the listing. "
                        + "The listing excludes deleted jobs and jobs that failed completely, "
                        + "so it will not reappear.");
            }
            String status = job.get("status").asText();
            if (TERMINAL_JOB_STATUSES.contains(status)) {
                if (listener != null) {
                    listener.onPoll(job, 0.0);
                }
                return job;
            }

            double remaining = (deadline - System.nanoTime()) / 1_000_000_000.0;
            if (remaining <= 0) {
                throw new PollTimeoutException(String.format(Locale.ROOT,
                        "Job %d was still '%s' after %.0fs. It keeps processing on the server; "
                                + "collect it later.", jobId, status, timeoutSeconds));
            }
            double sleepFor = Math.min(delay, remaining);
            if (listener != null) {
                listener.onPoll(job, sleepFor);
            }
            sleep(sleepFor);
            delay = Math.min(delay * 2, MAX_POLL_DELAY_SECONDS);
        }
    }

    // -----------------------------------------------------------------
    // Downloads
    // -----------------------------------------------------------------

    /**
     * Outcome of a download call.
     *
     * <p>{@link #path} is null when the server answered 204, which means nothing
     * was ready to send. That is a normal outcome, not a failure.
     */
    public static final class Download {
        public final int statusCode;
        public final Path path;
        public final List<JsonNode> metadata;
        public final String contentType;
        public final long sizeBytes;

        Download(int statusCode, Path path, List<JsonNode> metadata, String contentType,
                long sizeBytes) {
            this.statusCode = statusCode;
            this.path = path;
            this.metadata = metadata;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
        }

        public boolean isEmpty() {
            return path == null;
        }
    }

    private Download asDownload(HttpResponse<byte[]> response, Path outputDir, String fallbackName) {
        List<JsonNode> metadata = parseDocumentsMetadata(
                response.headers().firstValue("X-Documents-Metadata").orElse(null));

        if (response.statusCode() == 204) {
            return new Download(204, null, metadata, null, 0);
        }

        String filename = filenameFromContentDisposition(
                response.headers().firstValue("Content-Disposition").orElse(null), fallbackName);
        try {
            Files.createDirectories(outputDir);
            Path target = uniquePath(outputDir, filename);
            Files.write(target, response.body());
            return new Download(response.statusCode(), target, metadata,
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.body().length);
        } catch (IOException error) {
            throw new PrivacyShieldException("Could not save the download: " + error.getMessage(),
                    error);
        }
    }

    // -----------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------

    /** Parses a JSON response body. */
    public static JsonNode parseJson(HttpResponse<byte[]> response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException error) {
            throw new PrivacyShieldException("Response is not valid JSON: "
                    + new String(response.body(), StandardCharsets.UTF_8), error);
        }
    }

    /** Extracts the API's {@code message} field, falling back to the raw body. */
    public static String apiMessage(byte[] body) {
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return "(no response body)";
        }
        try {
            JsonNode parsed = MAPPER.readTree(text);
            if (parsed.hasNonNull("message")) {
                return parsed.get("message").asText();
            }
        } catch (IOException ignored) {
            // Not JSON; fall through to the raw text.
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /**
     * Maps an HTTP status onto the exception hierarchy above.
     *
     * <p>Anything below 400 returns quietly, which is what makes 204 an ordinary
     * answer for the download endpoints instead of an error.
     *
     * @param response the response to inspect
     */
    public static void raiseForStatus(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status < 400) {
            return;
        }
        String detail = apiMessage(response.body());
        String where = response.request().method() + " " + response.request().uri();
        String message = "HTTP " + status + " on " + where + ": " + detail;

        switch (status) {
            case 400:
                throw new BadRequestException(message, status, detail);
            case 401:
                throw new AuthenticationException(message, status, detail);
            case 403:
                throw new AccessDeniedException(message, status, detail);
            case 404:
                throw new NotFoundException(message, status, detail);
            case 410:
                throw new GoneException(message, status, detail);
            default:
                if (status >= 500) {
                    throw new ServerException(message, status, detail);
                }
                throw new PrivacyShieldException(message, status, detail);
        }
    }

    /**
     * Parses the {@code X-Documents-Metadata} header into a list of nodes.
     *
     * <p>Returns an empty list when the header is absent or unparseable. The
     * header is the only per-document report the API gives you, and it arrives
     * on 204 responses too, so read it on every download.
     *
     * @param rawHeader the header value, or null
     * @return one node per document
     */
    public static List<JsonNode> parseDocumentsMetadata(String rawHeader) {
        List<JsonNode> documents = new ArrayList<>();
        if (rawHeader == null || rawHeader.trim().isEmpty()) {
            return documents;
        }
        try {
            JsonNode parsed = MAPPER.readTree(rawHeader);
            if (parsed.isArray()) {
                for (JsonNode document : parsed) {
                    documents.add(document);
                }
            }
        } catch (IOException ignored) {
            // A malformed header must not break a download that succeeded.
        }
        return documents;
    }

    /** Returns the documents that are still going to change state. */
    public static List<JsonNode> documentsInProgress(List<JsonNode> metadata) {
        List<JsonNode> pending = new ArrayList<>();
        for (JsonNode document : metadata) {
            String status = document.path("status").asText("");
            if (!TERMINAL_DOCUMENT_STATUSES.contains(status)) {
                pending.add(document);
            }
        }
        return pending;
    }

    /**
     * Builds a path that does not exist yet, adding {@code (2)}, {@code (3)} as
     * needed.
     *
     * <p>Overwriting matters here more than usual: the API marks documents as
     * downloaded when it sends them, so a file this client clobbers cannot be
     * fetched again.
     *
     * @param outputDir destination folder
     * @param filename  preferred name
     * @return a free path inside {@code outputDir}
     */
    public static Path uniquePath(Path outputDir, String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";

        Path candidate = outputDir.resolve(filename);
        int counter = 2;
        while (Files.exists(candidate)) {
            candidate = outputDir.resolve(stem + " (" + counter + ")" + extension);
            counter++;
        }
        return candidate;
    }

    /** Formats a byte count for console output. */
    public static String formatBytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
    }

    /** True when the name ends in an extension the upload endpoint accepts. */
    public static boolean isSupportedFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Guesses a part's {@code Content-Type} from its extension.
     *
     * <p>Covers every type the upload endpoint accepts, because a single request
     * may mix PDFs, images and archives.
     *
     * @param filename name of the file
     * @return the content type for its multipart part
     */
    public static String guessContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            return "image/tiff";
        }
        return "application/octet-stream";
    }

    /**
     * Builds a {@code multipart/form-data} body with one {@code file} part per
     * file, since {@link HttpClient} has no multipart support.
     *
     * <p>The trailing CRLF after each part and the closing {@code --boundary--}
     * are mandatory. A missing one produces a 400 that looks like a server
     * problem and is not.
     *
     * @param files    local files to include
     * @param boundary delimiter, without the leading {@code --}
     * @return the request body
     */
    public static byte[] buildMultipartBody(List<Path> files, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: " + guessContentType(filename) + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(Files.readAllBytes(file));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new PrivacyShieldException("Could not read a file to upload: "
                    + error.getMessage(), error);
        }
        return out.toByteArray();
    }

    /**
     * Extracts the filename from a {@code Content-Disposition} header.
     *
     * <p>When the name has non-ASCII characters the server sends both the
     * extended parameter {@code filename*=UTF-8''...} (RFC 5987/6266,
     * percent-encoded) and, for compatibility with old clients, a plain
     * {@code filename="..."} holding an RFC 2047 encoded-word such as
     * {@code =?UTF-8?Q?informe.pdf?=}. The extended parameter is the correct one
     * and needs no heuristics, so it always wins; the plain form is only decoded
     * when there is no {@code filename*=}.
     *
     * @param contentDisposition header value, or null
     * @param fallback           name to use when nothing can be extracted
     * @return the filename to save under
     */
    public static String filenameFromContentDisposition(String contentDisposition,
            String fallback) {
        if (contentDisposition == null) {
            return fallback;
        }

        Matcher extended = Pattern.compile("filename\\*\\s*=\\s*([^;]+)")
                .matcher(contentDisposition);
        if (extended.find()) {
            String value = extended.group(1).trim().replaceAll("^\"|\"$", "");
            String charset = "UTF-8";
            String encoded = value;
            int firstQuote = value.indexOf('\'');
            if (firstQuote >= 0) {
                charset = value.substring(0, firstQuote);
                int secondQuote = value.indexOf('\'', firstQuote + 1);
                encoded = secondQuote >= 0
                        ? value.substring(secondQuote + 1)
                        : value.substring(firstQuote + 1);
            }
            return percentDecode(encoded, charset.isEmpty() ? "UTF-8" : charset);
        }

        Matcher plain = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?")
                .matcher(contentDisposition);
        if (plain.find()) {
            return decodeRfc2047(plain.group(1));
        }

        return fallback;
    }

    /** Decodes a percent-encoded string (RFC 3986), as used by {@code filename*=}. */
    private static String percentDecode(String value, String charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                out.write((high << 4) | low);
                i += 2;
            } else {
                out.write(c);
            }
        }
        return new String(out.toByteArray(), Charset.forName(charset));
    }

    /**
     * Decodes an RFC 2047 encoded-word ({@code =?charset?Q?text?=} or
     * {@code =?charset?B?text?=}). Text in any other shape is returned unchanged.
     */
    private static String decodeRfc2047(String text) {
        Matcher matcher = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(text);
        if (!matcher.matches()) {
            return text;
        }
        String charset = matcher.group(1);
        String encoding = matcher.group(2).toUpperCase(Locale.ROOT);
        String encodedText = matcher.group(3);
        byte[] bytes = encoding.equals("B")
                ? Base64.getDecoder().decode(encodedText)
                : decodeQuotedPrintable(encodedText);
        return new String(bytes, Charset.forName(charset));
    }

    /** Decodes quoted-printable (RFC 2047 section 4.2). */
    private static byte[] decodeQuotedPrintable(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < text.length()) {
                int high = Character.digit(text.charAt(i + 1), 16);
                int low = Character.digit(text.charAt(i + 2), 16);
                out.write((high << 4) | low);
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // -----------------------------------------------------------------
    // Helpers for the direct-HTTP examples
    // -----------------------------------------------------------------

    /**
     * Sends one request with a throwaway {@link HttpClient}, turning the checked
     * exceptions into unchecked ones.
     *
     * <p>Used by the examples that call the API by hand so you can see what goes
     * over the wire. It has no retry and no token handling: for a real
     * integration, instantiate this class instead.
     *
     * @param request the request to send
     * @return the response, with the body as bytes
     */
    public static HttpResponse<byte[]> sendOnce(HttpRequest request) {
        try {
            return HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException error) {
            throw new ServerException("Network failure: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ServerException("Interrupted while waiting for a response", error);
        }
    }

    /**
     * Obtains a token with a single call, reading the credentials from the
     * environment.
     *
     * <p>No renewal: the examples that use this run for seconds. A process that
     * runs longer than 30 minutes needs {@link #authenticate()} on an instance.
     *
     * @param baseUrl API base URL, including the /api/v1 prefix
     * @return the access token
     */
    public static String authenticateOnce(String baseUrl) {
        String body = "{\"api_key\":\"" + Config.apiKey() + "\"}";
        HttpResponse<byte[]> response = sendOnce(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/auth/" + Config.accountId()))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        raiseForStatus(response);
        return parseJson(response).get("Access-Token").asText();
    }

    // -----------------------------------------------------------------
    // Request plumbing
    // -----------------------------------------------------------------

    @FunctionalInterface
    private interface RequestSender {
        HttpResponse<byte[]> send() throws IOException, InterruptedException;
    }

    private String ensureToken() {
        double age = getTokenAgeSeconds();
        if (token == null || age > TOKEN_LIFETIME_SECONDS - TOKEN_REFRESH_MARGIN_SECONDS) {
            authenticate();
        }
        return token;
    }

    /** Sends an authenticated request, handling both retry policies. */
    private HttpResponse<byte[]> authenticatedRequest(String method, String path, byte[] body,
            String contentType, Duration timeout) {
        boolean tokenRefreshed = false;
        while (true) {
            final String currentToken = ensureToken();
            HttpResponse<byte[]> response = sendWithBackoff(() -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(timeout)
                        .header("Access-Token", currentToken);
                if (contentType != null) {
                    builder.header("Content-Type", contentType);
                }
                builder.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
                return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            });

            if (response.statusCode() == 401 && !tokenRefreshed) {
                // The token may have been invalidated before its nominal expiry.
                // One forced refresh and one retry; a second 401 is a credential
                // problem and must surface.
                tokenRefreshed = true;
                authenticate();
                continue;
            }

            raiseForStatus(response);
            return response;
        }
    }

    /**
     * Retries server-side and network failures only.
     *
     * <p>A 4xx is returned untouched for the caller to map: retrying a malformed
     * request or an exhausted quota just sends the same failure again.
     */
    private HttpResponse<byte[]> sendWithBackoff(RequestSender sender) {
        double delay = INITIAL_BACKOFF_SECONDS;
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<byte[]> response = null;
            try {
                response = sender.send();
            } catch (IOException error) {
                lastError = error;
                if (attempt == MAX_ATTEMPTS) {
                    throw new ServerException("Network failure after " + attempt
                            + " attempt(s): " + error.getMessage(), error);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ServerException("Interrupted while waiting for a response", error);
            }

            if (response != null) {
                boolean serverError = response.statusCode() >= 500 && response.statusCode() < 600;
                if (!serverError || attempt == MAX_ATTEMPTS) {
                    return response;
                }
            }

            sleep(delay);
            delay = Math.min(delay * 2, MAX_BACKOFF_SECONDS);
        }
        throw new ServerException("Request failed after " + MAX_ATTEMPTS + " attempts: "
                + (lastError == null ? "unknown cause" : lastError.getMessage()), lastError);
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PrivacyShieldException("Interrupted while waiting", error);
        }
    }
}
