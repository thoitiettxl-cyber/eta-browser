import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  TOKEN,
  root,
  runCli,
  success,
  withFakeBridge,
} from "./helpers/fake-bridge.mjs";

const snapshots = path.join(path.dirname(fileURLToPath(import.meta.url)), "snapshots");

async function withConfig(block) {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "eta-browser-cli-config-"));
  const config = path.join(temporary, "config.json");
  try {
    await block(config, temporary);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

async function snapshot(name) {
  return JSON.parse(await readFile(path.join(snapshots, name), "utf8"));
}

function payload(result) {
  return JSON.parse(result.stdout);
}

function baseHealth(overrides = {}) {
  return {
    service: "eta-browser-bridge",
    protocol: 2,
    endpoint: "127.0.0.1:18765",
    session_leased: false,
    active_request_id: null,
    browser_available: true,
    host: "example.com",
    title: "Example Domain",
    is_loading: false,
    is_user_controlling: false,
    human_handoff_pending: false,
    ...overrides,
  };
}

test("version output is snapshot-stable", async () => {
  const result = await runCli(18_765, ["--version"]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(payload(result), await snapshot("version.json"));
});

test("help is diagnostic-only and lists production commands", async () => {
  const result = await runCli(18_765, ["--help"]);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, "");
  for (const command of [
    "session acquire",
    "get-readable",
    "get-text",
    "find-elements",
    "observe",
    "request-help",
    "console | network",
    "wait-for-selector",
    "screenshot --output PATH",
  ]) {
    assert.match(result.stderr, new RegExp(command));
  }
});

test("health output is snapshot-stable and validates protocol v2", async () => {
  await withFakeBridge(
    (request) => {
      assert.equal(request.token, TOKEN);
      assert.equal(request.client_id, "eta-browser-cli");
      assert.equal(request.method, "health");
      return success(request, baseHealth());
    },
    async (port) => {
      const result = await runCli(port, ["health"]);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(result.stderr, "");
      assert.deepEqual(payload(result), await snapshot("health.json"));
    },
  );
});

test("usage errors are snapshot-stable and exit 2", async () => {
  const result = await runCli(18_765, ["click"]);
  assert.equal(result.status, 2);
  assert.deepEqual(payload(result), await snapshot("usage-error.json"));
  assert.match(result.stderr, /TARGET_REQUIRED/);
});

test("exit-code categories distinguish transport, bridge, and browser failure", async (t) => {
  await t.test("transport exits 3", async () => {
    const result = await runCli(65_534, ["health"], { env: { ETA_BROWSER_PORT: "65534" } });
    assert.equal(result.status, 3);
    assert.equal(payload(result).error.category, "transport");
    assert.equal(payload(result).error.code, "CONNECTION_FAILED");
  });

  await t.test("bridge exits 4", async () => {
    await withFakeBridge(
      (request) => ({ id: request.id, ok: false, error: { code: "UNAUTHORIZED", message: "no" } }),
      async (port) => {
        const result = await runCli(port, ["health"]);
        assert.equal(result.status, 4);
        assert.equal(payload(result).error.category, "bridge");
        assert.equal(payload(result).error.code, "UNAUTHORIZED");
      },
    );
  });

  await t.test("browser exits 5 and preserves bounded result", async () => {
    await withFakeBridge(
      (request) => {
        if (request.method === "browser.session.acquire") {
          return success(request, { lease_id: "lease-browser-error", client_id: "eta-browser-cli" });
        }
        if (request.method === "browser.session.release") {
          return success(request, { released: true });
        }
        return success(request, {
          browser: {
            ok: false,
            tool: "browser_use",
            action: "wait_for_selector",
            status: "not_found",
            code: "ELEMENT_NOT_FOUND",
            message: "selector did not appear",
          },
          images: [],
        });
      },
      async (port) => {
        const result = await runCli(port, ["wait-for-selector", "#missing"]);
        assert.equal(result.status, 5, result.stderr);
        assert.deepEqual(payload(result), await snapshot("browser-error.json"));
      },
    );
  });
});

test("pair stores a mode-0600 credential and prints it only for setup", async () => {
  await withConfig(async (config) => {
    const result = await runCli(18_765, ["pair"], {
      config,
      token: "",
      env: { ETA_BROWSER_TOKEN: "", ETA_BROWSER_ALLOW_NONSTANDARD_PORT: "" },
    });
    assert.equal(result.status, 0, result.stderr);
    const output = payload(result);
    assert.match(output.result.token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(output.result.config_path, config);
    const stored = JSON.parse(await readFile(config, "utf8"));
    assert.equal(stored.token, output.result.token);
    assert.equal(stored.host, "127.0.0.1");
    assert.equal(stored.port, 18_765);
    assert.equal((await stat(config)).mode & 0o777, 0o600);
  });
});

test("stored credentials override ambient credentials; explicit token overrides stored", async () => {
  const seenTokens = [];
  await withFakeBridge(
    (request) => {
      seenTokens.push(request.token);
      return success(request, baseHealth());
    },
    async (port) => {
      await withConfig(async (config) => {
        await writeFile(config, `${JSON.stringify({ host: "127.0.0.1", port, token: TOKEN })}\n`, { mode: 0o600 });
        const stored = await runCli(port, ["health"], {
          config,
          token: "stale-environment-token-that-is-long-enough",
        });
        assert.equal(stored.status, 0, stored.stderr);
        const explicit = await runCli(port, [
          "health",
          "--token",
          "explicit-token-that-is-long-enough-123",
        ], { config });
        assert.equal(explicit.status, 0, explicit.stderr);
      });
    },
  );
  assert.deepEqual(seenTokens, [TOKEN, "explicit-token-that-is-long-enough-123"]);
});

test("config permissions are repaired and symlinks are rejected", async () => {
  await withConfig(async (config, temporary) => {
    await writeFile(config, `${JSON.stringify({ host: "127.0.0.1", port: 18_765, token: TOKEN })}\n`, { mode: 0o644 });
    const status = await runCli(18_765, ["session", "status"], { config });
    assert.equal(status.status, 0, status.stderr);
    assert.equal((await stat(config)).mode & 0o777, 0o600);

    const target = path.join(temporary, "target.json");
    const link = path.join(temporary, "link.json");
    await writeFile(target, "{}\n", { mode: 0o600 });
    await symlink(target, link);
    const rejected = await runCli(18_765, ["session", "status"], { config: link });
    assert.equal(rejected.status, 2);
    assert.equal(payload(rejected).error.code, "ETA_BROWSER_CONFIG_SYMLINK");
  });
});

test("rotate persists replacement without printing old/new credentials; revoke removes it", async () => {
  const replacement = "replacement-token-that-is-long-enough-12345";
  await withFakeBridge(
    (request) => {
      if (request.method === "pairing.rotate") {
        assert.equal(request.token, TOKEN);
        return success(request, { rotated: true, token: replacement });
      }
      assert.equal(request.method, "pairing.revoke");
      assert.equal(request.token, replacement);
      return success(request, { revoked: true });
    },
    async (port) => {
      await withConfig(async (config) => {
        await writeFile(config, `${JSON.stringify({ host: "127.0.0.1", port, token: TOKEN })}\n`, { mode: 0o600 });
        const rotated = await runCli(port, ["rotate"], { config, token: "" });
        assert.equal(rotated.status, 0, rotated.stderr);
        assert.equal(rotated.stdout.includes(TOKEN), false);
        assert.equal(rotated.stdout.includes(replacement), false);
        assert.equal(rotated.stderr.includes(TOKEN), false);
        assert.equal(rotated.stderr.includes(replacement), false);
        assert.equal(JSON.parse(await readFile(config, "utf8")).token, replacement);

        const revoked = await runCli(port, ["revoke"], { config, token: "" });
        assert.equal(revoked.status, 0, revoked.stderr);
        assert.equal(revoked.stdout.includes(replacement), false);
        assert.deepEqual(JSON.parse(await readFile(config, "utf8")), {
          host: "127.0.0.1",
          port,
        });
      });
    },
  );
});

test("session status and forget remain available after credential revocation", async () => {
  await withConfig(async (config) => {
    await writeFile(config, `${JSON.stringify({
      host: "127.0.0.1",
      port: 18_765,
      session: { lease_id: "stale-private-lease" },
    })}\n`, { mode: 0o600 });
    const status = await runCli(18_765, ["session", "status"], { config, token: "" });
    assert.equal(status.status, 0, status.stderr);
    assert.equal(payload(status).result.acquired, true);
    assert.equal(status.stdout.includes("stale-private-lease"), false);

    const forgotten = await runCli(18_765, ["session", "forget"], { config, token: "" });
    assert.equal(forgotten.status, 0, forgotten.stderr);
    assert.equal(payload(forgotten).result.forgotten, true);
    assert.equal(JSON.parse(await readFile(config, "utf8")).session, undefined);
  });
});

test("request timeout is bounded and reported as transport failure", async () => {
  await withFakeBridge(
    () => new Promise(() => {}),
    async (port) => {
      const started = Date.now();
      const result = await runCli(port, ["health", "--request-timeout-ms", "500"]);
      assert.equal(result.status, 3);
      assert.equal(payload(result).error.code, "REQUEST_TIMEOUT");
      assert.ok(Date.now() - started < 2_500, `timeout took ${Date.now() - started}ms`);
    },
  );
});

test("CLI runs from a working directory outside the repository", async () => {
  await withFakeBridge(
    (request) => success(request, baseHealth()),
    async (port) => {
      const outside = await mkdtemp(path.join(os.tmpdir(), "eta-browser-cli-outside-"));
      try {
        const result = await runCli(port, ["health"], { cwd: outside });
        assert.equal(result.status, 0, result.stderr);
        assert.equal(payload(result).result.protocol, 2);
        assert.notEqual(outside, root);
      } finally {
        await rm(outside, { recursive: true, force: true });
      }
    },
  );
});

test("new interaction and handoff usage errors fail before transport", async () => {
  const cases = [
    [["click", "--selector", "button", "--ref", "@e1"], "AMBIGUOUS_TARGET"],
    [["hover", "--ref", "stale"], "INVALID_REF"],
    [["select", "--selector", "select"], "VALUE_REQUIRED"],
    [["press", "F13"], "INVALID_KEY"],
    [["request-help", "Continue", "--completion-match", "neither"], "INVALID_COMPLETION_MATCH"],
    [["request-help", "x".repeat(601)], "PROMPT_TOO_LONG"],
  ];
  for (const [args, code] of cases) {
    const result = await runCli(18_765, args);
    assert.equal(result.status, 2, result.stderr);
    assert.equal(payload(result).error.code, code);
  }
});
