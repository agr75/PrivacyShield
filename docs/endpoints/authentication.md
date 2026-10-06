# Authentication endpoint

Exchanges your account's API key for a short-lived access token. This is the entry point: every other endpoint needs the token this one returns.

```
POST /api/v1/auth/{accountId}
```

No authentication is required to call it — the API key in the body *is* the authentication.

## Headers

| Header | Value | Required |
|---|---|---|
| `Content-Type` | `application/json` | Yes |

## Path parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `accountId` | Long | Yes | Your account identifier, from the [My Account](https://privacyshield.fundamentia.com/administration) section of the administration panel |

## Query parameters

None.

## Request body

`application/json`

| Field | Type | Required | Description |
|---|---|---|---|
| `api_key` | String | Yes | Your account's API key, from the [Api Key](https://privacyshield.fundamentia.com/administration?tab=apikey) section of the administration panel |

```json
{
  "api_key": "YOUR_API_KEY"
}
```

## Success response

`200 OK`

```json
{
  "success": true,
  "message": "Generated access token",
  "Access-Token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhY2NvdW50SWQiOjEwNDIsImV4cCI6MTc0OTQ3NTgyNX0.SIGNATURE_TRUNCATED"
}
```

| Field | Type | Description |
|---|---|---|
| `success` | Boolean | `true` |
| `message` | String | `Generated access token` |
| `Access-Token` | String | The JWT to send as the `Access-Token` header on every other request. Valid for 30 minutes |

Note that the token arrives in a field called `Access-Token` and is sent back in a header of the same name. It is not an `Authorization: Bearer` header.

## Errors

| Status | Message | Cause | What to do |
|---|---|---|---|
| 400 | `The body format is incorrect` | The body is not valid JSON, or `api_key` is missing | Fix the request. Do not retry as-is |
| 403 | `Invalid key` | The key format is not recognized | Re-copy the key from the administration panel |
| 404 | `Account not found` | No account has that `accountId` | Check `accountId`. A common mistake is using the template identifier here |
| 429 | `Lmite de peticiones comsumidas. Rate limit execeed.` | More than 10 calls from your account in 60 seconds, across all endpoints | Wait 60 seconds, then retry. See [limits.md](../limits.md#rate-limiting) |
| 500 | `An unexpected error has occurred on the server` | Server-side failure | Retry with exponential backoff |

A `403` means the key itself is wrong; a `404` means the account is wrong. The two failures look identical from the outside, so check both values before assuming your credentials were revoked.

## Token lifetime

The token expires **30 minutes** after it is issued. There is no refresh token: you get a new one by calling this endpoint again.

Do not call this endpoint before every request. Get a token, reuse it, and replace it when it is about to expire or when a request returns `401`. See [authentication.md](../authentication.md#refresh-strategy) for the full strategy.

## Examples

### cURL

```bash
curl -X POST "https://api.privacyshield.fundamentia.com/api/v1/auth/1042" \
  -H "Content-Type: application/json" \
  -d '{"api_key": "'"$PRIVACYSHIELD_API_KEY"'"}'
```

### Python

```python
import os
import requests

DEFAULT_BASE_URL = "https://api.privacyshield.fundamentia.com/api/v1"
BASE_URL = os.environ.get("PRIVACYSHIELD_BASE_URL", DEFAULT_BASE_URL)
ACCOUNT_ID = os.environ["PRIVACYSHIELD_ACCOUNT_ID"]
API_KEY = os.environ["PRIVACYSHIELD_API_KEY"]

response = requests.post(
    f"{BASE_URL}/auth/{ACCOUNT_ID}",
    json={"api_key": API_KEY},
    timeout=30,
)
response.raise_for_status()
access_token = response.json()["Access-Token"]

print(f"Token obtained: {access_token[:20]}...")
```

Full example: [`examples/python/01_authenticate.py`](../../examples/python/01_authenticate.py)

### Java

```java
String baseUrl = System.getenv().getOrDefault(
        "PRIVACYSHIELD_BASE_URL",
                            "https://api.privacyshield.fundamentia.com/api/v1");
String accountId = System.getenv("PRIVACYSHIELD_ACCOUNT_ID");
String apiKey = System.getenv("PRIVACYSHIELD_API_KEY");

ObjectMapper mapper = new ObjectMapper();
String requestBody = mapper.writeValueAsString(Map.of("api_key", apiKey));

HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/auth/" + accountId))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
        .build();

HttpResponse<String> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString());

String accessToken = mapper.readTree(response.body()).get("Access-Token").asText();
System.out.println("Token obtained: " + accessToken.substring(0, 20) + "...");
```

Full example: [`Authenticate.java`](../../examples/java/src/main/java/com/privacyshield/examples/Authenticate.java)

## Next

Send documents to a template: [submit-documents.md](submit-documents.md)
