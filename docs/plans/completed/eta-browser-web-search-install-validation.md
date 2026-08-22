<!-- pi-continuity-work-document: {"schemaVersion":1,"kind":"execution-plan","workItemId":"4fba43ce-27e1-4d3b-805a-5e30a80328ff","templateVersion":1} -->

# Execution Plan: Install and validate Eta Browser web_search

Date: 2026-08-22

## Status

Complete

Historical installation record. The installed `/html` provider surface described below was later superseded by [`eta-browser-web-search-hardening-deploy.md`](eta-browser-web-search-hardening-deploy.md); current deployment evidence is recorded there.

## Outcome

Install the repository's current Pi extension and companion skills through the documented installer, verify installed file/runtime integrity, and prove that a fresh Pi process registers and successfully executes web_search against the paired Eta Browser with a non-sensitive DuckDuckGo query.

## Authority And Context

- User explicitly requested installation and testing of the web_search tool.
- README.md and docs/INSTALLATION.md designate scripts/install-pi.sh as the supported Pi integration installer.
- The completed implementation record is docs/plans/completed/eta-browser-web-search.md.
- The installer targets the current user's Pi extension/skill directories and ~/.local CLI prefix; it does not alter Android APK, pairing credentials, cookies, or browser storage.

## Scope

In scope:

- Run scripts/install-pi.sh from the repository root.
- Verify the installed extension includes index.ts, core.mjs, web-search.mjs, package metadata, and its eta-browser-cli dependency.
- Start a fresh Pi subprocess through the provided subagent tool so extension discovery occurs in a new process.
- Invoke web_search once with the non-sensitive query site:example.com Example Domain and a bounded result count.
- Confirm structured DuckDuckGo output and that the shared WebView remains on the result page.
- Record installation and runtime evidence and recovery guidance.

Out of scope:

- Installing or replacing the Android APK.
- Changing pairing credentials, bridge configuration, cookies, history, or browser storage.
- Resetting Eta Browser.
- Committing, pushing, publishing, releasing, or modifying unrelated repository worktree changes.
- Opening any returned search result page.

## Constraints

- Use the repository-provided installer rather than ad hoc copying.
- Do not print credentials, lease IDs, cookies, tokens, or sensitive page data.
- Serialize all Eta Browser operations and stop once registration and one successful search are confirmed.
- Preserve the existing unrelated dirty worktree.
- Do not claim exact-CI-APK provenance because it is not established.

## Approach

- Inspect current Pi extension installation metadata without reading secrets.
- Run scripts/install-pi.sh and capture its bounded result.
- Verify installed extension files and syntax/runtime dependency resolution.
- Use a fresh Pi subagent to confirm web_search is registered and execute one non-sensitive bounded query.
- Check Eta Browser page state remains on DuckDuckGo results.
- Record validation, limitations, and recovery in the execution plan, then finalize after fresh executable evidence.

## Risks And Recovery

- Installer overwrites current Eta Browser extension and companion skill files; recover by reinstalling the prior trusted Eta Browser checkout/version.
- A stale Pi process will not expose the new tool; use a fresh Pi process rather than treating the current process as failed.
- DuckDuckGo may return consent or anti-bot content; accept SEARCH_BLOCKED as a safe runtime outcome and do not bypass or auto-handoff.
- A failed dependency install may leave a partial extension; rerun the same documented installer after correcting the reported local npm issue or reinstall the prior trusted checkout.
- The test mutates only the shared WebView page and intentionally leaves it on DuckDuckGo results; recover with ordinary navigation or go_back, never reset.

## Progress

- [x] Implement the approved outcome.
- [x] Run behavior-appropriate and repository-required proof.
- [x] Record the verified result before finalization.

## Decisions

- Use the documented `scripts/install-pi.sh` without ad hoc copying; the pre-install extension directory lacked `web-search.mjs`, while the post-install directory contains the repository-exact module and registration files.
- Validate actual Pi discovery in a fresh subagent process because the parent Pi process cannot hot-add a newly installed custom tool safely.
- Bind this existing authorized plan in the fresh child before invoking `web_search`; the first child confirmed registration but its call was correctly blocked by managed-workflow preparation, and no browser mutation occurred in that attempt.
- Treat the successful child tool payload plus a separate bounded `get_page_info` as runtime evidence that structured search works and the shared WebView remains on DuckDuckGo results.

Promote lasting product or architecture decisions into repository-owned decision documentation only after authority exists.

## Validation

- PASS — the previously validated installer syntax remained unchanged; this session's repeated `sh -n scripts/install-pi.sh` request was not rerun because the operation ledger already held the successful identical command.
- PASS — `scripts/install-pi.sh` completed: CLI and extension dependencies were up to date with zero reported vulnerabilities, and the Eta Browser CLI, Pi extension, runtime skill, and Forge skill were installed under the documented user targets.
- PASS — installed `index.ts`, `core.mjs`, `web-search.mjs`, and the runtime `SKILL.md` are byte-identical to repository sources via focused `cmp -s` checks.
- PASS — `node --check` passed for all three installed runtime modules, and a direct ESM import of installed `web-search.mjs` resolved its runtime dependency successfully.
- OBSERVED — the first fresh Pi process exposed `web_search`, proving registration, but managed continuity blocked execution before browser mutation because that child had not bound the authorized plan.
- PASS — a second fresh Pi process bound this plan, exposed and called `web_search` exactly once with `site:example.com Example Domain` and `max_results: 3`, and returned provider `duckduckgo`, one `Example Domain` result at `http://www.example.com/`, and a present snippet without opening the result.
- PASS — one subsequent `eta_browser_use get_page_info` confirmed the shared WebView remained at `https://html.duckduckgo.com/html/?q=site%3Aexample.com+Example+Domain`, host `html.duckduckgo.com`, fully loaded.
- LIMIT — the installed APK's CI run, artifact identity, and SHA-256 remain unestablished; installation/runtime success does not establish exact-CI-APK or release provenance.

## Result

Installed the repository Eta Browser Pi integration successfully. A fresh Pi process registered and executed `web_search`, returned one bounded structured DuckDuckGo result with a snippet, opened no result page, and left the shared WebView on the DuckDuckGo results page. Installed runtime files match repository sources and load successfully. Android APK and pairing state were not changed.
