import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const TOKEN = "test-token-that-is-at-least-thirty-two-chars";
export const root = path.dirname(path.dirname(path.dirname(fileURLToPath(import.meta.url))));
export const cli = path.join(root, "eta-browser.mjs");

let configSequence = 0;

export async function withFakeBridge(handler, block) {
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
      let request;
      try {
        request = JSON.parse(input.slice(0, newline));
      } catch (error) {
        failures.push(error);
        socket.destroy();
        return;
      }
      Promise.resolve()
        .then(() => handler(request, socket))
        .then((response) => {
          if (response !== undefined && !socket.destroyed) {
            socket.end(`${JSON.stringify(response)}\n`);
          }
        })
        .catch((error) => {
          failures.push(error);
          if (!socket.destroyed) {
            socket.end(`${JSON.stringify({
              id: request?.id ?? null,
              ok: false,
              error: { code: "FAKE_BRIDGE_FAILURE", message: error.message },
            })}\n`);
          }
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
    await new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
}

export function spawnCli(port, args, {
  env = {},
  cwd = root,
  token = TOKEN,
  config = path.join(os.tmpdir(), `eta-browser-cli-empty-${process.pid}-${++configSequence}.json`),
} = {}) {
  const child = spawn(process.execPath, [cli, ...args], {
    cwd,
    env: {
      ...process.env,
      ETA_BROWSER_HOST: "127.0.0.1",
      ETA_BROWSER_PORT: String(port),
      ETA_BROWSER_TOKEN: token,
      ETA_BROWSER_ALLOW_NONSTANDARD_PORT: "1",
      ETA_BROWSER_CONFIG: config,
      ...env,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const completed = new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("close", (status, signal) => resolve({ status, signal, stdout, stderr }));
  });
  return { child, completed };
}

export async function runCli(port, args, options = {}) {
  return spawnCli(port, args, options).completed;
}

export function success(request, result) {
  return { id: request.id, ok: true, result };
}

export function failure(request, code, message = code) {
  return { id: request.id, ok: false, error: { code, message } };
}
