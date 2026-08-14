#!/usr/bin/env node

import { randomBytes } from "node:crypto";
import path from "node:path";
import { mkdir, rename, rm, writeFile } from "node:fs/promises";
import {
  BridgeTransportError,
  DEFAULT_REQUEST_TIMEOUT_MS,
  EtaBrowserClient,
  PROTOCOL_VERSION,
  STOP_REQUEST_TIMEOUT_MS,
  createRequestId,
} from "./lib/client.mjs";
import {
  ConfigError,
  loadStoredConfig,
  saveStoredConfig,
  selectedConnection,
  storedSession,
  withoutSession,
} from "./lib/config.mjs";

const CLI_VERSION = "1.0.0";
const OUTPUT_VERSION = 1;
const CLIENT_ID = "eta-browser-cli";

const EXIT = Object.freeze({
  SUCCESS: 0,
  USAGE: 2,
  TRANSPORT: 3,
  BRIDGE: 4,
  BROWSER: 5,
  SIGINT: 130,
  SIGTERM: 143,
});

const BOOLEAN_FLAGS = new Set(["help", "version", "submit"]);
const GLOBAL_FLAGS = new Set(["help", "host", "port", "token", "request-timeout-ms"]);
const ACTION_COMMANDS = new Set([
  "navigate",
  "get-readable",
  "get-text",
  "find-elements",
  "click",
  "type",
  "scroll",
  "screenshot",
  "get-page-info",
  "go-back",
  "go-forward",
  "reload",
  "wait-for-selector",
  "action",
]);
const COMMAND_FLAGS = Object.freeze({
  pair: new Set(["host", "port", "token"]),
  configure: new Set(["host", "port", "token"]),
  health: new Set(),
  rotate: new Set(),
  revoke: new Set(),
  session: new Set(),
  stop: new Set(),
  reset: new Set(),
  navigate: new Set(["timeout-ms"]),
  "get-readable": new Set(["offset", "max-chars"]),
  "get-text": new Set(["selector", "offset", "max-chars"]),
  "find-elements": new Set(["selector"]),
  click: new Set(["selector", "coordinate-x", "coordinate-y"]),
  type: new Set(["text", "selector", "coordinate-x", "coordinate-y", "submit"]),
  scroll: new Set(["selector", "direction", "amount"]),
  screenshot: new Set(["output"]),
  "get-page-info": new Set(),
  "go-back": new Set(),
  "go-forward": new Set(),
  reload: new Set(),
  "wait-for-selector": new Set(["timeout-ms"]),
  action: new Set(["output"]),
});

class CliError extends Error {
  constructor(code, message, {
    exitCode = EXIT.USAGE,
    category = "usage",
    details = undefined,
    result = undefined,
  } = {}) {
    super(message);
    this.name = "CliError";
    this.code = code;
    this.exitCode = exitCode;
    this.category = category;
    this.details = details;
    this.result = result;
  }
}

class SignalInterruption extends Error {
  constructor(signal, details = undefined) {
    super(`Interrupted by ${signal}`);
    this.name = "SignalInterruption";
    this.signal = signal;
    this.exitCode = signal === "SIGTERM" ? EXIT.SIGTERM : EXIT.SIGINT;
    this.details = details;
  }
}

class SignalCoordinator {
  constructor() {
    this.signal = undefined;
    this.active = undefined;
    this.handlers = new Map([
      ["SIGINT", () => this.interrupt("SIGINT")],
      ["SIGTERM", () => this.interrupt("SIGTERM")],
    ]);
    for (const [signal, handler] of this.handlers) process.on(signal, handler);
  }

  async run(operation, {
    abortOnSignal = true,
    cancel = undefined,
  } = {}) {
    const abortController = new AbortController();
    const active = {
      abortController,
      abortOnSignal,
      cancel,
      cancelPromise: undefined,
    };
    this.active = active;
    if (this.signal) this.applyInterruption(active);
    try {
      const result = await operation(abortController.signal);
      if (this.signal) {
        const cancellation = await active.cancelPromise;
        throw new SignalInterruption(this.signal, signalDetails(cancellation, cancel));
      }
      return result;
    } catch (error) {
      if (this.signal) {
        const cancellation = await active.cancelPromise;
        throw new SignalInterruption(this.signal, signalDetails(cancellation, cancel));
      }
      throw error;
    } finally {
      if (this.active === active) this.active = undefined;
    }
  }

  throwIfInterrupted() {
    if (this.signal) throw new SignalInterruption(this.signal);
  }

  close() {
    for (const [signal, handler] of this.handlers) process.off(signal, handler);
  }

  interrupt(signal) {
    if (this.signal) {
      process.exit(signal === "SIGTERM" ? EXIT.SIGTERM : EXIT.SIGINT);
    }
    this.signal = signal;
    if (this.active) this.applyInterruption(this.active);
  }

  applyInterruption(active) {
    if (active.cancel && !active.cancelPromise) {
      active.cancelPromise = Promise.resolve()
        .then(active.cancel)
        .catch((error) => ({ ok: false, error: { code: cancellationErrorCode(error) } }))
        .finally(() => active.abortController.abort());
      return;
    }
    if (active.abortOnSignal) active.abortController.abort();
  }
}

function signalDetails(cancellation, cancel) {
  if (!cancel) return { cancellation: "not_available" };
  if (!cancellation) return { cancellation: "not_completed" };
  if (cancellation.ok) {
    return {
      cancellation: cancellation.result?.stopped ? "accepted" : "not_accepted",
      request_id: cancellation.result?.request_id,
    };
  }
  return {
    cancellation: "failed",
    cancellation_code: String(cancellation.error?.code ?? "CANCELLATION_FAILED"),
  };
}

function cancellationErrorCode(error) {
  if (error instanceof BridgeTransportError) return error.code;
  return "CANCELLATION_FAILED";
}

function parseArgs(argv) {
  const positional = [];
  const flags = new Map();
  for (let index = 0; index < argv.length; index++) {
    const value = argv[index];
    if (value === "-h") {
      setFlag(flags, "help", true);
      continue;
    }
    if (value === "-V") {
      setFlag(flags, "version", true);
      continue;
    }
    if (!value.startsWith("--")) {
      positional.push(value);
      continue;
    }
    const separator = value.indexOf("=");
    const rawName = separator < 0 ? value.slice(2) : value.slice(2, separator);
    if (!rawName) throw new CliError("INVALID_FLAG", "Flag name cannot be empty");
    if (separator >= 0) {
      setFlag(flags, rawName, value.slice(separator + 1));
      continue;
    }
    if (BOOLEAN_FLAGS.has(rawName)) {
      setFlag(flags, rawName, true);
      continue;
    }
    const next = argv[index + 1];
    if (next === undefined || next.startsWith("--")) {
      throw new CliError("FLAG_VALUE_REQUIRED", `--${rawName} requires a value`);
    }
    setFlag(flags, rawName, next);
    index++;
  }
  return { positional, flags };
}

function setFlag(flags, name, value) {
  if (flags.has(name)) throw new CliError("DUPLICATE_FLAG", `--${name} was provided more than once`);
  flags.set(name, value);
}

function normalizeCommand(value) {
  const normalized = String(value ?? "").trim().toLowerCase().replaceAll("_", "-");
  if (normalized === "read") return "get-readable";
  if (normalized === "page-info") return "get-page-info";
  return normalized;
}

function validateFlags(command, flags) {
  const allowed = new Set([...GLOBAL_FLAGS, ...(COMMAND_FLAGS[command] ?? [])]);
  for (const name of flags.keys()) {
    if (!allowed.has(name)) throw new CliError("UNKNOWN_FLAG", `Unknown flag for ${command}: --${name}`);
  }
}

function requestTimeout(flags) {
  return integerFlag(flags, "request-timeout-ms", DEFAULT_REQUEST_TIMEOUT_MS, {
    min: 500,
    max: 120_000,
  });
}

function integerFlag(flags, name, fallback, { min = -2_147_483_648, max = 2_147_483_647 } = {}) {
  if (!flags.has(name)) return fallback;
  const raw = String(flags.get(name));
  if (!/^-?\d+$/.test(raw)) throw new CliError("INVALID_INTEGER", `--${name} must be an integer`);
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new CliError("INTEGER_OUT_OF_RANGE", `--${name} must be between ${min} and ${max}`);
  }
  return parsed;
}

function booleanFlag(flags, name, fallback = false) {
  if (!flags.has(name)) return fallback;
  const raw = flags.get(name);
  if (raw === true || raw === "true") return true;
  if (raw === "false") return false;
  throw new CliError("INVALID_BOOLEAN", `--${name} must be true or false`);
}

function optionalStringFlag(flags, name) {
  if (!flags.has(name)) return undefined;
  return String(flags.get(name));
}

function requirePositionals(positional, count, usageCode = "UNEXPECTED_ARGUMENT") {
  if (positional.length !== count) {
    throw new CliError(usageCode, `Expected ${count - 1} command argument(s), received ${positional.length - 1}`);
  }
}

function actionParams(command, positional, flags) {
  switch (command) {
    case "navigate": {
      requirePositionals(positional, 2);
      return {
        action: "navigate",
        url: positional[1],
        timeout_ms: integerFlag(flags, "timeout-ms", 25_000),
      };
    }
    case "get-readable":
      requirePositionals(positional, 1);
      return {
        action: "get_readable",
        offset: integerFlag(flags, "offset", 0),
        max_chars: integerFlag(flags, "max-chars", 8_000),
      };
    case "get-text":
      requirePositionals(positional, 1);
      return compact({
        action: "get_text",
        selector: optionalStringFlag(flags, "selector"),
        offset: integerFlag(flags, "offset", 0),
        max_chars: integerFlag(flags, "max-chars", 8_000),
      });
    case "find-elements":
      requirePositionals(positional, 1);
      return compact({ action: "find_elements", selector: optionalStringFlag(flags, "selector") });
    case "click":
      requirePositionals(positional, 1);
      return targetParams("click", flags);
    case "type": {
      if (positional.length > 2) requirePositionals(positional, 2);
      if (positional.length === 2 && flags.has("text")) {
        throw new CliError("DUPLICATE_TEXT", "Provide type text either positionally or with --text");
      }
      const text = flags.has("text") ? String(flags.get("text")) : positional[1];
      if (text === undefined) throw new CliError("TEXT_REQUIRED", "type requires text or --text");
      return {
        ...targetParams("type", flags),
        text,
        submit: booleanFlag(flags, "submit", false),
      };
    }
    case "scroll":
      requirePositionals(positional, 1);
      return compact({
        action: "scroll",
        selector: optionalStringFlag(flags, "selector"),
        direction: optionalStringFlag(flags, "direction") ?? "down",
        amount: integerFlag(flags, "amount", 600),
      });
    case "screenshot":
      requirePositionals(positional, 1);
      if (!flags.has("output")) {
        throw new CliError("OUTPUT_REQUIRED", "screenshot requires --output PATH");
      }
      return { action: "screenshot", read_image: true };
    case "get-page-info":
      requirePositionals(positional, 1);
      return { action: "get_page_info" };
    case "go-back":
      requirePositionals(positional, 1);
      return { action: "go_back" };
    case "go-forward":
      requirePositionals(positional, 1);
      return { action: "go_forward" };
    case "reload":
      requirePositionals(positional, 1);
      return { action: "reload" };
    case "wait-for-selector":
      requirePositionals(positional, 2);
      return {
        action: "wait_for_selector",
        selector: positional[1],
        timeout_ms: integerFlag(flags, "timeout-ms", 5_000),
      };
    case "action": {
      requirePositionals(positional, 2);
      try {
        const parsed = JSON.parse(positional[1]);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
          throw new Error("action JSON must be an object");
        }
        if (parsed.action === "screenshot" && !flags.has("output")) {
          throw new CliError("OUTPUT_REQUIRED", "raw screenshot action requires --output PATH");
        }
        return parsed;
      } catch (error) {
        if (error instanceof CliError) throw error;
        throw new CliError("INVALID_ACTION_JSON", "action must be a valid JSON object");
      }
    }
    default:
      throw new CliError("UNKNOWN_COMMAND", `Unknown command: ${command}`);
  }
}

function targetParams(action, flags) {
  const selector = optionalStringFlag(flags, "selector");
  const hasX = flags.has("coordinate-x");
  const hasY = flags.has("coordinate-y");
  if (hasX !== hasY) {
    throw new CliError("COORDINATE_PAIR_REQUIRED", "--coordinate-x and --coordinate-y must be used together");
  }
  if (selector === undefined && !hasX) {
    throw new CliError("TARGET_REQUIRED", `${action} requires --selector or both coordinates`);
  }
  return compact({
    action,
    selector,
    coordinate_x: hasX ? integerFlag(flags, "coordinate-x", undefined) : undefined,
    coordinate_y: hasY ? integerFlag(flags, "coordinate-y", undefined) : undefined,
  });
}

function compact(value) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined));
}

function generatePairingToken() {
  return randomBytes(32).toString("base64url");
}

function bridgeFailure(response) {
  return new CliError(
    String(response?.error?.code ?? "BRIDGE_ERROR"),
    String(response?.error?.message ?? "Eta Browser bridge rejected the request"),
    { exitCode: EXIT.BRIDGE, category: "bridge" },
  );
}

function requireBridgeSuccess(response) {
  if (!response?.ok) throw bridgeFailure(response);
  return response.result ?? {};
}

function browserFailure(result) {
  const browser = result?.browser;
  if (!browser || browser.ok !== false) return undefined;
  return new CliError(
    String(browser.code ?? "BROWSER_FAILED"),
    String(browser.message ?? "Browser action failed"),
    {
      exitCode: EXIT.BROWSER,
      category: "browser",
      result,
    },
  );
}

function clientFor(connection, flags) {
  return new EtaBrowserClient({
    ...connection,
    clientId: CLIENT_ID,
    requestTimeoutMs: requestTimeout(flags),
  });
}

async function pairCommand(command, positional, flags, stored, env) {
  requirePositionals(positional, 1);
  const endpoint = selectedConnection({
    flags,
    stored,
    env,
    requireToken: false,
    preferStored: false,
  });
  const token = String(flags.get("token") ?? env.ETA_BROWSER_TOKEN ?? "") || generatePairingToken();
  if (token.length < 32 || token.length > 128) {
    throw new CliError("ETA_BROWSER_TOKEN_INVALID", "Pairing token must contain 32 to 128 characters");
  }
  const target = await saveStoredConfig({ host: endpoint.host, port: endpoint.port, token }, env);
  return success(command, {
    paired: true,
    host: endpoint.host,
    port: endpoint.port,
    config_path: target,
    token,
    next_step: "Enter this token in Eta Browser, pair this device, then enable the bridge.",
  });
}

async function sessionCommand({ positional, flags, stored, env, client, signalCoordinator }) {
  const operation = normalizeCommand(positional[1]);
  if (!operation || !["acquire", "release", "status", "forget"].includes(operation)) {
    throw new CliError("SESSION_COMMAND_REQUIRED", "Use session acquire, release, status, or forget");
  }
  requirePositionals(positional, 2);
  const current = storedSession(stored);
  if (operation === "status") {
    return success("session status", {
      acquired: Boolean(current),
    });
  }
  if (operation === "forget") {
    const target = await saveStoredConfig(withoutSession(stored), env);
    return success("session forget", { forgotten: Boolean(current), config_path: target });
  }
  if (operation === "acquire") {
    if (current) throw new CliError("SESSION_ALREADY_ACQUIRED", "A browser session is already stored");
    const response = await signalCoordinator.run(
      (signal) => client.request("browser.session.acquire", { signal }),
    );
    const result = requireBridgeSuccess(response);
    const leaseId = String(result.lease_id ?? "");
    if (!leaseId) {
      throw new CliError("LEASE_ID_MISSING", "Bridge did not return a session lease", {
        exitCode: EXIT.BRIDGE,
        category: "bridge",
      });
    }
    const target = await saveStoredConfig({
      ...withoutSession(stored),
      session: { lease_id: leaseId },
    }, env).catch(async (error) => {
      await client.request("browser.session.release", {
        leaseId,
        timeoutMs: STOP_REQUEST_TIMEOUT_MS,
      }).catch(() => undefined);
      throw error;
    });
    const visibleResult = { ...result };
    delete visibleResult.lease_id;
    return success("session acquire", {
      ...visibleResult,
      acquired: true,
      persisted: true,
      config_path: target,
    });
  }
  if (!current) throw new CliError("SESSION_REQUIRED", "No stored browser session is available");
  const response = await signalCoordinator.run(
    (signal) => client.request("browser.session.release", {
      leaseId: current.leaseId,
      signal,
    }),
  );
  const result = requireBridgeSuccess(response);
  const target = await saveStoredConfig(withoutSession(stored), env);
  return success("session release", { ...result, persisted: false, config_path: target });
}

async function withLease({ stored, client, signalCoordinator, operation }) {
  const persistent = storedSession(stored);
  if (persistent) return operation(persistent.leaseId, true);

  const acquiredResponse = await signalCoordinator.run(
    (signal) => client.request("browser.session.acquire", { signal }),
  );
  const acquired = requireBridgeSuccess(acquiredResponse);
  const leaseId = String(acquired.lease_id ?? "");
  if (!leaseId) {
    throw new CliError("LEASE_ID_MISSING", "Bridge did not return a session lease", {
      exitCode: EXIT.BRIDGE,
      category: "bridge",
    });
  }
  let result;
  let operationError;
  try {
    result = await operation(leaseId, false);
  } catch (error) {
    operationError = error;
  }
  const releaseError = await releaseTemporaryLease(client, leaseId);
  if (operationError) throw operationError;
  if (releaseError) throw releaseError;
  return result;
}

async function releaseTemporaryLease(client, leaseId) {
  let lastResponse;
  const deadline = Date.now() + STOP_REQUEST_TIMEOUT_MS;
  do {
    try {
      lastResponse = await client.request("browser.session.release", {
        leaseId,
        timeoutMs: STOP_REQUEST_TIMEOUT_MS,
      });
      if (lastResponse.ok) return undefined;
      if (lastResponse.error?.code !== "SESSION_BUSY") return bridgeFailure(lastResponse);
    } catch (error) {
      return error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  return bridgeFailure(lastResponse);
}

async function browserActionCommand({
  command,
  positional,
  flags,
  stored,
  client,
  signalCoordinator,
}) {
  const params = actionParams(command, positional, flags);
  const result = await withLease({
    stored,
    client,
    signalCoordinator,
    operation: async (leaseId) => {
      const requestId = createRequestId();
      const cancel = () => client.request("browser.stop", {
        leaseId,
        targetRequestId: requestId,
        timeoutMs: STOP_REQUEST_TIMEOUT_MS,
      });
      const response = await signalCoordinator.run(
        (signal) => client.request("browser.execute", {
          leaseId,
          params,
          requestId,
          signal,
        }),
        { abortOnSignal: false, cancel },
      );
      return requireBridgeSuccess(response);
    },
  });
  const screenshot = params.action === "screenshot" && flags.has("output");
  const failure = browserFailure(result);
  if (failure) {
    removeInlineImages(result);
    throw failure;
  }
  if (screenshot) {
    await writeScreenshot(result, String(flags.get("output")));
  } else {
    removeInlineImages(result);
  }
  return success(command, result);
}

function removeInlineImages(result) {
  if (!Array.isArray(result?.images)) return;
  for (const image of result.images) {
    if (image && typeof image === "object") delete image.data_url;
  }
}

async function writeScreenshot(result, outputValue) {
  const image = result?.images?.[0];
  if (!image?.data_url) {
    throw new CliError("SCREENSHOT_DATA_MISSING", "Bridge did not return screenshot data", {
      exitCode: EXIT.BRIDGE,
      category: "bridge",
    });
  }
  const match = /^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$/.exec(image.data_url);
  if (!match) {
    throw new CliError("INVALID_SCREENSHOT_DATA", "Bridge returned invalid screenshot data", {
      exitCode: EXIT.BRIDGE,
      category: "bridge",
    });
  }
  const outputPath = path.resolve(outputValue);
  if (outputValue === "-") throw new CliError("INVALID_OUTPUT", "Binary screenshot output cannot use stdout");
  const temporary = `${outputPath}.${process.pid}.${createRequestId()}.tmp`;
  try {
    await mkdir(path.dirname(outputPath), { recursive: true });
    const data = Buffer.from(match[2], "base64");
    await writeFile(temporary, data, { mode: 0o600, flag: "wx" });
    await rename(temporary, outputPath);
    delete image.data_url;
    image.mime_type = image.mime_type ?? match[1];
    image.output_path = outputPath;
    image.bytes = data.length;
  } catch (error) {
    await rm(temporary, { force: true }).catch(() => undefined);
    throw new CliError("OUTPUT_WRITE_FAILED", "Unable to write screenshot output", {
      details: { output_path: outputPath },
    });
  }
}

async function stopCommand({ positional, stored, client, signalCoordinator }) {
  if (positional.length > 2) requirePositionals(positional, 2);
  const session = storedSession(stored);
  if (!session) throw new CliError("SESSION_REQUIRED", "stop requires a stored browser session");
  let requestId = positional[1];
  if (!requestId) {
    const healthResponse = await signalCoordinator.run(
      (signal) => client.request("health", { signal }),
    );
    const health = requireBridgeSuccess(healthResponse);
    requestId = health.active_request_id ? String(health.active_request_id) : "";
  }
  if (!requestId) throw new CliError("ACTIVE_REQUEST_REQUIRED", "No active browser request was found");
  const response = await signalCoordinator.run(
    (signal) => client.request("browser.stop", {
      leaseId: session.leaseId,
      targetRequestId: requestId,
      timeoutMs: STOP_REQUEST_TIMEOUT_MS,
      signal,
    }),
  );
  return success("stop", requireBridgeSuccess(response));
}

async function resetCommand({ positional, stored, client, signalCoordinator }) {
  requirePositionals(positional, 1);
  const result = await withLease({
    stored,
    client,
    signalCoordinator,
    operation: async (leaseId) => {
      const response = await signalCoordinator.run(
        (signal) => client.request("browser.reset", { leaseId, signal }),
        { abortOnSignal: false },
      );
      return requireBridgeSuccess(response);
    },
  });
  const failure = browserFailure(result);
  if (failure) throw failure;
  return success("reset", result);
}

async function run(argv, env = process.env) {
  const { positional, flags } = parseArgs(argv);
  if (flags.has("version")) {
    if (positional.length > 0) throw new CliError("UNEXPECTED_ARGUMENT", "--version accepts no command");
    return success("version", { cli_version: CLI_VERSION, output_version: OUTPUT_VERSION });
  }
  const command = normalizeCommand(positional[0]);
  if (command === "help" || flags.has("help")) {
    printHelp();
    return undefined;
  }
  if (!command) {
    printHelp();
    throw new CliError("COMMAND_REQUIRED", "A command is required");
  }
  if (!(command in COMMAND_FLAGS)) throw new CliError("UNKNOWN_COMMAND", `Unknown command: ${command}`);
  validateFlags(command, flags);

  const { config: stored } = await loadStoredConfig(env);
  if (command === "pair" || command === "configure") {
    return pairCommand(command, positional, flags, stored, env);
  }
  if (
    command === "session" &&
    ["status", "forget"].includes(normalizeCommand(positional[1]))
  ) {
    return sessionCommand({ positional, flags, stored, env });
  }

  const connection = selectedConnection({ flags, stored, env, requireToken: true });
  const client = clientFor(connection, flags);
  const signalCoordinator = new SignalCoordinator();
  try {
    if (command === "session") {
      return await sessionCommand({ positional, flags, stored, env, client, signalCoordinator });
    }
    if (command === "health") {
      requirePositionals(positional, 1);
      const response = await signalCoordinator.run(
        (signal) => client.request("health", { signal }),
      );
      const result = requireBridgeSuccess(response);
      if (result.protocol !== PROTOCOL_VERSION) {
        throw new CliError(
          "UNSUPPORTED_PROTOCOL",
          `Expected browser protocol ${PROTOCOL_VERSION}, received ${String(result.protocol)}`,
          { exitCode: EXIT.BRIDGE, category: "bridge" },
        );
      }
      return success("health", result);
    }
    if (command === "rotate") {
      requirePositionals(positional, 1);
      const response = await signalCoordinator.run(
        (signal) => client.request("pairing.rotate", { signal }),
      );
      const result = requireBridgeSuccess(response);
      const token = String(result.token ?? "");
      if (!token) {
        throw new CliError("ROTATED_TOKEN_MISSING", "Bridge did not return a replacement credential", {
          exitCode: EXIT.BRIDGE,
          category: "bridge",
        });
      }
      const target = await saveStoredConfig({ host: connection.host, port: connection.port, token }, env);
      delete result.token;
      return success("rotate", { ...result, config_path: target });
    }
    if (command === "revoke") {
      requirePositionals(positional, 1);
      const response = await signalCoordinator.run(
        (signal) => client.request("pairing.revoke", { signal }),
      );
      const result = requireBridgeSuccess(response);
      const target = await saveStoredConfig({ host: connection.host, port: connection.port }, env);
      return success("revoke", { ...result, config_path: target });
    }
    if (command === "stop") {
      return await stopCommand({ positional, stored, client, signalCoordinator });
    }
    if (command === "reset") {
      return await resetCommand({ positional, stored, client, signalCoordinator });
    }
    if (ACTION_COMMANDS.has(command)) {
      return await browserActionCommand({
        command,
        positional,
        flags,
        stored,
        client,
        signalCoordinator,
      });
    }
    throw new CliError("UNKNOWN_COMMAND", `Unknown command: ${command}`);
  } finally {
    signalCoordinator.close();
  }
}

function success(command, result) {
  return { version: OUTPUT_VERSION, ok: true, command, result };
}

function errorPayload(command, error) {
  if (error instanceof SignalInterruption) {
    return {
      version: OUTPUT_VERSION,
      ok: false,
      command: command || null,
      error: {
        category: "interrupted",
        code: "INTERRUPTED",
        message: error.message,
        signal: error.signal,
        ...(error.details ? { details: error.details } : {}),
      },
    };
  }
  const normalized = normalizeError(error);
  return {
    version: OUTPUT_VERSION,
    ok: false,
    command: command || null,
    error: {
      category: normalized.category,
      code: normalized.code,
      message: normalized.message,
      ...(normalized.details ? { details: normalized.details } : {}),
    },
    ...(normalized.result ? { result: normalized.result } : {}),
  };
}

function normalizeError(error) {
  if (error instanceof CliError) return error;
  if (error instanceof ConfigError) {
    return new CliError(error.code, error.message, { exitCode: EXIT.USAGE, category: "usage" });
  }
  if (error instanceof BridgeTransportError) {
    const protocolCodes = new Set(["INVALID_RESPONSE", "RESPONSE_TOO_LARGE"]);
    const usageCodes = new Set(["REQUEST_TOO_LARGE"]);
    if (usageCodes.has(error.code)) {
      return new CliError(error.code, error.message, { exitCode: EXIT.USAGE, category: "usage" });
    }
    if (protocolCodes.has(error.code)) {
      return new CliError(error.code, error.message, { exitCode: EXIT.BRIDGE, category: "bridge" });
    }
    return new CliError(error.code, error.message, { exitCode: EXIT.TRANSPORT, category: "transport" });
  }
  return new CliError("UNEXPECTED_ERROR", "Eta Browser CLI failed unexpectedly", {
    exitCode: EXIT.BRIDGE,
    category: "bridge",
  });
}

function printHelp() {
  process.stderr.write(`Eta Browser CLI ${CLI_VERSION}\n\n`);
  process.stderr.write("Usage: eta-browser <command> [arguments] [flags]\n\n");
  process.stderr.write("Setup and bridge:\n");
  process.stderr.write("  pair                         Generate/store a pairing token; print it once\n");
  process.stderr.write("  health                       Inspect bridge, lease, and browser state\n");
  process.stderr.write("  rotate | revoke              Rotate or revoke the stored credential\n\n");
  process.stderr.write("Session lifecycle:\n");
  process.stderr.write("  session acquire              Acquire and persist the single browser lease\n");
  process.stderr.write("  session release              Release the persisted lease\n");
  process.stderr.write("  session status | forget      Inspect or locally forget persisted lease state\n");
  process.stderr.write("  stop [REQUEST_ID]             Cancel the active request for the stored lease\n");
  process.stderr.write("  reset                         Clear page, cookies, and history\n\n");
  process.stderr.write("Browser actions:\n");
  process.stderr.write("  navigate URL                  Navigate to a URL\n");
  process.stderr.write("  get-readable                  Extract readable Markdown (alias: read)\n");
  process.stderr.write("  get-text                      Extract text, optionally under --selector\n");
  process.stderr.write("  find-elements                 Find interactive elements\n");
  process.stderr.write("  click                         Click --selector or coordinates\n");
  process.stderr.write("  type TEXT                     Type into --selector or coordinates\n");
  process.stderr.write("  scroll                        Scroll up/down, optionally under --selector\n");
  process.stderr.write("  screenshot --output PATH      Save a private screenshot file\n");
  process.stderr.write("  get-page-info                 Inspect page metadata (alias: page-info)\n");
  process.stderr.write("  go-back | go-forward | reload Navigate browser history\n");
  process.stderr.write("  wait-for-selector SELECTOR    Wait for a CSS selector\n");
  process.stderr.write("  action JSON                   Raw browser.execute params escape hatch\n\n");
  process.stderr.write("Global flags: --host --port --token --request-timeout-ms --help\n");
  process.stderr.write("JSON is written to stdout. Diagnostics and help are written to stderr.\n");
}

const parsedCommand = (() => {
  try {
    return normalizeCommand(parseArgs(process.argv.slice(2)).positional[0]);
  } catch {
    return null;
  }
})();

try {
  const payload = await run(process.argv.slice(2));
  if (payload) process.stdout.write(`${JSON.stringify(payload)}\n`);
} catch (error) {
  const payload = errorPayload(parsedCommand, error);
  process.stdout.write(`${JSON.stringify(payload)}\n`);
  const normalized = error instanceof SignalInterruption ? error : normalizeError(error);
  const code = error instanceof SignalInterruption ? "INTERRUPTED" : normalized.code;
  process.stderr.write(`eta-browser: ${code}: ${normalized.message}\n`);
  process.exitCode = error instanceof SignalInterruption ? error.exitCode : normalized.exitCode;
}
