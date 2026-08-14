import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  TOKEN,
  spawnCli,
  success,
  withFakeBridge,
} from "./helpers/fake-bridge.mjs";

for (const [signal, expectedExit] of [["SIGINT", 130], ["SIGTERM", 143]]) {
  test(`${signal} cancels the exact active request before exiting ${expectedExit}`, async () => {
    let executeRequest;
    let executeSocket;
    let stopRequest;
    const executeStarted = promiseWithResolvers();

    await withFakeBridge(
      (request, socket) => {
        if (request.method === "browser.session.acquire") {
          return success(request, { lease_id: "signal-lease", client_id: "eta-browser-cli" });
        }
        if (request.method === "browser.execute") {
          executeRequest = request;
          executeSocket = socket;
          executeStarted.resolve();
          return new Promise(() => {});
        }
        if (request.method === "browser.stop") {
          stopRequest = request;
          if (executeSocket && !executeSocket.destroyed) {
            executeSocket.end(`${JSON.stringify(success(executeRequest, {
              browser: {
                ok: false,
                action: executeRequest.params.action,
                status: "cancelled",
                code: "CANCELLED",
                message: "cancelled",
              },
              images: [],
            }))}\n`);
          }
          return success(request, {
            stopped: true,
            request_id: request.request_id,
            interrupt_delivered: true,
          });
        }
        if (request.method === "browser.session.release") {
          return success(request, { released: true });
        }
        throw new Error(`Unexpected method ${request.method}`);
      },
      async (port) => {
        const running = spawnCli(port, ["navigate", "https://example.invalid"]);
        await Promise.race([
          executeStarted.promise,
          new Promise((_, reject) => setTimeout(() => reject(new Error("execute did not start")), 3_000)),
        ]);
        running.child.kill(signal);
        const result = await Promise.race([
          running.completed,
          new Promise((_, reject) => setTimeout(() => reject(new Error("CLI did not exit")), 5_000)),
        ]);

        assert.equal(result.status, expectedExit, result.stderr || result.stdout);
        const payload = JSON.parse(result.stdout);
        assert.equal(payload.ok, false);
        assert.equal(payload.error.category, "interrupted");
        assert.equal(payload.error.signal, signal);
        assert.equal(payload.error.details.cancellation, "accepted");
        assert.equal(stopRequest.client_id, executeRequest.client_id);
        assert.equal(stopRequest.lease_id, executeRequest.lease_id);
        assert.equal(stopRequest.request_id, executeRequest.id);
        assert.equal(result.stdout.includes(TOKEN), false);
        assert.equal(result.stderr.includes(TOKEN), false);
      },
    );
  });
}

test("reset reports interruption without sending browser.stop", async () => {
  let stopSeen = false;
  let resetSocket;
  let resetRequest;
  const resetStarted = promiseWithResolvers();

  await withFakeBridge(
    (request, socket) => {
      if (request.method === "browser.session.acquire") {
        return success(request, { lease_id: "reset-lease", client_id: "eta-browser-cli" });
      }
      if (request.method === "browser.reset") {
        resetSocket = socket;
        resetRequest = request;
        resetStarted.resolve();
        setTimeout(() => {
          if (!resetSocket.destroyed) {
            resetSocket.end(`${JSON.stringify(success(resetRequest, {
              browser: { ok: true, action: "reset", status: "ok" },
              images: [],
            }))}\n`);
          }
        }, 100);
        return undefined;
      }
      if (request.method === "browser.stop") stopSeen = true;
      if (request.method === "browser.session.release") return success(request, { released: true });
      return undefined;
    },
    async (port) => {
      const running = spawnCli(port, ["reset"]);
      await resetStarted.promise;
      running.child.kill("SIGINT");
      const result = await running.completed;
      assert.equal(result.status, 130, result.stderr || result.stdout);
      assert.equal(JSON.parse(result.stdout).error.details.cancellation, "not_available");
      assert.equal(stopSeen, false);
    },
  );
});

test("explicit stop requires the persisted owner lease and exact request id", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "eta-browser-stop-"));
  const config = path.join(temporary, "config.json");
  try {
    await withFakeBridge(
      (request) => {
        assert.equal(request.method, "browser.stop");
        assert.equal(request.lease_id, "persisted-lease");
        assert.equal(request.request_id, "known-request");
        return success(request, {
          stopped: true,
          request_id: "known-request",
          interrupt_delivered: true,
        });
      },
      async (port) => {
        await writeFile(config, `${JSON.stringify({
          host: "127.0.0.1",
          port,
          token: TOKEN,
          session: { lease_id: "persisted-lease" },
        })}\n`, { mode: 0o600 });
        const running = spawnCli(port, ["stop", "known-request"], { config, token: "" });
        const result = await running.completed;
        assert.equal(result.status, 0, result.stderr);
        assert.equal(JSON.parse(result.stdout).result.stopped, true);
      },
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

function promiseWithResolvers() {
  let resolve;
  let reject;
  const promise = new Promise((resolveValue, rejectValue) => {
    resolve = resolveValue;
    reject = rejectValue;
  });
  return { promise, resolve, reject };
}
