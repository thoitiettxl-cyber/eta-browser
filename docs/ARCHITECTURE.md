# Architecture

## Ownership

This repository owns the standalone Eta Browser Android app, its loopback protocol, production CLI, Pi adapter, companion skill, tests, and release documentation.

The Eta repository separately owns Eta's internal `browser_use` implementation. The implementations intentionally remain independent because sharing an Android library across repositories would add release coupling and a remote dependency to Eta. Compatibility is maintained through the original 13-action core, protocol/argument tests, payload limits, and installed-device parity checks. Eta Browser also owns seven additive standalone actions accepted in [`decisions/0001-bounded-browser-agent-extensions.md`](decisions/0001-bounded-browser-agent-extensions.md); those extensions do not change protocol version 2 or Eta's internal catalog.

## Android app

The `app` module uses application ID and namespace `com.thoitiettxl.eta`. It owns a serialized singleton `BrowserSessionEngine`, user takeover activities, persistent pairing state, and the foreground loopback bridge.

The shared WebView normally remains detached from an Activity, matching Eta's source lifecycle. A fresh detached screenshot switches the WebView to a software layer and calls `view.draw` into a bounded bitmap. BrowserActivity temporarily attaches the same instance for observation or user takeover; after a committed page has been visibly attached, detached capture uses the renderer's retained picture because Chromium no longer produces that page through software `view.draw`.

The WebView profile preserves the source Eta behavior. It is permissive by design and must not be described as a sandbox.

## Transport

The bridge listens only on `127.0.0.1:18765` and accepts one newline-delimited JSON request per TCP connection. Authentication uses the app-private pairing credential. Protocol version 2 requires explicit lease acquisition and release.

Request frames retain at most 64 KiB. Oversized frames are bounded-drained toward newline or EOF with a 1 MiB ceiling and two-second deadline before `REQUEST_TOO_LARGE` is returned. Bridge work is bounded to four executing requests plus eight queued connections; excess connections receive `SERVER_BUSY`.

## Session and cancellation

Only one lease may own the browser. Execute and reset require matching `client_id` and `lease_id`. One operation may be active. Stop additionally requires the exact active request ID. Released leases remain stale, and reset is non-cancellable.

The Android UI can attach to the same WebView. While user takeover is active, external browser actions fail with `USER_CONTROL_ACTIVE`.

`request_help` is one cancellable active browser operation. It retains the authenticated lease, marks the shared WebView as user-controlled without cancelling itself, presents a generic notification plus an in-app prompt, and waits for explicit Done/Cancel, timeout, or bounded URL/selector completion criteria. Other browser operations cannot interleave. Exact `browser.stop`, bridge shutdown, revoke, and normal cancellation interrupt the wait and clear handoff UI state.

## Semantic observation and refs

`observe` injects a bounded semantic DOM traversal and returns at most 32 visible controls with role, accessible name, safe state, bounds, selector fallback, and opaque `@eN` refs. Form values are never returned. Only the latest observation's document-local ref table exists. A later observation replaces it; navigation, reset, document replacement, or a disconnected node causes ref targeting to fail with `STALE_ELEMENT_REF`.

`click`, `type`, `hover`, and `select` accept one selector, ref, or coordinate pair. `press` may target one of those or the active element. `hover` and key delivery use bounded synthetic WebView events rather than CDP and therefore require installed-device acceptance for target sites.

## Diagnostics

Console and network diagnostics are synchronized in-memory rings capped at 200 entries. Cursor reads return at most 100 items and remain subject to the normal approximately 12 KiB text envelope.

Console messages are consumed by `WebChromeClient` and are not printed to app logs. Network events use normal `WebViewClient` request/error callbacks, strip URL user-info/query/fragment, and never capture headers, bodies, cookies, authorization data, interception payloads, or timings. Android WebView does not expose complete successful response metadata through this boundary, so `network` explicitly reports incomplete callback-based coverage rather than a DevTools-equivalent trace. Reset clears both rings.

## Payloads

Text is bounded to approximately 12 KiB. Screenshots are bounded to 1280 by 2400 pixels and are returned as JPEG data by the bridge. The CLI writes screenshots only to owner-private files; the Pi adapter converts screenshot data to native image tool content without returning inline base64 in text.

## Clients

`tools/eta-browser-cli` owns stable JSON envelope version 1, private credential/session storage, signal-aware exact cancellation, and exit codes `0`, `2`, `3`, `4`, `5`, `130`, and `143`.

`pi/eta-browser-extension` imports the CLI's public transport and config exports. It exposes `eta_browser_use`; pairing and persistent-session administration remain CLI responsibilities.

`pi/skills/eta-browser-skill-forge` converts an explicitly authorized, verified Eta Browser workflow into one review-only site-specific `SKILL.md`. It uses the same serialized action and human-handoff boundaries and never installs generated skills automatically.
