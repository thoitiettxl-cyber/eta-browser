# Eta Browser CLI

`eta-browser` is the production command-line client for the separately installable Eta Browser Android app. It controls the app's single shared WebView through the authenticated loopback bridge at `127.0.0.1:18765`.

The CLI requires Node.js 20 or newer and has no production npm dependencies.

## Security boundary

- The bridge accepts only local TCP connections on `127.0.0.1:18765`, but localhost is shared with other apps on the device. Pairing authentication is still mandatory.
- Eta Browser intentionally preserves Eta's permissive WebView profile. It is **not a security sandbox**: local/mixed content, third-party cookies, autoplay, and form submission remain enabled, and the app does not add URL, DNS, IP, redirect, or Service Worker filtering.
- Pairing credentials and persistent lease state are stored in `${XDG_CONFIG_HOME:-~/.config}/eta-browser/config.json` with mode `0600`; the parent directory is mode `0700`.
- `pair` prints the generated credential once because it must be entered into Eta Browser. `rotate`, `revoke`, health, session, and browser commands never print credentials. Avoid `--token` during normal use because command-line arguments may be visible in process listings or shell history.
- Page text and screenshots may contain sensitive data. JSON remains on stdout; diagnostics remain on stderr. Screenshots are written only to an explicit `--output` path and inline image data is removed from the JSON result.

## Install

From this repository:

```sh
cd /absolute/path/to/eta-browser/tools/eta-browser-cli
npm link
```

This installs the `eta-browser` command through npm's configured prefix. If an older wrapper already owns that command, review or remove it deliberately before linking; the repository does not overwrite existing global commands automatically.

For a repository-local smoke test without changing PATH:

```sh
node /absolute/path/to/eta-browser/tools/eta-browser-cli/eta-browser.mjs --help
```

The executable resolves its modules relative to itself and works from any current working directory.

The reusable Story 5 transport is exported as `eta-browser-cli` and private
config helpers as `eta-browser-cli/config`; consumers should import these
modules rather than shelling out or reading the credential file directly.

## Pair and enable the bridge

1. Install and open the Eta Browser Android app.
2. Generate a setup credential:

   ```sh
   eta-browser pair
   ```

3. Enter the printed token in Eta Browser, tap **Pair this device**, then enable the bridge.
4. Verify protocol v2 and current browser state:

   ```sh
   eta-browser health
   ```

`configure` remains an alias for `pair` for compatibility with the Story 1/3 spike.

Rotation and revocation:

```sh
eta-browser rotate
eta-browser revoke
```

Rotation replaces the credential in the app and local config without printing it. Revocation removes the local token and requests bridge shutdown.

## Stable JSON contract

Every non-help command emits exactly one JSON object followed by a newline on stdout.

Success:

```json
{
  "version": 1,
  "ok": true,
  "command": "health",
  "result": {}
}
```

Error:

```json
{
  "version": 1,
  "ok": false,
  "command": "navigate",
  "error": {
    "category": "transport",
    "code": "REQUEST_TIMEOUT",
    "message": "Bridge request timed out"
  }
}
```

Browser-level failures also retain the bridge's bounded `result` so callers can inspect the browser action/status without parsing stderr. The top-level `version` is the output contract version; incompatible output changes require a new value. Exact representative payloads are snapshot-tested under `test/snapshots/`.

Help is written to stderr and emits no JSON:

```sh
eta-browser --help
eta-browser --version
```

## Exit codes

| Code | Category | Meaning |
| ---: | --- | --- |
| `0` | success | Command completed successfully. |
| `2` | usage/config | Invalid arguments, missing/unsafe config, or locally oversized request. |
| `3` | transport | Bridge unavailable, connection failure, empty response, or request timeout. |
| `4` | bridge/protocol | Auth, lease, bridge method, protocol-version, malformed-response, or server error. |
| `5` | browser | The bridge replied, but the browser action returned `browser.ok=false`. |
| `130` | interrupted | `SIGINT`; cancellable actions first send exact-request `browser.stop`. |
| `143` | interrupted | `SIGTERM`; cancellable actions first send exact-request `browser.stop`. |

## Session lifecycle

Actions remain convenient one-shot commands: without a persistent session, the CLI acquires a temporary lease, executes one operation, then releases it.

Use a persistent lease for multi-command workflows:

```sh
eta-browser session acquire
eta-browser navigate https://example.com
eta-browser get-readable
eta-browser find-elements --selector 'a,button,input'
eta-browser session release
```

Commands:

```sh
eta-browser session status
eta-browser session forget  # local recovery only; does not release the bridge lease
eta-browser stop [REQUEST_ID]
```

`session status` and `session forget` inspect only local mode-`0600` state and
do not require a live credential. Acquire, release, actions, and stop remain
authenticated bridge operations.

`session forget` is a recovery command for stale local state. Prefer `session release`; forgetting a live lease can leave the bridge occupied until the app bridge is disabled/revoked/restarted.

`stop` requires a persisted session. If `REQUEST_ID` is omitted, the CLI reads `active_request_id` from authenticated health, then sends `browser.stop` with the stored lease and exact request ID.

## Browser commands

The original 13 actions from Eta's `browser_use` catalog remain the compatibility core. Eta Browser also exposes seven additive standalone actions under protocol version 2.

```sh
eta-browser navigate URL [--timeout-ms 25000]
eta-browser get-readable [--offset 0] [--max-chars 8000]
eta-browser get-text [--selector CSS] [--offset 0] [--max-chars 8000]
eta-browser find-elements [--selector CSS]
eta-browser observe
eta-browser click --selector CSS
eta-browser click --ref @e7
eta-browser click --coordinate-x X --coordinate-y Y
eta-browser type TEXT --selector CSS [--submit]
eta-browser type TEXT --ref @e7 [--submit]
eta-browser type TEXT --coordinate-x X --coordinate-y Y [--submit]
eta-browser hover --selector CSS
eta-browser hover --ref @e7
eta-browser select VALUE --selector CSS
eta-browser select VALUE --ref @e7
eta-browser press Enter [--selector CSS | --ref @e7]
eta-browser scroll [--selector CSS] [--direction up|down] [--amount 600]
eta-browser screenshot --output PATH
eta-browser get-page-info
eta-browser go-back
eta-browser go-forward
eta-browser reload
eta-browser wait-for-selector CSS [--timeout-ms 5000]
eta-browser request-help PROMPT [--title TITLE] [--target-selector CSS] [--timeout-ms 300000]
eta-browser console [--since 0] [--limit 50]
eta-browser network [--since 0] [--limit 50]
```

`observe` returns at most 32 visible semantic controls. Its `@eN` refs are valid only for the latest observation in the current document. A later observation, navigation, reset, document replacement, or disconnected element makes an older ref fail with `STALE_ELEMENT_REF`; observe again rather than falling back blindly to coordinates.

High-level `select` accepts one option value. The raw `action` command and Pi adapter also accept a bounded `values` array for multi-select controls. `press` accepts `Enter`, `Escape`, `Tab`, `Shift+Tab`, arrow keys, `Home`, `End`, `PageUp`, `PageDown`, `Space`, `Backspace`, `Delete`, and `Ctrl+A`.

Compatibility aliases:

```sh
eta-browser read       # get-readable
eta-browser page-info  # get-page-info
```

Raw escape hatch for protocol-supported action parameters:

```sh
eta-browser action '{"action":"get_text","selector":"article","max_chars":12000}'
```

Prefer the high-level commands; `action` is for forward-compatible repair or testing, not the normal interface.

## Human handoff

Use `request-help` when the page requires login, CAPTCHA, OTP, payment confirmation, sensitive consent, or another user-only step:

```sh
eta-browser request-help "Complete verification, then tap Done" \
  --title "Verification required" \
  --target-selector '#challenge' \
  --completion-url-contains /dashboard \
  --completion-selector '#account-menu' \
  --completion-match any \
  --stable-for-ms 1000 \
  --timeout-ms 300000
```

The bridge keeps the authenticated lease and active request while BrowserActivity gives the user the shared WebView. The notification is generic and does not contain the prompt or page content. Result outcomes are `continued`, `cancelled`, `timed_out`, and `completed`. The action never returns values entered by the user. It remains cancellable through exact-request `browser.stop` and signals.

After `continued` or `completed`, observe the page again before using any prior selector or ref assumption. Treat `cancelled` as rejection and do not seek a bypass. Do not automatically repeat `timed_out`.

## Read-only diagnostics

`console` and `network` read bounded in-memory rings using `since`/`next_since` cursors and limits from 1 to 100. Reset clears both buffers.

Console entries contain bounded level/message/source/line metadata and are consumed instead of printed to Android logs. Network entries come from normal WebView request/error callbacks. URLs omit user-info, query, and fragment; headers, bodies, cookies, authorization data, timing, and interception are never captured. Successful response status coverage is incomplete, so `network` is not a DevTools-equivalent trace.

## Timeouts and cancellation

The transport timeout defaults to 45 seconds and is bounded between 500 ms and 120 seconds:

```sh
eta-browser health --request-timeout-ms 5000
```

Browser-specific timeouts are sent through `--timeout-ms`: navigation clamps to 25 seconds, selector waits to 30 seconds, and human handoff to five minutes. The CLI automatically keeps the request transport alive slightly longer than a bounded `request-help` timeout.

On `SIGINT` or `SIGTERM` during `browser.execute`, the CLI sends `browser.stop` using the same `client_id`, lease, and exact active request ID before closing the request connection. Reset is non-cancellable by protocol; signals do not send `browser.stop`. The CLI continues waiting for its response or bounded transport timeout, then reports interruption.

## Real-device smoke workflow

After installing a CI-built debug APK and enabling a paired bridge, run the
non-resetting repository workflow:

```sh
npm run smoke:device
```

It defaults to `https://example.com` and `/tmp/eta-browser-smoke.jpg`. Override
them with `ETA_BROWSER_SMOKE_URL` and `ETA_BROWSER_SMOKE_OUTPUT`. The workflow
does not call `reset`, so it does not clear cookies/history. Its expanded form is:

```sh
set -eu
eta-browser health
eta-browser session acquire
trap 'eta-browser session release >/dev/null 2>&1 || true' EXIT INT TERM
eta-browser navigate https://example.com
eta-browser wait-for-selector h1
eta-browser get-readable --max-chars 2000
eta-browser find-elements --selector a
eta-browser observe
eta-browser get-page-info
eta-browser console --limit 10
eta-browser network --limit 20
eta-browser screenshot --output /tmp/eta-browser-example.jpg
test -s /tmp/eta-browser-example.jpg
eta-browser session release
trap - EXIT INT TERM
```

For cancellation proof, run a long `navigate` or `wait-for-selector`, send `SIGINT`, and verify exit code `130`, JSON category `interrupted`, and health returning no active request. Do not use a sensitive URL or form value for acceptance transcripts.

## Troubleshooting and recovery

- `CONNECTION_FAILED`: open Eta Browser, confirm pairing, and explicitly enable the foreground bridge.
- `UNAUTHORIZED` / `PAIRING_REQUIRED`: run `eta-browser pair`, install that token in the app, then enable the bridge.
- `SESSION_BUSY`: another client or a forgotten local lease owns the single browser session. Release it from the owning config; otherwise disable/re-enable or revoke the bridge from the app.
- `STALE_CLIENT`: local lease state no longer matches the bridge. Run `eta-browser session forget`, then acquire a new session.
- `USER_CONTROL_ACTIVE`: leave the human-takeover browser screen before retrying automation.
- `HUMAN_HANDOFF_UNAVAILABLE`: open Eta Browser's browser screen in the foreground or allow its human-handoff notification channel, then retry once.
- `STALE_ELEMENT_REF`: run `observe` again; never assume an older ref still names the same element.
- `REQUEST_TIMEOUT`: increase `--request-timeout-ms` only within the documented bound and investigate bridge/browser state; do not remove the timeout.
- Full recovery: disable the bridge in Eta Browser. Revoking credentials additionally clears pairing and stops the bridge; browser reset is separate and clears page/cookies/history.

## Development and validation

```sh
npm run check
npm test
```

The integration suite uses a fake TCP bridge and covers stable JSON snapshots, exit codes, private config, the 13-action compatibility core plus seven standalone actions, persistent sessions, screenshots, bounded handoff timeout, exact-request signal cancellation, and execution from a cwd outside the repository.
