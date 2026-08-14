# Installation and recovery

## Clean migration from the development package

Version `1.0.0` uses Android application ID `com.thoitiettxl.eta`. The former development app used `fuck.andes.browser`; Android treats them as unrelated applications, so browser data, pairing credentials, and enabled-bridge state are not migrated.

Before enabling the new app, disable or uninstall the old app. Both versions use fixed loopback port `18765`; only one can own it. Uninstalling the old package removes its private browser and pairing data.

## Android app

Install an APK built from this repository and open **Eta Browser**. Pairing and bridge enablement are intentionally explicit after every clean install.

## CLI

Node.js 20 or newer is required.

From the repository:

```sh
npm install --global --prefix "$HOME/.local" "$PWD/tools/eta-browser-cli"
export PATH="$HOME/.local/bin:$PATH"
eta-browser --help
```

Pairing:

```sh
eta-browser pair
```

Enter the token printed by that command into the Android app, tap **Pair this device**, then enable the bridge. The token is printed once; do not paste it into chat, logs, issues, or shell transcripts.

Check connectivity:

```sh
eta-browser health
eta-browser navigate https://example.com
eta-browser get-readable
eta-browser observe
eta-browser screenshot --output ./eta-browser.jpg
```

See [the CLI reference](../tools/eta-browser-cli/README.md) for all commands, exit codes, sessions, cancellation, and configuration recovery.

## Pi extension and skill

Run from the repository root:

```sh
scripts/install-pi.sh
```

The installer copies the extension to `~/.pi/agent/extensions/eta-browser`, installs its local CLI package dependency, copies the companion skill to `~/.pi/agent/skills/eta-browser`, and installs the CLI under `~/.local`. Restart Pi, then call `eta_browser_use` with `health`.

Allow Eta Browser notifications if `request_help` must alert the user while BrowserActivity is not already visible. The handoff notification contains only a generic instruction; the task prompt appears inside the app.

## Recovery

- `CONNECTION_FAILED`: open the app and explicitly enable the bridge.
- `UNAUTHORIZED`: pair again or rotate the credential; never expose it in diagnostic output.
- `SESSION_BUSY`: release the owning persistent CLI session or wait for the active operation.
- `USER_CONTROL_ACTIVE`: exit the Android takeover screen before external actions.
- `STALE_ELEMENT_REF`: run `eta-browser observe` again and use a ref from that latest result.
- Login/CAPTCHA/OTP/payment confirmation: use `eta-browser request-help`; do not pass the sensitive value through chat or CLI arguments.
- Port conflict: disable or uninstall the old `fuck.andes.browser` app and check that no unrelated process owns `127.0.0.1:18765`.
- Local stale session: use `eta-browser session forget`; this cannot release a live bridge lease.
- Complete reset: revoke pairing, reset browser data, disable the bridge, then clear app data or uninstall if needed.
