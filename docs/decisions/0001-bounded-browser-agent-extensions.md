# 0001 Bounded Browser Agent Extensions

Date: 2026-08-14

## Status

Accepted

## Context

Eta Browser exposes the 13-action vocabulary inherited from Eta's internal `browser_use` implementation. That core supports basic navigation, extraction, selectors, interaction, screenshots, history, and waits, but it does not provide a semantic observe-act loop, explicit agent-to-human handoff, select/press/hover primitives, or bounded read-only diagnostics.

The repository owner approved the BrowserSkill-inspired proposal to add those capabilities while preserving Eta Browser's existing Android shared-WebView model and security boundary. The WebView remains permissive and is not a sandbox, so an arbitrary JavaScript evaluation primitive or credential-oriented diagnostics would materially exceed the approved authority.

The reference reviewed for that proposal was Tencent's [`BrowserSkill` companion skill at commit `f443cfa`](https://github.com/Tencent/BrowserSkill/blob/f443cfae741131f19fe65952026b88c326c4ac1e/skill/SKILL.md). Its bounded-goal, observation, and human-help policies were adapted; its Chromium Agent Window, tab borrowing, CDP, arbitrary evaluation, and auto-update model were not adopted.

## Decision

Protocol version 2 remains in force. Eta Browser retains the original 13 actions as its Eta-compatible core and adds these standalone, additive actions:

- `observe`: bounded semantic controls with opaque refs for the latest observation in the current document;
- `hover`, `select`, and `press`: bounded interaction primitives, with ref-aware targeting where applicable;
- `request_help`: explicit, cancellable user handoff with bounded prompt/title, optional target highlighting, bounded completion criteria, and deterministic outcomes;
- `console` and `network`: bounded, memory-only, read-only diagnostic buffers.

Refs are ephemeral authority to target a page element, not stable identifiers. A new observation replaces the prior ref table; navigation, reset, document replacement, or a disconnected element invalidates a ref and must fail closed.

`request_help` temporarily gives the user control of the shared WebView while retaining the authenticated lease and active request. The app surfaces a generic notification and in-app prompt, blocks concurrent external browser actions, supports exact-request cancellation, and clears control/notification state on every outcome. It must not collect, return, log, or persist passwords, OTPs, CAPTCHA answers, payment data, or other values entered by the user.

Diagnostics are bounded and reset-cleared. Console callbacks are consumed into memory rather than printed to logs. Network diagnostics may record sanitized URL/method/main-frame/request/error metadata available from normal WebView callbacks, but never headers, bodies, cookies, authorization data, interception results, or a claim of complete DevTools-equivalent coverage.

The Pi skill must require bounded execution: define observable success, prefer semantic observation, refresh page assumptions after navigation or handoff, stop after success, limit retries, and ask the user rather than brute-forcing sensitive or blocked steps.

## Alternatives Considered

1. Copy Tencent BrowserSkill's Chromium Agent Window, tab borrow/return, CDP, and explicit session lifecycle. Rejected because Eta Browser owns one shared Android WebView and already has protocol-v2 lease/cancellation semantics.
2. Add arbitrary JavaScript `evaluate` as a universal escape hatch. Rejected because it would materially expand access to storage, tokens, form values, and page authority beyond the approved bounded action contract.
3. Add CDP-based complete network/console tooling. Rejected because it would expand the runtime and security boundary; normal WebView callbacks provide a smaller read-only diagnostic surface.
4. Change the protocol version for additive actions. Rejected because authentication, methods, framing, lease ownership, cancellation, and envelopes remain compatible; clients already reject unsupported actions explicitly.

## Consequences

Positive:

- Agents gain a safer, less brittle observe-act loop and deterministic stale-ref behavior.
- CAPTCHA, login, OTP, payment confirmation, and other human-only steps have a visible first-class handoff path.
- Common controls no longer require brittle click/type workarounds.
- UI regression diagnosis gains bounded console and network-error evidence without exposing headers or bodies.
- The existing loopback/authentication/lease/revocation and payload boundaries remain intact.

Tradeoffs:

- The standalone action vocabulary now exceeds Eta's internal 13-action catalog, so compatibility documentation and tests must distinguish the core from extensions.
- Synthetic WebView hover and key behavior is less complete than Chromium CDP and requires installed-device acceptance.
- `request_help` can hold the single lease for up to five minutes, although it remains exactly cancellable.
- Callback-based network output is intentionally incomplete and must not be represented as a full response trace.
- Additive clients must handle unsupported-action errors when paired with an older APK.

## Follow-Up

- Implementation and validation evidence is recorded under `docs/plans/completed/browser-agent-handoff-observation.md`.
- Keep Android build/lint/unit-test authority in GitHub Actions and require CI-built installed-device acceptance before completion.
- Revisit protocol versioning only if a future change becomes incompatible rather than additive.
