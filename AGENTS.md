# Agent instructions

Use this repository as the source of truth for the standalone Eta Browser Android app, CLI, Pi adapter, protocol, tests, and documentation.

- Read `README.md` and only the relevant file under `docs/` before changing behavior.
- Keep Android application ID and namespace `com.thoitiettxl.eta` unless the user explicitly authorizes a migration.
- Preserve protocol version 2, the fixed loopback endpoint, authentication, lease ownership, payload bounds, and permissive WebView compatibility unless product authority explicitly changes them.
- The permissive WebView is not a security sandbox; do not weaken the opt-in, loopback-only, authenticated, visible, and revocable bridge boundary.
- Do not print or commit credentials, lease IDs, cookies, page data, or inline screenshots.
- Use GitHub Actions for Android build/lint evidence. Do not claim an Android build from the Alpine development chroot.
- Run CLI and Pi adapter native checks for affected host code.
- Installed CI-built APK behavior is required for runtime completion claims.
- Do not publish packages, APKs, or GitHub Releases without explicit authorization.
