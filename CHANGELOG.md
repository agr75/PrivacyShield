# Changelog

All notable changes to this repository are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the version refers
to this documentation, not to the API — the API version is `v1` throughout.

## [1.1.0] - 2026-10-06

### Changed

- Documented the rate limit: **10 requests per 60 seconds per account, shared
  across all endpoints**. Above that the API returns `429` with no `Retry-After` header, and
  its body is not the standard envelope (`internalID`, `errorInfo`, `fecha`).
  This replaces the statement in 1.0.0 that there was no rate limiting.
- `limits.md`, `errors.md`, `lifecycle.md`, the FAQ and every endpoint page
  explain how to handle `429`: wait 60 seconds and retry, never with the `5xx`
  backoff.
- `openapi.yaml`: new `TooManyRequests` response and `RateLimitError` schema,
  added as `429` to all seven operations.

### Known gaps

- The Python and Java example clients do not handle `429` yet.

## [1.0.0] - 2026-08-27

First public release: complete documentation and working examples for API v1.

### Added

- Documentation for all seven endpoints, each with cURL, Python and Java calls:
  authentication, document submission, job status, both downloads, and both
  cancellation endpoints.
- A glossary establishing **template** as the term used everywhere, and calling
  out the single exception: the `flow` path segment, kept for URL compatibility.
  Path parameter, job listing and error messages all say `templateId` / template.
- The base URL rule: `BASE_URL` includes the `/api/v1` prefix, and endpoint
  pages show the full path.
- Document and job lifecycle pages with Mermaid diagrams, including why
  `completed` does not mean every document has finished.
- A consolidated error reference mapping each status code to a retry decision.
- Limits: 150 MB per request, accepted formats, per-template page quota, the
  5-day retention window, filename restrictions, and the absence of rate
  limiting.
- Prominent documentation of `X-Documents-Metadata`, including that it arrives
  on `204` responses and that only the account-wide download adds `jobId`.
- Warnings that downloading marks documents as downloaded, and what that means
  for retries and concurrency.
- Python examples (3.9+, `requests` only) and Java examples (JDK 11+,
  `java.net.http.HttpClient` with Jackson), at functional parity: same names,
  same behaviour, same console output.
- A reusable client in both languages: token renewal before expiry, one retry on
  `401`, exponential backoff on `5xx`, polling with a global timeout, and
  downloads that never overwrite an existing file.
- Batch processing examples that classify per-file failures and exit non-zero
  when any file fails.
- `openapi.yaml`: an OpenAPI 3.0.3 specification of all seven endpoints, with
  request and response schemas, every documented error message as a named
  example, and the `X-Documents-Metadata` header. Referenced from the README.
