import assert from "node:assert/strict";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import test from "node:test";
import { executeWebSearch } from "./web-search.mjs";

const TOKEN = "eta-browser-web-search-test-token-123456789";
const SEARCH_URL = "https://duckduckgo.com/?q=eta+browser+%26+pi";
const RESULT_LINK_SELECTOR = "[data-testid=result] h2 > a";
const RESULT_SNIPPET_SELECTOR = "[data-testid=result] [data-result=snippet]";

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

async function withConfig(port, block) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "eta-browser-web-search-"));
  const target = path.join(directory, "config.json");
  await writeFile(target, `${JSON.stringify({
    host: "127.0.0.1",
    port,
    token: TOKEN,
  })}\n`, { mode: 0o600 });
  const env = {
    ...process.env,
    ETA_BROWSER_CONFIG: target,
    ETA_BROWSER_ALLOW_NONSTANDARD_PORT: "1",
  };
  try {
    await block(env);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

function success(request, result) {
  return { id: request.id, ok: true, result };
}

function browserSuccess(request, action, extra = {}) {
  return success(request, {
    browser: { ok: true, action, status: "ok", ...extra },
    images: [],
  });
}

function browserFailure(request, action, code, message = code) {
  return success(request, {
    browser: { ok: false, action, status: "blocked", code, message },
    images: [],
  });
}

function rootNavigation(request, url = SEARCH_URL) {
  return browserSuccess(request, "navigate", {
    url,
    host: new URL(url).hostname,
    title: "eta browser & pi at DuckDuckGo",
  });
}

function rootPageInfo(request, url = `${SEARCH_URL}&ia=web`) {
  return browserSuccess(request, "get_page_info", {
    url,
    host: new URL(url).hostname,
    title: "eta browser & pi at DuckDuckGo",
  });
}

test("search uses one lease and returns bounded normalized root-SERP organic results", async () => {
  const methods = [];
  let actionIndex = 0;
  const destination = "https://example.com/docs?q=eta";
  const redirectDestination = "http://second.example/path";
  const redirect = `https://duckduckgo.com/l/?uddg=${encodeURIComponent(redirectDestination)}&rut=test`;
  const adUrl = "https://duckduckgo.com/y.js?ad_domain=ads.example&click_metadata=secret-ad-token";

  await withBridge((request) => {
    methods.push(request.method);
    if (request.method === "browser.session.acquire") {
      return success(request, { lease_id: "temporary-secret-search-lease" });
    }
    if (request.method === "browser.session.release") {
      assert.equal(request.lease_id, "temporary-secret-search-lease");
      return success(request, { released: true });
    }

    assert.equal(request.method, "browser.execute");
    assert.equal(request.lease_id, "temporary-secret-search-lease");
    const index = actionIndex++;
    if (index === 0) {
      assert.deepEqual(request.params, {
        action: "navigate",
        url: SEARCH_URL,
      });
      return rootNavigation(request);
    }
    if (index === 1) {
      assert.deepEqual(request.params, {
        action: "find_elements",
        selector: RESULT_LINK_SELECTOR,
      });
      return browserSuccess(request, "find_elements", {
        element_count: 4,
        elements: [
          { text: "Sponsored result", href: adUrl },
          { text: "Eta Browser docs", href: destination },
          { text: "Second result", href: redirect },
          { text: "Duplicate", href: destination },
        ],
      });
    }
    if (index === 2) {
      assert.deepEqual(request.params, {
        action: "find_elements",
        selector: RESULT_SNIPPET_SELECTOR,
      });
      return browserSuccess(request, "find_elements", {
        element_count: 4,
        elements: [
          { text: "Advertisement copy." },
          { text: "Standalone Android browser for local agent control." },
          { text: "A second organic result." },
          { text: "Duplicate snippet." },
        ],
      });
    }
    if (index === 3) {
      assert.deepEqual(request.params, {
        action: "get_text",
        max_chars: 4000,
      });
      return browserSuccess(request, "get_text", { text: "Search results" });
    }
    assert.equal(index, 4);
    assert.deepEqual(request.params, { action: "get_page_info" });
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    const result = await executeWebSearch({
      query: "  eta browser & pi  ",
      max_results: 2,
    }, undefined, env);
    const payload = JSON.parse(result.content[0].text);
    assert.deepEqual(payload, {
      query: "eta browser & pi",
      provider: "duckduckgo",
      results: [
        {
          title: "Eta Browser docs",
          url: destination,
          snippet: "Standalone Android browser for local agent control.",
        },
        {
          title: "Second result",
          url: redirectDestination,
          snippet: "A second organic result.",
        },
      ],
      result_count: 2,
    });
    assert.deepEqual(result.details, {
      provider: "duckduckgo",
      surface: "duckduckgo-root",
      result_count: 2,
      snippet_count: 2,
      max_results: 2,
      side_effect: "shared_browser_left_on_search_results",
    });
    assert.doesNotMatch(
      JSON.stringify(result),
      /temporary-secret-search-lease|eta-browser-web-search-test-token|secret-ad-token|Sponsored result/,
    );
  }));

  assert.deepEqual(methods, [
    "browser.session.acquire",
    "browser.execute",
    "browser.execute",
    "browser.execute",
    "browser.execute",
    "browser.execute",
    "browser.session.release",
  ]);
});

test("dynamic root results settle in place without a second navigation", async () => {
  const methods = [];
  let extractionAttempt = 0;
  let navigationCount = 0;
  await withBridge((request) => {
    methods.push(request.method);
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "settle-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    const action = request.params.action;
    if (action === "navigate") {
      navigationCount += 1;
      return rootNavigation(request);
    }
    if (action === "find_elements" && request.params.selector === RESULT_LINK_SELECTOR) {
      return browserSuccess(request, "find_elements", {
        elements: extractionAttempt === 0 ? [] : [
          { text: "Example Domain", href: "http://www.example.com/" },
        ],
      });
    }
    if (action === "find_elements") {
      return browserSuccess(request, "find_elements", {
        elements: extractionAttempt === 0 ? [] : [
          { text: "This domain is for use in illustrative examples." },
        ],
      });
    }
    if (action === "get_text") {
      const text = extractionAttempt === 0 ? "DuckDuckGo" : "Search results";
      extractionAttempt += 1;
      return browserSuccess(request, "get_text", { text });
    }
    assert.equal(action, "get_page_info");
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    const result = await executeWebSearch({ query: "eta", max_results: 1 }, undefined, env);
    const payload = JSON.parse(result.content[0].text);
    assert.equal(payload.result_count, 1);
    assert.equal(payload.results[0].url, "http://www.example.com/");
    assert.equal(payload.results[0].snippet, "This domain is for use in illustrative examples.");
  }));
  assert.equal(methods.filter((method) => method === "browser.session.acquire").length, 1);
  assert.equal(methods.filter((method) => method === "browser.session.release").length, 1);
  assert.equal(navigationCount, 1);
  assert.equal(extractionAttempt, 2);
});

test("normal DuckDuckGo root no-results page returns an empty result list", async () => {
  let actionIndex = 0;
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "empty-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    const index = actionIndex++;
    if (index === 0) return rootNavigation(request);
    if (index < 3) return browserSuccess(request, "find_elements", { element_count: 0, elements: [] });
    if (index === 3) {
      return browserSuccess(request, "get_text", {
        text: "No results found for \"no-such-result-fixture\".",
      });
    }
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    const result = await executeWebSearch({ query: "no-such-result-fixture" }, undefined, env);
    const payload = JSON.parse(result.content[0].text);
    assert.deepEqual(payload.results, []);
    assert.equal(payload.result_count, 0);
    assert.equal(result.details.snippet_count, 0);
  }));
});

test("unrecognized or anti-bot root surfaces fail closed with SEARCH_BLOCKED", async () => {
  const methods = [];
  let actionIndex = 0;
  await withBridge((request) => {
    methods.push(request.method);
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "blocked-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    const index = actionIndex++;
    if (index === 0) return rootNavigation(request);
    if (index === 1) {
      return browserSuccess(request, "find_elements", {
        element_count: 1,
        elements: [{ text: "Challenge help", href: "https://example.com/fake-result" }],
      });
    }
    if (index === 2) return browserSuccess(request, "find_elements", { element_count: 0, elements: [] });
    if (index === 3) {
      return browserSuccess(request, "get_text", {
        text: "Unfortunately, bots use DuckDuckGo too. Please complete the following challenge.",
      });
    }
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "eta browser" }, undefined, env),
      /\[SEARCH_BLOCKED\] reason=challenge;/,
    );
  }));
  assert.equal(methods.at(-1), "browser.session.release");
});

test("incidental no-results wording without the query still fails closed", async () => {
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "incidental-empty-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    if (request.params.action === "navigate") return rootNavigation(request);
    if (request.params.action === "find_elements") {
      return browserSuccess(request, "find_elements", { elements: [] });
    }
    if (request.params.action === "get_text") {
      return browserSuccess(request, "get_text", {
        text: "DuckDuckGo temporarily unavailable. No more results could be loaded.",
      });
    }
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "eta" }, undefined, env),
      /\[SEARCH_BLOCKED\] reason=surface_unrecognized;/,
    );
  }));
});

test("empty unrecognized surface is not misreported as no results", async () => {
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "unknown-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    if (request.params.action === "navigate") return rootNavigation(request);
    if (request.params.action === "find_elements") {
      return browserSuccess(request, "find_elements", { elements: [] });
    }
    if (request.params.action === "get_text") {
      return browserSuccess(request, "get_text", { text: "DuckDuckGo" });
    }
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "unknown fixture" }, undefined, env),
      /\[SEARCH_BLOCKED\] reason=surface_unrecognized;/,
    );
  }));
});

test("snippet count mismatch omits uncertain snippets instead of shifting association", async () => {
  let actionIndex = 0;
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "snippet-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    const index = actionIndex++;
    if (index === 0) return rootNavigation(request);
    if (index === 1) {
      return browserSuccess(request, "find_elements", {
        elements: [
          { text: "First", href: "https://first.example/" },
          { text: "Second", href: "https://second.example/" },
        ],
      });
    }
    if (index === 2) {
      return browserSuccess(request, "find_elements", {
        elements: [{ text: "Only the second result has a snippet." }],
      });
    }
    if (index === 3) return browserSuccess(request, "get_text", { text: "Search results" });
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    const result = await executeWebSearch({ query: "snippet association", max_results: 2 }, undefined, env);
    const payload = JSON.parse(result.content[0].text);
    assert.equal(payload.results[0].snippet, null);
    assert.equal(payload.results[1].snippet, null);
    assert.equal(result.details.snippet_count, 0);
  }));
});

test("wrong initial origin stops before root DOM extraction", async () => {
  const methods = [];
  await withBridge((request) => {
    methods.push(request.method);
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "wrong-origin-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    assert.equal(request.method, "browser.execute");
    assert.equal(request.params.action, "navigate");
    return rootNavigation(request, "https://html.duckduckgo.com/html/?q=eta");
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "eta" }, undefined, env),
      /\[SEARCH_BLOCKED\] reason=wrong_origin;/,
    );
  }));
  assert.deepEqual(methods, [
    "browser.session.acquire",
    "browser.execute",
    "browser.session.release",
  ]);
});

test("final page origin is verified after extraction", async () => {
  let actionIndex = 0;
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "late-origin-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    const index = actionIndex++;
    if (index === 0) return rootNavigation(request);
    if (index === 1) {
      return browserSuccess(request, "find_elements", {
        elements: [{ text: "Result", href: "https://result.example/" }],
      });
    }
    if (index === 2) return browserSuccess(request, "find_elements", { elements: [{ text: "Snippet" }] });
    if (index === 3) return browserSuccess(request, "get_text", { text: "Search results" });
    return rootPageInfo(request, "https://example.net/interstitial");
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "eta" }, undefined, env),
      /\[SEARCH_BLOCKED\] reason=wrong_origin;/,
    );
  }));
});

for (const [code, reason] of [["HTTP_403", "provider_denied"], ["HTTP_429", "rate_limited"]]) {
  test(`${code} navigation failure maps to SEARCH_BLOCKED without retry`, async () => {
    const methods = [];
    await withBridge((request) => {
      methods.push(request.method);
      if (request.method === "browser.session.acquire") return success(request, { lease_id: `${code}-lease` });
      if (request.method === "browser.session.release") return success(request, { released: true });
      return browserFailure(request, "navigate", code, `DuckDuckGo returned ${code}`);
    }, async (port) => withConfig(port, async (env) => {
      await assert.rejects(
        executeWebSearch({ query: "eta" }, undefined, env),
        new RegExp(`\\[SEARCH_BLOCKED\\] reason=${reason};`),
      );
    }));
    assert.deepEqual(methods, [
      "browser.session.acquire",
      "browser.execute",
      "browser.session.release",
    ]);
  });
}

test("other navigation failures preserve their bridge code", async () => {
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "http-503-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    return browserFailure(request, "navigate", "HTTP_503", "DuckDuckGo unavailable");
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "eta" }, undefined, env),
      /\[HTTP_503\] DuckDuckGo unavailable/,
    );
  }));
});

test("serialized search output stays within the bounded text envelope", async () => {
  const links = Array.from({ length: 10 }, (_, index) => ({
    text: `Result ${index} ${"界".repeat(160)}`,
    href: `https://result-${index}.example/${"a".repeat(270)}`,
  }));
  const snippets = links.map(() => ({ text: `Snippet ${"界".repeat(160)}` }));
  let actionIndex = 0;
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "payload-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    const index = actionIndex++;
    if (index === 0) return rootNavigation(request);
    if (index === 1) return browserSuccess(request, "find_elements", { elements: links });
    if (index === 2) return browserSuccess(request, "find_elements", { elements: snippets });
    if (index === 3) return browserSuccess(request, "get_text", { text: "Search results" });
    return rootPageInfo(request);
  }, async (port) => withConfig(port, async (env) => {
    const result = await executeWebSearch({ query: "payload budget", max_results: 10 }, undefined, env);
    const payload = JSON.parse(result.content[0].text);
    assert.ok(Buffer.byteLength(result.content[0].text, "utf8") <= 12_000);
    assert.ok(payload.result_count > 0);
    assert.ok(payload.result_count < 10);
    assert.equal(result.details.result_count, payload.result_count);
  }));
});

test("invalid search inputs fail before config or transport access", async () => {
  await assert.rejects(
    executeWebSearch({ query: "   " }, undefined, {}),
    /\[QUERY_REQUIRED\]/,
  );
  await assert.rejects(
    executeWebSearch({ query: "x".repeat(501) }, undefined, {}),
    /\[QUERY_TOO_LONG\]/,
  );
  await assert.rejects(
    executeWebSearch({ query: "eta", max_results: 0 }, undefined, {}),
    /\[INVALID_MAX_RESULTS\]/,
  );
});

test("Pi installer includes the web search runtime module", async () => {
  const installer = await readFile(new URL("../../scripts/install-pi.sh", import.meta.url), "utf8");
  assert.match(installer, /cp "\$EXTENSION_SOURCE\/web-search\.mjs" "\$EXTENSION_TARGET\/web-search\.mjs"/);
  assert.match(installer, /eta_browser_use or web_search/);
});

test("cancellation during same-page settle wait releases without another navigation", async () => {
  const controller = new AbortController();
  const actions = [];
  await withBridge((request) => {
    if (request.method === "browser.session.acquire") return success(request, { lease_id: "settle-cancel-lease" });
    if (request.method === "browser.session.release") return success(request, { released: true });
    assert.equal(request.method, "browser.execute");
    actions.push(request.params.action);
    if (request.params.action === "navigate") return rootNavigation(request);
    if (request.params.action === "find_elements") {
      return browserSuccess(request, "find_elements", { elements: [] });
    }
    assert.equal(request.params.action, "get_text");
    setTimeout(() => controller.abort(), 20);
    return browserSuccess(request, "get_text", { text: "DuckDuckGo" });
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "settle cancellation" }, controller.signal, env),
      /\[CANCELLED\]/,
    );
  }));
  assert.deepEqual(actions, ["navigate", "find_elements", "find_elements", "get_text"]);
});

test("cancellation stops the exact active search action and releases the lease", async () => {
  const controller = new AbortController();
  let actionIndex = 0;
  let activeRequest;
  let stopRequest;
  const methods = [];

  await withBridge((request) => {
    methods.push(request.method);
    if (request.method === "browser.session.acquire") {
      return success(request, { lease_id: "cancel-search-lease" });
    }
    if (request.method === "browser.execute") {
      const index = actionIndex++;
      if (index === 0) return rootNavigation(request);
      activeRequest = request;
      setTimeout(() => controller.abort(), 20);
      return undefined;
    }
    if (request.method === "browser.stop") {
      stopRequest = request;
      return success(request, { stopped: true });
    }
    assert.equal(request.method, "browser.session.release");
    return success(request, { released: true });
  }, async (port) => withConfig(port, async (env) => {
    await assert.rejects(
      executeWebSearch({ query: "eta browser" }, controller.signal, env),
      /\[CANCELLED\]/,
    );
  }));

  assert.equal(stopRequest.lease_id, activeRequest.lease_id);
  assert.equal(stopRequest.request_id, activeRequest.id);
  assert.ok(methods.indexOf("browser.stop") < methods.indexOf("browser.session.release"));
  assert.equal(methods.at(-1), "browser.session.release");
});
