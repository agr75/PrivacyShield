# Submit documents

Sends one or more documents to a template for anonymization. Returns immediately with a job identifier — nothing is anonymized yet.

```
POST /api/v1/flow/{templateId}
```

The path segment is spelled `flow`, an older internal name; everything else — the `templateId` parameter, the job listing, the error messages — says template. See the [glossary](../introduction.md#glossary).

## Headers

| Header | Value | Required |
|---|---|---|
| `Access-Token` | The JWT from [authentication](authentication.md) | Yes |
| `Content-Type` | `multipart/form-data; boundary=...` | Yes — set by your HTTP client |

Let your HTTP library set `Content-Type`. Writing it by hand without the matching boundary produces a request the server cannot parse.

## Path parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `templateId` | Long | Yes | Identifier of the template to send the documents to. Created and visible in the web interface |

## Query parameters

None.

## Request body

`multipart/form-data`

| Field | Type | Required | Description |
|---|---|---|---|
| `file` | File | Yes | A PDF, an image (`.png`, `.jpg`, `.jpeg`, `.tif`, `.tiff`) or a ZIP containing only PDFs and images |

Images are converted to PDF automatically. A ZIP is expanded and each file inside becomes its own document in the same job.

### Sending several files at once

Repeat the `file` part, once per file, in a single request. Several `multipart` parts share the name `file`:

```
--boundary
Content-Disposition: form-data; name="file"; filename="contract.pdf"
Content-Type: application/pdf

%PDF-1.7...
--boundary
Content-Disposition: form-data; name="file"; filename="invoice-2025-03.pdf"
Content-Type: application/pdf

%PDF-1.7...
--boundary--
```

The response's `docs` field counts every accepted document, and all of them belong to one job.

**The files do not have to be the same type.** You can mix PDFs and images in one request, and you can include a ZIP alongside loose files. Each part is validated on its own.

A ZIP achieves the same result — one job, many documents — and is the better choice when the file count is high or the names come from somewhere you do not control, since you validate the archive once instead of building a large multipart body.

Either way, the 150 MB limit applies to the whole request, not to each file. See [limits.md](../limits.md#request-size).

## Success response

`200 OK`

```json
{
  "success": true,
  "message": "Documents send succesfully",
  "docs": 3,
  "job": 12345
}
```

| Field | Type | Description |
|---|---|---|
| `success` | Boolean | `true` |
| `message` | String | `Documents send succesfully` |
| `docs` | Integer | Number of documents accepted into the job. A ZIP with 3 PDFs reports `3`, not `1` |
| `job` | Long | Identifier of the created job. **Keep it** — you need it for status, download and cancellation |

> **`200` means "accepted", not "anonymized".** The documents are queued. Poll for completion before downloading — see [lifecycle.md](../lifecycle.md#polling-strategy).

The field is called `job` here and `jobId` everywhere else in the API. It is the same value.

### Save the job identifier

It is the only handle you get on this submission. If you lose it you cannot target the job for status or download, and your only recovery is the account-wide download, which returns whatever is ready across the whole account in batches of 10. Persist `job` before you do anything else with the response.

## Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 400 | `The template does not exist` | No template with that `templateId` | Check the identifier in the web interface. Do not retry |
| 400 | `Not enough pages` | The template's page quota cannot cover this submission | Business condition. Top up the quota; retrying changes nothing |
| 400 | `Unsupported file type` | Not a PDF, a supported image, or a valid ZIP | Do not retry. Convert the file first |
| 400 | `Unsupported file inside a ZIP` | The ZIP contains an unsupported file | The whole submission failed. Rebuild the archive |
| 400 | `Invalid file name` | The filename contains forbidden characters | Sanitize the name — see [limits.md](../limits.md#filenames) |
| 401 | `The Access-Token header is missing` | Header not sent | Add the header |
| 401 | `Invalid token` | Token expired or invalid | Get a new token and retry once |
| 403 | `Access denied` | The template belongs to another account | Configuration problem. Abort |
| 404 | `Account not found` | The account in the token no longer exists | Configuration problem. Abort |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

Three different responses all mean "check your `templateId`": `400 The template does not exist`, `403 Access denied` (it exists but belongs to someone else) and `404 Account not found` (the account in your token is gone).

A rejected submission creates no job and consumes no pages.

## Examples

All examples send `contract.pdf` to template `789`.

### cURL

```bash
curl -X POST "https://api.privacyshield.fundamentia.com/api/v1/flow/789" \
  -H "Access-Token: $ACCESS_TOKEN" \
  -F "file=@contract.pdf"
```

Several files in one request:

```bash
curl -X POST "https://api.privacyshield.fundamentia.com/api/v1/flow/789" \
  -H "Access-Token: $ACCESS_TOKEN" \
  -F "file=@contract.pdf" \
  -F "file=@invoice-2025-03.pdf" \
  -F "file=@report.pdf"
```

### Python

```python
import os
import requests

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"
BASE_URL = os.environ.get("PRIVACYSHIELD_BASE_URL", DEFAULT_BASE_URL)
TEMPLATE_ID = os.environ["PRIVACYSHIELD_TEMPLATE_ID"]

file_paths = ["contract.pdf", "invoice-2025-03.pdf", "report.pdf"]

# Keep the handles open until the request is sent, then close them all.
open_files = [open(path, "rb") for path in file_paths]
try:
    files = [("file", (os.path.basename(path), handle))
             for path, handle in zip(file_paths, open_files)]
    response = requests.post(
        f"{BASE_URL}/flow/{TEMPLATE_ID}",
        headers={"Access-Token": access_token},
        files=files,
        timeout=300,          # uploads can be large; do not use the default
    )
finally:
    for handle in open_files:
        handle.close()

response.raise_for_status()
result = response.json()
print(f"Job {result['job']} created with {result['docs']} document(s)")
```

Full example: [`examples/python/02_upload_document.py`](../../examples/python/02_upload_document.py)

### Java

`HttpClient` has no multipart support, so you build the body yourself:

```java
String boundary = "PrivacyShieldBoundary" + UUID.randomUUID();
ByteArrayOutputStream body = new ByteArrayOutputStream();

for (Path path : List.of(Path.of("contract.pdf"))) {
    body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
    body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
            + path.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
    body.write("Content-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
    body.write(Files.readAllBytes(path));
    body.write("\r\n".getBytes(StandardCharsets.UTF_8));
}
body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/flow/" + templateId))
        .header("Access-Token", accessToken)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
        .build();

HttpResponse<String> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString());

JsonNode result = new ObjectMapper().readTree(response.body());
System.out.println("Job " + result.get("job").asLong()
        + " created with " + result.get("docs").asInt() + " document(s)");
```

The trailing `\r\n` after each part and the closing `--boundary--` are mandatory. A missing one produces a `400` that looks like a server problem and is not.

Full example: [`UploadDocument.java`](../../examples/java/src/main/java/com/privacyshield/examples/UploadDocument.java) — the multipart builder is reusable in [`PrivacyShieldClient.java`](../../examples/java/src/main/java/com/privacyshield/examples/PrivacyShieldClient.java).

## Next

Job `12345` is now queued. Check on it: [job-status.md](job-status.md)
