#!/usr/bin/env python3
"""Example 06 — cancel queued work.

What it does
    Cancels one job, or every active job of a template with --template.

    Only documents that have not started processing are cancelled. Documents
    already in the pipeline finish normally and their pages are charged, so
    cancelling is a race worth losing early rather than late.

    An `abortedDocuments` of 0 is a success: it means everything had already
    started.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_TEMPLATE_ID      the template the job belongs to
    PRIVACYSHIELD_JOB_ID       the job to cancel, unless passed as an argument
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 06_cancel_job.py [job-id]      # cancel one job
    python3 06_cancel_job.py --template    # cancel every active job of the template
"""

from __future__ import annotations

import sys
from typing import List

import requests

from privacyshield_client import PrivacyShieldError, raise_for_status

try:
    import config
except ModuleNotFoundError:
    raise SystemExit(
        "config.py not found. Create it first:\n\n    cp config.example.py config.py\n"
    )


def authenticate(base_url: str) -> str:
    response = requests.post(
        f"{base_url}/auth/{config.account_id()}",
        json={"api_key": config.api_key()},
        timeout=30,
    )
    raise_for_status(response)
    return response.json()["Access-Token"]


def cancel_one_job(base_url: str, access_token: str, template_id: int, job_id: int) -> None:
    print(f"Cancelling job {job_id} of template {template_id} ...")
    response = requests.post(
        f"{base_url}/flow/{template_id}/job/{job_id}/abort",
        headers={"Access-Token": access_token},
        timeout=30,
    )
    raise_for_status(response)

    aborted = int(response.json()["abortedDocuments"])
    print(f"{aborted} document(s) moved to aborted.")
    if aborted:
        print("Documents that had already started processing are unaffected and will finish.")
    else:
        print(
            "Every document had already started processing, so there was nothing "
            "to cancel. This is a success, not an error."
        )


def cancel_whole_template(base_url: str, access_token: str, template_id: int) -> None:
    # This also cancels jobs your process did not create, including submissions
    # made from the web interface.
    print(f"Cancelling every active job of template {template_id} ...")
    response = requests.post(
        f"{base_url}/flow/{template_id}/abort",
        headers={"Access-Token": access_token},
        timeout=30,
    )
    raise_for_status(response)

    body = response.json()
    print(f"{body['abortedJobs']} job(s) affected:")
    for job in body.get("jobs", []):
        print(f"  job {job['jobId']}: {job['abortedDocuments']} document(s) cancelled")


def main(argv: List[str]) -> int:
    base_url = config.base_url()
    template_id = config.template_id()
    access_token = authenticate(base_url)

    if argv and argv[0] == "--template":
        cancel_whole_template(base_url, access_token, template_id)
    else:
        job_id = int(argv[0]) if argv else config.job_id()
        cancel_one_job(base_url, access_token, template_id, job_id)

    print()
    print(
        "Cancelled documents consume no pages. Finished documents stay "
        "downloadable - check 04_download_result.py before writing the job off."
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
