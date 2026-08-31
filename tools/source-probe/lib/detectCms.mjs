/** 探测 maccms / 苹果CMS 标准采集 API（对应转源方式一，type 0/1）。 */

import { join, fetchText } from "./http.mjs";

// 常见 maccms/CMS 采集接口路径（JSON 与 XML）
const CANDIDATE_PATHS = [
  "api.php/provide/vod/?ac=list",
  "api.php/provide/vod/?ac=detail",
  "api.php/provide/vod/at/json/?ac=list",
  "api.php/provide/vod/at/xml/?ac=list",
  "provide/vod/?ac=list",
  "inc/api.php?ac=list",
  "api.php/provide/vod",
];

/**
 * 逐个尝试候选接口，命中即返回。
 * 返回 { found, type, apiUrl, format, sample, tried[] }。
 *   type: 1=JSON, 0=XML（对应 SourceBean.type）
 */
export async function probeCmsApi(base, { timeout, ua }) {
  const tried = [];
  for (const p of CANDIDATE_PATHS) {
    const url = join(base, p);
    try {
      const res = await fetchText(url, { timeout, ua });
      const verdict = classifyCmsResponse(res);
      tried.push({ url, status: res.status, verdict: verdict.kind });
      if (verdict.kind === "json" || verdict.kind === "xml") {
        return {
          found: true,
          type: verdict.kind === "json" ? 1 : 0,
          apiUrl: cleanApiUrl(url),
          format: verdict.kind,
          fields: verdict.fields,
          sample: verdict.sample,
          tried,
        };
      }
    } catch (e) {
      tried.push({ url, error: e.name === "AbortError" ? "timeout" : e.message });
    }
  }
  return { found: false, tried };
}

/** 去掉查询参数，得到可直接填入 SourceBean.api 的干净地址。 */
function cleanApiUrl(url) {
  try {
    const u = new URL(url);
    u.search = "";
    return u.toString();
  } catch {
    return url;
  }
}

/** 判定响应是否为标准 CMS 采集结果。 */
function classifyCmsResponse(res) {
  const body = (res.body || "").trim();
  if (!body) return { kind: "empty" };

  // JSON 判定：含 list 数组且条目有 vod_name / vod_id 等字段
  const looksJson =
    res.contentType.includes("json") || body.startsWith("{") || body.startsWith("[");
  if (looksJson) {
    try {
      const obj = JSON.parse(body);
      const list = obj?.list || obj?.data || obj?.rows;
      if (Array.isArray(list) && list.length > 0) {
        const item = list[0] || {};
        const keys = Object.keys(item);
        const cmsFields = keys.filter((k) =>
          /^vod_|^type_|^tid$|^name$|^id$/i.test(k)
        );
        if (cmsFields.length >= 2 || "vod_name" in item || "vod_id" in item) {
          return {
            kind: "json",
            fields: keys.slice(0, 20),
            sample: pickSample(item),
          };
        }
      }
    } catch {
      /* 不是合法 JSON，继续 */
    }
  }

  // XML 判定：maccms XML 采集含 <rss><list><video>
  const looksXml =
    res.contentType.includes("xml") || body.startsWith("<?xml") || body.startsWith("<rss");
  if (looksXml && /<video>/i.test(body) && /<(name|last|dt|note)/i.test(body)) {
    return { kind: "xml", fields: ["name", "type", "dd", "note"], sample: null };
  }

  return { kind: "other" };
}

function pickSample(item) {
  const out = {};
  for (const k of ["vod_id", "vod_name", "vod_pic", "vod_remarks", "type_name"]) {
    if (k in item) out[k] = item[k];
  }
  return out;
}
