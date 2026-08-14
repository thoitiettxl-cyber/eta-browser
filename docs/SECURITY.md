# Security

## Web content

Eta Browser is not a security sandbox. Its WebView intentionally allows JavaScript, local content, mixed content, third-party cookies, autoplay, and form submission for compatibility with Eta's browser behavior. There is no additional URL, host, DNS, IP, redirect, request-method, or Service Worker policy layer.

Do not use the browser for untrusted pages when the permissive profile is unacceptable. Pages can observe or modify state available to the shared WebView profile.

## Local bridge

The bridge is disabled until the user explicitly enables it. While enabled it runs as a foreground service and binds only to `127.0.0.1:18765`.

Loopback does not identify a trusted application. Other same-device processes may connect, so every request except the one-time pairing setup flow requires the active credential. Keep the credential in app-private storage and the CLI's owner-only mode-`0600` configuration file.

`eta-browser pair` is the only command allowed to print a credential, once. Rotate, revoke, routine CLI commands, Pi tool results, logs, and errors must not print credentials or lease identifiers.

## Sensitive browser data

Page text, URLs, form values, cookies, screenshots, credentials, and inline screenshot data must not be logged. CLI screenshot output is written to a caller-selected regular file with owner-only permissions. Pi screenshot results use native image content and omit inline image data from textual metadata.

Semantic `observe` never returns input or form values. Its opaque refs are document-local targeting handles for the latest observation, not stable identifiers or permission tokens. Stale or disconnected refs fail closed.

`request_help` is a visible user-control boundary for login, CAPTCHA, OTP, payment confirmation, and similar human-only steps. The notification is generic so prompt or page content is not exposed on the lock screen. The prompt exists only in app memory while the request is active. Eta Browser does not capture, return, log, or persist values the user enters during handoff.

Console and network diagnostics remain sensitive page data and must be requested only when needed. They are bounded, memory-only, and reset-cleared. Console callbacks are consumed instead of printed to logs. Network diagnostics strip URL user-info/query/fragment and capture no headers, bodies, cookies, authorization data, or timing/interception payloads. Callback-based network coverage is intentionally incomplete.

Arbitrary JavaScript evaluation, CDP, credential extraction, and network interception remain outside the external action contract.

## Recovery

- Disable the bridge to stop accepting new external work.
- Rotate credentials when a configured client should remain paired but the credential may be exposed.
- Revoke credentials to clear pairing and stop the bridge.
- Reset browser state separately to clear the current page, cookies, storage, history, semantic refs, pending handoff state, and in-memory diagnostics.
- Clear app data or uninstall for complete local recovery.

Disabling, revoking, or shutting down the bridge closes active sockets and interrupts active external browser work. Reset is intentionally non-cancellable once dispatched.
