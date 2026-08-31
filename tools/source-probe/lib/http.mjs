/** HTTP 工具：URL 规整、带超时的 fetch。仅用 Node 内置能力。 */

export const DEFAULT_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

/** 把用户输入规整为带协议、无尾斜杠的站点根地址。 */
export function normalizeBaseUrl(input) {
  let s = String(input).trim();
  if (!/^https?:\/\//i.test(s)) s = "https://" + s;
  const u = new URL(s); // 抛错则由调用方处理
  // 只取到 origin 作为站点根，路径信息留作参考但探测以 origin 为基准
  return u.origin;
}

/** 拼接相对/绝对路径到 base。 */
export function join(base, path) {
  try {
    return new URL(path, base.endsWith("/") ? base : base + "/").toString();
  } catch {
    return path;
  }
}

/**
 * 带超时的 GET，返回 { ok, status, headers, body, finalUrl, contentType }。
 * 不抛网络错误以外的异常；超时/网络失败抛 Error。
 */
export async function fetchText(url, { timeout = 10000, ua = DEFAULT_UA, headers = {} } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(url, {
      method: "GET",
      redirect: "follow",
      signal: ctrl.signal,
      headers: {
        "User-Agent": ua,
        Accept: "text/html,application/json,application/xml;q=0.9,*/*;q=0.8",
        ...headers,
      },
    });
    const contentType = res.headers.get("content-type") || "";
    // 限制读取大小，避免大页面拖慢
    const body = await readCapped(res, 1_500_000);
    return {
      ok: res.ok,
      status: res.status,
      headers: Object.fromEntries(res.headers.entries()),
      contentType,
      body,
      finalUrl: res.url || url,
    };
  } finally {
    clearTimeout(timer);
  }
}

async function readCapped(res, maxBytes) {
  const reader = res.body?.getReader?.();
  if (!reader) return await res.text();
  const chunks = [];
  let total = 0;
  const decoder = new TextDecoder("utf-8", { fatal: false });
  let out = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    out += decoder.decode(value, { stream: true });
    if (total >= maxBytes) {
      try { await reader.cancel(); } catch {}
      break;
    }
  }
  out += decoder.decode();
  return out;
}
