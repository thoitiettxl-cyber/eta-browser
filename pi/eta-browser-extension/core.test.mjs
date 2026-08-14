import assert from "node:assert/strict";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import test from "node:test";
import { BROWSER_ACTIONS, executeEtaBrowser } from "./core.mjs";

const TOKEN = "eta-browser-extension-test-token-123456789";

async function withBridge(handler, block) {
  const failures = [];
  const sockets = new Set();
  const server = net.createServer({ allowHalfOpen: true }, (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    let input = "";
    socket.on("data", (chunk) => {
      input += chunk.toString("utf8");
      const newline = input.indexOf("\n");
      if (newline < 0) return;
      socket.pause();
      const request = JSON.parse(input.slice(0, newline));
      Promise.resolve(handler(request, socket)).then((response) => {
        if (response !== undefined && !socket.destroyed) socket.end(`${JSON.stringify(response)}\n`);
      }).catch((error) => {
        failures.push(error);
        socket.destroy();
      });
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    assert.equal(typeof address, "object");
    await block(address.port);
    if (failures.length) throw failures[0];
  } finally {
    for (const socket of sockets) socket.destroy();
    await new Promise((resolve) => server.close(resolve));
  }
}

async function withConfig(port, session, block) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "eta-browser-extension-"));
  const target = path.join(directory, "config.json");
  await writeFile(target, `${JSON.stringify({
    host: "127.0.0.1",
    port,
    token: TOKEN,
    ...(session ? { session: { lease_id: session } } : {}),
  })}\n`, { mode: 0o600 });
  const env = {
    ...process.env,
    ETA_BROWSER_CONFIG: target,
    ETA_BROWSER_ALLOW_NONSTANDARD_PORT: "1",
  };
  try {
    await block(env, target);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

function success(request, result) {
  return { id: request.id, ok: true, result };
}

test("health uses private config and returns no credential", async () => {
  await withBridge((request) => {
    assert.equal(request.method, "health");
    assert.equal(request.token, TOKEN);
    return success(request, { protocol: 2, available: true, session_leased: false });
  }, async (port) => withConfig(port, undefined, async (env) => {
    const result = await executeEtaBrowser({ action: "health" }, undefined, env);
    const rendered = JSON.stringify(result);
    assert.doesNotMatch(rendered, new RegExp(TOKEN));
    assert.match(result.content[0].text, /"protocol":2/);
  }));
});

test("one-shot action acquires, executes, and releases temporary lease", async () => {
  const methods = [];
  await withBridge((request) => {
    methods.push(request.method);
    if (request.method === "browser.session.acquire") {
      return success(request, { lease_id: "temporary-secret-lease" });
    }
    if (request.method === "browser.execute") {
      assert.equal(request.lease_id, "temporary-secret-lease");
      assert.deepEqual(request.params, {
        action: "navigate",
        url: "https://example.com",
        timeout_ms: 1500,
      });
      return success(request, { browser: { ok: true, action: "navigate", status: "ok" }, images: [] });
    }
    assert.equal(request.method, "browser.session.release");
    assert.equal(request.lease_id, "temporary-secret-lease");
    return success(request, { released: true });
  }, async (port) => withConfig(port, undefined, async (env) => {
    const result = await executeEtaBrowser({
      action: "navigate",
      url: "https://example.com",
      timeout_ms: 1500,
    }, undefined, env);
    assert.deepEqual(methods, ["browser.session.acquire", "browser.execute", "browser.session.release"]);
    assert.doesNotMatch(JSON.stringify(result), /temporary-secret-lease/);
  }));
});

test("persistent lease is reused without modifying stored config", async () => {
  await withBridge((request) => {
    assert.equal(request.method, "browser.execute");
    assert.equal(request.lease_id, "stored-secret-lease");
    return success(request, { browser: { ok: true, action: "get_page_info", status: "ok" }, images: [] });
  }, async (port) => withConfig(port, "stored-secret-lease", async (env, target) => {
    const before = await readFile(target, "utf8");
    const result = await executeEtaBrowser({ action: "get_page_info" }, undefined, env);
    assert.equal(await readFile(target, "utf8"), before);
    assert.doesNotMatch(JSON.stringify(result), /stored-secret-lease/);
  }));
});

test("screenshot returns image content without inline data URL", async () => {
  const imageData = Buffer.from("test-image").toString("base64");
  await withBridge((request) => {
    if (request.method === "browser.execute") {
      assert.deepEqual(request.params, { action: "screenshot", read_image: true });
      return success(request, {
        browser: { ok: true, action: "screenshot", status: "ok" },
        images: [{
          data_url: `data:image/jpeg;base64,${imageData}`,
          mime_type: "image/jpeg",
          bytes: 10,
          width: 20,
          height: 30,
        }],
      });
    }
    throw new Error(`Unexpected ${request.method}`);
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    const result = await executeEtaBrowser({ action: "screenshot" }, undefined, env);
    assert.deepEqual(result.content[1], { type: "image", data: imageData, mimeType: "image/jpeg" });
    assert.doesNotMatch(result.content[0].text, /data:image|test-image/);
    assert.doesNotMatch(JSON.stringify(result.details), /data:image|stored-secret-lease/);
  }));
});

test("abort sends stop with exact request identity", async () => {
  const controller = new AbortController();
  let executeRequest;
  let stopRequest;
  await withBridge((request) => {
    if (request.method === "browser.execute") {
      executeRequest = request;
      setTimeout(() => controller.abort(), 20);
      return undefined;
    }
    if (request.method === "browser.stop") {
      stopRequest = request;
      return success(request, { stopped: true });
    }
    throw new Error(`Unexpected ${request.method}`);
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    await assert.rejects(
      executeEtaBrowser({ action: "wait_for_selector", selector: "#later" }, controller.signal, env),
      /\[CANCELLED\]/,
    );
    assert.equal(stopRequest.client_id, executeRequest.client_id);
    assert.equal(stopRequest.lease_id, executeRequest.lease_id);
    assert.equal(stopRequest.request_id, executeRequest.id);
  }));
});

test("bridge and browser failures surface stable public error codes", async () => {
  let call = 0;
  await withBridge((request) => {
    call += 1;
    if (call === 1) {
      return { id: request.id, ok: false, error: { code: "UNAUTHORIZED", message: "Pairing required" } };
    }
    return success(request, {
      browser: {
        ok: false,
        action: "get_page_info",
        status: "blocked",
        code: "USER_CONTROL_ACTIVE",
        message: "User controls the shared browser",
      },
      images: [],
    });
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    await assert.rejects(
      executeEtaBrowser({ action: "health" }, undefined, env),
      /\[UNAUTHORIZED\] Pairing required/,
    );
    await assert.rejects(
      executeEtaBrowser({ action: "get_page_info" }, undefined, env),
      /\[USER_CONTROL_ACTIVE\] User controls the shared browser/,
    );
  }));
});

test("reset ignores abort after dispatch because reset is non-cancellable", async () => {
  const controller = new AbortController();
  const methods = [];
  await withBridge(async (request) => {
    methods.push(request.method);
    assert.equal(request.method, "browser.reset");
    setTimeout(() => controller.abort(), 5);
    await new Promise((resolve) => setTimeout(resolve, 20));
    return success(request, { browser: { ok: true, action: "reset", status: "ok" } });
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    const result = await executeEtaBrowser({ action: "reset" }, controller.signal, env);
    assert.match(result.content[0].text, /"status":"ok"/);
    assert.deepEqual(methods, ["browser.reset"]);
  }));
});

test("publishes the Eta core plus seven bounded standalone actions", () => {
  assert.equal(BROWSER_ACTIONS.length, 20);
  for (const action of ["observe", "hover", "select", "press", "request_help", "console", "network"]) {
    assert.ok(BROWSER_ACTIONS.includes(action));
  }
  assert.ok(!BROWSER_ACTIONS.includes("evaluate"));
});

test("semantic refs and interaction parameters are forwarded without lease exposure", async () => {
  const expected = [
    { action: "observe" },
    { action: "hover", ref: "@e7" },
    { action: "select", ref: "@e8", values: ["one", "two"] },
    { action: "press", key: "Enter", ref: "@e9" },
  ];
  let index = 0;
  await withBridge((request) => {
    assert.equal(request.method, "browser.execute");
    assert.deepEqual(request.params, expected[index]);
    const action = expected[index].action;
    index += 1;
    return success(request, { browser: { ok: true, action, status: "ok" }, images: [] });
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    for (const input of expected) {
      const result = await executeEtaBrowser(input, undefined, env);
      assert.doesNotMatch(JSON.stringify(result), /stored-secret-lease/);
    }
  }));
  assert.equal(index, expected.length);
});

test("request_help forwards bounded handoff criteria and remains cancellable", async () => {
  const controller = new AbortController();
  let executeRequest;
  let stopRequest;
  await withBridge((request) => {
    if (request.method === "browser.execute") {
      executeRequest = request;
      assert.deepEqual(request.params, {
        action: "request_help",
        prompt: "Complete verification",
        title: "Verification",
        target_selector: "#challenge",
        completion_criteria: {
          url_contains: "/dashboard",
          selector_exists: "#account",
          match: "any",
          stable_for_ms: 500,
        },
        timeout_ms: 300000,
      });
      setTimeout(() => controller.abort(), 20);
      return undefined;
    }
    if (request.method === "browser.stop") {
      stopRequest = request;
      return success(request, { stopped: true });
    }
    throw new Error(`Unexpected ${request.method}`);
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    await assert.rejects(
      executeEtaBrowser({
        action: "request_help",
        prompt: "Complete verification",
        title: "Verification",
        target_selector: "#challenge",
        completion_criteria: {
          url_contains: "/dashboard",
          selector_exists: "#account",
          match: "any",
          stable_for_ms: 500,
        },
        timeout_ms: 300000,
      }, controller.signal, env),
      /\[CANCELLED\]/,
    );
  }));
  assert.equal(stopRequest.lease_id, executeRequest.lease_id);
  assert.equal(stopRequest.request_id, executeRequest.id);
});

test("diagnostic cursors are forwarded and invalid targets fail before transport", async () => {
  let calls = 0;
  await withBridge((request) => {
    calls += 1;
    assert.deepEqual(request.params, { action: "network", since: 12, limit: 25 });
    return success(request, {
      browser: {
        ok: true,
        action: "network",
        status: "ok",
        captures_headers: false,
        captures_bodies: false,
      },
      images: [],
    });
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    const result = await executeEtaBrowser({ action: "network", since: 12, limit: 25 }, undefined, env);
    assert.match(result.content[0].text, /"captures_headers":false/);
    await assert.rejects(
      executeEtaBrowser({ action: "click", selector: "button", ref: "@e1" }, undefined, env),
      /\[AMBIGUOUS_TARGET\]/,
    );
    await assert.rejects(
      executeEtaBrowser({ action: "request_help", prompt: "Continue", completion_criteria: {} }, undefined, env),
      /\[COMPLETION_CRITERIA_REQUIRED\]/,
    );
  }));
  assert.equal(calls, 1);
});

test("request_help without explicit timeout uses the bounded default contract", async () => {
  await withBridge((request) => {
    assert.equal(request.method, "browser.execute");
    assert.deepEqual(request.params, { action: "request_help", prompt: "Continue" });
    return success(request, {
      browser: { ok: true, action: "request_help", status: "continued", outcome: "continued" },
      images: [],
    });
  }, async (port) => withConfig(port, "stored-secret-lease", async (env) => {
    const result = await executeEtaBrowser({ action: "request_help", prompt: "Continue" }, undefined, env);
    assert.match(result.content[0].text, /"outcome":"continued"/);
  }));
});
