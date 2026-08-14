import net from "node:net";
import { randomUUID } from "node:crypto";

export const DEFAULT_HOST = "127.0.0.1";
export const DEFAULT_PORT = 18_765;
export const PROTOCOL_VERSION = 2;
export const DEFAULT_REQUEST_TIMEOUT_MS = 45_000;
export const STOP_REQUEST_TIMEOUT_MS = 5_000;
export const MAX_REQUEST_BYTES = 64 * 1024;
export const MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

export class BridgeTransportError extends Error {
  constructor(code, message, cause = undefined) {
    super(message, cause ? { cause } : undefined);
    this.name = "BridgeTransportError";
    this.code = code;
  }
}

export function createRequestId() {
  return randomUUID();
}

export class EtaBrowserClient {
  constructor({
    host = DEFAULT_HOST,
    port = DEFAULT_PORT,
    token,
    clientId = "eta-browser-cli",
    requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
  }) {
    this.host = host;
    this.port = port;
    this.token = token;
    this.clientId = clientId;
    this.requestTimeoutMs = requestTimeoutMs;
  }

  async request(method, {
    params = undefined,
    leaseId = undefined,
    requestId = createRequestId(),
    targetRequestId = undefined,
    timeoutMs = this.requestTimeoutMs,
    signal = undefined,
  } = {}) {
    const body = {
      id: requestId,
      token: this.token,
      client_id: this.clientId,
      method,
      ...(leaseId === undefined ? {} : { lease_id: leaseId }),
      ...(targetRequestId === undefined ? {} : { request_id: targetRequestId }),
      ...(params === undefined ? {} : { params }),
    };
    const frame = `${JSON.stringify(body)}\n`;
    if (Buffer.byteLength(frame, "utf8") > MAX_REQUEST_BYTES) {
      throw new BridgeTransportError(
        "REQUEST_TOO_LARGE",
        `Bridge request exceeds ${MAX_REQUEST_BYTES} bytes`,
      );
    }

    const response = await sendFrame({
      host: this.host,
      port: this.port,
      frame,
      timeoutMs,
      signal,
    });
    if (!response || typeof response !== "object" || Array.isArray(response)) {
      throw new BridgeTransportError("INVALID_RESPONSE", "Bridge response must be a JSON object");
    }
    if (typeof response.ok !== "boolean") {
      throw new BridgeTransportError("INVALID_RESPONSE", "Bridge response is missing ok");
    }
    if (response.id !== null && response.id !== undefined && response.id !== requestId) {
      throw new BridgeTransportError("INVALID_RESPONSE", "Bridge response id does not match request");
    }
    return response;
  }
}

function sendFrame({ host, port, frame, timeoutMs, signal }) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    const chunks = [];
    let bytes = 0;
    let settled = false;

    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener("abort", abort);
      socket.destroy();
      callback(value);
    };

    const abort = () => {
      finish(
        reject,
        new BridgeTransportError("REQUEST_ABORTED", "Bridge request was aborted"),
      );
    };

    if (signal?.aborted) {
      abort();
      return;
    }
    signal?.addEventListener("abort", abort, { once: true });

    socket.setNoDelay(true);
    socket.setTimeout(timeoutMs);
    socket.on("connect", () => socket.end(frame));
    socket.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > MAX_RESPONSE_BYTES) {
        finish(
          reject,
          new BridgeTransportError(
            "RESPONSE_TOO_LARGE",
            `Bridge response exceeds ${MAX_RESPONSE_BYTES} bytes`,
          ),
        );
        return;
      }
      chunks.push(chunk);
    });
    socket.on("timeout", () => {
      finish(
        reject,
        new BridgeTransportError("REQUEST_TIMEOUT", "Bridge request timed out"),
      );
    });
    socket.on("error", (error) => {
      finish(
        reject,
        new BridgeTransportError("CONNECTION_FAILED", "Unable to reach Eta Browser bridge", error),
      );
    });
    socket.on("end", () => {
      if (settled) return;
      const raw = Buffer.concat(chunks).toString("utf8").trim();
      if (!raw) {
        finish(
          reject,
          new BridgeTransportError("EMPTY_RESPONSE", "Bridge returned an empty response"),
        );
        return;
      }
      try {
        finish(resolve, JSON.parse(raw));
      } catch (error) {
        finish(
          reject,
          new BridgeTransportError("INVALID_RESPONSE", "Bridge returned invalid JSON", error),
        );
      }
    });
  });
}
