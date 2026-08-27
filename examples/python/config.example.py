"""Configuration for the PrivacyShield examples.

Copy this file to `config.py` before running anything:

    cp config.example.py config.py

`config.py` is listed in .gitignore, so anything you put there stays local.

Every value is read from an environment variable first. With the variables
exported you do not have to edit the copy at all:

    export PRIVACYSHIELD_ACCOUNT_ID=1042
    export PRIVACYSHIELD_API_KEY=...
    export PRIVACYSHIELD_TEMPLATE_ID=789

The fallbacks below exist for local experiments. Prefer the environment: a key
typed into a file is a key that gets copied somewhere by accident.
"""

from __future__ import annotations

import os
import sys

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"

# Optional local fallbacks. Leave empty to require the environment variables.
ACCOUNT_ID_FALLBACK = ""
API_KEY_FALLBACK = ""
TEMPLATE_ID_FALLBACK = ""
JOB_ID_FALLBACK = ""
BASE_URL_FALLBACK = ""

# Where the examples write downloaded documents.
OUTPUT_DIR = "./downloads"

_WHERE_TO_FIND = {
    "PRIVACYSHIELD_ACCOUNT_ID": (
        "the My Account section of "
        "https://privacyshield.fundamentia.com/administration"
    ),
    "PRIVACYSHIELD_API_KEY": (
        "the Api Key section of "
        "https://privacyshield.fundamentia.com/administration?tab=apikey"
    ),
    "PRIVACYSHIELD_TEMPLATE_ID": (
        "the template list in https://privacyshield.fundamentia.com - "
        "templates are created in the web interface, not through the API"
    ),
    "PRIVACYSHIELD_JOB_ID": (
        "the `job` field returned when you upload a document "
        "(see 02_upload_document.py), or the listing from 03_check_status.py"
    ),
}


def _read(name: str, fallback: str) -> str:
    return (os.environ.get(name, "") or fallback).strip()


def require(name: str, fallback: str = "") -> str:
    """Returns a required setting, or exits with an actionable message."""
    value = _read(name, fallback)
    if value:
        return value

    hint = _WHERE_TO_FIND.get(name, "your account administrator")
    print(
        f"Missing configuration: {name}\n"
        f"\n"
        f"  export {name}=...\n"
        f"\n"
        f"Where to find it: {hint}.\n"
        f"You can also set the matching *_FALLBACK value in config.py.",
        file=sys.stderr,
    )
    raise SystemExit(1)


def require_int(name: str, fallback: str = "") -> int:
    """Same as `require`, but rejects a value that is not a whole number."""
    value = require(name, fallback)
    try:
        return int(value)
    except ValueError:
        print(
            f"Invalid configuration: {name} must be a number, got {value!r}.",
            file=sys.stderr,
        )
        raise SystemExit(1) from None


def base_url() -> str:
    """The API base URL, including the /api/v1 prefix and no trailing slash."""
    return _read("PRIVACYSHIELD_BASE_URL", BASE_URL_FALLBACK or DEFAULT_BASE_URL).rstrip("/")


def account_id() -> int:
    return require_int("PRIVACYSHIELD_ACCOUNT_ID", ACCOUNT_ID_FALLBACK)


def api_key() -> str:
    return require("PRIVACYSHIELD_API_KEY", API_KEY_FALLBACK)


def template_id() -> int:
    """Identifier of the template documents are sent to.

    Named after the API literal `templateId`, which is what the endpoint path uses.
    The web interface calls the same thing a template.
    """
    return require_int("PRIVACYSHIELD_TEMPLATE_ID", TEMPLATE_ID_FALLBACK)


def job_id() -> int:
    """Identifier of a pre-existing job, for the examples that act on one."""
    return require_int("PRIVACYSHIELD_JOB_ID", JOB_ID_FALLBACK)
