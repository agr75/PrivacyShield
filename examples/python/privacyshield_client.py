"""Reusable client for the PrivacyShield REST API.

This is the one file in this folder meant to be copied into your own project.
The numbered examples show the raw HTTP calls so you can see what goes over
the wire; this class wraps them with the behaviour a real integration needs:

  * an access token that renews itself before the 30-minute lifetime is up
  * exactly one retry on 401, in case a token was invalidated early
  * exponential backoff on 5xx, and no retry at all on 4xx
  * 204 treated as a normal answer rather than an error
  * polling with backoff, a global timeout, and a terminal outcome when a job
    disappears from the listing
  * downloads that never overwrite an existing file, because a download cannot
    be repeated: the server marks documents as downloaded when it sends them

Requires Python 3.9+ and `requests`.
"""

from __future__ import annotations

import json
import os
import re
import time
from email.header import decode_header
from typing import Any, Callable, Dict, List, NamedTuple, Optional
from urllib.parse import unquote

import requests

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"

# The API issues tokens with a 30-minute lifetime. Renewing a few minutes early
# costs one extra call per half hour and removes a whole class of failure that
# otherwise only shows up in the middle of a long wait.
TOKEN_LIFETIME_SECONDS = 30 * 60
TOKEN_REFRESH_MARGIN_SECONDS = 5 * 60

# 5xx and network failures are the only retryable conditions.
MAX_ATTEMPTS = 4
INITIAL_BACKOFF_SECONDS = 2.0
MAX_BACKOFF_SECONDS = 30.0

# Polling defaults. The cap matters more than the initial delay: without it, a
# job that takes an hour is polled with hour-long gaps.
DEFAULT_POLL_TIMEOUT_SECONDS = 15 * 60
INITIAL_POLL_DELAY_SECONDS = 2.0
MAX_POLL_DELAY_SECONDS = 30.0

DEFAULT_REQUEST_TIMEOUT_SECONDS = 30.0
UPLOAD_REQUEST_TIMEOUT_SECONDS = 300.0

# A job in one of these states will not change on its own.
TERMINAL_JOB_STATUSES = frozenset({"completed", "downloaded", "cancelled"})
# A document in one of these states will not change on its own.
TERMINAL_DOCUMENT_STATUSES = frozenset({"done", "aborted", "error"})

SUPPORTED_EXTENSIONS = (".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".zip")


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class PrivacyShieldError(RuntimeError):
    """Base class for every failure this client reports."""

    def __init__(
        self,
        message: str,
        status_code: Optional[int] = None,
        api_message: Optional[str] = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.api_message = api_message


class BadRequestError(PrivacyShieldError):
    """400 — the request is wrong, or the quota cannot cover it. Never retry."""


class AuthenticationError(PrivacyShieldError):
    """401 — missing or invalid token. Retried once automatically."""


class AccessDeniedError(PrivacyShieldError):
    """403 — the resource belongs to another account. A configuration problem."""


class NotFoundError(PrivacyShieldError):
    """404 — no such account, template or job."""


class GoneError(PrivacyShieldError):
    """410 — the job was deleted. Results are unrecoverable, normally because
    more than 5 days passed since the documents finished."""


class ServerError(PrivacyShieldError):
    """5xx or a network failure. Retried with backoff before it reaches you."""


class PollTimeoutError(PrivacyShieldError):
    """The global polling timeout elapsed. The job keeps running on the server."""


class JobVanishedError(PrivacyShieldError):
    """The job stopped appearing in the listing, which excludes deleted jobs and
    jobs that failed completely. Waiting longer will not bring it back."""


_STATUS_ERRORS = {
    400: BadRequestError,
    401: AuthenticationError,
    403: AccessDeniedError,
    404: NotFoundError,
    410: GoneError,
}


# ---------------------------------------------------------------------------
# Module-level helpers
# ---------------------------------------------------------------------------


def api_message(response: requests.Response) -> str:
    """Extracts the API's `message` field, falling back to the raw body."""
    try:
        body = response.json()
    except ValueError:
        text = (response.text or "").strip()
        return text[:200] if text else "(no response body)"
    if isinstance(body, dict) and body.get("message"):
        return str(body["message"])
    return json.dumps(body)[:200]


def raise_for_status(response: requests.Response) -> None:
    """Maps an HTTP status onto the exception hierarchy above.

    Anything below 400 returns quietly, which is what makes 204 an ordinary
    answer for the download endpoints instead of an error.
    """
    if response.status_code < 400:
        return
    status = response.status_code
    detail = api_message(response)
    error_class = _STATUS_ERRORS.get(status)
    if error_class is None:
        error_class = ServerError if status >= 500 else PrivacyShieldError
    request = response.request
    where = f"{request.method} {request.url}" if request is not None else "request"
    raise error_class(
        f"HTTP {status} on {where}: {detail}",
        status_code=status,
        api_message=detail,
    )


def filename_from_content_disposition(
    content_disposition: Optional[str], fallback: str
) -> str:
    """Extracts the filename from a `Content-Disposition` header.

    When the name has non-ASCII characters the server sends both the extended
    parameter `filename*=UTF-8''...` (RFC 5987/6266, percent-encoded) and, for
    compatibility with old clients, a plain `filename="..."` holding an RFC 2047
    encoded-word such as `=?UTF-8?Q?informe.pdf?=`. The extended parameter is
    the correct one and needs no heuristics, so it always wins; the plain form
    is only decoded when there is no `filename*=`.
    """
    if not content_disposition:
        return fallback

    extended_match = re.search(r"filename\*\s*=\s*([^;]+)", content_disposition)
    if extended_match:
        value = extended_match.group(1).strip().strip('"')
        if "'" in value:
            charset, _language, encoded = value.split("'", 2)
        else:
            charset, encoded = "utf-8", value
        return unquote(encoded, encoding=charset or "utf-8")

    plain_match = re.search(r'filename\s*=\s*"?([^";]+)"?', content_disposition)
    if plain_match:
        return "".join(
            part.decode(encoding or "utf-8") if isinstance(part, bytes) else part
            for part, encoding in decode_header(plain_match.group(1))
        )

    return fallback


def parse_documents_metadata(raw_header: Optional[str]) -> List[Dict[str, Any]]:
    """Parses the `X-Documents-Metadata` header into a list of dictionaries.

    Returns an empty list when the header is absent or unparseable. The header
    is the only per-document report the API gives you, and it arrives on 204
    responses too, so read it on every download.
    """
    if not raw_header:
        return []
    try:
        parsed = json.loads(raw_header)
    except ValueError:
        return []
    return parsed if isinstance(parsed, list) else []


def documents_in_progress(metadata: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Returns the documents that are still going to change state."""
    return [
        document
        for document in metadata
        if document.get("status") not in TERMINAL_DOCUMENT_STATUSES
    ]


def unique_path(output_dir: str, filename: str) -> str:
    """Builds a path that does not exist yet, adding ` (2)`, ` (3)` as needed.

    Overwriting matters here more than usual: the API marks documents as
    downloaded when it sends them, so a file this client clobbers cannot be
    fetched again.
    """
    stem, extension = os.path.splitext(filename)
    candidate = os.path.join(output_dir, filename)
    counter = 2
    while os.path.exists(candidate):
        candidate = os.path.join(output_dir, f"{stem} ({counter}){extension}")
        counter += 1
    return candidate


def format_bytes(size: int) -> str:
    """Formats a byte count for console output."""
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    return f"{size / (1024 * 1024):.1f} MB"


class Download(NamedTuple):
    """Outcome of a download call.

    `path` is None when the server answered 204, which means nothing was ready
    to send. That is a normal outcome, not a failure.
    """

    status_code: int
    path: Optional[str]
    metadata: List[Dict[str, Any]]
    content_type: Optional[str]
    size_bytes: int

    @property
    def is_empty(self) -> bool:
        return self.path is None


# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------


class PrivacyShieldClient:
    """Authenticated client for one PrivacyShield account.

    The token is managed internally: you never pass it to a method. Create one
    client and reuse it, so the connection pool and the token are shared.

        client = PrivacyShieldClient(account_id=1042, api_key="...")
        result = client.upload_documents(789, ["contract.pdf"])
        client.wait_for_job(result["job"])
        download = client.download_job_documents(result["job"], "./downloads")
    """

    def __init__(
        self,
        account_id: int,
        api_key: str,
        base_url: str = DEFAULT_BASE_URL,
        session: Optional[requests.Session] = None,
        timeout: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
    ) -> None:
        self.account_id = int(account_id)
        self.base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._session = session or requests.Session()
        self._timeout = timeout
        self._token: Optional[str] = None
        self._token_obtained_at = 0.0

    # -- context manager -------------------------------------------------

    def __enter__(self) -> "PrivacyShieldClient":
        return self

    def __exit__(self, *_exc_info: Any) -> None:
        self.close()

    def close(self) -> None:
        self._session.close()

    # -- authentication --------------------------------------------------

    @property
    def token_age_seconds(self) -> Optional[float]:
        """Seconds since the current token was issued, or None if there is none."""
        if self._token is None:
            return None
        return time.monotonic() - self._token_obtained_at

    def authenticate(self) -> str:
        """Exchanges the API key for a new access token.

        Called automatically when needed. Call it directly only to force a
        renewal. Its own failures are final: there is no token to refresh.
        """
        response = self._send_with_backoff(
            lambda: self._session.post(
                self._url(f"/auth/{self.account_id}"),
                json={"api_key": self._api_key},
                timeout=self._timeout,
            )
        )
        raise_for_status(response)
        body = response.json()
        token = body.get("Access-Token")
        if not token:
            raise PrivacyShieldError(
                f"The authentication response contains no Access-Token field: {body}"
            )
        self._token = token
        self._token_obtained_at = time.monotonic()
        return token

    # -- endpoints -------------------------------------------------------

    def upload_documents(self, template_id: int, file_paths: List[str]) -> Dict[str, Any]:
        """Sends one or more documents to a template. Returns the parsed body.

        Repeat parts named `file` carry the documents; they do not have to be
        the same type. The response's `job` field is the only handle you get on
        the submission, so persist it before doing anything else.
        """
        for path in file_paths:
            if not os.path.isfile(path):
                raise PrivacyShieldError(f"File not found: {path}")

        handles = [open(path, "rb") for path in file_paths]
        try:

            def rewind() -> None:
                # A retried upload has to send the bytes again, and the previous
                # attempt left every handle at EOF.
                for handle in handles:
                    handle.seek(0)

            files = [
                ("file", (os.path.basename(path), handle))
                for path, handle in zip(file_paths, handles)
            ]
            response = self._request(
                "POST",
                f"/flow/{template_id}",
                files=files,
                timeout=UPLOAD_REQUEST_TIMEOUT_SECONDS,
                before_retry=rewind,
            )
        finally:
            for handle in handles:
                handle.close()
        return response.json()

    def get_jobs_status(self, page: int = 0) -> Dict[str, Any]:
        """Returns one page of the job listing, newest first, 40 per page."""
        response = self._request("GET", "/jobs/status", params={"page": page})
        return response.json()

    def find_job(self, job_id: int, max_pages: int = 25) -> Optional[Dict[str, Any]]:
        """Locates a job in the paginated listing.

        Returns None when the job is not there, which is a terminal outcome: the
        listing excludes deleted jobs and jobs that failed completely.
        """
        page = 0
        while page < max_pages:
            body = self.get_jobs_status(page)
            for job in body.get("jobs", []):
                if int(job["jobId"]) == int(job_id):
                    return job
            limit = int(body.get("limit") or 40)
            total = int(body.get("total") or 0)
            if (page + 1) * limit >= total:
                return None
            page += 1
        return None

    def abort_job(self, template_id: int, job_id: int) -> Dict[str, Any]:
        """Cancels the queued documents of one job.

        `abortedDocuments` can be 0: that means every document had already
        started processing, which is a success, not an error.
        """
        response = self._request("POST", f"/flow/{template_id}/job/{job_id}/abort")
        return response.json()

    def abort_template(self, template_id: int) -> Dict[str, Any]:
        """Cancels the queued documents of every active job of a template.

        This affects jobs your process did not create, including submissions
        made from the web interface.
        """
        response = self._request("POST", f"/flow/{template_id}/abort")
        return response.json()

    def download_job_documents(self, job_id: int, output_dir: str) -> Download:
        """Downloads the finished documents of one job.

        One document comes back as a PDF, several as a ZIP — check
        `download.content_type` instead of assuming. The returned metadata
        describes every document in the job, including the ones not sent.
        """
        response = self._request("GET", f"/jobs/{job_id}/documents/download")
        return self._as_download(
            response, output_dir, fallback_name=f"job_{job_id}_documents"
        )

    def download_pending_documents(self, output_dir: str) -> Download:
        """Downloads up to 10 finished, not-yet-downloaded documents of the account.

        Always a ZIP. The metadata lists only what this call returned, and each
        entry carries an extra `jobId` telling you which job it came from.
        """
        response = self._request("GET", "/accounts/documents/pending/download")
        return self._as_download(response, output_dir, fallback_name="documents.zip")

    # -- waiting ---------------------------------------------------------

    def wait_for_job(
        self,
        job_id: int,
        timeout: float = DEFAULT_POLL_TIMEOUT_SECONDS,
        initial_delay: float = INITIAL_POLL_DELAY_SECONDS,
        max_delay: float = MAX_POLL_DELAY_SECONDS,
        on_poll: Optional[Callable[[Dict[str, Any], float], None]] = None,
    ) -> Dict[str, Any]:
        """Polls the job listing until the job reaches a terminal status.

        The token renews itself inside the loop, so a wait longer than the
        30-minute token lifetime works without any action from the caller.

        `on_poll` is called with the job entry and the seconds about to be
        slept, which is how the examples print progress.

        Raises PollTimeoutError if the timeout elapses, and JobVanishedError if
        the job leaves the listing.
        """
        deadline = time.monotonic() + timeout
        delay = initial_delay
        while True:
            job = self.find_job(job_id)
            if job is None:
                raise JobVanishedError(
                    f"Job {job_id} is no longer in the listing. The listing excludes "
                    f"deleted jobs and jobs that failed completely, so it will not "
                    f"reappear."
                )
            status = str(job.get("status"))
            if status in TERMINAL_JOB_STATUSES:
                if on_poll is not None:
                    on_poll(job, 0.0)
                return job

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise PollTimeoutError(
                    f"Job {job_id} was still '{status}' after {timeout:.0f}s. It keeps "
                    f"processing on the server; collect it later."
                )
            sleep_for = min(delay, remaining)
            if on_poll is not None:
                on_poll(job, sleep_for)
            time.sleep(sleep_for)
            delay = min(delay * 2, max_delay)

    # -- internals -------------------------------------------------------

    def _url(self, path: str) -> str:
        # base_url already ends in /api/v1, so paths start after the prefix.
        return f"{self.base_url}{path}"

    def _ensure_token(self) -> str:
        age = self.token_age_seconds
        if age is None or age > TOKEN_LIFETIME_SECONDS - TOKEN_REFRESH_MARGIN_SECONDS:
            self.authenticate()
        assert self._token is not None
        return self._token

    def _request(
        self,
        method: str,
        path: str,
        before_retry: Optional[Callable[[], None]] = None,
        **kwargs: Any,
    ) -> requests.Response:
        """Sends an authenticated request, handling both retry policies."""
        token_refreshed = False
        while True:
            headers = dict(kwargs.pop("headers", None) or {})
            headers["Access-Token"] = self._ensure_token()
            call_kwargs = dict(kwargs)
            call_kwargs.setdefault("timeout", self._timeout)
            response = self._send_with_backoff(
                lambda: self._session.request(
                    method, self._url(path), headers=headers, **call_kwargs
                ),
                before_retry=before_retry,
            )

            if response.status_code == 401 and not token_refreshed:
                # The token may have been invalidated before its nominal expiry.
                # One forced refresh and one retry; a second 401 is a credential
                # problem and must surface.
                token_refreshed = True
                self.authenticate()
                if before_retry is not None:
                    before_retry()
                continue

            raise_for_status(response)
            return response

    def _send_with_backoff(
        self,
        send: Callable[[], requests.Response],
        before_retry: Optional[Callable[[], None]] = None,
    ) -> requests.Response:
        """Retries server-side and network failures only.

        A 4xx is returned untouched for the caller to map: retrying a malformed
        request or an exhausted quota just sends the same failure again.
        """
        delay = INITIAL_BACKOFF_SECONDS
        last_error: Optional[Exception] = None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                response = send()
            except (requests.ConnectionError, requests.Timeout) as exc:
                last_error = exc
                if attempt == MAX_ATTEMPTS:
                    raise ServerError(
                        f"Network failure after {attempt} attempt(s): {exc}"
                    ) from exc
            else:
                if not 500 <= response.status_code < 600 or attempt == MAX_ATTEMPTS:
                    return response
            time.sleep(delay)
            delay = min(delay * 2, MAX_BACKOFF_SECONDS)
            if before_retry is not None:
                before_retry()
        raise ServerError(f"Request failed after {MAX_ATTEMPTS} attempts: {last_error}")

    def _as_download(
        self, response: requests.Response, output_dir: str, fallback_name: str
    ) -> Download:
        metadata = parse_documents_metadata(response.headers.get("X-Documents-Metadata"))
        if response.status_code == 204:
            return Download(204, None, metadata, None, 0)

        os.makedirs(output_dir, exist_ok=True)
        filename = filename_from_content_disposition(
            response.headers.get("Content-Disposition"), fallback=fallback_name
        )
        path = unique_path(output_dir, filename)
        with open(path, "wb") as handle:
            handle.write(response.content)
        return Download(
            response.status_code,
            path,
            metadata,
            response.headers.get("Content-Type"),
            len(response.content),
        )
