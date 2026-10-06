# Cancel jobs

Two endpoints cancel queued work: one targets a single job, the other every active job of a template.

| | One job | Whole template |
|---|---|---|
| Path | `POST /api/v1/flow/{templateId}/job/{jobId}/abort` | `POST /api/v1/flow/{templateId}/abort` |
| Scope | One job | Every active job of the template |
| Response detail | `abortedDocuments` | `abortedJobs` plus a per-job breakdown |
| Not found | `404 Job not found` | `404 Template not found` |

> **Only documents that have not started processing are cancelled.** Documents already in the pipeline finish normally and their pages are charged. Finished documents are untouched and stay downloadable.

Cancelling is therefore a race against the pipeline: the sooner you call, the more you save. It is not a way to stop work that is already under way, and there is no way to un-cancel.

---

## Cancel one job

```
POST /api/v1/flow/{templateId}/job/{jobId}/abort
```

Note that you need **both** identifiers. The template is part of the path even though the job identifier alone would locate the job.

### Headers

| Header | Value | Required |
|---|---|---|
| `Access-Token` | The JWT from [authentication](authentication.md) | Yes |

### Path parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `templateId` | Long | Yes | The template the job was submitted to |
| `jobId` | Long | Yes | The job to cancel |

### Query parameters and body

None. There is no body — send the POST empty.

### Success response

`200 OK`

Job `12345` had three documents. Two were still queued and were cancelled; the third had already started processing and was left alone:

```json
{
  "success": true,
  "message": "Documents aborted",
  "abortedDocuments": 2
}
```

| Field | Type | Description |
|---|---|---|
| `success` | Boolean | `true` |
| `message` | String | `Documents aborted` |
| `abortedDocuments` | Integer | How many documents actually moved to `aborted` |

### `abortedDocuments: 0` is a success

It means every document had already started processing, so there was nothing left to cancel. The call worked. Do not treat it as an error and do not retry — calling again will also return `0`.

Compare `abortedDocuments` against the `docs` count from the upload if you need to know how much of the job you saved.

### Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 401 | `Invalid token` | Token expired or invalid | Get a new token and retry once |
| 403 | `Access denied` | The job belongs to another account | Configuration problem. Abort |
| 404 | `Job not found` | No job with that `jobId` | Check the identifier. Abort |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

---

## Cancel every job of a template

```
POST /api/v1/flow/{templateId}/abort
```

Cancels the queued documents of every active job of the template, in one call.

> **This affects jobs you did not create.** Anything queued against this template is cancelled, including submissions from other processes, other integrations or the web interface. On a shared template, prefer cancelling job by job.

### Headers

| Header | Value | Required |
|---|---|---|
| `Access-Token` | The JWT from [authentication](authentication.md) | Yes |

### Path parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `templateId` | Long | Yes | The template whose jobs should be cancelled |

### Query parameters and body

None.

### Success response

`200 OK`

```json
{
  "success": true,
  "message": "Jobs aborted",
  "abortedJobs": 2,
  "jobs": [
    { "jobId": 12345, "abortedDocuments": 2 },
    { "jobId": 12346, "abortedDocuments": 1 }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `success` | Boolean | `true` |
| `message` | String | `Jobs aborted` |
| `abortedJobs` | Integer | Number of jobs affected |
| `jobs` | Array | One entry per affected job |

Fields of each entry:

| Field | Type | Description |
|---|---|---|
| `jobId` | Long | The job |
| `abortedDocuments` | Integer | Documents cancelled in that job |


### Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 401 | `Invalid token` | Token expired or invalid | Get a new token and retry once |
| 403 | `Access denied` | The template belongs to another account | Configuration problem. Abort |
| 404 | `Template not found` | No template with that `templateId` | Check the identifier. Abort |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

Note that a non-existent template is `404 Template not found` here, while sending documents to a non-existent template is [`400 The template does not exist`](submit-documents.md#errors). Same mistake, two different status codes.

---

## After cancelling

Cancelling does not delete the job. What is left behind:

- **Cancelled documents** are in `aborted`, which is terminal, and consume no pages.
- **Documents that were already processing** finish normally, become `done` and are downloadable. Their pages are charged.
- **Finished documents** are unaffected and still downloadable.

So a cancelled job can still have results worth collecting. Check `X-Documents-Metadata` on a download before writing the job off — see [download-results.md](download-results.md#the-x-documents-metadata-header).

The job reports `cancelled` only when **every** document is aborted. A job where one document slipped through into processing will report `processing`, then `completed`, and never `cancelled`. See [lifecycle.md](../lifecycle.md#job-states).

## Examples

### cURL

Cancel job `12345` of template `789`:

```bash
curl -X POST \
  "https://api.privacyshield.fundamentia.com/api/v1/flow/789/job/12345/abort" \
  -H "Access-Token: $ACCESS_TOKEN"
```

Cancel every job of template `789`:

```bash
curl -X POST \
  "https://api.privacyshield.fundamentia.com/api/v1/flow/789/abort" \
  -H "Access-Token: $ACCESS_TOKEN"
```

### Python

```python
import os
import requests

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"
BASE_URL = os.environ.get("PRIVACYSHIELD_BASE_URL", DEFAULT_BASE_URL)
TEMPLATE_ID = os.environ["PRIVACYSHIELD_TEMPLATE_ID"]
JOB_ID = 12345

response = requests.post(
    f"{BASE_URL}/flow/{TEMPLATE_ID}/job/{JOB_ID}/abort",
    headers={"Access-Token": access_token},
    timeout=30,
)
response.raise_for_status()
aborted = response.json()["abortedDocuments"]

# Zero is a normal outcome: everything had already started processing.
print(f"{aborted} document(s) cancelled in job {JOB_ID}")
```

Every job of the template:

```python
response = requests.post(
    f"{BASE_URL}/flow/{TEMPLATE_ID}/abort",
    headers={"Access-Token": access_token},
    timeout=30,
)
response.raise_for_status()
body = response.json()

print(f"{body['abortedJobs']} job(s) affected")
for job in body["jobs"]:
    print(f"  job {job['jobId']}: {job['abortedDocuments']} document(s) cancelled")
```

Full example: [`examples/python/06_cancel_job.py`](../../examples/python/06_cancel_job.py)

### Java

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/flow/" + templateId + "/job/" + jobId + "/abort"))
        .header("Access-Token", accessToken)
        .POST(HttpRequest.BodyPublishers.noBody())   // no body on this endpoint
        .build();

HttpResponse<String> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString());

int aborted = new ObjectMapper().readTree(response.body())
        .get("abortedDocuments").asInt();

// Zero is a normal outcome: everything had already started processing.
System.out.println(aborted + " document(s) cancelled in job " + jobId);
```

Full example: [`CancelJob.java`](../../examples/java/src/main/java/com/privacyshield/examples/CancelJob.java)

## Next

Back to the [endpoint index](../introduction.md#endpoints), or see the [complete working integration](../../examples/python/05_end_to_end.py).
