# Java examples

Runnable examples for the PrivacyShield REST API. JDK 11 or newer, one
dependency: Jackson for JSON. HTTP comes from the JDK's `java.net.http.HttpClient`.

These are a 1:1 port of the [Python examples](../python): same names, same
behaviour, same console output.

## Setup

```bash
mvn -q compile
```

The first build downloads Jackson and the exec plugin, so it needs network
access to your Maven repository. After that it works offline.

Export your credentials:

```bash
export PRIVACYSHIELD_ACCOUNT_ID=1042
export PRIVACYSHIELD_API_KEY=your-api-key
export PRIVACYSHIELD_TEMPLATE_ID=789
```

| Variable | Required | Where it comes from |
|---|---|---|
| `PRIVACYSHIELD_ACCOUNT_ID` | Yes | [My Account](https://privacyshield.fundamentia.com/administration) in the administration panel |
| `PRIVACYSHIELD_API_KEY` | Yes | [Api Key](https://privacyshield.fundamentia.com/administration?tab=apikey) in the administration panel |
| `PRIVACYSHIELD_TEMPLATE_ID` | For upload and cancel | The template you send documents to, created in the web interface |
| `PRIVACYSHIELD_JOB_ID` | For `DownloadResult` and `CancelJob` | The `job` value `UploadDocument` prints |
| `PRIVACYSHIELD_BASE_URL` | No | Defaults to `https://api.privacyshield.fundamentia.com/api/v1` |

A missing variable stops the example with a message naming it and where to find
its value. Nothing is read from a hardcoded credential.

The variable is called `PRIVACYSHIELD_TEMPLATE_ID` because `templateId` is what the API
path uses. The web interface calls the same thing a **template**. See the
[glossary](../../docs/introduction.md#glossary).

## The examples

| Class | What it shows |
|---|---|
| [Authenticate](src/main/java/com/privacyshield/examples/Authenticate.java) | Exchange the API key for an access token |
| [UploadDocument](src/main/java/com/privacyshield/examples/UploadDocument.java) | Send one or more files in a single multipart request |
| [CheckStatus](src/main/java/com/privacyshield/examples/CheckStatus.java) | List jobs and their status — the endpoint you poll |
| [DownloadResult](src/main/java/com/privacyshield/examples/DownloadResult.java) | Download one job's results, including the `204` case |
| [EndToEnd](src/main/java/com/privacyshield/examples/EndToEnd.java) | **The whole flow with polling.** Start here |
| [CancelJob](src/main/java/com/privacyshield/examples/CancelJob.java) | Cancel one job, or every job of a template |
| [DownloadPending](src/main/java/com/privacyshield/examples/DownloadPending.java) | Drain everything pending across the account |
| [BatchProcessing](src/main/java/com/privacyshield/examples/BatchProcessing.java) | A folder at a time, with retries and a summary |
| [PrivacyShieldClient](src/main/java/com/privacyshield/examples/PrivacyShieldClient.java) | Reusable client — token renewal, retries, polling, multipart |

`UploadDocument`, `CheckStatus`, `DownloadResult`, `CancelJob` and
`DownloadPending` build their requests with `HttpClient` directly so you can see
exactly what goes over the wire. `EndToEnd` and `BatchProcessing` use
`PrivacyShieldClient`, which is the file to copy into your own project.

## Running them

Through Maven:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.Authenticate

mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.UploadDocument \
    -Dexec.args="contract.pdf scan.png"

mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.EndToEnd \
    -Dexec.args="contract.pdf"

mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.CancelJob \
    -Dexec.args="--template"

mvn -q compile exec:java -Dexec.mainClass=com.privacyshield.examples.BatchProcessing \
    -Dexec.args="./inbox 1800"
```

Or without the exec plugin, which is useful if your build environment cannot
download it:

```bash
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" com.privacyshield.examples.EndToEnd contract.pdf
```

Downloads are written to `./downloads`, which is git-ignored.

## What `EndToEnd` prints

```
PrivacyShield end-to-end example
  base URL : https://api.privacyshield.fundamentia.com/api/v1
  account  : 1042
  template : 789
  files    : 1

1) Authenticating ...
   Access token obtained.
2) Uploading 1 file(s) ...
   Job 12345 created, 1 document(s) accepted.
3) Waiting for job 12345 (timeout 900s) ...
   [   0s] new           - next check in 2s
   [   2s] processing    - next check in 4s
   [  22s] completed
4) Downloading results ...
   DOCUMENT                       STATUS      PAGES  COMPLETED
   contract.pdf                   done           12  2025-06-09T14:31:02
   Saved application/pdf to ./downloads/contract.pdf (39.1 KB)
   Those documents are now marked as downloaded.

Done in 24s. Job 12345 finished with status 'completed'.
```

## Why Jackson, when the reference client has no dependencies

The reference test client shipped with the API parses JSON with regular
expressions to stay dependency-free, and says so in its own comments. That
works for flat bodies like the authentication response, but this API also
returns arrays: the job listing, and the `X-Documents-Metadata` header. Matching
those with a regex means re-implementing a JSON parser badly.

If a dependency is genuinely not an option, keep the regex approach for the flat
responses and hand-write a small parser for the two array cases — but read
`X-Documents-Metadata` either way. It is the only per-document report the API
gives you.

## Java-specific notes

**Failures are unchecked.** `PrivacyShieldException` extends
`RuntimeException`, and the `IOException` / `InterruptedException` that
`HttpClient` throws are wrapped in `ServerException`. That keeps the examples
readable; wrap them in checked exceptions if your codebase prefers that.

**The multipart body is built in memory.** `HttpClient` has no multipart
support, so `buildMultipartBody` assembles a `byte[]`. It also means a retried
upload re-sends the same array with nothing to rewind. For files near the 150 MB
request limit, stream from disk with `BodyPublishers.ofFile` for a single-file
upload instead.

**Every `String.format` uses `Locale.ROOT`.** Otherwise a machine with a
comma-decimal locale prints `39,1 KB`, and the output stops matching the Python
examples and your own logs.

**`Config` reads only the environment.** The Python examples also support a
git-ignored `config.py` for local values; there is no Java equivalent, so
everything comes from environment variables.

## Behaviour worth knowing before you copy this code

**The token expires after 30 minutes.** `PrivacyShieldClient` renews it about
five minutes early and also retries a request once after a `401`. That second
half matters: a token can be invalidated before its nominal expiry.

**`204 No Content` is not an error.** It means nothing is ready to download yet.
Every example treats it as an ordinary outcome and reads
`X-Documents-Metadata`, which is present on `204` responses too.

**Downloading marks documents as downloaded.** They are not sent again. So the
examples write the file to disk before printing anything, and never overwrite an
existing file — `uniquePath()` adds ` (2)` instead.

**Retry only `5xx` and network failures.** A `400` fails identically forever.
`403`, `404` and `410` are configuration or retention problems, not transient
ones.

**Polling is the only completion signal.** `waitForJob` backs off exponentially
from 2 to 30 seconds, honours a global timeout, and stops with
`JobVanishedException` if the job leaves the listing — which is what happens to
a job that fails completely, and would otherwise be an infinite wait.

**Results are deleted 5 days after they finish.** Persist them somewhere of your
own as soon as you receive them.

## Checking the code compiles

```bash
mvn -q compile
```

There is no test environment: PrivacyShield has a single production
environment, and every submission consumes page quota. Test with documents you
are allowed to process, against a template created for that purpose.
