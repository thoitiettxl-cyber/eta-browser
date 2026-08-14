import { homedir } from "node:os";
import path from "node:path";
import {
  chmod,
  lstat,
  mkdir,
  readFile,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { DEFAULT_HOST, DEFAULT_PORT } from "./client.mjs";

export class ConfigError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "ConfigError";
    this.code = code;
  }
}

export function configPath(env = process.env) {
  if (env.ETA_BROWSER_CONFIG) return path.resolve(env.ETA_BROWSER_CONFIG);
  const base = env.XDG_CONFIG_HOME
    ? path.resolve(env.XDG_CONFIG_HOME)
    : path.join(homedir(), ".config");
  return path.join(base, "eta-browser", "config.json");
}

export async function loadStoredConfig(env = process.env) {
  const target = configPath(env);
  let metadata;
  try {
    metadata = await lstat(target);
  } catch (error) {
    if (error?.code === "ENOENT") return { path: target, config: {} };
    throw new ConfigError("ETA_BROWSER_CONFIG_UNREADABLE", "Unable to inspect Eta Browser config");
  }
  if (metadata.isSymbolicLink()) {
    throw new ConfigError("ETA_BROWSER_CONFIG_SYMLINK", "Refusing a symlink Eta Browser config");
  }
  if (!metadata.isFile()) {
    throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config is not a regular file");
  }
  if (typeof process.getuid === "function" && metadata.uid !== process.getuid()) {
    throw new ConfigError("ETA_BROWSER_CONFIG_OWNER", "Eta Browser config has the wrong owner");
  }
  try {
    if ((metadata.mode & 0o777) !== 0o600) await chmod(target, 0o600);
    const parsed = JSON.parse(await readFile(target, "utf8"));
    validateConfig(parsed);
    return { path: target, config: parsed };
  } catch (error) {
    if (error instanceof ConfigError) throw error;
    if (error instanceof SyntaxError) {
      throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config is not valid JSON");
    }
    throw new ConfigError("ETA_BROWSER_CONFIG_UNREADABLE", "Unable to read Eta Browser config");
  }
}

export async function saveStoredConfig(config, env = process.env) {
  validateConfig(config);
  const target = configPath(env);
  const directory = path.dirname(target);
  const temporary = `${target}.${process.pid}.${randomUUID()}.tmp`;
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const directoryMetadata = await lstat(directory);
  if (directoryMetadata.isSymbolicLink() || !directoryMetadata.isDirectory()) {
    throw new ConfigError("ETA_BROWSER_CONFIG_DIRECTORY", "Eta Browser config directory is unsafe");
  }
  if (typeof process.getuid === "function" && directoryMetadata.uid !== process.getuid()) {
    throw new ConfigError("ETA_BROWSER_CONFIG_OWNER", "Eta Browser config directory has the wrong owner");
  }
  await chmod(directory, 0o700);
  try {
    try {
      const targetMetadata = await lstat(target);
      if (targetMetadata.isSymbolicLink() || !targetMetadata.isFile()) {
        throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Refusing an unsafe Eta Browser config target");
      }
      if (typeof process.getuid === "function" && targetMetadata.uid !== process.getuid()) {
        throw new ConfigError("ETA_BROWSER_CONFIG_OWNER", "Eta Browser config has the wrong owner");
      }
    } catch (error) {
      if (error instanceof ConfigError) throw error;
      if (error?.code !== "ENOENT") {
        throw new ConfigError("ETA_BROWSER_CONFIG_UNWRITABLE", "Unable to inspect Eta Browser config");
      }
    }
    await writeFile(temporary, `${JSON.stringify(config, null, 2)}\n`, {
      mode: 0o600,
      flag: "wx",
    });
    await rename(temporary, target);
    await chmod(target, 0o600);
    return target;
  } catch (error) {
    await rm(temporary, { force: true }).catch(() => undefined);
    if (error instanceof ConfigError) throw error;
    throw new ConfigError("ETA_BROWSER_CONFIG_UNWRITABLE", "Unable to write Eta Browser config");
  }
}

export function selectedConnection({
  flags,
  stored,
  env = process.env,
  requireToken,
  preferStored = true,
}) {
  const environmentHost = env.ETA_BROWSER_HOST || undefined;
  const environmentToken = env.ETA_BROWSER_TOKEN || undefined;
  const host = String(
    flags.get("host") ??
      (preferStored ? stored.host ?? environmentHost : environmentHost ?? stored.host) ??
      DEFAULT_HOST,
  );
  const rawPort = flags.get("port") ?? (
    preferStored
      ? stored.port ?? env.ETA_BROWSER_PORT ?? DEFAULT_PORT
      : env.ETA_BROWSER_PORT ?? stored.port ?? DEFAULT_PORT
  );
  const port = parsePort(rawPort);
  const token = String(
    flags.get("token") ??
      (preferStored ? stored.token ?? environmentToken : environmentToken ?? stored.token) ??
      "",
  );

  if (host !== DEFAULT_HOST) {
    throw new ConfigError("LOOPBACK_HOST_REQUIRED", `Host must be ${DEFAULT_HOST}`);
  }
  if (port === undefined) {
    throw new ConfigError("ETA_BROWSER_PORT_INVALID", "Port must be an integer from 1 to 65535");
  }
  if (port !== DEFAULT_PORT && env.ETA_BROWSER_ALLOW_NONSTANDARD_PORT !== "1") {
    throw new ConfigError("FIXED_PORT_REQUIRED", `Port must be ${DEFAULT_PORT}`);
  }
  if (requireToken && !token) {
    throw new ConfigError("ETA_BROWSER_TOKEN_REQUIRED", "Run eta-browser pair first");
  }
  if (token && (token.length < 32 || token.length > 128)) {
    throw new ConfigError("ETA_BROWSER_TOKEN_INVALID", "Pairing token must contain 32 to 128 characters");
  }
  return { host, port, token };
}

export function storedSession(config) {
  const session = config.session;
  if (!session || typeof session !== "object" || Array.isArray(session)) return undefined;
  const leaseId = String(session.lease_id ?? "").trim();
  if (!leaseId) return undefined;
  return { leaseId };
}

export function withoutSession(config) {
  const next = { ...config };
  delete next.session;
  return next;
}

function validateConfig(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config must be a JSON object");
  }
  if (value.host !== undefined && typeof value.host !== "string") {
    throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config host must be a string");
  }
  if (value.port !== undefined && parsePort(value.port) === undefined) {
    throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config port is invalid");
  }
  if (value.token !== undefined && typeof value.token !== "string") {
    throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config token must be a string");
  }
  if (value.session !== undefined) {
    if (!value.session || typeof value.session !== "object" || Array.isArray(value.session)) {
      throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config session is invalid");
    }
    if (typeof value.session.lease_id !== "string" || !value.session.lease_id.trim()) {
      throw new ConfigError("ETA_BROWSER_CONFIG_INVALID", "Eta Browser config lease is invalid");
    }
  }
}

function parsePort(value) {
  if (value === undefined || value === null || value === "") return undefined;
  const text = String(value);
  if (!/^\d+$/.test(text)) return undefined;
  const parsed = Number.parseInt(text, 10);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65_535) return undefined;
  return parsed;
}
