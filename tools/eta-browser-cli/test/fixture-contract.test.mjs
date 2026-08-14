import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const directory = path.dirname(fileURLToPath(import.meta.url));
const fixturePath = path.join(directory, "fixtures", "bounded-actions.html");

test("bounded action runtime fixture owns every accepted extension surface", async () => {
  const fixture = await readFile(fixturePath, "utf8");
  const requiredIds = [
    "status",
    "ref-target",
    "replace-target",
    "visible-field",
    "password-field",
    "contenteditable-field",
    "state-select",
    "hover-zone",
    "hover-menu",
    "hover-action",
    "enter-input",
    "enter-submit",
    "otp",
    "complete-handoff",
    "account-menu",
    "console-log",
    "network-failure",
  ];

  for (const id of requiredIds) {
    assert.match(fixture, new RegExp(`id=["']${id}["']`));
  }
  assert.match(fixture, /old\.replaceWith\(replacement\)/);
  assert.match(fixture, /console\.warn\('eta-fixture-console-warning'\)/);
  assert.match(fixture, /secret-query-must-be-redacted=yes/);
  assert.match(fixture, /history\.pushState\(\{\}, '', '\/dashboard'\)/);
  assert.doesNotMatch(fixture, /https?:\/\/(?!127\.0\.0\.1)/);
});
