# Download results

Two endpoints return anonymized documents. They differ in scope, in format and in how much they return per call.

| | Per job | Per account |
|---|---|---|
| Path | `GET /api/v1/jobs/{jobId}/documents/download` | `GET /api/v1/accounts/documents/pending/download` |
| Scope | One job | Every job in the account |
| Content type | `application/pdf` for one document, `application/zip` for several | Always `application/zip` |
| Documents per call | Every finished, not-yet-downloaded document of the job | At most 10 |
| Metadata includes `jobId` | No | Yes |
| Nothing ready | `204` | `204` |

Both mark what they return as downloaded. Read [Downloading marks documents as downloaded](#downloading-marks-documents-as-downloaded) before you build retry logic around either.

---

## Download the documents of one job

```
GET /api/v1/jobs/{jobId}/documents/download
```

Returns the finished documents of one job. One document comes back as a PDF, several as a ZIP.

### Headers

| Header | Value | Required |
|---|---|---|
| `Access-Token` | The JWT from [authentication](authentication.md) | Yes |

### Path parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `jobId` | Long | Yes | The `job` value returned by [submit-documents](submit-documents.md) |

### Query parameters

None.

### Request body

None.

### Success response — documents available

`200 OK`

| Response header | Example | Description |
|---|---|---|
| `Content-Type` | `application/zip` | `application/pdf` for a single document, `application/zip` for several |
| `Content-Disposition` | `attachment; filename="job_12345_documents.zip"` | Suggested filename |
| `X-Documents-Metadata` | See [below](#the-x-documents-metadata-header) | Status of **every** document in the job, not just the returned ones |

The body is the raw PDF or ZIP. Do not assume which one you got — read `Content-Type`. A three-document job can hand you a single PDF on one call (one document finished) and a ZIP on the next (the other two finished).

### Success response — nothing available

`204 No Content`

No body. `X-Documents-Metadata` is still present and still describes every document in the job, which is how you tell "still processing" from "everything failed".

This is a normal response, not an error. See [Reading a 204](#reading-a-204).

### Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 400 | `Invalid jobId` | `jobId` is not a valid number — not numeric, or empty | Fix the request. Do not retry |
| 401 | `Invalid token` | Token expired or invalid | Get a new token and retry once |
| 403 | `This resource is not accessible` | The job belongs to another account | Configuration problem. Abort |
| 404 | `The job do not exist` | No job with that `jobId` | Check the identifier. Abort |
| 410 | `The job has been deleted` | The job was deleted, normally by the [5-day retention window](../limits.md#retention) | Terminal. The result is gone. Do not retry |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

`404` and `410` mean different things. A `404` suggests a wrong identifier — check it. A `410` means the identifier was right and you are too late.

---

## Download everything pending in the account

```
GET /api/v1/accounts/documents/pending/download
```

Returns finished documents that have not been downloaded yet, from any job in the account, in a single ZIP. Useful when you run a collector process, or when you have lost track of job identifiers.

### Headers

| Header | Value | Required |
|---|---|---|
| `Access-Token` | The JWT from [authentication](authentication.md) | Yes |

### Path, query parameters and body

None. There is no way to filter by template, by job or by date, and no way to raise the 10-document limit.

### Success response — documents available

`200 OK`

| Response header | Example | Description |
|---|---|---|
| `Content-Type` | `application/zip` | Always a ZIP, even for a single document |
| `Content-Disposition` | `attachment; filename="documents.zip"` | Suggested filename |
| `X-Documents-Metadata` | See [below](#the-x-documents-metadata-header) | The documents included in this ZIP, each with an extra `jobId` field |

**At most 10 documents per call** — the 10 most recently modified. If more are waiting, call again: the returned documents are marked as downloaded and drop out of the pending set, so each call gives you the next batch. Stop when you get a `204`.

Documents are marked as downloaded **atomically before the response is sent**, so concurrent callers get disjoint sets rather than duplicates. Several workers can drain the queue in parallel safely.

### Success response — nothing available

`204 No Content`

No body. `X-Documents-Metadata` contains an empty array:

```
X-Documents-Metadata: []
```

That empty array is the definitive "nothing is waiting anywhere in the account".

### Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 401 | `Invalid token` | Token expired or invalid | Get a new token and retry once |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

No `404`: an account with nothing pending returns `204`, not an error.

---

## The `X-Documents-Metadata` header

This header is the observability mechanism of both download endpoints, and it is easy to miss because it arrives on a response whose body you are busy writing to disk. **Read it on every download, including on `204`.** It is the only place the API tells you the per-document outcome.

The value is a JSON array, ASCII-encoded, on one line:

```
X-Documents-Metadata: [{"documentId":98761,"documentName":"contract.pdf","status":"done","completedAt":"2025-06-09T14:31:02","pageCount":12},{"documentId":98762,"documentName":"invoice-2025-03.pdf","status":"done","completedAt":"2025-06-09T14:33:47","pageCount":2},{"documentId":98763,"documentName":"report.pdf","status":"pending","completedAt":null,"pageCount":null}]
```

Formatted, that is:

```json
[
  {
    "documentId": 98761,
    "documentName": "contract.pdf",
    "status": "done",
    "completedAt": "2025-06-09T14:31:02",
    "pageCount": 12
  },
  {
    "documentId": 98762,
    "documentName": "invoice-2025-03.pdf",
    "status": "done",
    "completedAt": "2025-06-09T14:33:47",
    "pageCount": 2
  },
  {
    "documentId": 98763,
    "documentName": "report.pdf",
    "status": "pending",
    "completedAt": null,
    "pageCount": null
  }
]
```

| Field | Type | Description |
|---|---|---|
| `documentId` | Long | Internal document identifier |
| `documentName` | String | Original filename |
| `status` | String | `pending`, `processing`, `done`, `aborted` or `error` — see [lifecycle.md](../lifecycle.md#document-states) |
| `completedAt` | String \| null | Completion timestamp, ISO 8601, or `null` if not finished |
| `pageCount` | Long \| null | Page count, or `null` if not known yet |
| `jobId` | Long | **Account download only.** The job the document belongs to |

### The `jobId` difference

`jobId` appears **only** in the account-wide download. The per-job download omits it, because you already know the job — you put it in the URL.

That single field is what makes the account-wide download usable: without it you would get a ZIP of anonymized files with no way to tell which submission each one answers. If you route documents from different sources through PrivacyShield, key your bookkeeping on `jobId` plus `documentName` from this header.

### Metadata scope differs between the two endpoints

| Endpoint | What the header describes |
|---|---|
| Per job | **Every** document in the job — including ones still `pending`, still `processing`, `aborted` or in `error`, and ones you already downloaded |
| Per account | **Only** the documents included in this ZIP |

So the per-job header is a status report on the whole job, while the account header is a manifest of what you just received. Do not read the account header expecting to learn about documents that were not returned.

### Reading a `204`

On a `204`, the metadata tells you what to do next:

| What the metadata shows | Meaning | Next step |
|---|---|---|
| Some documents `pending` or `processing` | Still working | Keep polling with backoff |
| Every document `done` | You already downloaded them all | Stop. There is nothing new |
| Every document `error` | The job produced nothing | Stop. Waiting will not help. Inspect the source files |
| Every document `aborted` | The job was cancelled | Stop |
| `[]` (account download) | Nothing pending in the account | Stop |

Without this header, "still processing" and "everything failed" are the same empty response, and a polling loop cannot tell them apart. That is why you read it even when there is no body.

---

## Downloading marks documents as downloaded

> **A successful download changes server state.** Every document returned is marked as downloaded. This is not idempotent — a second identical request does not return the same content.

The consequences are different for the two endpoints:

| Endpoint | Second identical call returns |
|---|---|
| Per job | Only documents finished since the previous call. Already-downloaded documents are not returned again |
| Per account | The next batch of up to 10 — never the same documents |

What this means in practice:

- **Write the file to durable storage before you acknowledge it.** If your process crashes between receiving the bytes and saving them, the API will not hand them back. `X-Documents-Metadata` will show the document as `done`, and the download will return `204`.
- **Never retry a download the way you retry a read.** A retry after a partial failure gives you a different, possibly empty, response. Treat the response body as a one-shot resource.
- **Do not use the download endpoint as a status check.** Calling it to see whether a job is finished consumes the results as a side effect. Poll [`GET /api/v1/jobs/status`](job-status.md) instead — it changes nothing.
- **Concurrency is safe but greedy.** Two workers calling the account download get disjoint sets. Neither gets duplicates, and neither can undo taking its batch.

The job's status also moves to `downloaded` once every finished document has been collected, which is your signal that the job is fully drained.

---

## Choosing between the two downloads

**Use the per-job download** when you know which job you are waiting for. This is the normal case: you uploaded, you kept the `job` value, you polled, you download. You get exactly that job's documents with no cap, and the metadata tells you whether the job still has work outstanding.

**Use the account-wide download** when:

- you run a collector that drains everything ready across all templates on a schedule
- several workers should share the pending queue without coordinating — the atomic marking does the coordination for you
- you lost the job identifiers and need to recover the results anyway

Its limitations are real: 10 documents per call, always a ZIP, no filtering, and you depend on the `jobId` field in the metadata to work out what each file is.

You can mix them. A common shape is per-job downloads on the request path, plus a periodic account-wide sweep as a safety net for results nobody collected — which also protects you from the [5-day retention window](../limits.md#retention).

---

## Examples

Job `12345` has three documents; two are finished.

### cURL

Per job — `-J -O` makes curl honour the server's filename, `-D` captures the headers so you can read the metadata:

```bash
curl "https://api.privacyshield.fundamentia.com/api/v1/jobs/12345/documents/download" \
  -H "Access-Token: $ACCESS_TOKEN" \
  -D headers.txt -J -O

grep -i "^x-documents-metadata:" headers.txt
```

Per account:

```bash
curl "https://api.privacyshield.fundamentia.com/api/v1/accounts/documents/pending/download" \
  -H "Access-Token: $ACCESS_TOKEN" \
  -D headers.txt -o documents.zip

# 204 leaves a zero-byte file behind. Check the status code before trusting it.
head -1 headers.txt
```

### Python

```python
import json
import os
import requests

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"
BASE_URL = os.environ.get("PRIVACYSHIELD_BASE_URL", DEFAULT_BASE_URL)
JOB_ID = 12345

response = requests.get(
    f"{BASE_URL}/jobs/{JOB_ID}/documents/download",
    headers={"Access-Token": access_token},
    timeout=300,
)

# Read the metadata first: it is the only per-document report, and it is
# present on 204 too.
metadata = json.loads(response.headers.get("X-Documents-Metadata", "[]"))
for doc in metadata:
    print(f"  {doc['documentName']:<24} {doc['status']:<12} "
          f"pages={doc['pageCount']}")

if response.status_code == 204:
    pending = [d for d in metadata if d["status"] in ("pending", "processing")]
    print("Nothing ready yet." if pending else "Nothing left to download.")
else:
    response.raise_for_status()
    # Persist before doing anything else: the API will not return this again.
    with open("result.zip", "wb") as handle:
        handle.write(response.content)
    print(f"Saved {len(response.content)} bytes")
```

Full examples: [`04_download_result.py`](../../examples/python/04_download_result.py) and [`07_download_pending.py`](../../examples/python/07_download_pending.py). Both use the `Content-Disposition` parser in [`privacyshield_client.py`](../../examples/python/privacyshield_client.py), which handles the RFC 5987 and RFC 2047 forms the server sends for non-ASCII filenames.

### Java

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/jobs/" + jobId + "/documents/download"))
        .header("Access-Token", accessToken)
        .GET()
        .build();

HttpResponse<byte[]> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofByteArray());

// Read the metadata first — it arrives on 204 as well.
String rawMetadata = response.headers()
        .firstValue("X-Documents-Metadata").orElse("[]");
for (JsonNode doc : new ObjectMapper().readTree(rawMetadata)) {
    System.out.printf("  %-24s %-12s pages=%s%n",
            doc.get("documentName").asText(),
            doc.get("status").asText(),
            doc.get("pageCount").isNull() ? "-" : doc.get("pageCount").asText());
}

if (response.statusCode() == 204) {
    System.out.println("Nothing ready yet.");
} else {
    // Persist before doing anything else: the API will not return this again.
    Files.write(Path.of("result.zip"), response.body());
    System.out.println("Saved " + response.body().length + " bytes");
}
```

Full examples: [`DownloadResult.java`](../../examples/java/src/main/java/com/privacyshield/examples/DownloadResult.java) and [`DownloadPending.java`](../../examples/java/src/main/java/com/privacyshield/examples/DownloadPending.java)

## Next

Cancel work you no longer need: [cancel-jobs.md](cancel-jobs.md)
