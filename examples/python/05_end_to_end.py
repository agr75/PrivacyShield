#!/usr/bin/env python3
"""Example 05 — the complete flow, end to end.

What it does
    Authenticate, upload, wait for the job to finish, download the results.
    This is the example to read first if you are integrating PrivacyShield.

    It uses PrivacyShieldClient, which handles the parts you would otherwise
    have to write yourself:

      * the job it downloads is the one the upload returned, not a constant
      * polling with exponential backoff and a global timeout
      * the access token renews itself during the wait, so a job that outlives
        the 30-minute token lifetime still works
      * 401 retried once, 5xx retried with backoff, 4xx not retried at all
      * 204 treated as "not ready yet" rather than an error

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_TEMPLATE_ID      the template to send the documents to
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 05_end_to_end.py contract.pdf [more-files ...]
"""

from __future__ import annotations

import os
import sys
import time
from typing import Any, Dict, List

from privacyshield_client import (
    SUPPORTED_EXTENSIONS,
    PrivacyShieldClient,
    PrivacyShieldError,
    documents_in_progress,
    format_bytes,
)

try:
    import config
except ModuleNotFoundError:
    raise SystemExit(
        "config.py not found. Create it first:\n\n    cp config.example.py config.py\n"
    )

POLL_TIMEOUT_SECONDS = 15 * 60


def check_paths(paths: List[str]) -> None:
    for path in paths:
        if not os.path.isfile(path):
            raise SystemExit(f"File not found: {path}")
        if not path.lower().endswith(SUPPORTED_EXTENSIONS):
            raise SystemExit(
                f"Unsupported file type: {path}\n"
                f"Supported extensions: {', '.join(SUPPORTED_EXTENSIONS)}"
            )


def print_documents(metadata: List[Dict[str, Any]]) -> None:
    if not metadata:
        return
    print(f"   {'DOCUMENT':<30} {'STATUS':<11} {'PAGES':>5}  COMPLETED")
    for document in metadata:
        pages = document.get("pageCount")
        completed = document.get("completedAt")
        print(
            f"   {str(document.get('documentName')):<30} "
            f"{str(document.get('status')):<11} "
            f"{('-' if pages is None else str(pages)):>5}  "
            f"{'-' if completed is None else completed}"
        )


def main(argv: List[str]) -> int:
    if not argv:
        raise SystemExit(
            "Usage: python3 05_end_to_end.py <file> [more-files ...]\n"
            f"Supported extensions: {', '.join(SUPPORTED_EXTENSIONS)}"
        )
    check_paths(argv)

    base_url = config.base_url()
    account_id = config.account_id()
    template_id = config.template_id()
    output_dir = config.OUTPUT_DIR
    started_at = time.monotonic()

    print("PrivacyShield end-to-end example")
    print(f"  base URL : {base_url}")
    print(f"  account  : {account_id}")
    print(f"  template : {template_id}")
    print(f"  files    : {len(argv)}")
    print()

    with PrivacyShieldClient(
        account_id=account_id, api_key=config.api_key(), base_url=base_url
    ) as client:
        print("1) Authenticating ...")
        client.authenticate()
        print("   Access token obtained.")

        print(f"2) Uploading {len(argv)} file(s) ...")
        upload = client.upload_documents(template_id, argv)
        job_id = int(upload["job"])
        print(f"   Job {job_id} created, {upload['docs']} document(s) accepted.")

        print(f"3) Waiting for job {job_id} (timeout {POLL_TIMEOUT_SECONDS}s) ...")

        def report(job: Dict[str, Any], sleep_for: float) -> None:
            elapsed = time.monotonic() - started_at
            suffix = "" if sleep_for <= 0 else f"  - next check in {sleep_for:.0f}s"
            # rstrip: the status column is padded, and the terminal status line
            # has no suffix to fill it.
            print(f"   [{elapsed:>4.0f}s] {str(job['status']):<12}{suffix}".rstrip())

        job = client.wait_for_job(job_id, timeout=POLL_TIMEOUT_SECONDS, on_poll=report)

        print("4) Downloading results ...")
        download = client.download_job_documents(job_id, output_dir)
        print_documents(download.metadata)

        if download.is_empty:
            # Reachable: a job whose documents all failed reports 'completed'
            # via its other documents, or everything was already collected.
            print("   HTTP 204 - the server had nothing to send.")
        else:
            print(
                f"   Saved {download.content_type} to {download.path} "
                f"({format_bytes(download.size_bytes)})"
            )
            print("   Those documents are now marked as downloaded.")

        pending = documents_in_progress(download.metadata)
        if pending:
            print(
                f"   {len(pending)} document(s) have not finished yet - run "
                f"04_download_result.py later to collect them."
            )

    elapsed = time.monotonic() - started_at
    print()
    print(f"Done in {elapsed:.0f}s. Job {job_id} finished with status '{job['status']}'.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
