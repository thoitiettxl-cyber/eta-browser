# Eta Browser

Eta Browser is a separately installable Android WebView browser designed for local AI-agent control. It provides:

- one shared browser session that the user can take over in the Android app;
- an authenticated newline-delimited JSON bridge on `127.0.0.1:18765`;
- the `eta-browser` Node.js CLI;
- a Pi extension exposing the `eta_browser_use` custom tool;
- bounded text and screenshot payloads compatible with Eta's browser action contract.
- bounded semantic observation, explicit user handoff, interaction extensions, and read-only diagnostics layered additively on protocol version 2.

The Android application ID and namespace are `com.thoitiettxl.eta`. Version `1.0.0` is a clean installation and does not migrate private data from the former development package `fuck.andes.browser`.

## Security boundary

Eta Browser intentionally preserves Eta's permissive WebView profile. It is **not a security sandbox**. Local and mixed content, third-party cookies, autoplay, JavaScript, and form submission remain enabled, and the app does not add URL, DNS, IP, redirect, or Service Worker filtering.

External control is opt-in, bound to loopback, authenticated, visible through a foreground service, and revocable. Other local applications may still reach loopback, so the pairing credential is the authority boundary. See [docs/SECURITY.md](docs/SECURITY.md).

## Components

- `app/`: Android application and browser/bridge contract tests.
- `tools/eta-browser-cli/`: Node 20+ CLI and reusable transport/config modules.
- `pi/eta-browser-extension/`: Pi custom-tool adapter.
- `pi/skills/eta-browser/`: companion Pi skill.
- `docs/`: architecture, installation, validation, and release guidance.

Eta itself keeps its internal browser implementation in the Eta repository. The two repositories do not use a cross-repository Gradle dependency; protocol, action, payload, and installed-device parity evidence are used to control divergence.

## Install and pair

See [docs/INSTALLATION.md](docs/INSTALLATION.md) for the complete clean-install, pairing, CLI, Pi, migration, and recovery procedure.

In summary:

1. Install the CI-built debug APK or a trusted signed release of `com.thoitiettxl.eta`.
2. Install the CLI from `tools/eta-browser-cli`.
3. Run `eta-browser pair`, enter the one-time token in the app, then explicitly enable the bridge.
4. Install the Pi adapter with `scripts/install-pi.sh` when Pi integration is required.

## Development

Prerequisites:

- JDK 25;
- Android SDK platform 37;
- Node.js 20 or newer.

Host-side checks:

```sh
node scripts/check-browser-action-contract.mjs
node --test scripts/check-browser-action-contract.test.mjs
npm --prefix tools/eta-browser-cli run check
npm --prefix tools/eta-browser-cli test
npm --prefix pi/eta-browser-extension install --ignore-scripts --no-package-lock
npm --prefix pi/eta-browser-extension run check
npm --prefix pi/eta-browser-extension test
```

Android build, lint, and unit tests are authoritative through GitHub Actions:

```sh
./gradlew --no-daemon --no-configuration-cache :app:assembleDebug
./gradlew --no-daemon --no-configuration-cache :app:lintDebug
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest
```

Installed-APK behavior remains the runtime acceptance gate. See [docs/VALIDATION.md](docs/VALIDATION.md).

## Protocol summary

Protocol version 2 uses one authenticated JSON request per TCP connection. A client must acquire an opaque lease before browser execution or reset. Only one lease and one active operation are allowed. Cancellation requires the exact client, lease, and request identity; reset is non-cancellable.

The Eta-compatible core remains 13 actions: `navigate`, `get_readable`, `get_text`, `find_elements`, `click`, `type`, `scroll`, `screenshot`, `get_page_info`, `go_back`, `go_forward`, `reload`, and `wait_for_selector`.

The standalone app adds seven bounded protocol-v2 extensions: semantic `observe` with ephemeral refs; `hover`, `select`, and `press`; explicit `request_help` user handoff; and memory-only `console` and callback-based `network` diagnostics. These additions do not expose arbitrary JavaScript, network interception, headers, bodies, cookies, or remote control.

## License

[PolyForm Noncommercial License 1.0.0](LICENSE).
