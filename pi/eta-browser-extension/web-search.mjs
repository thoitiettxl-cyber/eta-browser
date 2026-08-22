import { executeEtaBrowserWorkflow } from "./core.mjs";

const PROVIDER = "duckduckgo";
const SEARCH_ENDPOINT = "https://duckduckgo.com/";
const SEARCH_ORIGIN = new URL(SEARCH_ENDPOINT).origin;
const RESULT_LINK_SELECTOR = "[data-testid=result] h2 > a";
const RESULT_SNIPPET_SELECTOR = "[data-testid=result] [data-result=snippet]";
const DEFAULT_MAX_RESULTS = 5;
const MAX_RESULTS = 10;
const MAX_QUERY_CHARS = 500;
const MAX_RESULT_URL_CHARS = 320;
const MAX_OUTPUT_BYTES = 12_000;
const MAX_SETTLE_ATTEMPTS = 4;
const SETTLE_DELAY_MS = 250;
const BLOCKED_PATTERNS = [
  /unfortunately, bots use duckduckgo too/i,
  /please complete the following challenge/i,
  /verify (?:that )?you are (?:a )?human/i,
];

export async function executeWebSearch(input, signal, env = process.env) {
  const { query, maxResults } = validateSearchInput(input);
  const searchUrl = buildSearchUrl(query);
  let evidence;

  try {
    evidence = await executeEtaBrowserWorkflow(
      async (execute) => {
        const navigation = browserPayload(await execute({ action: "navigate", url: searchUrl }));
        requireSearchOrigin(navigation.url);

        let extraction;
        for (let attempt = 0; attempt < MAX_SETTLE_ATTEMPTS; attempt += 1) {
          const links = browserPayload(await execute({
            action: "find_elements",
            selector: RESULT_LINK_SELECTOR,
          })).elements;
          const snippets = browserPayload(await execute({
            action: "find_elements",
            selector: RESULT_SNIPPET_SELECTOR,
          })).elements;
          const pageText = cleanText(browserPayload(await execute({
            action: "get_text",
            max_chars: 4000,
          })).text, 4000);
          extraction = { links, snippets, pageText };
          if (hasSettledSurface(extraction, query) || attempt + 1 === MAX_SETTLE_ATTEMPTS) break;
          await waitForSettle(signal);
        }
        const finalPage = browserPayload(await execute({ action: "get_page_info" }));
        requireSearchOrigin(finalPage.url);

        return extraction;
      },
      signal,
      env,
      { maxActions: 14, requestTimeoutMs: 60_000 },
    );
  } catch (error) {
    if (error?.publicCode === "HTTP_403") {
      throw blockedSearchError("provider_denied", "DuckDuckGo rejected the search request");
    }
    if (error?.publicCode === "HTTP_429") {
      throw blockedSearchError("rate_limited", "DuckDuckGo rate-limited the search request");
    }
    throw error;
  }

  const linkCandidates = Array.isArray(evidence.links) ? evidence.links : [];
  const results = collectResults(linkCandidates, evidence.snippets, maxResults);

  if (BLOCKED_PATTERNS.some((pattern) => pattern.test(evidence.pageText))) {
    throw blockedSearchError("challenge", "DuckDuckGo exposed an anti-bot challenge");
  }
  if (results.length === 0 && linkCandidates.length > 0) {
    throw blockedSearchError("surface_unrecognized", "DuckDuckGo result links were not safe to return");
  }
  if (results.length === 0 && !isNoResultsPage(evidence.pageText, query)) {
    throw blockedSearchError("surface_unrecognized", "DuckDuckGo did not expose a recognizable result surface");
  }

  const { payload, text } = boundedPayload(query, results);
  const snippetCount = payload.results.filter((result) => result.snippet).length;
  return {
    content: [{ type: "text", text }],
    details: {
      provider: PROVIDER,
      surface: "duckduckgo-root",
      result_count: payload.result_count,
      snippet_count: snippetCount,
      max_results: maxResults,
      side_effect: "shared_browser_left_on_search_results",
    },
  };
}

function validateSearchInput(input) {
  const rawQuery = typeof input?.query === "string" ? input.query.trim() : "";
  if (!rawQuery) throw searchError("QUERY_REQUIRED", "web_search requires a nonblank query");
  if (rawQuery.length > MAX_QUERY_CHARS) {
    throw searchError("QUERY_TOO_LONG", `web_search query must not exceed ${MAX_QUERY_CHARS} characters`);
  }
  const maxResults = input.max_results ?? DEFAULT_MAX_RESULTS;
  if (!Number.isInteger(maxResults) || maxResults < 1 || maxResults > MAX_RESULTS) {
    throw searchError("INVALID_MAX_RESULTS", `max_results must be an integer from 1 to ${MAX_RESULTS}`);
  }
  return {
    query: rawQuery.replace(/\s+/g, " "),
    maxResults,
  };
}

function buildSearchUrl(query) {
  const url = new URL(SEARCH_ENDPOINT);
  url.searchParams.set("q", query);
  return url.href;
}

function requireSearchOrigin(value) {
  let parsed;
  try {
    parsed = new URL(String(value || ""));
  } catch {
    throw blockedSearchError("wrong_origin", "DuckDuckGo returned an invalid page URL");
  }
  if (parsed.origin !== SEARCH_ORIGIN) {
    throw blockedSearchError("wrong_origin", "DuckDuckGo navigation left the approved search origin");
  }
}

function browserPayload(output) {
  const text = output?.content?.find((item) => item?.type === "text")?.text;
  try {
    const payload = JSON.parse(text);
    return payload && typeof payload === "object" && !Array.isArray(payload) ? payload : {};
  } catch {
    throw searchError("SEARCH_RESPONSE_INVALID", "Eta Browser returned an invalid search response");
  }
}

function collectResults(linkValue, snippetValue, maxResults) {
  const links = Array.isArray(linkValue) ? linkValue : [];
  const snippets = Array.isArray(snippetValue) ? snippetValue : [];
  const snippetsAligned = links.length > 0 && snippets.length === links.length;
  const results = [];
  const seen = new Set();

  for (let index = 0; index < links.length && results.length < maxResults; index += 1) {
    const title = cleanText(links[index]?.text ?? links[index]?.aria_label, 240);
    const url = normalizeResultUrl(links[index]?.href);
    if (!title || !url || seen.has(url)) continue;
    seen.add(url);
    const snippet = snippetsAligned ? cleanText(snippets[index]?.text, 240) : "";
    results.push({
      title,
      url,
      snippet: snippet || null,
    });
  }
  return results;
}

function normalizeResultUrl(value) {
  if (typeof value !== "string" || !value.trim()) return null;
  const rawValue = value.trim();
  if (rawValue.length >= MAX_RESULT_URL_CHARS) return null;
  let parsed;
  try {
    parsed = new URL(rawValue, SEARCH_ENDPOINT);
  } catch {
    return null;
  }

  if (isDuckDuckGoHost(parsed.hostname)) {
    if (parsed.origin !== SEARCH_ORIGIN || parsed.pathname !== "/l/") return null;
    const destination = parsed.searchParams.get("uddg");
    if (!destination) return null;
    try {
      parsed = new URL(destination);
    } catch {
      return null;
    }
    if (isDuckDuckGoHost(parsed.hostname)) return null;
  }

  if (!new Set(["http:", "https:"]).has(parsed.protocol)) return null;
  if (parsed.username || parsed.password) return null;
  const normalized = parsed.href;
  return normalized.length <= MAX_RESULT_URL_CHARS ? normalized : null;
}

function boundedPayload(query, results) {
  const boundedResults = [...results];
  let payload;
  let text;
  do {
    payload = {
      query,
      provider: PROVIDER,
      results: boundedResults,
      result_count: boundedResults.length,
    };
    text = JSON.stringify(payload);
    if (Buffer.byteLength(text, "utf8") <= MAX_OUTPUT_BYTES) break;
    boundedResults.pop();
  } while (boundedResults.length > 0);
  return { payload, text };
}

function isDuckDuckGoHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  return host === "duckduckgo.com" || host.endsWith(".duckduckgo.com");
}

function cleanText(value, limit) {
  if (typeof value !== "string") return "";
  return value.replace(/[\t\r\n ]+/g, " ").trim().slice(0, limit);
}

function isNoResultsPage(pageText, query) {
  const normalizedText = cleanText(pageText, 4000).toLocaleLowerCase("en-US");
  const normalizedQuery = cleanText(query, MAX_QUERY_CHARS).toLocaleLowerCase("en-US");
  const marker = "no results found for";
  const markerIndex = normalizedText.indexOf(marker);
  if (markerIndex < 0 || !normalizedQuery) return false;
  const evidence = normalizedText.slice(markerIndex + marker.length).trimStart();
  return evidence.startsWith(normalizedQuery) ||
    evidence.startsWith(`"${normalizedQuery}"`) ||
    evidence.startsWith(`“${normalizedQuery}”`) ||
    evidence.startsWith(`'${normalizedQuery}'`);
}

function hasSettledSurface(evidence, query) {
  const links = Array.isArray(evidence?.links) ? evidence.links : [];
  return links.length > 0 ||
    BLOCKED_PATTERNS.some((pattern) => pattern.test(evidence?.pageText || "")) ||
    isNoResultsPage(evidence?.pageText || "", query);
}

function waitForSettle(signal) {
  if (signal?.aborted) {
    return Promise.reject(searchError("CANCELLED", "Eta Browser operation was cancelled"));
  }
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      reject(searchError("CANCELLED", "Eta Browser operation was cancelled"));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, SETTLE_DELAY_MS);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function blockedSearchError(reason, message) {
  return searchError("SEARCH_BLOCKED", `reason=${reason}; ${message}`);
}

function searchError(code, message) {
  const error = new Error(`[${code}] ${message}`);
  error.publicCode = code;
  return error;
}
