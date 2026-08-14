import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { cp, mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";
import { root, success, withFakeBridge } from "./helpers/fake-bridge.mjs";

const execFileAsync = promisify(execFile);

test("npm local install exposes eta-browser from outside the source tree", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "eta-browser-install-"));
  const sourceCopy = path.join(temporary, "source");
  const outside = path.join(temporary, "outside");
  try {
    await cp(root, sourceCopy, {
      recursive: true,
      filter: (source) => !source.includes(`${path.sep}node_modules${path.sep}`),
    });
    await mkdir(outside);
    await execFileAsync("npm", ["install", "--no-audit", "--no-fund", "--ignore-scripts", sourceCopy], {
      cwd: temporary,
      env: { ...process.env, npm_config_cache: path.join(temporary, "npm-cache") },
    });
    const bin = path.join(temporary, "node_modules", ".bin", "eta-browser");

    const version = await execFileAsync(bin, ["--version"], { cwd: outside });
    assert.equal(JSON.parse(version.stdout).result.cli_version, "1.0.0");
    const installedPackage = JSON.parse(await readFile(
      path.join(temporary, "node_modules", "eta-browser-cli", "package.json"),
      "utf8",
    ));
    assert.deepEqual(installedPackage.exports, {
      ".": "./lib/client.mjs",
      "./config": "./lib/config.mjs",
    });

    await withFakeBridge(
      (request) => success(request, {
        service: "eta-browser-bridge",
        protocol: 2,
        endpoint: "127.0.0.1:18765",
        session_leased: false,
        active_request_id: null,
      }),
      async (port) => {
        const health = await execFileAsync(bin, ["health"], {
          cwd: outside,
          env: {
            ...process.env,
            ETA_BROWSER_HOST: "127.0.0.1",
            ETA_BROWSER_PORT: String(port),
            ETA_BROWSER_TOKEN: "installed-smoke-token-that-is-long-enough",
            ETA_BROWSER_ALLOW_NONSTANDARD_PORT: "1",
            ETA_BROWSER_CONFIG: path.join(temporary, "config.json"),
          },
        });
        assert.equal(JSON.parse(health.stdout).result.protocol, 2);
      },
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
