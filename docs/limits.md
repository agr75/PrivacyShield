# Limits and requirements

## At a glance

| Limit | Value |
|---|---|
| Maximum request size | 150 MB |
| Pages per submission | Bounded by the page quota of the template you send to |
| Result retention | 5 days after a document finishes |
| Documents per account-wide download | 10 |
| Jobs per page in the status listing | 40 (fixed) |
| Access token lifetime | 30 minutes |

## Accepted file formats

| Format | Extensions | Notes |
|---|---|---|
| PDF | `.pdf` | Processed directly |
| Image | `.png`, `.jpg`, `.jpeg`, `.tif`, `.tiff` | Converted to PDF automatically |
| ZIP | `.zip` | Must contain only PDFs and supported images |

A ZIP is expanded and each file inside becomes its own document within the same job. One ZIP with five PDFs produces one job with five documents — which is why the upload response returns `docs: 5` for a single uploaded file.

Anything else is rejected with a `400`, and so is a ZIP that contains an unsupported file. In the ZIP case the whole submission fails, not just the offending entry, so validate archives before sending them. The exact messages are in [errors.md](errors.md#post-apiv1flowtemplateid).

## Request size

The hard limit is **150 MB per request**. This is the size of the entire request, so if you send several files in one call it applies to their combined size, not to each one.

For large batches, send several requests rather than one oversized archive. You get one job per request, which is also easier to track and to cancel selectively.

## Page quota

Your account holds page quota in two separate modalities, **basic** and **advanced**. Each template is configured against one of them, so **the template you send a document to determines which quota pays for it**. That single sentence covers both halves of how quota works: the pools belong to the account, the choice of pool belongs to the template.

The practical consequence: quota is not one number you can watch. Two templates in the same account can have very different amounts of room left, and a submission that succeeds against template `789` can be rejected against another template with no change on your side. If you route documents across several templates, handle the quota error per template rather than treating it as an account-level outage.

A submission that would exceed the remaining quota is rejected before processing:

```
400 · Not enough pages
```

The message carries no numbers, so it tells you that you are short but not by how much. Nothing is processed and no job is created, so handle this as a business condition and not as a transient failure — retrying changes nothing until the quota is topped up.

### When pages are charged

Pages are charged when a document starts processing, not when you upload it.

| Situation | Pages charged |
|---|---|
| Document processed normally | Yes |
| Document cancelled while still `pending` | No |
| Document cancelled after it started processing | Yes — it finishes and is charged in full |
| File rejected by the antivirus scan | No |

Cancelling a mistaken submission therefore recovers the quota of everything that has not started yet, and only that. See [lifecycle.md](lifecycle.md#cancelling-and-your-page-quota).

There is no endpoint that reports remaining quota, and the rejection message does not include the numbers. Read the remaining quota in the web interface, and treat the `400` as the signal that this template is out of pages.

## Filenames

Filenames cannot contain any of these characters:

```
\ / : * ? " < > |
```

nor control characters (ASCII 0–31). A filename that breaks the rule is rejected with a `400`.

This applies to the names of the files inside a ZIP too. Sanitize before uploading:

```python
import re

FORBIDDEN = re.compile(r'[\\/:*?"<>|\x00-\x1f]')

def safe_filename(name: str) -> str:
    return FORBIDDEN.sub("_", name)
```

Non-ASCII characters are fine. Accented filenames come back correctly encoded in `Content-Disposition`, and the example clients decode both the RFC 5987 and RFC 2047 forms the server may send.

## Antivirus scanning

Every uploaded file is scanned before it is accepted. Infected files are not processed: they land in the `error` state and consume no pages. The job itself is created normally, so you will see the failure in the `X-Documents-Metadata` header of a download rather than in the upload response.

## Download limits

The account-wide download (`GET /api/v1/accounts/documents/pending/download`) returns **at most 10 documents per call** — the 10 most recently modified. There is no parameter to raise that.

If you have more than 10 documents waiting, call it repeatedly. Each call returns the next batch, because the documents it returns are marked as downloaded and drop out of the pending set. Stop when you get a `204`.

The per-job download has no document cap: it returns every finished, not-yet-downloaded document of that job.

## Job listing

`GET /api/v1/jobs/status` returns 40 jobs per page, newest first. The page size is fixed and cannot be changed. Use `total` to work out how many pages exist.

Deleted jobs and jobs that failed completely are excluded from both `total` and the listing.

## Retention

> **Documents are deleted automatically 5 days after they finish processing. There are no exceptions and there is no way to extend the window.**

After that, `GET /api/v1/jobs/{jobId}/documents/download` returns `410 The job has been deleted` and the anonymized result is gone. PrivacyShield is not your archive: download your results and store them yourself.

What this means for your integration:

- **Download promptly.** Do not build a workflow that collects results weekly. A job finished on Monday is unavailable from Saturday.
- **Persist before you acknowledge.** Write the file to your own storage as soon as you receive it. `410` is not recoverable, and the pages are already charged.
- **Treat `410` as terminal.** Never retry or re-poll a job that returned `410` — it will never come back. Log the `jobId` and re-submit the source document if you still need it.
- **The 5 days start when the document finishes**, not when you upload it. A slow job that finishes two days after submission is available until day seven.

## Rate limiting

Limits are applied **per request**, not across requests. There is no throttling by request rate and no cap on concurrent requests, so you do not need a retry branch for a throttling status code.

What each request is checked against:

| Check | Failure |
|---|---|
| Request size | `400`, above 150 MB |
| Page quota of the target template | `400 Not enough pages` |
| File format and filename | `400` |

Still keep the `5xx` backoff from [errors.md](errors.md): the absence of throttling is not a promise that the server always answers. And be reasonable with parallelism — every concurrent upload is competing for the same pipeline and the same quota.

No maximum number of documents per job is specified. In practice a job is bounded by the 150 MB request limit and by the template's quota.
