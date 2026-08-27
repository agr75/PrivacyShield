# Python examples

Runnable examples for the PrivacyShield REST API. Python 3.9 or newer, one
dependency: `requests`.

## Setup

```bash
pip install -r requirements.txt
cp config.example.py config.py
```

Then export your credentials. With these set, `config.py` needs no editing:

```bash
export PRIVACYSHIELD_ACCOUNT_ID=1042
export PRIVACYSHIELD_API_KEY=your-api-key
export PRIVACYSHIELD_TEMPLATE_ID=789
```

| Variable | Required | Where it comes from |
|---|---|---|
| `PRIVACYSHIELD_ACCOUNT_ID` | Yes | [My Account](https://privacyshield.fundamentia.com/administration) in the administration panel |
| `PRIVACYSHIELD_API_KEY` | Yes | [Api Key](https://privacyshield.fundamentia.com/administration?tab=apikey) in the administration panel |
| `PRIVACYSHIELD_TEMPLATE_ID` | For upload and cancel | The template you send documents to, created in the web interface |
| `PRIVACYSHIELD_JOB_ID` | For examples 04 and 06 | The `job` value example 02 prints |
| `PRIVACYSHIELD_BASE_URL` | No | Defaults to `https://api.privacyshield.fundamentia.com/api/v1` |

A missing variable stops the example with a message naming it and where to find
its value. Nothing is read from a hardcoded credential — `config.py` is
git-ignored, and you can leave its fallbacks empty.

The variable is called `PRIVACYSHIELD_TEMPLATE_ID` because `templateId` is what the API
path uses. The web interface calls the same thing a **template**. See the
[glossary](../../docs/introduction.md#glossary).

## The examples

| File | What it shows |
|---|---|
| [01_authenticate.py](01_authenticate.py) | Exchange the API key for an access token |
| [02_upload_document.py](02_upload_document.py) | Send one or more files in a single multipart request |
| [03_check_status.py](03_check_status.py) | List jobs and their status — the endpoint you poll |
| [04_download_result.py](04_download_result.py) | Download one job's results, including the `204` case |
| [05_end_to_end.py](05_end_to_end.py) | **The whole flow with polling.** Start here |
| [06_cancel_job.py](06_cancel_job.py) | Cancel one job, or every job of a template |
| [07_download_pending.py](07_download_pending.py) | Drain everything pending across the account |
| [08_batch_processing.py](08_batch_processing.py) | A folder at a time, with retries and a summary |
| [privacyshield_client.py](privacyshield_client.py) | Reusable client — token renewal, retries, polling |

Examples 01–04, 06 and 07 call `requests` directly so you can see exactly what
goes over the wire. Examples 05 and 08 use `PrivacyShieldClient`, which is the
file to copy into your own project.

## Running them

```bash
python3 01_authenticate.py

python3 02_upload_document.py contract.pdf
python3 02_upload_document.py contract.pdf scan.png invoice.zip   # mixed types are fine

python3 03_check_status.py            # page 0
python3 03_check_status.py 1          # page 1

python3 04_download_result.py         # uses PRIVACYSHIELD_JOB_ID
python3 04_download_result.py 12345   # or an explicit job

python3 05_end_to_end.py contract.pdf

python3 06_cancel_job.py 12345
python3 06_cancel_job.py --template   # every active job of the template

python3 07_download_pending.py        # up to 5 batches of 10 documents
python3 08_batch_processing.py ./inbox
```

Downloads are written to `./downloads`, which is git-ignored.

## What example 05 prints

```
PrivacyShield end-to-end example
  base URL : https://api.privacyshield.fundamentia.com/api/v1
  account  : 1042
  template : 789
  files    : 1

1) Authenticating ...
   Access token obtained.
2) Uploading 1 file(s) ...
   Job 12345 created, 1 document(s) accepted.
3) Waiting for job 12345 (timeout 900s) ...
   [   0s] new           - next check in 2s
   [   2s] processing    - next check in 4s
   [  22s] completed
4) Downloading results ...
   DOCUMENT                       STATUS      PAGES  COMPLETED
   contract.pdf                   done           12  2025-06-09T14:31:02
   Saved application/pdf to downloads/contract.pdf (39.1 KB)
   Those documents are now marked as downloaded.

Done in 24s. Job 12345 finished with status 'completed'.
```

## Behaviour worth knowing before you copy this code

**The token expires after 30 minutes.** `PrivacyShieldClient` renews it about
five minutes early and also retries a request once after a `401`. That second
half matters: a token can be invalidated before its nominal expiry.

**`204 No Content` is not an error.** It means nothing is ready to download yet.
Every example treats it as an ordinary outcome and reads
`X-Documents-Metadata`, which is present on `204` responses too and is the only
per-document report the API offers.

**Downloading marks documents as downloaded.** They are not sent again. So the
examples write the file to disk before printing anything, and never overwrite an
existing file — `unique_path()` adds ` (2)` instead. A file this code clobbered
could not be fetched again.

**Retry only `5xx` and network failures.** A `400` fails identically forever:
retrying an unsupported file or an exhausted quota just repeats the failure.
`403`, `404` and `410` are configuration or retention problems, not transient
ones.

**Polling is the only completion signal.** There are no webhooks. `wait_for_job`
backs off exponentially from 2 to 30 seconds, honours a global timeout, and
stops with `JobVanishedError` if the job leaves the listing — which is what
happens to a job that fails completely, and would otherwise be an infinite wait.

**Results are deleted 5 days after they finish.** Persist them somewhere of your
own as soon as you receive them.

## Checking the code compiles

```bash
python3 -m py_compile *.py
```

There is no test environment: PrivacyShield has a single production
environment, and every submission consumes page quota. Test with documents you
are allowed to process, against a template created for that purpose.
