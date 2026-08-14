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

## Recovery

- Disable the bridge to stop accepting new external work.
- Rotate credentials when a configured client should remain paired but the credential may be exposed.
- Revoke credentials to clear pairing and stop the bridge.
- Reset browser state separately to clear the current page, cookies, storage, and history.
- Clear app data or uninstall for complete local recovery.

Disabling, revoking, or shutting down the bridge closes active sockets and interrupts active external browser work. Reset is intentionally non-cancellable once dispatched.
