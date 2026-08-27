#!/usr/bin/env python3
"""Example 02 — send documents to a template.

What it does
    Uploads one or more files in a single multipart request and prints the job
    identifier the API returns. The files do not have to be the same type: PDFs,
    images and ZIP archives can travel together.

    Nothing is anonymized when this returns. The documents are queued.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_TEMPLATE_ID      the template to send the documents to
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 02_upload_document.py contract.pdf [more-files ...]
"""

from __future__ import annotations

import os
import sys
from typing import List

import requests

from privacyshield_client import (
    SUPPORTED_EXTENSIONS,
    PrivacyShieldError,
    format_bytes,
    raise_for_status,
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


def check_paths(paths: List[str]) -> None:
    """Fails before uploading rather than after a 400 from the server."""
    for path in paths:
        if not os.path.isfile(path):
            raise SystemExit(f"File not found: {path}")
        if not path.lower().endswith(SUPPORTED_EXTENSIONS):
            raise SystemExit(
                f"Unsupported file type: {path}\n"
                f"Supported extensions: {', '.join(SUPPORTED_EXTENSIONS)}"
            )


def main(argv: List[str]) -> int:
    if not argv:
        raise SystemExit(
            "Usage: python3 02_upload_document.py <file> [more-files ...]\n"
            f"Supported extensions: {', '.join(SUPPORTED_EXTENSIONS)}"
        )

    check_paths(argv)
    base_url = config.base_url()
    template_id = config.template_id()
    access_token = authenticate(base_url)

    print(f"Uploading {len(argv)} file(s) to template {template_id}:")
    for path in argv:
        print(f"  {os.path.basename(path)} ({format_bytes(os.path.getsize(path))})")

    # One multipart part per file, all named "file". Handles stay open until the
    # request has been sent.
    handles = [open(path, "rb") for path in argv]
    try:
        files = [
            ("file", (os.path.basename(path), handle))
            for path, handle in zip(argv, handles)
        ]
        response = requests.post(
            f"{base_url}/flow/{template_id}",
            headers={"Access-Token": access_token},
            files=files,
            timeout=300,  # uploads are large; the default is far too short
        )
    finally:
        for handle in handles:
            handle.close()

    raise_for_status(response)
    body = response.json()
    job_id = body["job"]

    print(f"Job {job_id} created, {body['docs']} document(s) accepted.")
    print()
    print("Nothing is anonymized yet - processing is asynchronous.")
    print("Save the job id, it is your only handle on this submission:")
    print(f"    export PRIVACYSHIELD_JOB_ID={job_id}")
    print("Next: 03_check_status.py to watch it, or 05_end_to_end.py for the full flow.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
