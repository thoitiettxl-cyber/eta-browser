---
name: eta-browser-skill-forge
description: Create or repair a reviewable site-specific Pi skill from an explicitly authorized Eta Browser workflow. Use when the user asks to turn a repeated browser task into a skill or update such a skill after the site changes. Do not use for ordinary one-off browsing.
compatibility: Requires a paired Eta Browser, eta_browser_use, and the eta-browser runtime skill.
---

# Eta Browser Skill Forge

Create one reusable site-specific `SKILL.md` from a browser workflow that the user explicitly authorizes and that Eta Browser can verify.

The output stays in a user-approved review directory. Do not install, commit, push, publish, or run it beyond the approved validation without separate authorization.

## Workflow

1. Confirm the target site, repeated task, variable inputs, output directory, and whether login or another human-only step is expected.
2. Define an observable success condition before browsing.
3. Explore the shortest successful path with `eta_browser_use`:
   - serialize every browser call;
   - call `observe` before interaction and again after navigation, handoff, or meaningful DOM change;
   - prefer accessible names and stable site-owned selectors;
   - use `request_help` for login, CAPTCHA, OTP, payment, consent, or other user-only steps;
   - stop after the same step fails twice without progress.
4. Record only reusable procedure. Never retain credentials, cookies, tokens, user-entered form values, sensitive URL data, page dumps, screenshots, lease/request identifiers, or ephemeral refs such as `@eN`.
5. Create exactly one generated file, `SKILL.md`, using [the site-skill template](assets/site-skill-template.md). Keep concrete site behavior and selectors; parameterize user-specific values.
6. Run:

   ```sh
   python3 scripts/validate-generated-skill.py <generated-skill-directory>
   ```

7. Re-read the generated file. If the user authorized execution, test one normal path through current `eta_browser_use` observations and verify the stated success condition.
8. Report the output path, validation result, tested path, unverified assumptions, and that the skill remains uninstalled.

## Repair

When repairing a generated skill, reproduce the failing step, obtain a current observation, update only the stale instruction or selector, then validate and retest the affected path.

## Boundaries

- Use only the bounded `eta_browser_use` action surface for browser work.
- Do not add arbitrary JavaScript, CDP/debugger access, network interception, authenticated request replay, CAPTCHA solving, or a second browser automation runtime.
- Do not parallelize browser calls or create batch behavior that violates the single shared WebView, lease, and active-operation model.
- Do not infer successful behavior from source text alone; keep unverified assumptions explicit.
