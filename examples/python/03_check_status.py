#!/usr/bin/env python3
"""Example 03 — list your jobs and their status.

What it does
    Fetches one page of the job listing and prints it as a table. This is the
    endpoint you poll while waiting: it changes nothing on the server, unlike
    the download endpoints.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    python3 03_check_status.py [page]        # page is 0-based, default 0
"""

from __future__ import annotations

import math
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


def main(argv: List[str]) -> int:
    page = int(argv[0]) if argv else 0
    base_url = config.base_url()
    access_token = authenticate(base_url)

    response = requests.get(
        f"{base_url}/jobs/status",
        headers={"Access-Token": access_token},
        params={"page": page},
        timeout=30,
    )
    raise_for_status(response)
    body = response.json()

    total = int(body["total"])
    limit = int(body["limit"])
    pages = max(1, math.ceil(total / limit)) if limit else 1

    print(
        f"{total} job(s) in the account - page {body['page']} of {pages - 1} "
        f"- {limit} per page"
    )
    print()

    if not body["jobs"]:
        print("No jobs on this page.")
        print()
        print(
            "The listing excludes deleted jobs and jobs that failed completely, "
            "so a job you cannot find here will not reappear."
        )
        return 0

    print(f"{'JOB':>8}  {'TEMPLATE':>8}  {'STATUS':<12}  CREATED")
    for job in body["jobs"]:
        print(
            f"{job['jobId']:>8}  {job['templateId']:>8}  "
            f"{job['status']:<12}  {job['createdAt']}"
        )
    print()
    print(
        "new/processing = still working - completed = ready to download - "
        "downloaded = already collected - cancelled = aborted"
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
