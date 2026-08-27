#!/usr/bin/env python3
"""Example 04 — download the results of one job.

What it does
    Requests the finished documents of a job, prints the per-document report
    from the X-Documents-Metadata header, and saves the body to ./downloads.

    Two outcomes are normal: 200 with a PDF or ZIP, and 204 when nothing is
    ready yet. Both carry the metadata header.

    Downloading marks the returned documents as downloaded. They will not be
    sent again, so this example writes the file before printing anything else
    and never overwrites an existing one.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_JOB_ID       the job to download, unless passed as an argument
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 04_download_result.py [job-id]
"""

from __future__ import annotations

import os
import sys
from typing import Any, Dict, List

import requests

from privacyshield_client import (
    PrivacyShieldError,
    documents_in_progress,
    filename_from_content_disposition,
    format_bytes,
    parse_documents_metadata,
    raise_for_status,
    unique_path,
)

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


def print_metadata(job_id: int, metadata: List[Dict[str, Any]]) -> None:
    """Prints the per-document report. This is the only place the API tells you
    what happened to each document, and it arrives on 204 responses too."""
    if not metadata:
        print("The response carried no X-Documents-Metadata header.")
        return

    print(f"Documents in job {job_id}:")
    print(f"  {'DOCUMENT':<30} {'STATUS':<11} {'PAGES':>5}  COMPLETED")
    for document in metadata:
        pages = document.get("pageCount")
        completed = document.get("completedAt")
        print(
            f"  {str(document.get('documentName')):<30} "
            f"{str(document.get('status')):<11} "
            f"{('-' if pages is None else str(pages)):>5}  "
            f"{'-' if completed is None else completed}"
        )
    print()


def main(argv: List[str]) -> int:
    job_id = int(argv[0]) if argv else config.job_id()
    base_url = config.base_url()
    output_dir = config.OUTPUT_DIR
    access_token = authenticate(base_url)

    print(f"Requesting documents for job {job_id} ...")
    response = requests.get(
        f"{base_url}/jobs/{job_id}/documents/download",
        headers={"Access-Token": access_token},
        timeout=300,
    )

    # Read the metadata before anything else: it is present on 204 as well, and
    # a 410 here means the job passed the 5-day retention window.
    metadata = parse_documents_metadata(response.headers.get("X-Documents-Metadata"))
    raise_for_status(response)
    print()
    print_metadata(job_id, metadata)

    pending = documents_in_progress(metadata)

    if response.status_code == 204:
        print("HTTP 204 - nothing is ready to download yet. This is not an error.")
        if pending:
            print(f"{len(pending)} document(s) still pending or processing. Try again later.")
        elif metadata:
            print(
                "Every document is in a terminal state, so there is nothing left "
                "to collect from this job."
            )
        return 0

    os.makedirs(output_dir, exist_ok=True)
    filename = filename_from_content_disposition(
        response.headers.get("Content-Disposition"),
        fallback=f"job_{job_id}_documents",
    )
    path = unique_path(output_dir, filename)
    with open(path, "wb") as handle:
        handle.write(response.content)

    print(
        f"Saved {response.headers.get('Content-Type')} to {path} "
        f"({format_bytes(len(response.content))})"
    )
    print("Those documents are now marked as downloaded and will not be sent again.")
    if pending:
        print(
            f"{len(pending)} document(s) in this job are still in progress - "
            f"run this example again later to collect them."
        )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
