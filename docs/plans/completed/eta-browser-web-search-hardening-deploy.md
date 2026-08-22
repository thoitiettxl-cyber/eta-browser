<!-- pi-continuity-work-document: {"schemaVersion":1,"kind":"execution-plan","workItemId":"5b35b629-f6cf-4bee-9233-5168ba3eebdb","templateVersion":1} -->

# Execution Plan: Eta Browser web search root-SERP hardening and deployment

Date: 2026-08-22

## Status

Ready for completion

## Outcome

Harden the existing Pi-level DuckDuckGo web_search for stable personal use by moving to the branded root SERP, enforcing exact-origin and fail-closed classification, preserving one-lease exact-cancellation behavior, validating the installed runtime, committing only the authorized web-search work, pushing the current main branch to origin, and deploying the Pi integration through the repository installer.

## Authority And Context

- The user explicitly approved implementation and requested a final commit, push, and deploy.
- The approved design keeps web_search as a Pi-layer composition over existing Eta Browser protocol-v2 actions; it does not add Android, CLI, protocol, or browser action catalog surface.
- Deployment for this Pi-only behavior is the repository-documented scripts/install-pi.sh installation into the current user's Pi and local CLI prefixes; no APK replacement, package publication, GitHub Release, or service deployment is authorized.
- DuckDuckGo robots.txt currently disallows /html and /lite while allowing root query URLs; DuckDuckGo URL-parameter documentation describes parameters as intended for individual use. Root-SERP DOM selectors remain undocumented implementation details and must fail closed.

## Scope

In scope:

- Refactor the internal Pi adapter lease/sequence seam only as needed for conditional one-lease workflow execution without exposing credentials or lease IDs.
- Move DuckDuckGo search navigation to https://duckduckgo.com/?q=... and enforce exact HTTPS root-origin checks before and after extraction.
- Use one conservative organic root-SERP extraction profile, reject ads/internal navigation, associate optional snippets only when alignment is unambiguous, and retain bounded structured output.
- Map HTTP 403/429, challenge, wrong-origin, and unrecognized surfaces to SEARCH_BLOCKED with diagnostic reason text while preserving other transport, cancellation, and bridge errors.
- Add focused fake-bridge tests for root URL/selector behavior, exact-origin rejection, ads, direct and redirect URLs, snippet alignment, no-results, challenge, 403/429 mapping, action ordering, bounds, secrecy, and cancellation.
- Update repository documentation and the companion Pi skill for the hardened root-SERP behavior and recovery semantics.
- Run repository-native host validation and one non-sensitive installed-tool runtime smoke using the current installed APK.
- Finalize the execution plan, commit only the authorized web-search implementation/documentation and prior uncommitted web-search files, push main to origin, deploy via scripts/install-pi.sh, and verify installed source parity.

Out of scope:

- Android application, application ID, WebView profile, APK build or replacement, protocol version, CLI command surface, and accepted browser action catalog changes.
- Automatic provider fallback, hosted-model search integration, CDP or arbitrary JavaScript, CAPTCHA/consent bypass, automatic request_help, pagination, opening result pages, answer synthesis, or browser-state reset.
- Package publication, GitHub Release creation, signed APK workflow dispatch, PR creation, force push, history rewrite, or cleanup of unrelated worktree changes.
- Commercial or distributed DuckDuckGo integration claims.

## Constraints

- Preserve all unrelated tracked modifications, deletions, and untracked files; stage only explicitly reviewed web-search files and this plan.
- Keep public input bounds query<=500 and max_results 1..10 default 5, output<=12000 UTF-8 bytes, and optional snippets.
- Use one serialized authenticated lease, at most 16 existing browser actions, exact active-request cancellation, and release on every success or failure path.
- Do not log or return credentials, lease IDs, request IDs, cookies, tokens, raw page text, ad tracking URLs, or inline screenshot data.
- Leave the shared WebView visibly on the DuckDuckGo results/no-results page and perform no network retry, reload, fallback, consent acceptance, or anti-bot bypass.
- Use GitHub Actions as Android build/lint authority; because no Android source changes are planned, do not claim an Android build from the Alpine chroot.
- A fresh Pi process is required to discover newly installed extension code; runtime smoke must be non-sensitive and must not deliberately trigger CAPTCHA.

## Approach

- Inspect Git/GitHub/deployment state and separate authorized web-search changes from unrelated dirty work.
- Add focused red tests for the approved root-SERP and workflow behavior.
- Implement the smallest one-lease workflow seam and DuckDuckGo root adapter changes that satisfy the tests.
- Update relevant README, architecture, installation, validation, companion skill, and the durable execution plan without touching unrelated documentation.
- Run focused Pi adapter tests, full affected host checks, action-catalog checks, syntax checks, diff checks, and review the exact staged scope.
- Install the candidate through scripts/install-pi.sh, verify installed source parity, and run one fresh-process non-sensitive web_search smoke against the installed APK.
- Record validation and result in the plan, perform receipt-bound validation, finalize the plan to docs/plans/completed, revalidate after the move, and create a safe checkpoint.
- Stage only authorized files, create a Conventional Commits commit, push main to origin, verify the remote commit and required CI state as far as observable, then reinstall/verify the exact committed Pi integration if commit finalization changed installed files.

## Risks And Recovery

- DuckDuckGo can change undocumented root-SERP DOM. The adapter uses one conservative profile and returns SEARCH_BLOCKED instead of broad-selector fallback; recovery is to update the isolated profile and tests, not switch providers silently.
- A shared WebView can change state during execution. One lease, exact-origin checks before and after extraction, serialized actions, and fail-closed classification bound the risk.
- Installer writes current-user Pi extension, skill, Forge skill, and local CLI files without built-in rollback. Before deployment, preserve a temporary backup of affected installed targets when present; recovery is restore that backup or rerun scripts/install-pi.sh from the previous known-good commit, then start a fresh Pi process.
- Push mutates origin/main. Validate and review the exact commit first; no force push. If CI fails, preserve the commit and fix forward rather than rewrite history.
- The worktree contains extensive unrelated changes. Explicit path staging and staged-diff review prevent accidental inclusion; never reset, restore, or clean those changes.
- An earlier read-only browser find_elements operation is marked uncertain by Continuity although later serialized observation succeeded. Do not infer repository mutation from it; inspect actual browser/repository state and require fresh validation before checkpointing.

## Progress

- [x] Implement the approved outcome.
- [x] Run behavior-appropriate and repository-required proof.
- [x] Record the verified result before finalization.

## Decisions

- Keep the public `web_search` input/result contract and protocol action catalog unchanged while replacing the fixed sequence internals with a bounded callback workflow that exposes only an action runner, never client, credential, or lease state.
- Use only `https://duckduckgo.com/?q=...`, require the exact HTTPS origin before and after extraction, and keep one conservative root profile: `[data-testid=result] h2 > a` plus `[data-testid=result] [data-result=snippet]`.
- Pair snippets only when the root result-link and snippet arrays have equal lengths; otherwise return `null` snippets rather than risk shifted attribution.
- Preserve the established public `SEARCH_BLOCKED` code while adding bounded reason text for wrong origin, provider denial, rate limiting, challenge, and unrecognized surfaces. Preserve cancellation, transport, bridge, and non-403/429 HTTP codes.
- Do not add network retry, broad selector fallback, hosted search, CDP, provider switching, or live DuckDuckGo CI tests. Deterministic fake-bridge tests own parser behavior; one non-sensitive installed runtime smoke owns live compatibility evidence.
- The first installed root-SERP smoke failed closed before dynamically rendered organic results appeared, while an immediate bounded observation found the expected result on the same page. Add up to four 250 ms same-page extraction probes under the same lease, with one navigation and abort-aware waits; do not reload or repeat the search.
- Recognize no-results only when the canonical `No results found for` marker is immediately followed by the normalized query (optionally quoted). Incidental footer/error text mentioning no or no-more results remains `SEARCH_BLOCKED`.

Promote lasting product or architecture decisions into repository-owned decision documentation only after authority exists.

## Validation

- PASS — `npm --prefix ./pi/eta-browser-extension run check` validated `index.ts`, `core.mjs`, and `web-search.mjs` syntax.
- PASS — `node --test --test-concurrency=1 --test-reporter=spec pi/eta-browser-extension/core.test.mjs pi/eta-browser-extension/web-search.test.mjs` passed 31 tests. Coverage includes one-lease conditional execution, workflow timeout compatibility, root URL and exact-origin checks, ads/direct/redirect URLs, conservative snippets, bounded dynamic settling with one navigation, strict query-bound no-results evidence, challenge/unrecognized surfaces, HTTP 403/429 mapping, 12,000-byte output, secrecy, active-request cancellation, cancellation between probes, and release ordering.
- PASS — `node scripts/./check-browser-action-contract.mjs` and `node --test ./scripts/check-browser-action-contract.test.mjs` passed, including three positive/negative catalog tests; the Android/CLI/Pi accepted 20-action catalog is unchanged.
- PASS — `npm --prefix ./tools/eta-browser-cli run check` and `npm --prefix ./tools/eta-browser-cli test` passed all 49 CLI tests.
- PASS — `python3 -B -m unittest -v ./pi/skills/eta-browser-skill-forge/scripts/test_validate_generated_skill.py` passed all 8 Forge validator tests.
- PASS — `sh -n scripts/./install-pi.sh` and `git diff --check HEAD -- .` passed.
- PASS — independent pre-commit review found two low Standards issues (long-action workflow timeout mismatch and an unused exported sequence) and one medium Intent/Behavior issue (over-broad no-results evidence). The workflow now rejects actions exceeding its transport timeout, the unused sequence export was removed, and no-results evidence must immediately bind the canonical marker to the normalized query; focused regressions pass.
- PASS — the documented installer completed against the current candidate with zero reported npm vulnerabilities. Installed `index.ts`, `core.mjs`, `web-search.mjs`, and runtime `SKILL.md` are byte-identical to repository sources; a temporary pre-deployment backup exists for recovery.
- OBSERVED/FIXED — the first fresh-process root smoke failed safely with `SEARCH_BLOCKED reason=surface_unrecognized`; an immediate same-page observation found the expected organic result, proving a dynamic-render race rather than a provider or selector failure. The bounded same-page settle workflow and regression were added without reload or second navigation.
- PASS — a later fresh Pi process registered `web_search` and called the distinct non-sensitive query `site:example.com IANA Example Domain root SERP smoke` once. It returned provider `duckduckgo`, root surface, one `www.example.com/` organic result with a snippet, and opened no destination. A bounded follow-up observation verified the shared WebView remained fully loaded at exact origin `https://duckduckgo.com`, root path `/`, with the encoded query and `ia=web` state.
- PASS — validation and runtime output exposed no credentials, lease IDs, request IDs, cookies, tokens, raw page text, or ad tracking URLs.
- LIMIT — no Android source changed and no Android build was run in the Alpine chroot. The installed APK's exact CI run, artifact ID, and SHA-256 remain unestablished, so this work does not claim new APK provenance, Android build evidence, or release readiness.
- PENDING EXTERNAL VERIFICATION — observe the created commit on `origin/main` after push and report GitHub Actions checks as pending until they complete.

## Result

Implemented and installed the hardened Pi-level `web_search`. It now uses DuckDuckGo's branded root SERP, exact pre/post origin checks, one bounded conditional lease workflow, up to four abort-aware 250 ms same-page extraction probes, conservative organic selectors, unambiguous optional snippets, strict no-results evidence, stable `SEARCH_BLOCKED` reason text, HTTP 403/429 mapping, and unchanged public input/output bounds. Deterministic host checks, installed source parity, and a fresh-process root-SERP smoke passed. Unrelated dirty work remains untouched. Commit, push, and final exact-commit reinstall/remote verification remain the final authorized operations.
