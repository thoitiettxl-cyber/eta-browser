---
name: eta-browser
description: Control the paired standalone Eta Browser with Pi's eta_browser_use custom tool and discover DuckDuckGo results with web_search. Use for web search, browser navigation, semantic observation, rendered-page reading, element interaction, explicit human handoff, bounded console/network diagnosis, screenshots, reset, or recovery from bridge, authentication, lease, cancellation, SEARCH_BLOCKED, and USER_CONTROL_ACTIVE errors.
---

# Eta Browser

Use `eta_browser_use` for normal browser operations. Use the shell CLI only for initial pairing, credential repair, explicit persistent-session administration, or a CLI-only diagnostic the Pi tool does not expose.

Eta Browser owns one shared Android WebView, one protocol-v2 lease, and one active operation. Serialize browser calls; never run them in parallel.

## Search discovery

Use `web_search` when the requested outcome is a bounded DuckDuckGo result list. It accepts one query and up to ten results, uses the branded root results page with exact-origin checks, returns titles, destination URLs, and only unambiguously aligned optional snippets, and leaves the shared WebView on the search results page. Use `eta_browser_use` to open and read only the selected sources needed for the task.

The tool may briefly re-observe the same dynamically rendered result page, but it performs only one search navigation. Treat that bounded settling as part of the call, not as authority to reload or repeat a failed search.

Treat `SEARCH_BLOCKED` as a wrong-origin, provider-denial, rate-limit, consent, CAPTCHA, anti-bot, or unrecognized-result boundary. Do not reload, repeat the search, switch providers, accept consent, or attempt a bypass automatically. Use `request_help` only when the user authorizes the blocked human step.

## Bounded execution

1. Derive one concrete, observable success condition from the user's request before acting.
2. Take the shortest path: inspect, act, then perform only the confirmation proportional to the requested outcome.
3. Stop browser control as soon as success is confirmed. Do not navigate, click, reload, re-search, reset, or “double-check” after success unless the user requested that separate work.
4. If the same step fails twice without progress, stop and report the blocker or request human help. Do not brute-force selectors, coordinates, login, CAPTCHA, OTP, payment, or consent steps.
5. `reset` clears the current page, cookies, storage, history, refs, handoff state, and diagnostics. Use it only when the task explicitly requires that destructive browser-state change; reset is non-cancellable.

## Observation priority

Choose the smallest sufficient observation:

1. `get_page_info` for URL, title, viewport, scroll, and page state.
2. `get_readable` for primary readable content.
3. `observe` before interaction: it returns visible semantic controls and ephemeral refs.
4. `find_elements` when a specific CSS query is needed.
5. `get_text` for exact selector-scoped text extraction.
6. `screenshot` only for layout, canvas, image, styling, or other visual evidence that semantic/text output cannot establish.
7. `console` or `network` only for bounded read-only diagnosis. Network output is WebView callback-based, omits headers and bodies, and is not a complete DevTools trace.

Do not call screenshot, console, or network first merely to inspect a normal page.

## Ref and page-state rules

- Prefer a ref from the latest `observe` result over a brittle CSS selector.
- Refs are valid only for that latest observation in the current document. A later `observe`, navigation, reset, document replacement, or disconnected element invalidates prior refs.
- After navigation, asynchronous DOM replacement, a human handoff, history movement, reload, or `STALE_ELEMENT_REF`, observe again before acting.
- Prefer CSS selectors when a stable application-owned selector exists. Use coordinates only when semantic refs and stable selectors are unavailable and current visual evidence identifies the target.
- Use `wait_for_selector` after an interaction that updates the page asynchronously.

## Interaction

- Use `click` and `type` for ordinary controls.
- Use `select` for `<select>` option values instead of simulating clicks.
- Use `press` for the bounded supported keys or combos.
- Use `hover` only when the UI requires a hover-triggered surface, then immediately observe again before selecting a newly revealed item. WebView hover is synthetic and may require installed-page confirmation.

## Human handoff

Use `request_help` for login, CAPTCHA, OTP, payment confirmation, sensitive consent, or any step the user must perform personally.

- Give one precise in-app instruction and an optional target selector.
- Use completion criteria only when the post-help signal is concrete, such as a URL fragment or selector.
- Never ask the user to paste a password, OTP, CAPTCHA answer, payment value, cookie, or token into chat.
- While handoff is active, the user owns the shared WebView and other external browser actions remain blocked.
- `continued` or `completed`: discard previous page assumptions and observe again.
- `cancelled`: treat the requested step as rejected and stop; do not seek a bypass.
- `timed_out`: stop and ask whether the user wants another bounded handoff; do not retry automatically.

For an unsolicited `USER_CONTROL_ACTIVE`, stop external actions until the user exits takeover mode. Do not attempt to reclaim control or reset the browser.

## Availability diagnosis

- Call `health` when bridge availability or shared-WebView ownership is uncertain.
- A successful `health` proves the bridge is reachable. `browser_available: false` alone can be transient while the WebView registers or wakes.
- Do not tell the user to open Eta Browser or enable Bridge based on one `browser_available: false` snapshot.
- Retry `health` once or attempt the requested `navigate`. Ask the user to open Eta Browser and enable Bridge only after an actual connection error, or after unavailable state persists and the requested operation also fails for lack of a WebView.

## Recovery

- `USER_CONTROL_ACTIVE`: wait for the user to exit takeover; after return, observe again.
- `HUMAN_HANDOFF_UNAVAILABLE`: ask the user to open Eta Browser's browser screen or allow its human-handoff notifications, then retry only if they still want the step.
- `UNAUTHORIZED`, `PAIRING_REQUIRED`, or token/config errors: repair pairing with `eta-browser`; never request or print the credential in chat.
- Confirmed connection error: ask the user to open Eta Browser and explicitly enable the bridge.
- `SESSION_BUSY`: preserve single-client ownership. Wait for or release the owning CLI session instead of bypassing it.
- `STALE_ELEMENT_REF`: run `observe` again; do not silently switch to coordinates.
- Cancellation: retry only when the user still wants the operation. The tool sends `browser.stop` with the exact client, lease, and request identity.

Never expose credentials, lease IDs, request IDs, cookies, authorization data, inline screenshot base64, sensitive console/page data, or values entered by the user beyond what the task strictly requires.
