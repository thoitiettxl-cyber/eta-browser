import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  EXPECTED_BROWSER_ACTIONS,
  validateCatalogSources,
  validateRepository,
} from "./check-browser-action-contract.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function sources() {
  const [androidSource, piSource, cliSource] = await Promise.all([
    readFile(path.join(root, "app/src/main/kotlin/com/thoitiettxl/eta/core/BrowserActionContract.kt"), "utf8"),
    readFile(path.join(root, "pi/eta-browser-extension/core.mjs"), "utf8"),
    readFile(path.join(root, "tools/eta-browser-cli/eta-browser.mjs"), "utf8"),
  ]);
  return { androidSource, piSource, cliSource };
}

test("accepted browser action catalog passes across Android CLI and Pi", async () => {
  assert.equal(EXPECTED_BROWSER_ACTIONS.length, 20);
  assert.deepEqual(await validateRepository(root), []);
});

test("missing Pi action fails with authority and repair diagnostic", async () => {
  const current = await sources();
  current.piSource = current.piSource.replace('  "request_help",\n', "");

  const findings = validateCatalogSources(current);

  assert.equal(findings.length, 1);
  assert.match(findings[0], /Pi BROWSER_ACTIONS diverges/);
  assert.match(findings[0], /docs\/decisions\/0001-bounded-browser-agent-extensions\.md/);
  assert.match(findings[0], /Update Android, CLI, and Pi surfaces together/);
});

test("unapproved evaluate action fails rather than silently expanding authority", async () => {
  const current = await sources();
  current.androidSource = current.androidSource.replace(
    '    NETWORK("network");',
    '    NETWORK("network"),\n    EVALUATE("evaluate");',
  );

  const findings = validateCatalogSources(current);

  assert.equal(findings.length, 1);
  assert.match(findings[0], /Android BrowserAction enum diverges/);
  assert.match(findings[0], /evaluate/);
});
