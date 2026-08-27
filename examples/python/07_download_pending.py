#!/usr/bin/env python3
"""Example 07 — drain everything pending for the account.

What it does
    Calls the account-wide download repeatedly until it answers 204, saving each
    batch to ./downloads.

    This endpoint returns at most 10 documents per call, from any job, and
    always as a ZIP. Documents are marked as downloaded atomically before the
    response is sent, so each call returns the next batch and several workers
    can drain the same queue without getting duplicates.

    Use it as a safety net: it collects results nobody claimed, which is what
    protects you from the 5-day retention window.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 07_download_pending.py [max-batches]     # default 5
"""

from __future__ import annotations

import os
import sys
from typing import List

import requests

from privacyshield_client import (
    PrivacyShieldError,
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

DEFAULT_MAX_BATCHES = 5
DOCUMENTS_PER_CALL = 10


def authenticate(base_url: str) -> str:
    response = requests.post(
        f"{base_url}/auth/{config.account_id()}",
        json={"api_key": config.api_key()},
        timeout=30,
    )
    raise_for_status(response)
    return response.json()["Access-Token"]


def main(argv: List[str]) -> int:
    max_batches = int(argv[0]) if argv else DEFAULT_MAX_BATCHES
    base_url = config.base_url()
    output_dir = config.OUTPUT_DIR
    access_token = authenticate(base_url)

    print(f"Draining documents pending download for account {config.account_id()} ...")
    print()

    collected = 0
    batches = 0

    for batch in range(1, max_batches + 1):
        response = requests.get(
            f"{base_url}/accounts/documents/pending/download",
            headers={"Access-Token": access_token},
            timeout=300,
        )
        metadata = parse_documents_metadata(response.headers.get("X-Documents-Metadata"))
        raise_for_status(response)

        if response.status_code == 204:
            # An empty array here is the definitive "nothing waiting anywhere".
            print(f"Batch {batch}: HTTP 204 - nothing left.")
            break

        batches = batch
        collected += len(metadata)
        print(f"Batch {batch}: {len(metadata)} document(s)")
        for document in metadata:
            pages = document.get("pageCount")
            # jobId only appears in the account-wide download. Without it you
            # cannot tell which submission a file in the ZIP answers.
            print(
                f"  {str(document.get('documentName')):<30} "
                f"job {str(document.get('jobId')):<8} "
                f"{str(document.get('status')):<8} "
                f"{'-' if pages is None else pages} page(s)"
            )

        os.makedirs(output_dir, exist_ok=True)
        filename = filename_from_content_disposition(
            response.headers.get("Content-Disposition"), fallback="documents.zip"
        )
        # Never overwrite: a batch this script clobbers cannot be downloaded again.
        path = unique_path(output_dir, filename)
        with open(path, "wb") as handle:
            handle.write(response.content)
        print(f"  Saved to {path} ({format_bytes(len(response.content))})")

        if len(metadata) < DOCUMENTS_PER_CALL:
            print(
                f"Batch {batch} returned fewer than {DOCUMENTS_PER_CALL} documents, "
                f"so the queue is empty."
            )
            break
    else:
        print()
        print(
            f"Stopped after {max_batches} batch(es). More documents may still be "
            f"pending - run again to continue."
        )

    print()
    print(f"Collected {collected} document(s) in {batches} batch(es).")
    if collected:
        print("Those documents are now marked as downloaded and will not be returned again.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
