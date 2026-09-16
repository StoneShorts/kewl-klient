# Jagex Account Authentication Findings

**Analysis target:** `osclient.exe` loaded in IDA Pro (client build present during analysis).

**Scope:** Static reverse-engineering findings only. No account values, session IDs, character IDs, access tokens, or refresh tokens are recorded here.

## Executive summary

Launching the client with only `JX_SESSION_ID`, `JX_CHARACTER_ID`, and `JX_DISPLAY_NAME` is **not sufficient to describe the complete login flow**. The native client reads those values, stores them in an internal authentication state, and performs a subsequent HTTP/token exchange before using a short-lived access token for the game-session/play request.

The important conclusion is:

```text
JX_SESSION_ID + JX_CHARACTER_ID
    -> native auth state
    -> game-session/v1/tokens request
    -> access_token / refresh_token / expires_in response
    -> authenticated play/session request
    -> normal game login continuation
```

`JX_SESSION_ID` is therefore not merely copied directly into the final game-login packet. It is used as a bearer credential for an intermediate token exchange.

## Environment-variable ingestion

### `sub_140039A40` — environment-variable harvest

- **Address:** `0x140039A40`
- **RVA:** `0x39A40`
- **Caller:** `sub_1400396A0` (`0x1400396A0`, RVA `0x396A0`)
- **Startup caller:** `sub_14005A440` (`0x14005A440`, RVA `0x5A440`) constructs/initializes the auth object that owns this state.
- **API:** `GetEnvironmentVariableA`

The function reads the following variables, using the usual two-call pattern (query required length, allocate/construct a string, then read the value):

- `JX_DISPLAY_NAME`
- `JX_ACCESS_TOKEN`
- `JX_REFRESH_TOKEN`
- `JX_SESSION_ID`
- `JX_CHARACTER_ID`

The display name is copied into a separate global string at approximately `0x140CFAA10`. The other values are routed into authentication state through the internal string/state installation routines.

The function also branches on a client/service state byte at the client object (`qword_140E95668 + 8633`). If the client is already in the service-managed path, it uses the service/display-name route; otherwise it reads the access, refresh, session, and character environment variables.

**Status:** PROVEN by decompilation and direct `GetEnvironmentVariableA` references.

### `sub_1400396A0` — auth-state initialization

- **Address:** `0x1400396A0`
- **RVA:** `0x396A0`
- **Calls:** `sub_140039A40`
- **Called by:** `sub_14005A440`

It initializes the auth object, stores the owning object pointer at `a1+40`, clears the auth state word at `a1+48`, and then invokes the environment harvest function.

**Status:** PROVEN.

## Credential storage

The native client maintains separate small-string/string-object globals. The relevant storage locations observed in the current build are:

| Meaning | Global | RVA | Evidence |
|---|---:|---:|---|
| OAuth access token | `xmmword_141B91CC0` plus adjacent metadata | `0x1B91CC0` | Read by `sub_1406DA200`, `sub_1406D98D0`; populated by response handling |
| OAuth refresh token | `xmmword_141B91CD8` plus adjacent metadata | `0x1B91CD8` | Read by refresh logic; populated by response handling |
| OAuth expiry | `qword_141B91CF0` | `0x1B91CF0` | Set from `expires_in`; checked by refresh scheduler |
| Jagex session ID | `xmmword_141B91CF8` plus adjacent metadata | `0x1B91CF8` | Read by `sub_1406D9E10` as a bearer credential |
| Jagex character/account ID | `xmmword_141B91D10` plus adjacent metadata | `0x1B91D10` | Read by `sub_1406D9E10` as `accountId` |
| Current auth request object | `qword_141B91C98` | `0x1B91C98` | Replaced for each HTTP request |
| Auth state | `dword_141B91C90` | `0x1B91C90` | Values include request/processing states such as `1`, `3`, and `5` |

The globals are string objects, not plain null-terminated fixed buffers. Their short-string/heap-string representation explains the adjacent metadata checks in the decompiler output.

**Status:** PROVEN as storage and consumer locations for this executable. Exact semantic names for some internal string metadata fields are inferred from their use.

## Session-to-token exchange

### `sub_1406D9E10` — game-session token exchange

- **Address:** `0x1406D9E10`
- **RVA:** `0x6D9E10`
- **Callers:** `sub_1406C9180` and `sub_1406C9190`
- **Endpoint string:** `game-session/v1/tokens` at approximately `0x140BABA38`

The function:

1. Creates/replaces the current HTTP request object at `qword_141B91C98`.
2. Builds an `Authorization` header with:
   `Bearer <value from JX_SESSION_ID storage>`.
3. Sets:
   - `Content-Type: application/json`
   - `Accept: application/json`
4. Builds a JSON value containing:
   - key: `accountId`
   - value: the stored `JX_CHARACTER_ID` value
5. Submits the request through `sub_1408D1EE0`.
6. Sets the auth state to `5` and clears/resets request bookkeeping.

The decompiler shows the `accountId` key directly and the bearer source directly from the session-ID storage global. This is the strongest evidence that the session ID is exchanged rather than used as the final game credential.

**Status:** PROVEN for the request construction and inputs. The precise server response schema is established by the response parser below.

### `sub_1406DAAE0` — token-response parser

- **Address:** `0x1406DAAE0`
- **RVA:** `0x6DAAE0`
- **Input:** completed HTTP request/response object at `qword_141B91C98`
- **JSON keys read:** `access_token`, `refresh_token`, `expires_in`

For HTTP status `200`, it parses the response body and:

1. Extracts `access_token` into the OAuth access-token storage.
2. Extracts `refresh_token` into the OAuth refresh-token storage.
3. Computes and stores an expiry timestamp:
   `current_time + expires_in`
4. Replaces/clears the previous OAuth token strings.

For HTTP status `400`, or when the response is otherwise unusable, it clears the OAuth token state and expiry.

**Status:** PROVEN by decompilation. No response values were captured or stored.

## Cached OAuth-token path

### `sub_1406DA200` — authenticated play/session request

- **Address:** `0x1406DA200`
- **RVA:** `0x6DA200`
- **Caller:** `sub_1406D94A0` and other auth scheduling paths
- **Endpoint string:** `public/v1/games/YCfdbvr2pM1zUYMxJRexZY/play` at approximately `0x140BABA50`

This function uses the stored OAuth access token, not the original session ID:

1. Builds `Authorization: Bearer <OAuth access token>`.
2. Sets `Content-Type: application/x-www-form-urlencoded`.
3. Sets `Accept: application/json`.
4. Constructs the service/path request using the internal service-name/configuration string.
5. Submits through `sub_1408D1EE0`.

This is the subsequent authenticated request that advances the client toward the game/session login.

**Status:** PROVEN for token source, headers, endpoint string reference, and HTTP submission.

### `sub_1406D8F20` — session-pair availability check

- **Address:** `0x1406D8F20`
- **RVA:** `0x6D8F20`

Returns true when both the stored session ID and character/account ID are non-empty. This is the gate used to determine whether the session-based exchange is available.

**Status:** PROVEN.

### Refresh-token and token-validation paths

The binary also contains these additional auth functions:

| Function | RVA | Observed behavior |
|---|---:|---|
| `sub_1406D95C0` | `0x6D95C0` | Builds a form request using the refresh-token state and submits through the common HTTP layer; associated with `shield/oauth/token`. |
| `sub_1406D98D0` | `0x6D98D0` | Builds a token validation request using the access-token state; associated with `shield/oauth/check_token`. |
| `sub_1406D9B50` | `0x6D9B50` | Auth scheduling/response path that can invoke the cached-token machinery. |
| `sub_1406C9190` | `0x6C9190` | Auth state transition path; references the exchange, refresh, and token-validation functions. |
| `sub_1406C9350` | `0x6C9350` | Another auth-state/request scheduler path. |

The presence of `expires_in`, refresh-token storage, and the refresh/check-token endpoints proves that the client supports a second launch/session path in which a previously obtained OAuth token is reused or refreshed.

## Request/response state machine

The exact numeric enum names are not present in the stripped/decompiled output, but observed writes establish at least these transitions:

```text
state = 1  -> token validation request path
state = 3  -> refresh-token request path
state = 5  -> game-session/play request path or request completion setup
```

The current request object is repeatedly replaced at `qword_141B91C98`. Response handling examines the HTTP status at an offset inside that request object and dispatches token parsing/cleanup accordingly.

## What a launcher must provide

### Minimum observed inputs for the session-exchange path

A launcher must provide, through the process environment or an equivalent native initialization path:

- `JX_SESSION_ID`
- `JX_CHARACTER_ID`

`JX_DISPLAY_NAME` is also read and copied during initialization, but the inspected exchange function uses the session ID and character ID—not the display name—to construct the `game-session/v1/tokens` request.

### Existing-token path

The client also accepts:

- `JX_ACCESS_TOKEN`
- `JX_REFRESH_TOKEN`

These feed the cached OAuth-token path. The client may validate or refresh those values instead of performing the session exchange, depending on its current state and expiry data.

### Important caveat

The source strings and call graph show that environment variables are inputs to the native auth state, not a guarantee that login succeeds by themselves. Server responses, account/character validity, service configuration, world/session selection, and normal client state transitions still have to succeed.

## End-to-end proven flow

```text
sub_14005A440
  -> sub_1400396A0
      -> sub_140039A40
          -> GetEnvironmentVariableA("JX_SESSION_ID")
          -> GetEnvironmentVariableA("JX_CHARACTER_ID")
          -> store into auth string state

sub_1406D8F20
  -> verifies session ID + character ID are present

sub_1406C9180 / sub_1406C9190
  -> sub_1406D9E10
      -> Authorization: Bearer <JX_SESSION_ID>
      -> JSON: { "accountId": <JX_CHARACTER_ID> }
      -> game-session/v1/tokens

sub_1406DAAE0
  -> parses access_token, refresh_token, expires_in
  -> stores short-lived OAuth credentials

sub_1406DA200
  -> Authorization: Bearer <access_token>
  -> public/v1/games/.../play
  -> continues authenticated game/session startup
```

The final world-login packet construction was not identified as a direct consumer of `JX_SESSION_ID`; the native auth subsystem first obtains/uses OAuth-style credentials and submits the authenticated play/session request. Further tracing of the response callback after `sub_1406DA200` would be needed to label the exact boundary between the HTTP play response and the subsequent RuneScape world-login protocol.

## Confidence and limitations

- **PROVEN:** environment-variable names, first reader, storage globals, session exchange headers/body, endpoint strings, token-response fields, OAuth access-token use in the play request, refresh/check-token functions, and the relevant IDA addresses/RVAs.
- **INFERRED:** friendly names for stripped internal functions, exact numeric auth-state enum labels, and the final transition from the play/session response into the low-level world-login protocol.
- **Redaction:** no real credential, token, account ID, character ID, or session ID is included in this document.
