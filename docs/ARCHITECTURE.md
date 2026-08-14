# Architecture

## Ownership

This repository owns the standalone Eta Browser Android app, its loopback protocol, production CLI, Pi adapter, companion skill, tests, and release documentation.

The Eta repository separately owns Eta's internal `browser_use` implementation. The implementations intentionally remain independent because sharing an Android library across repositories would add release coupling and a remote dependency to Eta. Compatibility is maintained through the same 13-action vocabulary, protocol/argument tests, payload limits, and installed-device parity checks.

## Android app

The `app` module uses application ID and namespace `com.thoitiettxl.eta`. It owns a serialized singleton `BrowserSessionEngine`, user takeover activities, persistent pairing state, and the foreground loopback bridge.

MainActivity keeps the shared WebView attached behind its opaque controls from initial app startup. This primes Android WebView's rendering surface before any external request, so the first screenshot does not depend on the user opening BrowserActivity. BrowserActivity can still move the same WebView into its visible container; after it is destroyed, detached capture remains available.

The WebView profile preserves the source Eta behavior. It is permissive by design and must not be described as a sandbox.

## Transport

The bridge listens only on `127.0.0.1:18765` and accepts one newline-delimited JSON request per TCP connection. Authentication uses the app-private pairing credential. Protocol version 2 requires explicit lease acquisition and release.

Request frames retain at most 64 KiB. Oversized frames are bounded-drained toward newline or EOF with a 1 MiB ceiling and two-second deadline before `REQUEST_TOO_LARGE` is returned. Bridge work is bounded to four executing requests plus eight queued connections; excess connections receive `SERVER_BUSY`.

## Session and cancellation

Only one lease may own the browser. Execute and reset require matching `client_id` and `lease_id`. One operation may be active. Stop additionally requires the exact active request ID. Released leases remain stale, and reset is non-cancellable.

The Android UI can attach to the same WebView. While user takeover is active, external browser actions fail with `USER_CONTROL_ACTIVE`.

## Payloads

Text is bounded to approximately 12 KiB. Screenshots are bounded to 1280 by 2400 pixels and are returned as JPEG data by the bridge. The CLI writes screenshots only to owner-private files; the Pi adapter converts screenshot data to native image tool content without returning inline base64 in text.

## Clients

`tools/eta-browser-cli` owns stable JSON envelope version 1, private credential/session storage, signal-aware exact cancellation, and exit codes `0`, `2`, `3`, `4`, `5`, `130`, and `143`.

`pi/eta-browser-extension` imports the CLI's public transport and config exports. It exposes `eta_browser_use`; pairing and persistent-session administration remain CLI responsibilities.
