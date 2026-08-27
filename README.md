# PrivacyShield API

PrivacyShield removes sensitive data from documents. You send a PDF, an image or
a ZIP archive to a template you configured beforehand, and you get the same
document back with the fields that template covers replaced or masked.

This repository is the integration documentation and the working examples. It is
not the service itself.

## Before you start

You need two things that cannot be created through the API:

1. **An account.** Its numeric `accountId` and its API key are your credentials.
2. **A template.** This is the configuration that decides what gets anonymized.
   Templates are created and edited only in the
   [web interface](https://privacyshield.fundamentia.com) — there are no
   endpoints for managing them. Ask an account administrator to build one and
   give you its numeric identifier.

The API calls that identifier `templateId`, and the job listing calls it
`templateId`. Same thing under three names; see the
[glossary](docs/introduction.md#glossary).

## Getting your credentials

Both come from the administration panel and require an account administrator.

| Value | Where to find it |
|---|---|
| `accountId` | [My Account](https://privacyshield.fundamentia.com/administration) |
| `api_key` | [Api Key](https://privacyshield.fundamentia.com/administration?tab=apikey) |

The API key is long-lived and account-wide: anyone holding it can spend your
page quota and download your results. Keep it out of source control, and call
PrivacyShield from a backend rather than from a browser or a mobile app.

## Quickstart

Authenticate and send one document. Base URL includes the `/api/v1` prefix.

```python
import os, requests

BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"

token = requests.post(
    f"{BASE_URL}/auth/{os.environ['PRIVACYSHIELD_ACCOUNT_ID']}",
    json={"api_key": os.environ["PRIVACYSHIELD_API_KEY"]},
).json()["Access-Token"]

with open("contract.pdf", "rb") as document:
    result = requests.post(
        f"{BASE_URL}/flow/{os.environ['PRIVACYSHIELD_TEMPLATE_ID']}",
        headers={"Access-Token": token},
        files=[("file", ("contract.pdf", document))],
    ).json()

print(result)   # {'success': True, 'message': 'Documents send succesfully', 'docs': 1, 'job': 12345}
```

That is the shortest thing that works, not the shortest thing that works well:
it checks no status codes and handles no expiry. The
[examples](examples/python) do both.

## Processing is asynchronous

The upload returns a `job` identifier immediately. **Nothing has been anonymized
at that point.** There are no webhooks: you poll until the job is ready, then
download it.

Two consequences that shape any integration:

- The access token is valid for **30 minutes**, which a long wait can outlive.
- Results are deleted **5 days** after a document finishes. Download and store
  them yourself; PrivacyShield is not an archive.

For the complete flow — polling with backoff, token renewal, downloading — see
[`examples/python/05_end_to_end.py`](examples/python/05_end_to_end.py) or
[`EndToEnd.java`](examples/java/src/main/java/com/privacyshield/examples/EndToEnd.java).

## Documentation

| Page | What it covers |
|---|---|
| [introduction.md](docs/introduction.md) | What the service does, the glossary, the base URL rule, the endpoint list |
| [authentication.md](docs/authentication.md) | API key to access token, the 30-minute expiry, how to refresh |
| [lifecycle.md](docs/lifecycle.md) | Document and job states, diagrams, when to stop polling |
| [errors.md](docs/errors.md) | Every status code, what to do with it, and the message reference |
| [limits.md](docs/limits.md) | File sizes and formats, page quota, retention, filename rules |
| [faq.md](docs/faq.md) | The questions integrations actually run into |

### Endpoint reference

| Page | Endpoints |
|---|---|
| [authentication.md](docs/endpoints/authentication.md) | `POST /api/v1/auth/{accountId}` |
| [submit-documents.md](docs/endpoints/submit-documents.md) | `POST /api/v1/flow/{templateId}` |
| [job-status.md](docs/endpoints/job-status.md) | `GET /api/v1/jobs/status` |
| [download-results.md](docs/endpoints/download-results.md) | `GET /api/v1/jobs/{jobId}/documents/download` and `GET /api/v1/accounts/documents/pending/download` |
| [cancel-jobs.md](docs/endpoints/cancel-jobs.md) | `POST /api/v1/flow/{templateId}/job/{jobId}/abort` and `POST /api/v1/flow/{templateId}/abort` |

## Examples

Python and Java, same examples in both, same console output.
[Python setup](examples/python/README.md) ·
[Java setup](examples/java/README.md)

| Python | Java | What it shows |
|---|---|---|
| [01_authenticate.py](examples/python/01_authenticate.py) | [Authenticate](examples/java/src/main/java/com/privacyshield/examples/Authenticate.java) | Get an access token |
| [02_upload_document.py](examples/python/02_upload_document.py) | [UploadDocument](examples/java/src/main/java/com/privacyshield/examples/UploadDocument.java) | Send files, including mixed types, in one request |
| [03_check_status.py](examples/python/03_check_status.py) | [CheckStatus](examples/java/src/main/java/com/privacyshield/examples/CheckStatus.java) | List jobs and their status |
| [04_download_result.py](examples/python/04_download_result.py) | [DownloadResult](examples/java/src/main/java/com/privacyshield/examples/DownloadResult.java) | Download one job, handling `204` |
| [05_end_to_end.py](examples/python/05_end_to_end.py) | [EndToEnd](examples/java/src/main/java/com/privacyshield/examples/EndToEnd.java) | **The whole flow with polling** |
| [06_cancel_job.py](examples/python/06_cancel_job.py) | [CancelJob](examples/java/src/main/java/com/privacyshield/examples/CancelJob.java) | Cancel a job or a whole template |
| [07_download_pending.py](examples/python/07_download_pending.py) | [DownloadPending](examples/java/src/main/java/com/privacyshield/examples/DownloadPending.java) | Drain everything pending in the account |
| [08_batch_processing.py](examples/python/08_batch_processing.py) | [BatchProcessing](examples/java/src/main/java/com/privacyshield/examples/BatchProcessing.java) | A folder at a time, with retries |
| [privacyshield_client.py](examples/python/privacyshield_client.py) | [PrivacyShieldClient](examples/java/src/main/java/com/privacyshield/examples/PrivacyShieldClient.java) | Reusable client: token renewal, retries, polling |

Credentials come from `PRIVACYSHIELD_ACCOUNT_ID`, `PRIVACYSHIELD_API_KEY` and
`PRIVACYSHIELD_TEMPLATE_ID`. No example contains a credential.

There is one environment, production. There is no sandbox, and every submission
consumes page quota.

## Contributing

Corrections and additions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
For a problem with the service itself rather than with this documentation, use
the [Contact section](https://privacyshield.fundamentia.com/administration) of the administration panel.

## License

The contents of this repository — documentation and examples — are released
under the MIT License. See [LICENSE](LICENSE).

Using the PrivacyShield service is separate: it is governed by its terms of
service and by the contract for your account. The MIT License covers this
repository only and grants no right to use the service.
