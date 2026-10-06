# Errors

Errors use the standard response envelope:

```json
{
  "success": false,
  "message": "Invalid token"
}
```

**Branch on the HTTP status code.** The `message` field is written for humans and is not a stable identifier. Matching on its text will break.

The messages below are reproduced verbatim so you can match what you see in your logs — not so you can compare against them in code.

## What to do with each status

This table is the contract for your client. The example clients implement every row except `429`, which they do not handle yet.

| Status | Meaning | Retry? | What to do |
|---|---|---|---|
| 200 | Success | — | Process the response |
| 204 | Success, no content | — | Not an error. Nothing is ready to download yet |
| 400 | Bad request | **No** | The request is wrong or the quota is exhausted. Retrying sends the identical broken request. Report the `message` and stop |
| 401 | Missing or expired token | **Once** | Get a new token, retry the request once. A second `401` means a credential problem, not an expiry problem |
| 403 | Access denied | **No** | You are pointing at a resource that belongs to another account, or your API key is malformed. Configuration problem — abort |
| 404 | Not found | **No** | The account, template or job does not exist. Check the identifiers. Abort |
| 410 | Gone | **No** | The job was deleted — normally because its documents finished more than 5 days ago. The results are unrecoverable. Abort and do not re-poll it |
| 429 | Too many requests | **After waiting** | More than 10 requests from your account in 60 seconds, across all endpoints. The body is not the standard envelope and there is no `Retry-After` header: wait 60 seconds, then retry. Do not use the `5xx` backoff |
| 500 | Server error | **Yes** | Retry with exponential backoff and a bounded number of attempts |

Five branches cover everything: retry once after refreshing the token (401), wait out the rate-limit window and retry (429), retry with backoff (5xx), abort with a configuration message (403/404/410), abort with the API message (400).

## Full message reference

Messages are reproduced exactly as the API returns them. Treat them as diagnostic aids, not as values to compare against.

### `POST /api/v1/auth/{accountId}`

| Status | Message | Cause |
|---|---|---|
| 400 | `The body format is incorrect` | Body is not valid JSON, or `api_key` is missing |
| 403 | `Invalid key` | The key format is not recognized |
| 404 | `Account not found` | No account with that `accountId` |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

### `POST /api/v1/flow/{templateId}`

| Status | Message | Cause |
|---|---|---|
| 400 | `The template does not exist` | No template with that `templateId` |
| 400 | `Not enough pages` | The submission exceeds the remaining page quota of the target template |
| 400 | `Unsupported file type` | The file is not a PDF, a supported image, or a ZIP with valid contents |
| 400 | `Unsupported file inside a ZIP` | The ZIP contains an unsupported file |
| 400 | `Invalid file name` | The filename contains forbidden characters |
| 401 | `The Access-Token header is missing` | The header was not sent |
| 401 | `Invalid token` | The token expired or its signature is invalid |
| 403 | `Access denied` | The template belongs to another account |
| 404 | `Account not found` | The account in the token no longer exists |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

Note the split: `The template does not exist` is a `400`, not a `404`, while a template belonging to a different account is a `403`. All three mean "check your `templateId`".

### `POST /api/v1/flow/{templateId}/job/{jobId}/abort`

| Status | Message | Cause |
|---|---|---|
| 401 | `Invalid token` | Expired or invalid token |
| 403 | `Access denied` | The job belongs to another account |
| 404 | `Job not found` | No job with that `jobId` |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

### `POST /api/v1/flow/{templateId}/abort`

| Status | Message | Cause |
|---|---|---|
| 401 | `Invalid token` | Expired or invalid token |
| 403 | `Access denied` | The template belongs to another account |
| 404 | `Template not found` | No template with that `templateId` |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

### `GET /api/v1/jobs/status`

| Status | Message | Cause |
|---|---|---|
| 401 | `The Access-Token header is missing` | The header was not sent |
| 401 | `Invalid token` | Expired or invalid token |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

### `GET /api/v1/jobs/{jobId}/documents/download`

| Status | Message | Cause |
|---|---|---|
| 400 | `Invalid jobId` | `jobId` is not a valid number |
| 401 | `Invalid token` | Expired or invalid token |
| 403 | `This resource is not accessible` | The job belongs to another account |
| 404 | `The job do not exist` | No job with that `jobId` |
| 410 | `The job has been deleted` | The job was deleted and cannot be recovered |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

### `GET /api/v1/accounts/documents/pending/download`

| Status | Message | Cause |
|---|---|---|
| 401 | `Invalid token` | Expired or invalid token |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 requests from your account in 60 seconds, across all endpoints. The body uses `errorInfo`, not `message` — see [limits.md](limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure |

## Things that look like errors and are not

**`204 No Content` on a download.** There is nothing ready yet. Read `X-Documents-Metadata` to see why: documents still `pending` or `processing` means wait; all `error` means the job produced nothing and waiting will not help.

**`abortedDocuments: 0`.** You cancelled a job whose documents had all started processing already. The call worked; there was simply nothing left to cancel.

**An empty download the second time.** Downloading marks documents as downloaded, and they are not returned again by the account-wide endpoint. See [download-results.md](endpoints/download-results.md).

## Retry with backoff

Only for `5xx` and network failures. Start around 2 seconds, double each time, cap the wait, and bound the number of attempts so a persistent outage fails instead of hanging.

```python
# From privacyshield_client.py
delay = 2.0
for attempt in range(1, max_attempts + 1):
    try:
        return self._request(method, path, **kwargs)
    except ServerError:
        if attempt == max_attempts:
            raise
        time.sleep(delay)
        delay = min(delay * 2, 30.0)
```

Do not put `429` in this loop. The rate-limit window is 60 seconds and fixed, so a 2-second backoff just lands in the same window again. On `429`, wait 60 seconds, then retry. Limits enforced on a single request — size, format and page quota — return `400` and are never retried. See [limits.md](limits.md#rate-limiting).
