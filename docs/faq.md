# FAQ

## Can I create or edit templates through the API?

No. Templates are created and configured only in the [web interface](https://privacyshield.fundamentia.com). There are no endpoints for listing, creating, editing or deleting them.

Ask an account administrator to build the template and give you its numeric identifier — that is the `templateId` you send documents to.

## How do I know when a job has finished?

Poll. There are no webhooks and no callbacks.

Call `GET /api/v1/jobs/status` until your job reports `completed`, then download. Use exponential backoff, set a global timeout, and refresh your token inside the loop. See [lifecycle.md](lifecycle.md#polling-strategy) and [`examples/python/05_end_to_end.py`](../examples/python/05_end_to_end.py).

## The download returned `204`. What went wrong?

Nothing. `204 No Content` means no document is ready for download right now. It is a normal part of an asynchronous API.

Read the `X-Documents-Metadata` header, which is present on `204` responses too, to find out which case you are in:

| What the metadata shows | What it means |
|---|---|
| Documents in `pending` or `processing` | Still working. Keep polling |
| Every document in `done`, already downloaded | You already have them |
| Every document in `error` | The job produced nothing. Waiting will not help |
| An empty array `[]` (account download) | Nothing pending anywhere in the account |

## Why did the same download return nothing the second time?

Downloading marks documents as downloaded, and downloaded documents are not returned again by the account-wide endpoint.

That is a feature — it lets several workers share the account-wide download without processing the same file twice — but it means a download you lose is a download you cannot repeat that way. Write the file to disk before you acknowledge it anywhere else. See [download-results.md](endpoints/download-results.md#downloading-marks-documents-as-downloaded).

## Which download endpoint should I use?

Use the per-job download when you know the job you are waiting for — the normal case for a request/response integration.

Use the account-wide download when you run a collector process that drains everything ready across all templates, or when you lost track of your job identifiers. It always returns a ZIP and at most 10 documents per call.

Full comparison: [download-results.md](endpoints/download-results.md#choosing-between-the-two-downloads).

## Can several processes download at the same time?

Yes, for the account-wide endpoint. Documents are marked as downloaded atomically before the response is sent, so two concurrent callers get two disjoint sets of documents rather than duplicates.

## Why did I get a PDF instead of a ZIP?

The per-job download returns `application/pdf` when exactly one document is ready and `application/zip` when several are. Check the `Content-Type` of the response rather than assuming — with a multi-document job you may get a single PDF on one call and a ZIP on the next.

The account-wide download always returns a ZIP, even for one document.

## My job reports `completed` but a document is missing

`completed` means "no document is processing right now and at least one has finished". A document that has not started yet leaves the job in `completed` while still being pending.

Download what is ready, then check `X-Documents-Metadata`: if any document is still `pending` or `processing`, keep polling and download again later. See [lifecycle.md](lifecycle.md#completed-is-not-the-same-as-everything-is-done).

## I cancelled a job and `abortedDocuments` came back as `0`

Cancelling affects only documents that have not started processing. If every document was already in the pipeline, there is nothing to cancel. The call succeeded; it just had no work to do.

Those in-flight documents finish normally and their pages are charged. Only documents still `pending` when you cancel cost you nothing. See [limits.md](limits.md#when-pages-are-charged).

## How long do I have to download my results?

Five days from the moment a document finishes processing. After that it is deleted automatically, with no exceptions, and the download returns `410 The job has been deleted`.

The pages are already charged at that point, so a missed download costs you quota and the document. Persist every result to your own storage as soon as you receive it, and do not design a workflow that collects results weekly. See [limits.md](limits.md#retention).

## My job disappeared from `GET /api/v1/jobs/status`

The listing excludes deleted jobs and jobs that failed completely. If a job you are polling for stops appearing, stop polling — it will not come back. Fetch the job's documents once to read `X-Documents-Metadata` and see what happened.

## Is there a rate limit?

Yes. Your account can make 10 requests per 60 seconds, counted across all endpoints. The 11th returns `429`, with no `Retry-After` header and a body that is not the standard `{success, message}` envelope. Wait 60 seconds and retry. Do not use the `5xx` backoff, which retries too soon. See [limits.md](limits.md#rate-limiting).

## I get `401` in the middle of a long wait

Your token expired. Tokens are valid for 30 minutes and there is no refresh token.

Track when you obtained the token and get a new one before it reaches 25 minutes, and additionally retry once on a `401`. Both example clients do this. See [authentication.md](authentication.md#refresh-strategy).

## I get `400 The template does not exist`, but it does exist

Three different responses mean "check your `templateId`":

- `400 The template does not exist` — no template with that identifier
- `403 Access denied` — the template exists but belongs to another account
- `404 Account not found` — the account encoded in your token no longer exists

Confirm the identifier in the web interface, and confirm you authenticated with the account that owns the template.

## What happens to a file with a virus?

It is rejected by the scan, never processed, and left in the `error` state. It consumes no pages. The upload response is still a success — the failure appears in `X-Documents-Metadata` when you download.

## Can I send several files in one request?

Yes. Repeat the `file` part once per file in the same `multipart/form-data` request. All of them land in one job and `docs` counts them all.

They do not have to be the same type — PDFs and images can travel together in one request. See [submit-documents.md](endpoints/submit-documents.md#sending-several-files-at-once).

## Is there a sandbox or test environment?

No. There is a single production environment. Test with documents you are allowed to process, against a template created for that purpose.

## How do I report a problem?

For an error in this documentation or in the examples, open an issue in this repository.

For a problem with the service itself — a stuck job, a quota question, a template that does not anonymize what you expect — use the [Contact section](https://privacyshield.fundamentia.com/administration) of the administration panel. Include the `jobId` and the approximate time of the request.
