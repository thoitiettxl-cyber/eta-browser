<!-- pi-continuity-work-document: {"schemaVersion":1,"kind":"execution-plan","workItemId":"7cf97a35-77ca-48d9-95a8-90b90c764d2b","templateVersion":1} -->

# Execution Plan: DuckDuckGo web_search via Eta Browser

Date: 2026-08-22

## Status

Complete

Historical implementation record. The DuckDuckGo provider surface and one-lease helper were later superseded by [`eta-browser-web-search-hardening-deploy.md`](eta-browser-web-search-hardening-deploy.md): the current adapter uses the branded root SERP, exact-origin checks, and a bounded conditional workflow. The validation below records the original implementation state.

## Outcome

Add a bounded Pi custom tool named web_search that uses the existing authenticated Eta Browser protocol-v2 actions to search DuckDuckGo, returns up to ten structured organic results, leaves the shared WebView on the result page, and fails closed with SEARCH_BLOCKED for consent, CAPTCHA, anti-bot, or unrecognized result surfaces.

## Authority And Context

- User explicitly requested implementation and confirmed the Grill With Docs Shared Understanding.
- README.md and docs/ARCHITECTURE.md establish that the repository owns the Pi adapter and that protocol v2 retains the accepted 13-core plus 7-extension action catalog.
- docs/SECURITY.md requires credentials, lease IDs, cookies, page data, and inline screenshots to remain undisclosed.
- docs/VALIDATION.md requires native Pi adapter checks for affected host code and installed-APK evidence for runtime completion claims.
- The worktree contains unrelated pre-existing modifications that must be preserved.

## Scope

In scope:

- Register a Pi custom tool named web_search alongside eta_browser_use.
- Accept a nonblank bounded query and max_results from 1 through 10 with default 5.
- Use DuckDuckGo as the only initial provider and URL-encode the query.
- Use one serialized Eta Browser lease for navigation and bounded result extraction, preserving exact-request cancellation and lease release.
- Return bounded structured organic results with title, normalized destination URL, optional snippet, provider, query, and result_count.
- Add focused host-side tests for schema-independent core behavior, query encoding, result filtering, SEARCH_BLOCKED, secrecy, cancellation, and lease lifecycle.
- Update only directly relevant Pi adapter documentation or repository documentation if needed for discoverability and validation truth.

Out of scope:

- Changing the Android app, CLI command surface, protocol version, or accepted browser action catalog.
- Supporting Google, Bing, caller-defined providers, or automatic provider fallback.
- Opening result pages, crawling, synthesizing answers, or adding arbitrary JavaScript/CDP/network interception.
- Automatically accepting consent, invoking request_help, bypassing CAPTCHA or anti-bot controls, or resetting browser state.
- Installing the extension globally, committing, pushing, publishing, or releasing.

## Constraints

- Preserve the fixed loopback endpoint, authentication, lease ownership, payload bounds, and permissive visible shared-WebView boundary.
- Leave the shared WebView on DuckDuckGo results and do not claim prior SPA/form/scroll state is preserved.
- Do not expose credentials, lease IDs, request IDs, cookies, tokens, page dumps, or sensitive form values.
- Keep result and error payloads bounded; snippet may be null.
- Return SEARCH_BLOCKED for consent, CAPTCHA, anti-bot, or an unrecognized result surface.
- Preserve unrelated tracked and untracked worktree changes.

## Approach

- Inspect the Pi adapter transport seam and current fake-bridge tests to choose the smallest composite-operation interface.
- Add focused failing tests for DuckDuckGo URL construction, structured result parsing/filtering, lease sequencing, SEARCH_BLOCKED, cancellation, and secrecy.
- Implement a reusable one-lease composite Eta Browser execution seam and the web_search core behavior without expanding the protocol action catalog.
- Register the web_search Pi tool with bounded TypeBox parameters and prompt guidance.
- Run focused Pi adapter checks/tests, review the final diff, and run repository-required host validation relevant to the changed surface.
- If the paired installed runtime is available and test authorization remains applicable, execute one nonsensitive DuckDuckGo search; otherwise report runtime verification as pending.
- Record validation/result in this plan and finalize it only after evidence is complete.

## Risks And Recovery

- DuckDuckGo markup may change; keep provider-specific parsing local, return SEARCH_BLOCKED on unrecognized surfaces, and update the parser/test fixture when needed.
- A composite operation could leak or strand a lease on error/cancellation; prove release in fake-bridge tests and preserve exact browser.stop identity.
- Search mutates the one shared WebView; leave it visibly on results as agreed and recover with go_back or navigate, never reset.
- External consent/CAPTCHA could block runtime acceptance; stop with SEARCH_BLOCKED and use explicit human handoff only under separate user direction.
- If runtime verification is unavailable, retain host evidence and report the missing installed-APK evidence rather than claiming runtime completion.

## Progress

- [x] Implement the approved outcome.
- [x] Run behavior-appropriate and repository-required proof.
- [x] Record the verified result before finalization.

## Decisions

- Keep `web_search` at the Pi adapter layer and expose a reusable one-lease sequence seam from `core.mjs`; do not alter Android, CLI, protocol v2, or the accepted 20-action catalog.
- Use DuckDuckGo's bounded HTML result surface, exclude advertised result containers, normalize redirect destinations, reject user-info and potentially truncated URLs, and associate optional snippets by normalized destination URL rather than list position.
- Cap the serialized result envelope at 12,000 UTF-8 bytes in addition to the requested one-to-ten result bound.
- Treat a non-DuckDuckGo navigation host, known anti-bot text, or a resultless unrecognized page as `SEARCH_BLOCKED`; never auto-consent, hand off, or switch provider.

Promote lasting product or architecture decisions into repository-owned decision documentation only after authority exists.

## Validation

- PASS — `npm run check --prefix pi/eta-browser-extension` validated `index.ts`, `core.mjs`, and `web-search.mjs` syntax.
- PASS — `node --test --test-concurrency=1 pi/eta-browser-extension/core.test.mjs pi/eta-browser-extension/web-search.test.mjs` passed 20 tests covering the existing adapter plus one-lease search sequencing, query bounds/encoding, organic filtering, redirect normalization, URL-keyed optional snippets, 12,000-byte payload bounding, `SEARCH_BLOCKED`, installer inclusion, secrecy, exact cancellation, stop-before-release ordering, and release.
- PASS — `node --test scripts/check-browser-action-contract.test.mjs` passed three positive/negative catalog checks and confirmed the accepted Android/CLI/Pi 20-action contract remains unchanged.
- PASS — `sh -n scripts/install-pi.sh` and `git diff --check` passed.
- PASS — direct execution of the repository `executeWebSearch` module against the paired protocol-v2 Eta Browser used the non-sensitive query `site:example.com Example Domain`, returned one structured organic `Example Domain` result with a snippet, and left the shared WebView on DuckDuckGo results.
- OBSERVED — the first runtime smoke assertion over-specified the destination scheme as HTTPS while DuckDuckGo returned HTTP in that run; the acceptance assertion was corrected to the stable `www.example.com` host/path contract and the fresh rerun passed with an HTTPS destination. This was a smoke-assertion defect, not a product failure.
- LIMIT — the installed APK's CI run, artifact identity, and SHA-256 were not established in this task, so this evidence does not support a release or exact-CI-APK runtime-completion claim.
- REVIEW — the final task-owned diff was reviewed separately from the pre-existing unrelated dirty worktree; no unrelated changes were modified or discarded.

## Result

Implemented the bounded Pi-level `web_search` tool, its one-lease Eta Browser sequence seam, DuckDuckGo parser/fail-closed behavior, installer integration, runtime skill guidance, architecture/installation/validation documentation, and focused fake-bridge tests. Host checks and a live non-sensitive paired-browser smoke passed. The exact installed APK provenance remains unverified and must be supplied before any release or exact-CI-APK runtime-completion claim.
