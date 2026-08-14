# Validation

## Required repository checks

GitHub Actions is the authority for Android build, lint, and Gradle unit tests because the Alpine development chroot is not an accepted Android build environment.

Required CI results:

- the accepted Android/CLI/Pi browser action catalog check and its negative fixtures pass;
- `:app:assembleDebug` passes and uploads `eta-browser-debug.apk`;
- `:app:lintDebug` passes;
- `:app:testDebugUnitTest` passes;
- CLI syntax and all host tests pass;
- Pi adapter syntax and fake-bridge tests pass;
- no source or manifest reference to the retired package `fuck.andes.browser` remains.

Android unit-test sources are retained, but Android unit-test tasks are not a release gate. Runtime behavior is validated with the CI-built APK on the Android device.

## Signed release candidate evidence

After all required CI checks pass for the merged `main` commit, dispatch
`Build signed release APK` with that exact 40-character commit. Require:

1. the workflow source ref is `refs/heads/main` and its source commit equals the
   accepted CI commit;
2. `:app:assembleRelease` passes with all release signing inputs present;
3. `apksigner verify --verbose --print-certs` passes and records signer details;
4. the artifact includes `eta-browser-v1.0.0.apk`, its SHA-256 file,
   `apksigner-verification.txt`, and `release-metadata.txt`;
5. the downloaded APK SHA-256 equals the workflow evidence before installation;
6. only that exact APK proceeds to installed-device acceptance and any later
   GitHub Release.

Workflow success proves build and signing, not installed runtime behavior or
publication readiness.

## Installed-APK acceptance

For the package migration and extraction release, verify:

1. disable or uninstall the old `fuck.andes.browser` development app so it cannot retain port `18765`;
2. install the CI-built `com.thoitiettxl.eta` APK as a clean app;
3. confirm old pairing data is absent and a new pairing is required;
4. pair and enable the bridge;
5. before ever opening BrowserActivity, run CLI health, navigation, readable extraction, interaction, and screenshot; visually confirm that the first screenshot is not blank;
6. run reset and signal cancellation;
7. run Pi health, navigation, readable extraction, screenshot consumption, and `USER_CONTROL_ACTIVE` behavior;
8. confirm the listener is loopback-only and disabling the bridge removes it;
9. confirm package identity with Android package inspection;
10. capture the tested commit, Actions run, artifact ID, APK SHA-256, and device transcript.

A release must not be published while required CI or installed-APK acceptance is missing.

## Bounded agent-extension acceptance

Use the repository-owned fixture rather than an external account or sensitive page. From the repository root, start one foreground loopback server and stop it with Ctrl-C when acceptance is finished:

```sh
python3 -m http.server 18880 \
  --bind 127.0.0.1 \
  --directory tools/eta-browser-cli/test/fixtures
```

Navigate Eta Browser to `http://127.0.0.1:18880/bounded-actions.html`. The fixture contains deterministic controls for ref invalidation, select/press/hover, all handoff outcomes, console capture, and a deliberately missing image whose secret-looking query must be redacted from network diagnostics.

Before claiming the semantic observation, human handoff, interaction, or diagnostic extensions complete, install the CI-built APK for the exact tested commit and use a non-sensitive local fixture to verify:

1. `observe` returns visible semantic controls without any input, select, or contenteditable values; ref-based click/type succeeds from the latest observation;
2. a second `observe`, navigation, DOM replacement, and reset make prior refs fail with `STALE_ELEMENT_REF` rather than targeting a different element;
3. `select` changes the requested option, `press` exercises Enter/Tab/Ctrl+A behavior, and `hover` reveals a fixture surface or reports the documented synthetic-WebView limitation;
4. `request_help` shows only a generic notification, displays the prompt inside BrowserActivity, keeps the page interactive, blocks interleaving automation, and returns `continued`, `cancelled`, `timed_out`, and criteria-driven `completed` in separate runs;
5. exact-request SIGINT cancellation interrupts `request_help`, releases the lease, removes handoff UI/notification state, and leaves normal automation usable;
6. user-entered handoff values do not appear in action results, health, app logs, console output, network output, or repository transcripts;
7. `console` and `network` obey cursor/limit bounds; network URLs omit user-info/query/fragment and no headers, bodies, cookies, or authorization data are returned;
8. reset clears refs and both diagnostic buffers;
9. ordinary manual takeover, cancellation, reset, bridge disable/revoke, and both screenshot lifecycle modes still pass their existing regression gates;
10. both CLI and Pi exercise every additive action against the installed APK.

Synthetic hover/key fidelity and WebView callback-based network coverage must be reported exactly as observed; do not infer CDP-equivalent behavior from host tests.

## Skill Forge acceptance

1. Validate `pi/skills/eta-browser-skill-forge/` with Pi's `quick_validate.py`.
2. Run `python3 -B -m unittest pi/skills/eta-browser-skill-forge/scripts/test_validate_generated_skill.py`.
3. Generate one temporary site-specific `SKILL.md`, run the bundled validator, and confirm forbidden refs, sensitive material, alternate browser runtimes, and extra files fail their focused tests.
4. When browser execution is authorized, exercise one normal generated workflow through serialized `eta_browser_use` calls and verify its observable success contract.
5. Confirm the installer copies the Forge, template, and validator while generated site skills remain uninstalled and uncommitted.
