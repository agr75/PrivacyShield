#!/usr/bin/env python3
"""Example 08 — process a whole folder, with retries and a summary.

What it does
    Sends every supported file in a folder as its own job, waits for all of
    them, downloads each result, and prints a summary. One job per file rather
    than one job for everything, so a single bad file cannot fail the batch and
    each result can be traced back to its source.

    Failure handling is per file:
      * 400 (unsupported type, bad name, quota) — recorded, never retried
      * 401 — token refreshed and the request retried once, inside the client
      * 403/404/410 — recorded as configuration or retention problems
      * 5xx and network errors — retried with exponential backoff

    Exits 1 if any file failed, so it can be used from a scheduler.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_TEMPLATE_ID      the template to send the documents to
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 08_batch_processing.py ./inbox [timeout-seconds]
"""

from __future__ import annotations

import os
import sys
import time
from typing import Any, Dict, List, NamedTuple, Optional

from privacyshield_client import (
    SUPPORTED_EXTENSIONS,
    BadRequestError,
    GoneError,
    JobVanishedError,
    PollTimeoutError,
    PrivacyShieldClient,
    PrivacyShieldError,
    format_bytes,
)

try:
    import config
except ModuleNotFoundError:
    raise SystemExit(
        "config.py not found. Create it first:\n\n    cp config.example.py config.py\n"
    )

DEFAULT_TIMEOUT_SECONDS = 30 * 60


class Item(NamedTuple):
    """One file and whatever happened to it."""

    path: str
    job_id: Optional[int] = None
    documents: int = 0
    saved_to: Optional[str] = None
    size_bytes: int = 0
    error: Optional[str] = None

    @property
    def name(self) -> str:
        return os.path.basename(self.path)


def discover(folder: str) -> List[str]:
    """Returns the supported files of a folder, sorted, non-recursive."""
    if not os.path.isdir(folder):
        raise SystemExit(f"Not a folder: {folder}")
    return sorted(
        os.path.join(folder, name)
        for name in os.listdir(folder)
        if os.path.isfile(os.path.join(folder, name))
        and name.lower().endswith(SUPPORTED_EXTENSIONS)
    )


def describe(error: PrivacyShieldError) -> str:
    """Turns an exception into one line, with the reason it will not be retried."""
    if isinstance(error, BadRequestError):
        return f"rejected: {error.api_message} (400, not retried)"
    if isinstance(error, GoneError):
        return f"gone: {error.api_message} (410, past the retention window)"
    if isinstance(error, PollTimeoutError):
        return "still processing when the timeout elapsed (collect it later)"
    if isinstance(error, JobVanishedError):
        return "disappeared from the job listing (failed completely or was deleted)"
    if error.status_code is not None:
        return f"HTTP {error.status_code}: {error.api_message}"
    return str(error)


def upload_all(client: PrivacyShieldClient, template_id: int, paths: List[str]) -> List[Item]:
    print("Uploading ...")
    items: List[Item] = []
    for index, path in enumerate(paths, start=1):
        label = f"  [{index}/{len(paths)}] {os.path.basename(path):<30}"
        try:
            body = client.upload_documents(template_id, [path])
        except PrivacyShieldError as error:
            print(f"{label} failed: {describe(error)}")
            items.append(Item(path=path, error=describe(error)))
            continue
        job_id = int(body["job"])
        docs = int(body["docs"])
        print(f"{label} -> job {job_id} ({docs} doc)")
        items.append(Item(path=path, job_id=job_id, documents=docs))
    return items


def wait_all(client: PrivacyShieldClient, items: List[Item], deadline: float) -> List[Item]:
    pending = [item for item in items if item.job_id is not None]
    if not pending:
        return items

    print(f"Waiting for {len(pending)} job(s) ...")
    results: Dict[str, Item] = {item.path: item for item in items}
    for item in pending:
        # A single shared deadline: the batch as a whole gets the timeout, not
        # each job, so a slow first job cannot push the last one past it.
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            results[item.path] = item._replace(
                error="not waited for: the batch timeout had already elapsed"
            )
            print(f"  job {item.job_id} skipped: batch timeout elapsed")
            continue
        started = time.monotonic()
        try:
            job = client.wait_for_job(item.job_id, timeout=remaining)
        except PrivacyShieldError as error:
            results[item.path] = item._replace(error=describe(error))
            print(f"  job {item.job_id} failed: {describe(error)}")
            continue
        print(
            f"  job {item.job_id} {job['status']} after "
            f"{time.monotonic() - started:.0f}s"
        )
    return [results[item.path] for item in items]


def download_all(client: PrivacyShieldClient, items: List[Item], output_dir: str) -> List[Item]:
    ready = [item for item in items if item.job_id is not None and item.error is None]
    if not ready:
        return items

    print("Downloading ...")
    results: Dict[str, Item] = {item.path: item for item in items}
    for item in ready:
        try:
            download = client.download_job_documents(item.job_id, output_dir)
        except PrivacyShieldError as error:
            results[item.path] = item._replace(error=describe(error))
            print(f"  job {item.job_id} failed: {describe(error)}")
            continue
        if download.is_empty:
            # 204 after a terminal status means every document errored.
            statuses = ", ".join(
                str(document.get("status")) for document in download.metadata
            )
            results[item.path] = item._replace(
                error=f"nothing to download (document status: {statuses or 'unknown'})"
            )
            print(f"  job {item.job_id} -> nothing to download")
            continue
        results[item.path] = item._replace(
            saved_to=download.path, size_bytes=download.size_bytes
        )
        print(
            f"  job {item.job_id} -> {download.path} "
            f"({format_bytes(download.size_bytes)})"
        )
    return [results[item.path] for item in items]


def main(argv: List[str]) -> int:
    if not argv:
        raise SystemExit(
            "Usage: python3 08_batch_processing.py <folder> [timeout-seconds]"
        )

    folder = argv[0]
    timeout = float(argv[1]) if len(argv) > 1 else DEFAULT_TIMEOUT_SECONDS
    paths = discover(folder)

    base_url = config.base_url()
    template_id = config.template_id()
    output_dir = config.OUTPUT_DIR

    print("PrivacyShield batch processing")
    print(f"  folder   : {folder}")
    print(f"  template : {template_id}")
    print(f"  found    : {len(paths)} supported file(s)")
    print()

    if not paths:
        print(f"Nothing to do. Supported extensions: {', '.join(SUPPORTED_EXTENSIONS)}")
        return 0

    deadline = time.monotonic() + timeout
    with PrivacyShieldClient(
        account_id=config.account_id(), api_key=config.api_key(), base_url=base_url
    ) as client:
        items = upload_all(client, template_id, paths)
        items = wait_all(client, items, deadline)
        items = download_all(client, items, output_dir)

    uploaded = [item for item in items if item.job_id is not None]
    downloaded = [item for item in items if item.saved_to is not None]
    failed = [item for item in items if item.error is not None]

    print()
    print("Summary")
    print(f"  files found : {len(paths)}")
    print(f"  uploaded    : {len(uploaded)}")
    print(f"  downloaded  : {len(downloaded)}")
    print(f"  failed      : {len(failed)}")
    for item in failed:
        print(f"      {item.name}: {item.error}")

    if downloaded:
        print()
        print(f"Results are in {output_dir}/ and are marked as downloaded.")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
