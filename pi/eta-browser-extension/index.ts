import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { StringEnum } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { TOOL_ACTIONS, executeEtaBrowser } from "./core.mjs";
import { executeWebSearch } from "./web-search.mjs";

const pressKeys = [
  "Enter",
  "Escape",
  "Tab",
  "Shift+Tab",
  "ArrowUp",
  "ArrowDown",
  "ArrowLeft",
  "ArrowRight",
  "Home",
  "End",
  "PageUp",
  "PageDown",
  "Space",
  "Backspace",
  "Delete",
  "Ctrl+A",
] as const;

const completionCriteria = Type.Object({
  url_contains: Type.Optional(Type.String({ minLength: 1, maxLength: 320 })),
  selector_exists: Type.Optional(Type.String({ minLength: 1, maxLength: 240 })),
  match: Type.Optional(StringEnum(["any", "all"] as const)),
  stable_for_ms: Type.Optional(Type.Integer({ minimum: 0, maximum: 5000 })),
}, { additionalProperties: false });

const toolSchema = Type.Object({
  action: StringEnum(TOOL_ACTIONS as readonly string[], {
    description: "Browser operation to perform",
  }),
  url: Type.Optional(Type.String({ description: "URL for navigate" })),
  selector: Type.Optional(Type.String({ description: "CSS selector for text, find, click, type, hover, select, press, scroll, or wait" })),
  ref: Type.Optional(Type.String({ pattern: "^@e[1-9][0-9]{0,8}$", description: "Ephemeral element ref from the latest observe result" })),
  text: Type.Optional(Type.String({ description: "Text for type" })),
  submit: Type.Optional(Type.Boolean({ description: "Submit after typing" })),
  coordinate_x: Type.Optional(Type.Integer({ description: "Target X coordinate; provide with coordinate_y" })),
  coordinate_y: Type.Optional(Type.Integer({ description: "Target Y coordinate; provide with coordinate_x" })),
  direction: Type.Optional(StringEnum(["up", "down"] as const, { description: "Scroll direction" })),
  amount: Type.Optional(Type.Integer({ minimum: 1, maximum: 5000, description: "Scroll pixels" })),
  offset: Type.Optional(Type.Integer({ minimum: 0, maximum: 200000, description: "Text character offset" })),
  max_chars: Type.Optional(Type.Integer({ minimum: 256, maximum: 12000, description: "Maximum returned text characters" })),
  timeout_ms: Type.Optional(Type.Integer({ minimum: 500, maximum: 300000, description: "Navigate, selector, or human-handoff timeout" })),
  key: Type.Optional(StringEnum(pressKeys, { description: "Bounded key or key combo for press" })),
  value: Type.Optional(Type.String({ maxLength: 240, description: "Single select option value" })),
  values: Type.Optional(Type.Array(Type.String({ maxLength: 240 }), { minItems: 1, maxItems: 16, description: "Select option values" })),
  prompt: Type.Optional(Type.String({ minLength: 1, maxLength: 600, description: "In-app human handoff instruction" })),
  title: Type.Optional(Type.String({ maxLength: 120, description: "In-app human handoff title" })),
  target_selector: Type.Optional(Type.String({ minLength: 1, maxLength: 240, description: "Optional selector to highlight during human handoff" })),
  completion_criteria: Type.Optional(completionCriteria),
  since: Type.Optional(Type.Integer({ minimum: 0, description: "Console or network cursor" })),
  limit: Type.Optional(Type.Integer({ minimum: 1, maximum: 100, description: "Maximum console entries or network events" })),
}, { additionalProperties: false });

const webSearchSchema = Type.Object({
  query: Type.String({
    minLength: 1,
    maxLength: 500,
    description: "Search query sent to DuckDuckGo through the shared Eta Browser WebView",
  }),
  max_results: Type.Optional(Type.Integer({
    minimum: 1,
    maximum: 10,
    default: 5,
    description: "Maximum organic results to return",
  })),
}, { additionalProperties: false });

export default function etaBrowserExtension(pi: ExtensionAPI) {
  pi.registerTool({
    name: "eta_browser_use",
    label: "Eta Browser",
    description: "Control the paired, loopback-only Eta Browser. Supports health, the Eta-compatible action core, bounded standalone observation/handoff/interaction/diagnostic actions, reset, exact cancellation, and screenshot image content. Requires prior pairing with the eta-browser CLI. Text output is bounded by the browser contract; credentials and lease IDs are never returned.",
    promptSnippet: "Navigate, semantically observe, read, interact with, hand work to the user, diagnose, reset, and inspect screenshots from the paired Eta Browser",
    promptGuidelines: [
      "Use eta_browser_use for browser work when Eta Browser is paired and enabled.",
      "Define observable success, prefer observe or bounded text reads before acting, and stop browser control as soon as success is confirmed.",
      "Refs are valid only for the latest observe result in the current document; observe again after navigation, handoff, or meaningful DOM change.",
      "Use request_help for login, CAPTCHA, OTP, payment confirmation, or other user-only steps; observe again after control returns.",
      "Use eta_browser_use health before recovery diagnosis; USER_CONTROL_ACTIVE means the user currently owns the shared WebView.",
      "Use console and network only for bounded read-only diagnosis; they never include headers or bodies and network coverage is not a complete trace.",
      "Use eta_browser_use screenshot only when visual inspection is needed; the screenshot is returned as image content.",
    ],
    parameters: toolSchema,
    async execute(_toolCallId, params, signal) {
      try {
        return await executeEtaBrowser(params, signal);
      } catch (error) {
        const message = error instanceof Error ? error.message : "Eta Browser request failed";
        throw new Error(message);
      }
    },
  });
  pi.registerTool({
    name: "web_search",
    label: "Web Search (Eta Browser)",
    description: "Search DuckDuckGo's branded root results page through the paired, loopback-only Eta Browser and return bounded structured organic results. Uses one workflow of existing protocol-v2 browser actions, enforces exact-origin checks, leaves the shared WebView on the results page, and never opens results, retries blocked searches, or bypasses consent, CAPTCHA, and anti-bot controls.",
    promptSnippet: "Search DuckDuckGo through Eta Browser for bounded titles, destination URLs, and optional snippets",
    promptGuidelines: [
      "Use web_search for result discovery when the paired Eta Browser is available; use eta_browser_use to open and read a selected result.",
      "web_search leaves the one shared WebView on DuckDuckGo results and invalidates assumptions or refs from the prior page.",
      "Treat SEARCH_BLOCKED as a wrong-origin, provider-denial, rate-limit, consent, CAPTCHA, anti-bot, or unrecognized-surface boundary; do not reload, repeat, switch providers, or attempt a bypass.",
      "Results are discovery evidence, not a synthesized answer; open only the sources needed for the user's task.",
    ],
    parameters: webSearchSchema,
    async execute(_toolCallId, params, signal) {
      try {
        return await executeWebSearch(params, signal);
      } catch (error) {
        const message = error instanceof Error ? error.message : "Eta Browser web search failed";
        throw new Error(message);
      }
    },
  });
}
