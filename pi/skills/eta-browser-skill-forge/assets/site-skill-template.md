---
name: {{lowercase-hyphenated-name}}
description: {{specific repeated task, target site, and trigger conditions}}
compatibility: Requires a paired Eta Browser, eta_browser_use, and the eta-browser runtime skill.
---

# {{Skill title}}

Use serialized `eta_browser_use` calls. Define the requested inputs and observable success before acting, and stop when success is confirmed.

## Inputs

{{List required and optional non-sensitive inputs.}}

## Preconditions

{{State the starting URL or page state and any login state. Use request_help rather than handling credentials.}}

## Success contract

{{Describe the exact URL, selector, text, or page state that proves completion.}}

## Workflow

1. {{Inspect the current page with get_page_info, get_readable, or observe.}}
2. {{Use observe before interaction and target the current accessible control or a stable site-owned selector.}}
3. {{Perform one bounded action at a time.}}
4. {{Wait for and verify the observable postcondition.}}

Never store an `@eN` ref in this file; obtain current refs from `observe` at runtime.

## Recovery and stop conditions

After navigation, handoff, DOM replacement, or stale targeting, call `observe` again. Retry only with new evidence. If the same step fails twice without progress, stop and report the blocker.

## Human handoff

Use `request_help` for login, CAPTCHA, OTP, payment, consent, or any newly encountered user-only step. After handoff, discard prior page assumptions and observe again.

## Output contract

{{State the concise result to return and which observable evidence confirms success.}}

## Known limitations

{{List only site behavior that was not verified or is expected to change.}}
