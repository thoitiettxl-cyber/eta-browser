# Execution Plan: Story 6 Extraction Runtime Proof

Date: 2026-08-14

## Status

Active

## Outcome

Complete the standalone Eta Browser extraction with a public, independently owned repository, Android identity `com.thoitiettxl.eta`, passing repository CI, and installed-device evidence that the clean-install app works through both the CLI and Pi adapter without requiring BrowserActivity to be opened first.

No GitHub Release, APK publication, or npm publication is part of this work.

## Context

- Product and component map: [`README.md`](../../../README.md)
- Architecture and ownership: [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md)
- Installation and clean migration: [`docs/INSTALLATION.md`](../../INSTALLATION.md)
- Security boundary: [`docs/SECURITY.md`](../../SECURITY.md)
- Required acceptance: [`docs/VALIDATION.md`](../../VALIDATION.md)
- Release restrictions: [`docs/RELEASING.md`](../../RELEASING.md)
- Android screenshot implementation: `app/src/main/kotlin/com/thoitiettxl/eta/core/BrowserSessionEngine.kt`
- Main activity rendering lifecycle: `app/src/main/kotlin/com/thoitiettxl/eta/ui/MainActivity.kt`
- Production CLI: `tools/eta-browser-cli/`
- Pi adapter and skill: `pi/eta-browser-extension/`, `pi/skills/eta-browser/`

Eta cleanup is already committed separately as `b893735` in `thoitiettxl-cyber/Eta`; Eta retains its independent internal browser implementation.

## Scope

In scope:

- Validate the current first-screenshot fix candidate on a clean install before BrowserActivity is opened.
- Complete installed-device CLI and Pi behavior proof, including screenshots, user takeover, reset, cancellation, fixed loopback binding, and clean package migration.
- Keep pairing credentials out of repository files and transcripts; rotate immediately if exposed and revoke after acceptance.
- Keep Story 6 documentation and this plan synchronized with observed evidence.
- Commit and push the installed Harness and final evidence only after review and required checks.

Out of scope:

- GitHub Releases, signed production release publication, npm publication, or deployment.
- A shared cross-repository Android browser library.
- Changes to Eta's internal browser implementation.
- Multi-tab, multi-session, remote-network, MCP, or CDP support.

## Approach

1. Install CI artifact `9207186491` for commit `a0fcfc1` as a clean `com.thoitiettxl.eta` app, pair privately, enable the bridge, and do not open BrowserActivity.
2. Navigate a non-sensitive local fixture and visually inspect the first screenshot. Metadata alone is not acceptance.
3. If first-use screenshot still fails, preserve the failed evidence and revise only the Android rendering lifecycle with another CI-built clean-install candidate.
4. After the screenshot gate passes, run the remaining CLI/Pi, user-takeover, reset, cancellation, listener, package-identity, and recovery checks.
5. Revoke the final pairing credential, remove only task-created temporary artifacts when authorized, update final evidence, move this plan to `docs/plans/completed/`, run repository checks, then commit and push. Do not create a release.

## Risks And Recovery

- Android WebView may return a structurally valid but visually blank image. Require direct image inspection for every fresh candidate.
- Debug APKs built on separate CI runners may require uninstall/reinstall because signing identity may differ. Revoke pairing before uninstall and expect a new pairing afterward.
- The retired package can retain fixed port `18765`. Keep `fuck.andes.browser` uninstalled and verify the listener disappears after revoke/disable.
- A credential pasted into a transcript is compromised even for debug use. Rotate it immediately without printing the replacement, and revoke at the end.
- Harness installation currently exists as an uncommitted diff. The backup of the pre-Harness `AGENTS.md` is under `.harness-backup/20260814040342/AGENTS.md`; do not delete it until the Harness diff is accepted or committed.

## Progress

- [x] Created public repository `thoitiettxl-cyber/eta-browser` with clean root commit `7ad1fc5`.
- [x] Moved Android app, CLI, Pi adapter/skill, documentation, CI, installer, and license into this repository.
- [x] Migrated Android application ID, namespace, Kotlin packages, and service actions to `com.thoitiettxl.eta`; set version `1.0.0`.
- [x] Removed standalone ownership from Eta while preserving Eta's internal browser; Eta commit `b893735` and CI run `31766178328` passed.
- [x] Passed 39 CLI tests, 7 Pi adapter tests, package-identity checks, installer smoke, Android build, and Android lint.
- [x] Proved installed-device navigation, DOM reading, selectors, typing, clicking, scrolling, explicit history back/forward, reload, attached CLI/Pi screenshots, and detached screenshots after a visible BrowserActivity attachment.
- [x] Recorded failed clean-install first-screenshot candidates `48ac9bc` and `92d2fac`; both returned valid but visually blank images before BrowserActivity was opened.
- [x] Installed repository Harness core `0.1.10` in merge mode; `harness doctor` passes.
- [x] Tested candidate `a0fcfc1`, CI run `31768362887`, artifact `9207186491`, SHA-256 `f5ef51fadd5c84052378f364a4ea6e6533c7373d2bc7eda58b1eefad1418419c`; clean-install navigation and DOM extraction succeeded, but the first and later `view_draw` screenshots were visually blank at 1101 by 2400 pixels and 16,282 bytes.
- [x] Clean-install tested successor code commit `084442e` from pushed HEAD `9f37303`; CI run `31770124364` and the fresh first screenshot passed, but post-takeover detached capture failed. Artifact `9207809936` is 4,329,423 bytes with SHA-256 `d0c325a4f10373a5d9a20248a52da8878db0bfdcc4a4e9c80256148b974ba49b`.
- [ ] Revise and retest post-takeover detached capture: the fresh first screenshot passed with `software_view_draw`, but the same mode returned a blank 1272 by 2183 image after BrowserActivity had visibly attached and detached the committed page.
- [ ] Complete remaining runtime acceptance and final credential revocation.
- [ ] Record final evidence, complete this plan, commit/push Harness and final changes, and confirm both worktrees are clean.

## Decisions

- 2026-08-14: The standalone repository owns the Android app, CLI, Pi adapter/skill, docs, CI, and installer. Eta keeps a separate internal browser with no cross-repository Gradle dependency.
- 2026-08-14: Package migration is a clean install requiring re-pairing; no private state migration from `fuck.andes.browser` is attempted.
- 2026-08-14: First-screenshot acceptance requires visual inspection because valid dimensions, MIME type, byte count, and capture mode did not detect blank images.
- 2026-08-14: Keep the main activity controls at alpha `0.999` in candidate `a0fcfc1` so Android must composite the shared WebView while it remains visually hidden; this remains provisional until clean-install proof.
- 2026-08-14: Candidate `a0fcfc1` disproved the `0.999`-alpha composition assumption: the WebView remained attached and its DOM was readable, but repeated `view_draw` captures stayed visually blank while MainActivity was backgrounded.
- 2026-08-14: Direct comparison with `Mangi-11/Eta` at commit `0485d4a4b58c3edf5eac107815b0a50a828138a3` showed that `AgentBrowserSession` normally keeps the shared WebView detached, switches it to `LAYER_TYPE_SOFTWARE` when `windowToken` is absent, and always captures with `view.draw`. The standalone extraction had replaced that detached path with deprecated `capturePicture`; the successor candidate restores the source lifecycle and capture strategy instead of adding another MainActivity warmup.
- 2026-08-14: Runtime proof established a two-state capture lifecycle. Fresh detached software `view.draw` is non-blank, while software `view.draw` becomes blank after a committed page is visibly attached and detached. Prior Story 6 proof established that the retained-picture path works after visible attachment, so the next candidate uses it only after that state transition.
- 2026-08-14: GitHub Release and package publication remain unauthorized.

## Validation

Successor working-tree proof after restoring Eta's detached capture path:

- `scripts/check-package-identity.sh`
- `npm --prefix tools/eta-browser-cli run check`
- `npm --prefix tools/eta-browser-cli test` — 39 passed
- `npm --prefix pi/eta-browser-extension run check`
- `npm --prefix pi/eta-browser-extension test` — 7 passed
- `git diff --check`

Previously passed unaffected proof:

- isolated `scripts/install-pi.sh` smoke test
- `scripts/bin/harness doctor`

Android build/lint and installed-device proof remain pending for a committed CI candidate.

CI evidence:

- Initial extraction run `31765829848` passed Android build/lint and CLI/Pi validation.
- Eta cleanup run `31766178328` passed Eta build/lint.
- Candidate `48ac9bc` run `31767663855` passed all jobs but failed first-screenshot runtime acceptance.
- Candidate `92d2fac` run `31768052275` passed all jobs but failed first-screenshot runtime acceptance.
- Candidate `a0fcfc1` run `31768362887` passed all CI jobs but failed installed-device first-screenshot acceptance.
- Candidate `a0fcfc1` failed installed-device first-screenshot acceptance: `#proof` was visible and readable text matched, while the first and later attached `view_draw` JPEGs were visually all white with identical 16,282-byte payloads.
- Successor HEAD `9f37303` run `31770124364` passed Android build, Android lint, and CLI/Pi validation; artifact `9207809936` is ready for clean-install runtime acceptance.
- Successor HEAD `9f37303` passed the clean-install first-screenshot gate: Pi returned a visually non-blank 1272 by 2400 image, and CLI confirmed `capture_mode=software_view_draw` with a 130,164-byte JPEG before BrowserActivity opened.
- The same installed candidate passed Pi/CLI readable output, CLI selector discovery, typing, clicking, screenshots, exact-request SIGINT cancellation with exit 130 and idle recovery, reset page/cookie/history clearing, loopback-only binding, `USER_CONTROL_ACTIVE`, and automation recovery after takeover.
- Post-takeover detached screenshot then failed visually: `software_view_draw` returned an all-white 1272 by 2183 JPEG of 17,197 bytes despite a visible committed DOM.

Remaining integration/end-to-end proof:

- Clean-install first screenshot before BrowserActivity, visually non-blank.
- Pi screenshot consumption on the accepted candidate.
- `USER_CONTROL_ACTIVE` while takeover is enabled and recovery after exit.
- Signal cancellation with exact request identity and idle follow-up health.
- Reset clears page/cookies/history and leaves the bridge healthy.
- Listener remains fixed and loopback-only, then disappears after disable/revoke.
- Android app info confirms `com.thoitiettxl.eta` version `1.0.0` and retired package absence.

## Result

HEAD `9f37303` passed the clean-install first-screenshot gate and the remaining automated recovery/security checks, but failed post-takeover detached screenshot acceptance. A narrow lifecycle fallback candidate is pending. No release has been created.
