---
name: eta-browser
description: Control the paired standalone Eta Browser with Pi's eta_browser_use custom tool. Use for browser navigation, rendered-page reading, text extraction, element discovery, clicking, typing, scrolling, history, reload, selector waits, screenshots, browser reset, or recovery from bridge, authentication, lease, cancellation, and USER_CONTROL_ACTIVE errors.
---

# Eta Browser

Use `eta_browser_use` for browser operations. Use the shell CLI only for initial pairing, credential repair, or explicit persistent-session administration that the Pi tool does not expose.

## Operation

1. Call `health` when bridge availability or shared-WebView ownership is uncertain.
2. Call `navigate`, then inspect with `get_readable`, `get_text`, `find_elements`, or `get_page_info`.
3. Prefer CSS selectors for `click` and `type`. Use coordinates only when no stable selector exists.
4. Call `wait_for_selector` after navigation or an interaction that updates the page asynchronously.
5. Call `screenshot` only when visual evidence is needed. The tool returns Pi-native image content.
6. Call `reset` only when the task requires clearing the current page, cookies, and history. Reset is non-cancellable.

## Recovery

- For `USER_CONTROL_ACTIVE`, stop external actions until the user exits takeover mode.
- For `UNAUTHORIZED` or token/config errors, repair pairing with `eta-browser`; never request or print the credential in chat.
- For bridge connection errors, ask the user to open Eta Browser and explicitly enable the bridge.
- For `SESSION_BUSY`, preserve single-client ownership. Wait for or release the owning CLI session instead of bypassing it.
- After cancellation, retry only when the user still wants the operation. The tool sends `browser.stop` with the exact client, lease, and request identity.

Never expose credentials, lease IDs, request IDs, inline screenshot base64, cookies, or sensitive page data beyond what the task requires.
