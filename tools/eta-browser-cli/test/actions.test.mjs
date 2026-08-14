import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  TOKEN,
  runCli,
  success,
  withFakeBridge,
} from "./helpers/fake-bridge.mjs";

const cases = [
  {
    name: "navigate",
    args: ["navigate", "https://example.com", "--timeout-ms", "1200"],
    params: { action: "navigate", url: "https://example.com", timeout_ms: 1200 },
  },
  {
    name: "get_readable",
    args: ["get-readable", "--offset", "4", "--max-chars", "512"],
    params: { action: "get_readable", offset: 4, max_chars: 512 },
  },
  {
    name: "get_text",
    args: ["get-text", "--selector", "main", "--offset", "2", "--max-chars", "700"],
    params: { action: "get_text", selector: "main", offset: 2, max_chars: 700 },
  },
  {
    name: "find_elements",
    args: ["find-elements", "--selector", "button"],
    params: { action: "find_elements", selector: "button" },
  },
  {
    name: "click selector",
    args: ["click", "--selector", "#submit"],
    params: { action: "click", selector: "#submit" },
  },
  {
    name: "click coordinates",
    args: ["click", "--coordinate-x", "12", "--coordinate-y", "34"],
    params: { action: "click", coordinate_x: 12, coordinate_y: 34 },
  },
  {
    name: "type",
    args: ["type", "hello", "--selector", "input", "--submit"],
    params: { action: "type", selector: "input", text: "hello", submit: true },
  },
  {
    name: "scroll",
    args: ["scroll", "--selector", "main", "--direction", "up", "--amount", "321"],
    params: { action: "scroll", selector: "main", direction: "up", amount: 321 },
  },
  {
    name: "get_page_info",
    args: ["get-page-info"],
    params: { action: "get_page_info" },
  },
  {
    name: "go_back",
    args: ["go-back"],
    params: { action: "go_back" },
  },
  {
    name: "go_forward",
    args: ["go-forward"],
    params: { action: "go_forward" },
  },
  {
    name: "reload",
    args: ["reload"],
    params: { action: "reload" },
  },
  {
    name: "wait_for_selector",
    args: ["wait-for-selector", ".ready", "--timeout-ms", "900"],
    params: { action: "wait_for_selector", selector: ".ready", timeout_ms: 900 },
  },
  {
    name: "raw action alias",
    args: ["action", '{"action":"get_text","selector":"article"}'],
    params: { action: "get_text", selector: "article" },
  },
];

for (const item of cases) {
  test(`maps ${item.name} command to browser.execute`, async () => {
    const methods = [];
    await withFakeBridge(
      (request) => {
        methods.push(request.method);
        if (request.method === "browser.session.acquire") {
          return success(request, { lease_id: "temporary-lease", client_id: "eta-browser-cli" });
        }
        if (request.method === "browser.session.release") {
          assert.equal(request.lease_id, "temporary-lease");
          return success(request, { released: true });
        }
        assert.equal(request.method, "browser.execute");
        assert.equal(request.lease_id, "temporary-lease");
        assert.deepEqual(request.params, item.params);
        return success(request, {
          browser: { ok: true, tool: "browser_use", action: item.params.action, status: "ok" },
          images: [],
        });
      },
      async (port) => {
        const result = await runCli(port, item.args);
        assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(methods, [
          "browser.session.acquire",
          "browser.execute",
          "browser.session.release",
        ]);
      },
    );
  });
}

test("legacy read and page-info aliases remain compatible", async () => {
  const actions = [];
  await withFakeBridge(
    (request) => {
      if (request.method === "browser.session.acquire") {
        return success(request, { lease_id: `lease-${actions.length}`, client_id: "eta-browser-cli" });
      }
      if (request.method === "browser.session.release") return success(request, { released: true });
      actions.push(request.params.action);
      return success(request, {
        browser: { ok: true, action: request.params.action, status: "ok" },
        images: [],
      });
    },
    async (port) => {
      assert.equal((await runCli(port, ["read"])).status, 0);
      assert.equal((await runCli(port, ["page-info"])).status, 0);
    },
  );
  assert.deepEqual(actions, ["get_readable", "get_page_info"]);
});

test("screenshot writes bytes atomically and removes inline image data", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "eta-browser-screenshot-"));
  const output = path.join(temporary, "nested", "page.jpg");
  try {
    await withFakeBridge(
      (request) => {
        if (request.method === "browser.session.acquire") {
          return success(request, { lease_id: "screenshot-lease", client_id: "eta-browser-cli" });
        }
        if (request.method === "browser.session.release") return success(request, { released: true });
        assert.deepEqual(request.params, { action: "screenshot", read_image: true });
        return success(request, {
          browser: { ok: true, action: "screenshot", status: "ok" },
          images: [{
            data_url: `data:image/jpeg;base64,${Buffer.from("image-bytes").toString("base64")}`,
            mime_type: "image/jpeg",
            bytes: 11,
            width: 1,
            height: 1,
          }],
        });
      },
      async (port) => {
        const result = await runCli(port, ["screenshot", "--output", output]);
        assert.equal(result.status, 0, result.stderr);
        const payload = JSON.parse(result.stdout);
        assert.equal(payload.result.images[0].data_url, undefined);
        assert.equal(payload.result.images[0].output_path, output);
        assert.equal((await readFile(output)).toString("utf8"), "image-bytes");
      },
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("raw screenshot action requires an output path and never prints inline image data", async () => {
  const missing = await runCli(18_765, ["action", '{"action":"screenshot"}']);
  assert.equal(missing.status, 2);
  assert.equal(JSON.parse(missing.stdout).error.code, "OUTPUT_REQUIRED");
});

test("one-shot action fails closed when its temporary lease cannot be released", async () => {
  await withFakeBridge(
    (request) => {
      if (request.method === "browser.session.acquire") {
        return success(request, { lease_id: "unreleased-lease", client_id: "eta-browser-cli" });
      }
      if (request.method === "browser.execute") {
        return success(request, {
          browser: { ok: true, action: "get_page_info", status: "ok" },
          images: [],
        });
      }
      return { id: request.id, ok: false, error: { code: "STALE_CLIENT", message: "stale" } };
    },
    async (port) => {
      const result = await runCli(port, ["get-page-info"]);
      assert.equal(result.status, 4, result.stderr);
      assert.equal(JSON.parse(result.stdout).error.code, "STALE_CLIENT");
    },
  );
});

test("persistent session is acquired, reused without churn, stopped, and released", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "eta-browser-session-"));
  const config = path.join(temporary, "config.json");
  const methods = [];
  try {
    await writeFile(config, `${JSON.stringify({ host: "127.0.0.1", port: 18_765, token: TOKEN })}\n`, { mode: 0o600 });
    await withFakeBridge(
      (request) => {
        methods.push(request.method);
        if (request.method === "browser.session.acquire") {
          return success(request, { lease_id: "persistent-secret-lease", client_id: "eta-browser-cli" });
        }
        if (request.method === "browser.execute") {
          assert.equal(request.lease_id, "persistent-secret-lease");
          return success(request, {
            browser: { ok: true, action: request.params.action, status: "ok" },
            images: [],
          });
        }
        if (request.method === "health") {
          return success(request, {
            protocol: 2,
            active_request_id: "active-request-1",
          });
        }
        if (request.method === "browser.stop") {
          assert.equal(request.lease_id, "persistent-secret-lease");
          assert.equal(request.request_id, "active-request-1");
          return success(request, {
            stopped: true,
            request_id: request.request_id,
            interrupt_delivered: true,
          });
        }
        assert.equal(request.method, "browser.session.release");
        assert.equal(request.lease_id, "persistent-secret-lease");
        return success(request, { released: true });
      },
      async (port) => {
        const initial = { host: "127.0.0.1", port, token: TOKEN };
        await writeFile(config, `${JSON.stringify(initial)}\n`, { mode: 0o600 });

        const acquired = await runCli(port, ["session", "acquire"], { config, token: "" });
        assert.equal(acquired.status, 0, acquired.stderr);
        assert.equal(acquired.stdout.includes("persistent-secret-lease"), false);
        assert.equal(JSON.parse(await readFile(config, "utf8")).session.lease_id, "persistent-secret-lease");

        const action = await runCli(port, ["get-page-info"], { config, token: "" });
        assert.equal(action.status, 0, action.stderr);

        const status = await runCli(port, ["session", "status"], { config, token: "" });
        assert.equal(JSON.parse(status.stdout).result.acquired, true);
        assert.equal(status.stdout.includes("persistent-secret-lease"), false);

        const stopped = await runCli(port, ["stop"], { config, token: "" });
        assert.equal(stopped.status, 0, stopped.stderr);
        assert.equal(JSON.parse(stopped.stdout).result.request_id, "active-request-1");

        const released = await runCli(port, ["session", "release"], { config, token: "" });
        assert.equal(released.status, 0, released.stderr);
        assert.equal(JSON.parse(await readFile(config, "utf8")).session, undefined);
      },
    );
    assert.deepEqual(methods, [
      "browser.session.acquire",
      "browser.execute",
      "health",
      "browser.stop",
      "browser.session.release",
    ]);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
