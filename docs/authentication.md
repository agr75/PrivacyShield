# Authentication

PrivacyShield uses two credentials with two very different lifetimes.

| Credential | Lifetime | Where it goes | Purpose |
|---|---|---|---|
| `api_key` | Long-lived, until an administrator regenerates it | Request body of `POST /api/v1/auth/{accountId}` only | Proves you own the account |
| `Access-Token` | 30 minutes | `Access-Token` header of every other request | Authorizes individual API calls |

You exchange the API key for an access token, then use the token. The API key never travels on a normal request.

## Getting your credentials

Both values come from the PrivacyShield web interface and require an account administrator.

| Value | Where to find it |
|---|---|
| `accountId` | [My Account](https://privacyshield.fundamentia.com/administration) section of the administration panel |
| `api_key` | [Api Key](https://privacyshield.fundamentia.com/administration?tab=apikey) section of the administration panel |

You also need the identifier of a template (`templateId`) to send documents to. Templates are created in the web interface — see [introduction.md](introduction.md).

## Step 1 — Exchange the API key for a token

```bash
curl -X POST "https://api.privacyshield.fundamentia.com/api/v1/auth/1042" \
  -H "Content-Type: application/json" \
  -d '{"api_key": "YOUR_API_KEY"}'
```

```json
{
  "success": true,
  "message": "Generated access token",
  "Access-Token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

The token is in the `Access-Token` field of the body. Full reference: [endpoints/authentication.md](endpoints/authentication.md).

## Step 2 — Use the token

Send it as a header. The header name is the same as the JSON field: `Access-Token`. It is not an `Authorization: Bearer` header.

```bash
curl "https://api.privacyshield.fundamentia.com/api/v1/jobs/status" \
  -H "Access-Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

A missing header and an invalid token both return `401`, with different messages:

| Message | Meaning |
|---|---|
| `The Access-Token header is missing` | You did not send the header at all |
| `Invalid token` | The token expired, or its signature does not validate |

## Token expiry

> **The access token expires 30 minutes after it is issued.** Every request made with an expired token returns `401 Invalid token`. There is no refresh token — you get a new access token the same way you got the first one, by calling the authentication endpoint again with your API key.

Thirty minutes sounds like plenty until you write your first polling loop. A large job can outlive its token easily, and the failure lands in the middle of a wait, not at the start of your program. Plan for it.

### Refresh strategy

Use both halves. They cover different failure modes.

**Proactive.** Record when you obtained the token. Before each request, if the token is older than about 25 minutes, get a new one. This handles the predictable case and costs one extra call per half hour.

**Reactive.** If a request returns `401` anyway, get a new token and retry that request exactly once. This handles clock drift and tokens invalidated early. If the retry also returns `401`, stop — the problem is your API key or your account, not the token's age.

Retrying more than once on `401` turns a bad credential into a request loop. One retry, then fail loudly.

Both example clients implement exactly this: [`privacyshield_client.py`](../examples/python/privacyshield_client.py) and [`PrivacyShieldClient.java`](../examples/java/src/main/java/com/privacyshield/examples/PrivacyShieldClient.java).

```python
# Proactive refresh, from privacyshield_client.py
TOKEN_LIFETIME_SECONDS = 30 * 60
REFRESH_MARGIN_SECONDS = 5 * 60

def _ensure_token(self) -> str:
    age = time.monotonic() - self._token_obtained_at
    if self._token is None or age > TOKEN_LIFETIME_SECONDS - REFRESH_MARGIN_SECONDS:
        self.authenticate()
    return self._token
```

## Keeping the API key safe

The API key authenticates your whole account. Anyone holding it can submit documents against your page quota and download your anonymized results.

- Read it from an environment variable or a secret manager. Never commit it, and never put it in a source file that ships anywhere.
- Never use it from a browser or a mobile app. Both the API key and the token would be visible to the user, and the token is account-wide — it is not scoped to a single template. Call PrivacyShield from your backend.
- Rotate it through the administration panel if it leaks. Regenerating the key invalidates the old one.

The examples in this repository read every credential from the environment (`PRIVACYSHIELD_ACCOUNT_ID`, `PRIVACYSHIELD_API_KEY`, `PRIVACYSHIELD_TEMPLATE_ID`, `PRIVACYSHIELD_BASE_URL`) and fail with an explicit message when one is missing.

## Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 400 | `The body format is incorrect` | The body is not valid JSON, or `api_key` is missing | Fix the request. Do not retry as-is |
| 403 | `Invalid key` | The key format is not recognized | Check the value you copied from the administration panel |
| 404 | `Account not found` | No account has that `accountId` | Check `accountId` in the My Account section |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

Full error reference: [errors.md](errors.md).
