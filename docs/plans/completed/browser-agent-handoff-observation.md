# Execution Plan: Bounded Browser Agent Interaction

Date: 2026-08-14

## Status

Completed

## Outcome

Extend Eta Browser's protocol-v2 browser contract with bounded semantic observation, ephemeral element references, human handoff, missing interaction primitives, and read-only diagnostics while preserving the existing authenticated loopback, single-lease, single-operation, shared-WebView, payload, cancellation, and user-revocation boundaries.

The companion Pi skill must guide agents to define observable success, stop after success, observe before acting, discard stale page assumptions, use a bounded retry budget, and hand login/CAPTCHA/OTP/payment steps to the user.

## Context

- User-approved proposal in the 2026-08-14 BrowserSkill comparison conversation.
- Product and component map: [`README.md`](../../../README.md)
- Repository workflow: [`docs/WORKFLOW.md`](../../WORKFLOW.md)
- Architecture and ownership: [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md)
- Security boundary: [`docs/SECURITY.md`](../../SECURITY.md)
- Required validation: [`docs/VALIDATION.md`](../../VALIDATION.md)
- Accepted architecture decision: [`docs/decisions/0001-bounded-browser-agent-extensions.md`](../../decisions/0001-bounded-browser-agent-extensions.md)
- Android action contract and engine: `app/src/main/kotlin/com/thoitiettxl/eta/core/`
- User takeover UI: `app/src/main/kotlin/com/thoitiettxl/eta/ui/BrowserActivity.kt`
- Bridge service and protocol: `app/src/main/kotlin/com/thoitiettxl/eta/bridge/`
- Production CLI: `tools/eta-browser-cli/`
- Pi adapter and skill: `pi/eta-browser-extension/`, `pi/skills/eta-browser/`

## Scope

In scope:

- Keep the original 13 Eta-compatible browser actions unchanged and add protocol-v2 actions `observe`, `hover`, `select`, `press`, `request_help`, `console`, and `network`.
- Return a bounded semantic observation containing visible controls and opaque refs valid only for the latest observation in the current document.
- Allow `click`, `type`, `hover`, `select`, and optional-target `press` to consume a current ref; reject stale refs explicitly.
- Add explicit, cancellable human handoff with a bounded prompt/title, optional target highlighting, timeout, optional URL/selector completion criteria, and outcomes `continued`, `cancelled`, `timed_out`, and `completed`.
- Surface a generic Android notification for pending handoff without exposing prompt or page content on the lock screen.
- Add bounded in-memory console and WebView callback-based network event buffers; capture no headers, bodies, cookies, authorization data, or timing payloads, and clear buffers on reset.
- Add high-level CLI and Pi adapter support, contract tests, companion-skill guidance, architecture/security/validation documentation, and focused host proof.

Out of scope:

- Arbitrary JavaScript `evaluate`, CDP, remote control, multiple tabs, Agent Window/tab borrowing, device emulation, network interception/modification, request or response headers/bodies, cookies, automatic updates, or protocol version changes.
- Claiming complete Android build/lint/unit-test or installed-device behavior without GitHub Actions and a CI-built APK.
- Commit, push, release, APK publication, npm publication, or other external mutation unless separately authorized.

## Approach

1. Record the accepted additive protocol-v2 contract and update the companion skill with bounded execution, observation priority, stale-page, takeover, and retry rules.
2. Implement semantic observation, latest-observation refs, ref-aware targeting, and `hover`/`select`/`press` as one Android vertical slice with contract and DOM-script tests.
3. Implement `request_help` as a cancellable active browser operation coordinated with BrowserActivity, a generic notification, target highlighting, completion polling, and deterministic cleanup.
4. Implement synchronized bounded console/network buffers using WebView callbacks and expose cursor-based read-only actions.
5. Extend CLI and Pi schemas, validation, action forwarding, timeout handling, tests, help, and documentation for all additive actions.
6. Run focused JVM/Node proof, required native host checks, diff review, and repository validation available in the Alpine chroot.
7. Obtain GitHub Actions Android build/lint/unit-test evidence and installed-device proof from a CI-built APK before marking the plan completed; do not push or publish without separate authorization.

## Risks And Recovery

- Synthetic WebView hover/key events may not reproduce every Chromium/CDP default action. Keep behavior bounded, return the matched element and performed strategy, and require installed-device fixture proof.
- A blocking handoff can hold the single lease for minutes. Keep it cancellable through exact-request `browser.stop`, cap timeout at five minutes, and always clear handoff/UI/notification state in a `finally` path.
- Background activity starts are restricted. Use a generic notification that opens the existing BrowserActivity rather than silently launching UI.
- Semantic refs can become stale after navigation, re-observation, or DOM replacement. Store only the latest document-local ref table and fail closed with `STALE_ELEMENT_REF`.
- Console messages and URLs can contain sensitive data. Keep buffers memory-only and bounded, consume console callbacks instead of logging them, redact network query/fragment/user-info, and never capture headers or bodies.
- Android WebView does not expose complete successful response metadata without CDP/interception. Document `network` as callback-based request/error visibility rather than a complete network trace.
- Recovery is a normal code rollback. Runtime recovery remains bridge disable/revoke plus browser reset; reset clears the new refs, handoff, and diagnostic state.

## Progress

- [x] Verified the reference BrowserSkill and current Eta Browser architecture, security, action, CLI, Pi, and validation surfaces.
- [x] Received explicit product approval for the proposed P0, P1, and P2 scope.
- [x] Recorded the accepted lasting architecture/security decision.
- [x] Updated the Pi skill and product documentation for bounded execution and the additive contract.
- [x] Implemented semantic observation, refs, hover, select, and press with focused Android tests.
- [x] Implemented cancellable human handoff UI/notification/completion behavior with focused tests.
- [x] Implemented bounded console/network diagnostics with positive and negative security proof.
- [x] Extended CLI and Pi adapter commands, schemas, tests, and help.
- [x] Ran all available local validation and reviewed the final diff.
- [x] Obtained authoritative Android CI and installed-APK runtime evidence, including a focused replacement-build re-test for the runtime-found reset stale-ref fix.

## Decisions

- 2026-08-14: Preserve protocol version 2; the approved actions are additive and do not change authentication, lease, cancellation, frame, or response-envelope semantics.
- 2026-08-14: Keep the original 13 actions as the Eta compatibility core and describe the seven new actions as standalone Eta Browser extensions.
- 2026-08-14: Use one latest-observation ref table per document; a later observation invalidates earlier refs, and disconnected elements fail closed.
- 2026-08-14: `request_help` is an explicit user-control operation, not a credential collection primitive. Its notification is generic and its prompt remains inside the app.
- 2026-08-14: Network diagnostics use only normal WebView callbacks; no CDP, interception, headers, bodies, cookies, or complete success-status claim is introduced.
- 2026-08-14: Semantic observation treats `input`, `textarea`, `select`, and `contenteditable` controls as value-bearing and suppresses their own text/value content while retaining safe labels, roles, state, and bounds.
- 2026-08-14: If payload limiting removes trailing console/network records, `next_since` is rewritten to the last record actually returned so cursor pagination cannot skip hidden entries.

## Validation

- Focused proof:
  - Android JVM tests for action validation, DOM scripts/ref semantics, handoff criteria/state, diagnostic bounds/redaction, and payload truncation.
  - CLI action mapping, timeout, stable JSON, cancellation, screenshot secrecy, and fake-bridge tests.
  - Pi adapter schema/forwarding, handoff timeout/cancellation, browser-error sanitation, and image tests.
- Integration or end-to-end proof:
  - CI-built APK: observe/ref click and stale-ref rejection; hover/select/press fixture behavior; request-help continued/cancelled/timed-out/completed and exact cancellation; console/network bounded output and reset clearing; normal takeover recovery; first and post-takeover screenshot lifecycle regression.
- Repository-required checks:
  - `scripts/check-package-identity.sh`
  - `node scripts/check-browser-action-contract.mjs`
  - `node --test scripts/check-browser-action-contract.test.mjs`
  - `npm --prefix tools/eta-browser-cli run check`
  - `npm --prefix tools/eta-browser-cli test`
  - `npm --prefix pi/eta-browser-extension run check`
  - `npm --prefix pi/eta-browser-extension test`
  - `git diff --check`
  - `scripts/bin/harness doctor`
  - GitHub Actions `:app:assembleDebug`, `:app:lintDebug`, and `:app:testDebugUnitTest`

Local evidence collected in the Alpine chroot:

- the action-catalog guard and all 3 negative/positive guard tests pass;
- Android package identity passes;
- CLI syntax plus all 49 tests pass;
- Pi adapter syntax plus all 12 tests pass;
- all Android Kotlin sources and JVM tests compile manually against the locally available Android 36 platform, and all 52 JUnit tests pass;
- the installed Pi runtime dependencies load `index.ts`, register `eta_browser_use`, expose 22 tool operations (`health`, 20 browser actions, and `reset`), and retain the bounded prompt schema;
- CI YAML parses, `git diff --check` passes, and `scripts/bin/harness doctor` passes.

This manual Android 36 compile is supplemental only. The repository requires SDK 37 Gradle build/lint/unit-test evidence from GitHub Actions and installed-device acceptance from that CI-built APK.

Authoritative CI and artifact evidence:

- implementation commit `06f7b117ce24921c0125f7284e1a4bc5aba5c32f` passed GitHub Actions run [`31779695549`](https://github.com/thoitiettxl-cyber/eta-browser/actions/runs/31779695549), including Node validation and Android SDK 37 `:app:assembleDebug`, `:app:lintDebug`, and `:app:testDebugUnitTest`;
- runtime acceptance found that an old ref after reset failed closed with `NO_PAGE` rather than the documented `STALE_ELEMENT_REF`;
- focused fix commit `a1068493778b324349c636075bbff68b9629f342` passed replacement run [`31782945246`](https://github.com/thoitiettxl-cyber/eta-browser/actions/runs/31782945246) with the same Node and Android build/lint/unit-test matrix;
- final artifact `9212399087` was 4,392,631 bytes with SHA-256 `b3b65200a406fe2a684a74689d700b361462740842bb0b157dcbc3b0f43eee3b`, was installed by the user, and preserved pairing sufficiently to re-enable the bridge.

Installed-device evidence used only the repository-owned loopback fixture:

- semantic observation omitted input, select, password, and contenteditable values while returning safe labels, roles, state, bounds, and refs;
- latest-observation refs supported click/type and failed with `STALE_ELEMENT_REF` after re-observation, navigation, disconnected-node replacement, and—on the final replacement APK—reset;
- `select`, synthetic `hover`, `Ctrl+A`, `Tab`, and `Enter` produced the documented strategies and fixture outcomes;
- bounded console/network reads preserved cursors, redacted URL query data, reported no headers/bodies or complete response trace, and reset cleared both buffers;
- `request_help` returned user-driven `continued`, `cancelled`, criteria-driven `completed`, `timed_out`, and exact SIGINT interruption outcomes; all paths cleared handoff, user-control, notification/action, and lease state;
- the notification was user-confirmed generic while detailed title/prompt remained in-app, and the entered fixture value plus detailed prompt were absent from action, health, console, and network outputs;
- ordinary manual takeover blocked automation with `USER_CONTROL_ACTIVE` and recovered after user exit;
- screenshot regression passed non-empty bounded JPEG capture in both `software_view_draw` before visible attachment and `detached_picture` after visible attachment/detachment; the final APK also retained a non-empty `detached_picture` capture and clean handoff timeout recovery.

Observed limitations match the accepted contract: hover and key behavior use synthetic WebView events rather than CDP-native input, and network diagnostics are callback-based request/error evidence rather than a complete response trace.

## Result

Completed. Eta Browser now provides the approved bounded observation, ref targeting, interaction, human-handoff, and diagnostic extensions without changing protocol version 2 or weakening loopback authentication, lease ownership, exact cancellation, payload limits, shared-WebView ownership, or the permissive-not-a-sandbox boundary. Local checks, two authoritative Android CI runs, the exact final CI artifact, user-visible handoff confirmation, and installed-device acceptance are recorded above.
