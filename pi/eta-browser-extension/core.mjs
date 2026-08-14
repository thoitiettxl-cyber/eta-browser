import {
  EtaBrowserClient,
  PROTOCOL_VERSION,
  STOP_REQUEST_TIMEOUT_MS,
  createRequestId,
} from "eta-browser-cli";
import {
  loadStoredConfig,
  selectedConnection,
  storedSession,
} from "eta-browser-cli/config";

const CLIENT_ID = "eta-browser-cli";
const RELEASE_RETRY_MS = 100;
const PRESS_KEYS = new Set([
  "Enter", "Escape", "Tab", "Shift+Tab", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
  "Home", "End", "PageUp", "PageDown", "Space", "Backspace", "Delete", "Ctrl+A",
]);
const REF_PATTERN = /^@e[1-9][0-9]{0,8}$/;

export const BROWSER_ACTIONS = Object.freeze([
  "navigate",
  "get_readable",
  "get_text",
  "find_elements",
  "observe",
  "click",
  "type",
  "hover",
  "select",
  "press",
  "scroll",
  "screenshot",
  "get_page_info",
  "go_back",
  "go_forward",
  "reload",
  "wait_for_selector",
  "request_help",
  "console",
  "network",
]);

export const TOOL_ACTIONS = Object.freeze(["health", ...BROWSER_ACTIONS, "reset"]);

export async function executeEtaBrowser(input, signal, env = process.env) {
  validateInput(input);
  throwIfAborted(signal);

  const { config: stored } = await loadStoredConfig(env);
  const connection = selectedConnection({
    flags: new Map(),
    stored,
    env,
    requireToken: true,
  });
  const client = new EtaBrowserClient({
    ...connection,
    clientId: CLIENT_ID,
    requestTimeoutMs: requestTimeout(input),
  });

  if (input.action === "health") {
    const result = requireBridgeSuccess(await client.request("health", { signal }));
    if (result.protocol !== PROTOCOL_VERSION) {
      throw publicError("UNSUPPORTED_PROTOCOL", `Expected browser protocol ${PROTOCOL_VERSION}`);
    }
    return textResult("health", result, healthDetails(result));
  }

  return withLease(stored, client, async (leaseId) => {
    if (input.action === "reset") {
      throwIfAborted(signal);
      const result = requireBridgeSuccess(await client.request("browser.reset", { leaseId }));
      requireBrowserSuccess(result);
      return textResult("reset", result.browser ?? result, browserDetails(result));
    }

    const params = actionParams(input);
    const requestId = createRequestId();
    const result = await executeCancellable(client, leaseId, requestId, params, signal);
    requireBrowserSuccess(result);
    if (input.action === "screenshot") return screenshotResult(result);
    return textResult(input.action, result.browser ?? result, browserDetails(result));
  });
}

async function withLease(stored, client, operation) {
  const persistent = storedSession(stored);
  if (persistent) return operation(persistent.leaseId);

  const acquired = requireBridgeSuccess(await client.request("browser.session.acquire"));
  const leaseId = String(acquired.lease_id ?? "");
  if (!leaseId) throw publicError("LEASE_ID_MISSING", "Bridge did not return a browser lease");

  let value;
  let operationError;
  try {
    value = await operation(leaseId);
  } catch (error) {
    operationError = error;
  }
  const releaseError = await releaseTemporaryLease(client, leaseId);
  if (operationError) throw operationError;
  if (releaseError) throw releaseError;
  return value;
}

async function executeCancellable(client, leaseId, requestId, params, signal) {
  throwIfAborted(signal);
  const transportAbort = new AbortController();
  let cancellation;
  const cancel = () => {
    if (!cancellation) {
      cancellation = client.request("browser.stop", {
        leaseId,
        targetRequestId: requestId,
        timeoutMs: STOP_REQUEST_TIMEOUT_MS,
      }).catch(() => undefined).finally(() => transportAbort.abort());
    }
  };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    const response = await client.request("browser.execute", {
      leaseId,
      params,
      requestId,
      signal: transportAbort.signal,
    });
    if (signal?.aborted) {
      await cancellation;
      throw publicError("CANCELLED", "Eta Browser operation was cancelled");
    }
    return requireBridgeSuccess(response);
  } catch (error) {
    if (signal?.aborted) {
      await cancellation;
      throw publicError("CANCELLED", "Eta Browser operation was cancelled");
    }
    throw error;
  } finally {
    signal?.removeEventListener("abort", cancel);
  }
}

async function releaseTemporaryLease(client, leaseId) {
  const deadline = Date.now() + STOP_REQUEST_TIMEOUT_MS;
  let lastResponse;
  do {
    try {
      lastResponse = await client.request("browser.session.release", {
        leaseId,
        timeoutMs: STOP_REQUEST_TIMEOUT_MS,
      });
      if (lastResponse.ok) return undefined;
      if (lastResponse.error?.code !== "SESSION_BUSY") return bridgeError(lastResponse);
    } catch (error) {
      return sanitizeError(error);
    }
    await new Promise((resolve) => setTimeout(resolve, RELEASE_RETRY_MS));
  } while (Date.now() < deadline);
  return bridgeError(lastResponse);
}

function actionParams(input) {
  const params = compact({
    action: input.action,
    url: input.url,
    selector: input.selector,
    ref: input.ref,
    text: input.text,
    submit: input.submit,
    coordinate_x: input.coordinate_x,
    coordinate_y: input.coordinate_y,
    direction: input.direction,
    amount: input.amount,
    offset: input.offset,
    max_chars: input.max_chars,
    timeout_ms: input.timeout_ms,
    key: input.key,
    value: input.value,
    values: input.values,
    prompt: input.prompt,
    title: input.title,
    target_selector: input.target_selector,
    completion_criteria: input.completion_criteria,
    since: input.since,
    limit: input.limit,
    read_image: input.action === "screenshot" ? true : undefined,
  });
  return params;
}

function validateInput(input) {
  if (!input || !TOOL_ACTIONS.includes(input.action)) {
    throw publicError("INVALID_ACTION", "Unsupported Eta Browser action");
  }
  if (input.action === "navigate" && !nonBlank(input.url)) {
    throw publicError("URL_REQUIRED", "navigate requires url");
  }
  if (input.action === "wait_for_selector" && !nonBlank(input.selector)) {
    throw publicError("SELECTOR_REQUIRED", "wait_for_selector requires selector");
  }
  if (input.action === "type" && typeof input.text !== "string") {
    throw publicError("TEXT_REQUIRED", "type requires text");
  }
  if (["click", "type", "hover", "select"].includes(input.action)) validateTarget(input);
  if (input.action === "press") {
    if (!nonBlank(input.key)) throw publicError("KEY_REQUIRED", "press requires key");
    if (!PRESS_KEYS.has(input.key)) throw publicError("INVALID_KEY", "press key is unsupported");
    validateTarget(input, false);
  }
  if (input.action === "select") {
    const values = Array.isArray(input.values) ? input.values : [];
    if (typeof input.value !== "string" && values.length === 0) {
      throw publicError("VALUE_REQUIRED", "select requires value or values");
    }
    if (values.some((value) => typeof value !== "string") || values.length > 16) {
      throw publicError("INVALID_VALUES", "select values must contain at most 16 strings");
    }
    if ([input.value, ...values].some((value) => typeof value === "string" && value.length > 240)) {
      throw publicError("VALUE_TOO_LONG", "select values must not exceed 240 characters");
    }
  }
  if (input.action === "request_help") {
    if (!nonBlank(input.prompt)) throw publicError("PROMPT_REQUIRED", "request_help requires prompt");
    if (input.prompt.length > 600) throw publicError("PROMPT_TOO_LONG", "request_help prompt is too long");
    if (input.title !== undefined && input.title.length > 120) {
      throw publicError("TITLE_TOO_LONG", "request_help title is too long");
    }
    if (input.target_selector !== undefined && !nonBlank(input.target_selector)) {
      throw publicError("TARGET_SELECTOR_REQUIRED", "target_selector cannot be blank");
    }
    const criteria = input.completion_criteria;
    if (criteria && !nonBlank(criteria.url_contains) && !nonBlank(criteria.selector_exists)) {
      throw publicError(
        "COMPLETION_CRITERIA_REQUIRED",
        "completion_criteria requires url_contains or selector_exists",
      );
    }
    if (criteria?.match !== undefined && !["any", "all"].includes(criteria.match)) {
      throw publicError("INVALID_COMPLETION_MATCH", "completion match must be any or all");
    }
  }
  if (input.direction !== undefined && !["up", "down"].includes(input.direction)) {
    throw publicError("INVALID_DIRECTION", "direction must be up or down");
  }
}

function validateTarget(input, required = true) {
  const hasSelector = nonBlank(input.selector);
  const hasRef = nonBlank(input.ref);
  if (hasRef && !REF_PATTERN.test(input.ref)) {
    throw publicError("INVALID_REF", "ref must come from the latest observe result");
  }
  const hasX = input.coordinate_x !== undefined;
  const hasY = input.coordinate_y !== undefined;
  if (hasX !== hasY) {
    throw publicError("COORDINATE_PAIR_REQUIRED", "coordinate_x and coordinate_y must be provided together");
  }
  const targetCount = Number(hasSelector) + Number(hasRef) + Number(hasX);
  if (targetCount > 1) {
    throw publicError("AMBIGUOUS_TARGET", `${input.action} accepts exactly one target`);
  }
  if (required && targetCount === 0) {
    throw publicError("TARGET_REQUIRED", `${input.action} requires selector, ref, or coordinates`);
  }
}

function screenshotResult(result) {
  const browser = result.browser ?? {};
  const image = result.images?.[0];
  const match = /^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$/.exec(String(image?.data_url ?? ""));
  if (!match) throw publicError("SCREENSHOT_DATA_MISSING", "Bridge returned no valid screenshot image");
  const metadata = {
    action: "screenshot",
    status: browser.status,
    mime_type: image.mime_type ?? match[1],
    bytes: image.bytes,
    width: image.width,
    height: image.height,
  };
  return {
    content: [
      { type: "text", text: JSON.stringify(metadata) },
      { type: "image", data: match[2], mimeType: match[1] },
    ],
    details: metadata,
  };
}

function textResult(action, value, details) {
  return {
    content: [{ type: "text", text: JSON.stringify(value) }],
    details: { action, ...details },
  };
}

function healthDetails(result) {
  return {
    protocol: result.protocol,
    available: result.browser_available ?? result.available,
    is_user_controlling: result.is_user_controlling,
    human_handoff_pending: result.human_handoff_pending,
    session_leased: result.session_leased,
  };
}

function browserDetails(result) {
  const browser = result.browser ?? {};
  return {
    status: browser.status,
    ok: browser.ok,
    action: browser.action,
    image_count: Array.isArray(result.images) ? result.images.length : 0,
  };
}

function requireBridgeSuccess(response) {
  if (!response?.ok) throw bridgeError(response);
  return response.result ?? {};
}

function requireBrowserSuccess(result) {
  if (result?.browser?.ok === false) {
    throw publicError(
      String(result.browser.code ?? "BROWSER_FAILED"),
      String(result.browser.message ?? "Eta Browser action failed"),
    );
  }
}

function bridgeError(response) {
  return publicError(
    String(response?.error?.code ?? "BRIDGE_ERROR"),
    String(response?.error?.message ?? "Eta Browser bridge rejected the request"),
  );
}

function sanitizeError(error) {
  if (error?.publicCode) return error;
  return publicError(String(error?.code ?? "ETA_BROWSER_ERROR"), String(error?.message ?? "Eta Browser request failed"));
}

function publicError(code, message) {
  const error = new Error(`[${code}] ${message}`);
  error.publicCode = code;
  return error;
}

function requestTimeout(input) {
  const actionTimeout = Number.isInteger(input.timeout_ms) ? input.timeout_ms :
    (input.action === "request_help" ? 300_000 : 0);
  const maximum = input.action === "request_help" ? 310_000 : 60_000;
  return Math.max(45_000, Math.min(actionTimeout + 10_000, maximum));
}

function throwIfAborted(signal) {
  if (signal?.aborted) throw publicError("CANCELLED", "Eta Browser operation was cancelled");
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function compact(value) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined));
}
