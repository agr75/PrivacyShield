# Introduction

PrivacyShield removes sensitive data from documents. You send a PDF, an image, a text or CSV file, or a ZIP archive, and you get the same document back with the fields you configured replaced or masked.

What gets anonymized is decided by a **template**: a configuration you build once in the web interface and then reuse for every document you send to it. The API does not change what a template does — it only feeds documents into one.

## Two ways to use PrivacyShield

| | Web interface | REST API |
|---|---|---|
| Address | `https://privacyshield.fundamentia.com` | `https://api.privacyshield.fundamentia.com/api/v1` |
| Create and configure templates | Yes | No |
| Upload documents | Yes | Yes |
| Download results | Yes | Yes |
| Cancel work in progress | Yes | Yes |

**Templates can only be created and configured in the web interface.** There are no endpoints for listing, creating, editing or deleting templates. Before you write a single line of integration code, an account administrator has to build the template in the web interface and give you its numeric identifier.

## Glossary

One name for one concept, with a single exception in the URL structure.

| Term | Where you see it | What it is |
|---|---|---|
| **template** | Web interface, this documentation, error messages | The anonymization configuration you send documents to |
| `templateId` | Path parameter, response field of `GET /api/v1/jobs/status`, and the `PRIVACYSHIELD_TEMPLATE_ID` environment variable in the examples | The numeric identifier of a template |
| `flow` | The path segment only: `POST /api/v1/flow/{templateId}` | The same concept under an older internal name, kept for URL compatibility |

The exception is worth stating plainly, because it is the one thing that looks like an inconsistency and is not:

```
POST /api/v1/flow/789
             ^^^^ ^^^
             |    |
             |    the templateId you copied from the web interface
             older internal name for the concept, kept in the URL
```

You send documents to a **template**, and its identifier goes into a path segment spelled `flow`. Nothing else in the API uses that word: the parameter is `templateId`, the job listing returns `templateId`, and a wrong identifier comes back as `The template does not exist`.

The rest of the vocabulary:

| Term | What it is |
|---|---|
| **account** | Your organization in PrivacyShield. Identified by `accountId`. Owns the templates, the page quota and the API key. |
| **job** | One submission. Created every time you POST documents to a template. Identified by a `jobId`, which the upload response returns as `job`. |
| **document** | One file inside a job. A ZIP containing five PDFs produces five documents in a single job. Identified by `documentId`. |
| **api_key** | The long-lived credential of your account. Exchanged for an access token; never sent on normal requests. |
| **Access-Token** | The short-lived JWT you send on every authenticated request. Valid for 30 minutes. |

## Base URL and endpoint paths

The base URL **includes the version prefix** and has no trailing slash:

```
https://api.privacyshield.fundamentia.com/api/v1
```

Set it once and append only the endpoint-specific part:

```python
BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"
url = f"{BASE_URL}/flow/{template_id}"
# → https://api.privacyshield.fundamentia.com/api/v1/flow/789
```

Read this before you copy a path out of any page here, because there is one thing to get straight:

**Endpoint pages show the full path, starting with `/api/v1`.** A heading reads `POST /api/v1/flow/{templateId}` because that is the real path — it is what appears in a URL, in a log line and in a cURL command. Your base URL already ends in `/api/v1`, so what you append is the part after it:

| What the page shows | What you append to `BASE_URL` |
|---|---|
| `POST /api/v1/auth/{accountId}` | `/auth/{accountId}` |
| `POST /api/v1/flow/{templateId}` | `/flow/{templateId}` |
| `GET /api/v1/jobs/status` | `/jobs/status` |
| `GET /api/v1/jobs/{jobId}/documents/download` | `/jobs/{jobId}/documents/download` |

Appending the full path to a base URL that already ends in `/api/v1` produces `/api/v1/api/v1/...` and a `404` on every call. If every request 404s and your credentials are fine, check for the doubled prefix first.

The cURL examples in this documentation always spell out the complete URL, so you can paste them without working anything out.

## Endpoints

| Method | Path | What it does |
|---|---|---|
| POST | `/api/v1/auth/{accountId}` | Exchange your API key for an access token — [reference](endpoints/authentication.md) |
| POST | `/api/v1/flow/{templateId}` | Send documents to a template — [reference](endpoints/submit-documents.md) |
| POST | `/api/v1/flow/{templateId}/job/{jobId}/abort` | Cancel one job — [reference](endpoints/cancel-jobs.md) |
| POST | `/api/v1/flow/{templateId}/abort` | Cancel every active job of a template — [reference](endpoints/cancel-jobs.md) |
| GET | `/api/v1/jobs/status` | List your jobs and their status — [reference](endpoints/job-status.md) |
| GET | `/api/v1/jobs/{jobId}/documents/download` | Download the finished documents of one job — [reference](endpoints/download-results.md) |
| GET | `/api/v1/accounts/documents/pending/download` | Download everything finished and not yet downloaded — [reference](endpoints/download-results.md) |

Every endpoint requires the `Access-Token` header except authentication, which produces the token.

The same seven endpoints are also available as a machine-readable OpenAPI 3.0.3 specification:
[`openapi.yaml`](../openapi.yaml). Its `servers` URL carries the `/api/v1` prefix, so the paths
inside it start after that prefix — the full paths in the table above are what actually travels on
the wire.

## Processing is asynchronous

This is the part that shapes your integration:

1. You upload documents. The response comes back immediately with a `job` identifier. **Nothing has been anonymized yet.**
2. The documents move through the pipeline in the background. How long that takes depends on page count and queue load.
3. You poll until the documents are ready, then download them.

There are no webhooks and no callbacks. Polling is the only way to find out that a job has finished. See [lifecycle.md](lifecycle.md) for the states involved and the polling strategy, and [`examples/python/05_end_to_end.py`](../examples/python/05_end_to_end.py) for a working implementation.

## Request and response formats

Requests are JSON, except document upload, which is `multipart/form-data`.

Successful JSON responses always carry `success` and `message`, plus whatever fields the operation returns:

```json
{
  "success": true,
  "message": "Generated access token",
  "Access-Token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

Errors use the same envelope with `success` set to `false`:

```json
{
  "success": false,
  "message": "Invalid token"
}
```

Download endpoints are the exception: they return binary content (PDF or ZIP), or `204 No Content` when there is nothing ready. Both cases carry the `X-Documents-Metadata` header, which is where you find out what actually happened. See [download-results.md](endpoints/download-results.md).

### A note on response messages

The `message` field is a human-readable string, not a stable error code. Different endpoints word the same problem differently, and the wording can change at any time.

**Branch on the HTTP status code, never on the message text.** This documentation reproduces the messages verbatim in [errors.md](errors.md) so you can match what you see in your logs — not so you can compare against them in code.

## Next steps

- [authentication.md](authentication.md) — get a token and keep it alive
- [lifecycle.md](lifecycle.md) — document and job states, and when to stop polling
- [errors.md](errors.md) — what to do with each status code
- [limits.md](limits.md) — file sizes, formats, quotas and filename rules
