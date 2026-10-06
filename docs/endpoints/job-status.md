# Job status

Lists the jobs of the authenticated account with their current status, newest first, paginated. This is the endpoint you poll while waiting for a job to finish.

```
GET /api/v1/jobs/status
```

There is no endpoint for a single job. You retrieve the listing and find yours in it.

## Headers

| Header | Value | Required |
|---|---|---|
| `Access-Token` | The JWT from [authentication](authentication.md) | Yes |

## Path parameters

None.

## Query parameters

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `page` | Integer | No | `0` | Page number, zero-based |

The page size is fixed at 40 and cannot be changed.

## Request body

None.

## Success response

`200 OK`

```json
{
  "success": true,
  "message": "OK",
  "total": 45,
  "page": 0,
  "limit": 40,
  "jobs": [
    {
      "jobId": 12346,
      "templateId": 789,
      "status": "processing",
      "createdAt": "2025-06-09T15:02:11"
    },
    {
      "jobId": 12345,
      "templateId": 789,
      "status": "completed",
      "createdAt": "2025-06-09T14:23:45"
    }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `success` | Boolean | `true` |
| `message` | String | `OK` |
| `total` | Integer | Total jobs in the account, across all pages |
| `page` | Integer | The page you requested |
| `limit` | Integer | Results per page. Always `40` |
| `jobs` | Array | The jobs on this page, newest first |

### Fields of each job

| Field | Type | Description |
|---|---|---|
| `jobId` | Long | Job identifier — the same value the upload response returns as `job` |
| `templateId` | Long | Identifier of the template the job belongs to — the same value you sent as `templateId` |
| `status` | String | `new`, `processing`, `completed`, `downloaded` or `cancelled` |
| `createdAt` | String | Creation timestamp, ISO 8601 |

Identifiers are numbers everywhere in the API. The only thing that changes is the field name: the upload response calls the job `job`, this listing calls it `jobId`, and the template is `templateId` in a path but `templateId` here. Same values — see the [glossary](../introduction.md#glossary).

## What the statuses mean

| Status | Meaning | Should you keep polling? |
|---|---|---|
| `new` | Every document is still queued | Yes |
| `processing` | At least one document is being processed | Yes |
| `completed` | Nothing is processing and at least one document has finished | No — download now |
| `downloaded` | Every finished document has been downloaded | No |
| `cancelled` | Every document was aborted | No |

The status is computed from the job's documents on each request, not stored, so it can change between two calls seconds apart.

`completed` does not guarantee that every document in the job has finished — a document that has not started yet leaves the job `completed`. Download what is ready, then check `X-Documents-Metadata` for stragglers. See [lifecycle.md](../lifecycle.md#completed-is-not-the-same-as-everything-is-done).

## Jobs missing from the listing

`total` and `jobs` exclude:

- deleted jobs — including everything older than the [5-day retention window](../limits.md#retention)
- jobs that failed completely

So a job vanishing from the listing is a terminal outcome, not a transient glitch. A polling loop must treat "my job is no longer here" as a reason to stop, or it will wait forever for a job that failed outright.

## Paging

Jobs come back newest first, 40 per page, so a job you just created is on page 0. That is what makes polling cheap: you rarely need a second request.

If several processes create jobs against the same account at a high rate, 40 newer jobs can push yours off page 0. Page through until you find it:

```python
def find_job(base_url, access_token, job_id, max_pages=25):
    """Locates a job in the paginated listing. Returns None if it is not there."""
    page = 0
    while page < max_pages:
        body = requests.get(
            f"{base_url}/jobs/status",
            headers={"Access-Token": access_token},
            params={"page": page},
            timeout=30,
        ).json()

        for job in body["jobs"]:
            if job["jobId"] == job_id:
                return job

        # Stop once we have seen every job the account has.
        if (page + 1) * body["limit"] >= body["total"]:
            return None
        page += 1
    return None
```

Bound the loop. An account with 40,000 jobs would otherwise turn one lookup into a thousand requests.

## Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 401 | `The Access-Token header is missing` | Header not sent | Add the header |
| 401 | `Invalid token` | Token expired or invalid | Get a new token and retry once |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

There is no `404` here: an account with no jobs returns `200` with `total: 0` and an empty `jobs` array.

## Examples

### cURL

```bash
curl "https://api.privacyshield.fundamentia.com/api/v1/jobs/status?page=0" \
  -H "Access-Token: $ACCESS_TOKEN"
```

### Python

```python
import os
import requests

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"
BASE_URL = os.environ.get("PRIVACYSHIELD_BASE_URL", DEFAULT_BASE_URL)

response = requests.get(
    f"{BASE_URL}/jobs/status",
    headers={"Access-Token": access_token},
    params={"page": 0},
    timeout=30,
)
response.raise_for_status()
body = response.json()

print(f"{body['total']} job(s) in total, showing page {body['page']}")
for job in body["jobs"]:
    print(f"  job {job['jobId']:>8d}  template {job['templateId']:>6d}  "
          f"{job['status']:<12} created {job['createdAt']}")
```

Full example: [`examples/python/03_check_status.py`](../../examples/python/03_check_status.py)

### Java

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/jobs/status?page=0"))
        .header("Access-Token", accessToken)
        .GET()
        .build();

HttpResponse<String> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString());

JsonNode body = new ObjectMapper().readTree(response.body());
System.out.println(body.get("total").asInt() + " job(s) in total, showing page "
        + body.get("page").asInt());

for (JsonNode job : body.get("jobs")) {
    System.out.printf("  job %8d  template %6d  %-12s created %s%n",
            job.get("jobId").asLong(),
            job.get("templateId").asLong(),
            job.get("status").asText(),
            job.get("createdAt").asText());
}
```

Full example: [`CheckStatus.java`](../../examples/java/src/main/java/com/privacyshield/examples/CheckStatus.java)

## Next

Job `12345` reports `completed`. Download it: [download-results.md](download-results.md)
