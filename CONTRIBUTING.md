# Contributing

This repository holds the public documentation and examples for the PrivacyShield
API. It does not hold the service.

## Where to report what

| Problem | Where |
|---|---|
| An error, gap or unclear passage in this documentation | Open an issue here |
| An example that does not work | Open an issue here, with the command and the output |
| A stuck job, a quota question, a template that does not anonymize as expected | [Contact section](https://privacyshield.fundamentia.com/administration) of the administration panel |
| Behaviour that contradicts these docs | Both: an issue here, and the Contact section |

For a service problem, include the `jobId` and the approximate time of the
request. Never include an API key, an access token or a real document.

## Never commit

- API keys, access tokens, or any credential
- Real `accountId` or `templateId` values
- Real documents, or output produced from them
- Anything from a customer environment

Examples read credentials from `PRIVACYSHIELD_ACCOUNT_ID`,
`PRIVACYSHIELD_API_KEY` and `PRIVACYSHIELD_TEMPLATE_ID`. `.env` and
`examples/python/config.py` are git-ignored; keep it that way.

The invented values used throughout the documentation are account `1042`,
template `789`, job `12345` and documents `98761`–`98763`. Reuse those rather
than inventing new ones — a JSON example on one page should be consistent with
the page that follows it.

## Proposing a change

1. Fork and branch.
2. Make the change, and verify it (see below).
3. Open a pull request describing what was wrong and what your change fixes.

Doc-only corrections do not need an issue first.

## Documentation style

- **English, second person, short sentences.** "You poll until the job is ready",
  not "the client should then proceed to poll".
- **No marketing.** Not "powerful", "robust" or "effortless". Say what it does.
- **Tables for structured data, prose for consequences.** A table lists the
  fields; a paragraph explains what breaks if you ignore one.
- **Realistic, consistent examples.** If a response shows `"job": 12345`, the
  download example on the next page uses `12345`.
- **Warning blocks for anything that breaks an integration**: token expiry,
  documents being marked as downloaded, the 10-document cap, page quota, the
  5-day retention window.
- **Every endpoint page follows the same template**: description, method and
  path, headers, path parameters, query parameters, body, success response with a
  real JSON example, field table, error table, then cURL, Python and Java calls.
  cURL comes first — it is what people try first.

### Terminology

Say **template** in prose. That is what the web interface calls it.

Leave API literals exactly as the API spells them. The one place the word
template does not appear is the `flow` path segment, kept for URL compatibility:
`POST /api/v1/flow/{templateId}`. Do not "fix" that segment to match the prose,
and do not rename `templateId`. The
[glossary](docs/introduction.md#glossary) explains the mismatch.

Reproduce API messages **verbatim**, including their spelling. `Documents send
succesfully` is what the server returns, so that is what the documentation
shows. Silently correcting it would stop matching what people see in their logs.

## Example code

Both languages must stay at parity: same example names, same behaviour, same
console output. A change to one is a change to both.

**Python** — 3.9+, `requests` as the only dependency, type annotations
throughout.

**Java** — JDK 11+, `java.net.http.HttpClient`, Jackson for JSON. Every
`String.format` that prints a number uses `Locale.ROOT`, or the output changes
with the machine's locale.

Rules that apply to both:

- Credentials come from environment variables. A missing one fails with a message
  naming the variable and where to find its value.
- Console output stays ASCII. An em dash breaks a `cp1252` Windows terminal.
- Distinguish the failure modes: `400` never retried, `401` retried once after
  refreshing the token, `403`/`404`/`410` reported as configuration or retention
  problems, `5xx` retried with exponential backoff.
- `204 No Content` is a normal outcome, not an error.
- Read `X-Documents-Metadata` on every download, including on `204`.
- Never overwrite a downloaded file. The server marks documents as downloaded, so
  a clobbered file cannot be fetched again.
- Comment the non-obvious decision, not the line. "Rewind the handles: a retried
  upload left them at EOF" earns its place; "open the file" does not.

## Verifying a change

```bash
# Python
cd examples/python && python3 -m py_compile *.py

# Java
cd examples/java && mvn -q compile
```

Check that internal links still resolve, and that any new page is listed in the
root `README.md` index.

**Do not test against production with real customer documents.** There is one
environment and no sandbox, and every submission consumes page quota. Use
documents you are allowed to process, against a template created for testing.
