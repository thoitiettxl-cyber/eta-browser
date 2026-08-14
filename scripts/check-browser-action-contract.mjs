#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const EXPECTED_BROWSER_ACTIONS = Object.freeze([
  "navigate",
  "get_readable",
  "get_text",
  "find_elements",
  "observe",
  "click",
  "type",
  "hover",
  "select",
  "press",
  "scroll",
  "screenshot",
  "get_page_info",
  "go_back",
  "go_forward",
  "reload",
  "wait_for_selector",
  "request_help",
  "console",
  "network",
]);

const AUTHORITY = "docs/decisions/0001-bounded-browser-agent-extensions.md";

export function validateCatalogSources({ androidSource, piSource, cliSource }) {
  const findings = [];
  compare(
    findings,
    "Android BrowserAction enum",
    extractAndroidActions(androidSource),
    EXPECTED_BROWSER_ACTIONS,
  );
  compare(
    findings,
    "Pi BROWSER_ACTIONS",
    extractStringArray(piSource, "export const BROWSER_ACTIONS"),
    EXPECTED_BROWSER_ACTIONS,
  );
  compare(
    findings,
    "CLI ACTION_COMMANDS",
    extractStringArray(cliSource, "const ACTION_COMMANDS"),
    [...EXPECTED_BROWSER_ACTIONS.map((action) => action.replaceAll("_", "-")), "action"],
  );
  return findings;
}

export async function validateRepository(root) {
  const [androidSource, piSource, cliSource] = await Promise.all([
    readFile(path.join(root, "app/src/main/kotlin/com/thoitiettxl/eta/core/BrowserActionContract.kt"), "utf8"),
    readFile(path.join(root, "pi/eta-browser-extension/core.mjs"), "utf8"),
    readFile(path.join(root, "tools/eta-browser-cli/eta-browser.mjs"), "utf8"),
  ]);
  return validateCatalogSources({ androidSource, piSource, cliSource });
}

function extractAndroidActions(source) {
  const start = source.indexOf("internal enum class BrowserAction");
  const end = source.indexOf("companion object", start);
  if (start < 0 || end < 0) return [];
  return [...source.slice(start, end).matchAll(/\b[A-Z][A-Z_]*\("([a-z_]+)"\)/g)]
    .map((match) => match[1]);
}

function extractStringArray(source, marker) {
  const start = source.indexOf(marker);
  const open = source.indexOf("[", start);
  const close = source.indexOf("]", open);
  if (start < 0 || open < 0 || close < 0) return [];
  return [...source.slice(open + 1, close).matchAll(/"([a-z_-]+)"/g)]
    .map((match) => match[1]);
}

function compare(findings, owner, actual, expected) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) return;
  findings.push(
    `${owner} diverges from the accepted 13-core + 7-extension catalog (${AUTHORITY}). ` +
    `Expected [${expected.join(", ")}], found [${actual.join(", ")}]. ` +
    "Update Android, CLI, and Pi surfaces together or revise the accepted decision first.",
  );
}

async function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const findings = await validateRepository(root);
  if (findings.length) {
    for (const finding of findings) process.stderr.write(`browser-action-contract: ${finding}\n`);
    process.exitCode = 1;
    return;
  }
  process.stdout.write("Browser action contract check passed.\n");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
