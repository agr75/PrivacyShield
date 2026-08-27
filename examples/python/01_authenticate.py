#!/usr/bin/env python3
"""Example 01 — obtain an access token.

What it does
    Exchanges your API key for an Access-Token JWT and prints how long it lasts.
    This is the first call of every integration: the token it returns is what
    authorizes all the others.

What you need configured
    PRIVACYSHIELD_ACCOUNT_ID   your account identifier
    PRIVACYSHIELD_API_KEY      your account's API key
    PRIVACYSHIELD_BASE_URL     optional, defaults to production

How to run
    cp config.example.py config.py      # once
    python3 01_authenticate.py
"""

from __future__ import annotations

import sys
import time

import requests

from privacyshield_client import PrivacyShieldError, raise_for_status

try:
    import config
except ModuleNotFoundError:
    raise SystemExit(
        "config.py not found. Create it first:\n\n    cp config.example.py config.py\n"
    )

TOKEN_LIFETIME_MINUTES = 30


def main() -> int:
    base_url = config.base_url()
    account_id = config.account_id()

    print(f"Authenticating account {account_id} at {base_url} ...")

    response = requests.post(
        f"{base_url}/auth/{account_id}",
        json={"api_key": config.api_key()},
        timeout=30,
    )

    # 403 means the key is wrong; 404 means the account is wrong. Both look
    # identical from the outside, so the message matters here.
    raise_for_status(response)

    token = response.json()["Access-Token"]
    expires_at = time.localtime(time.time() + TOKEN_LIFETIME_MINUTES * 60)

    print(f"Access token obtained: {token[:20]}...")
    print(
        f"Valid for {TOKEN_LIFETIME_MINUTES} minutes, until about "
        f"{time.strftime('%H:%M:%S', expires_at)} local time."
    )
    print("Send it as the 'Access-Token' header on every other request.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PrivacyShieldError as error:
        print(f"\nFailed: {error}", file=sys.stderr)
        sys.exit(1)
