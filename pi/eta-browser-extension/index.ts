import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { StringEnum } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { TOOL_ACTIONS, executeEtaBrowser } from "./core.mjs";

const toolSchema = Type.Object({
  action: StringEnum(TOOL_ACTIONS as readonly string[], {
    description: "Browser operation to perform",
  }),
  url: Type.Optional(Type.String({ description: "URL for navigate" })),
  selector: Type.Optional(Type.String({ description: "CSS selector for text, find, click, type, scroll, or wait" })),
  text: Type.Optional(Type.String({ description: "Text for type" })),
  submit: Type.Optional(Type.Boolean({ description: "Submit after typing" })),
  coordinate_x: Type.Optional(Type.Integer({ description: "Target X coordinate; provide with coordinate_y" })),
  coordinate_y: Type.Optional(Type.Integer({ description: "Target Y coordinate; provide with coordinate_x" })),
  direction: Type.Optional(StringEnum(["up", "down"] as const, { description: "Scroll direction" })),
  amount: Type.Optional(Type.Integer({ minimum: 1, maximum: 5000, description: "Scroll pixels" })),
  offset: Type.Optional(Type.Integer({ minimum: 0, maximum: 200000, description: "Text character offset" })),
  max_chars: Type.Optional(Type.Integer({ minimum: 256, maximum: 12000, description: "Maximum returned text characters" })),
  timeout_ms: Type.Optional(Type.Integer({ minimum: 500, maximum: 30000, description: "Navigate or selector timeout" })),
}, { additionalProperties: false });

export default function etaBrowserExtension(pi: ExtensionAPI) {
  pi.registerTool({
    name: "eta_browser_use",
    label: "Eta Browser",
    description: "Control the paired, loopback-only Eta Browser. Supports health, all 13 Eta browser actions, reset, exact cancellation, and screenshot image content. Requires prior pairing with the eta-browser CLI. Text output is bounded by the browser contract; credentials and lease IDs are never returned.",
    promptSnippet: "Navigate, read, interact with, reset, and inspect screenshots from the paired Eta Browser",
    promptGuidelines: [
      "Use eta_browser_use for browser work when Eta Browser is paired and enabled.",
      "Use eta_browser_use health before recovery diagnosis; USER_CONTROL_ACTIVE means the user currently owns the shared WebView.",
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
}
