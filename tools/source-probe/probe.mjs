#!/usr/bin/env node
/**
 * TVBox 站点转源探测工具 (source-probe)
 *
 * 给定一个视频网站 URL，探测它最适合用哪种转源方式，并给出理由与下一步建议。
 * 对应 docs/转源指南.md 的 4 种方式：
 *   方式一 CMS API (type 0/1) —— 最优，几乎零开发
 *   方式二 JS Spider (type 3, .js) —— 服务端渲染网页
 *   方式三 Python Spider —— 同上
 *   方式四 JAR Spider —— 复杂/高性能
 *   + WebView 嗅探 —— SPA / 播放地址难解析时的兜底
 *
 * 用法:
 *   node probe.mjs <url> [--json] [--timeout 10000] [--ua "..."]
 * 示例:
 *   node probe.mjs https://example.com
 *   node probe.mjs example.com --json
 *
 * 仅做技术可行性探测，不抓取/存储站点内容。请自行确认使用场景合规与站点服务条款。
 */

import { parseArgs } from "node:util";
import {
  DEFAULT_UA,
  normalizeBaseUrl,
  fetchText,
} from "./lib/http.mjs";
import { probeCmsApi } from "./lib/detectCms.mjs";
import { analyzeHomepage } from "./lib/detectHtml.mjs";
import { decide, renderReport } from "./lib/decide.mjs";

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      json: { type: "boolean", default: false },
      timeout: { type: "string", default: "10000" },
      ua: { type: "string", default: DEFAULT_UA },
      help: { type: "boolean", short: "h", default: false },
    },
  });

  if (values.help || positionals.length === 0) {
    printUsage();
    process.exit(values.help ? 0 : 1);
  }

  let base;
  try {
    base = normalizeBaseUrl(positionals[0]);
  } catch (e) {
    console.error(`无法解析 URL: ${positionals[0]} (${e.message})`);
    process.exit(1);
  }

  const timeout = Math.max(2000, parseInt(values.timeout, 10) || 10000);
  const ua = values.ua;
  const ctx = { base, timeout, ua, notes: [] };

  // 1) 先抓首页，用于建站系统识别 + 渲染方式分析
  let homepage = null;
  try {
    homepage = await fetchText(base, { timeout, ua });
  } catch (e) {
    ctx.notes.push(`首页请求失败: ${e.message}`);
  }

  // 2) 探测 maccms/CMS 标准采集 API（方式一）
  const cms = await probeCmsApi(base, { timeout, ua });

  // 3) 分析首页 HTML：建站系统特征、渲染方式（SSR vs SPA）、疑似列表结构
  const html = homepage
    ? analyzeHomepage(homepage.body, homepage.finalUrl || base, homepage.headers)
    : null;

  // 4) 综合判定
  const result = decide({ base, cms, html, homepage, notes: ctx.notes });

  if (values.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(renderReport(result));
  }
  // 退出码：0=找到可行方式，2=需要人工进一步分析
  process.exit(result.recommendation.confidence === "low" ? 2 : 0);
}

function printUsage() {
  console.log(`TVBox 站点转源探测工具

用法:
  node probe.mjs <url> [选项]

选项:
  --json            以 JSON 输出结果
  --timeout <ms>    单请求超时，默认 10000
  --ua "<string>"   自定义 User-Agent
  -h, --help        显示帮助

示例:
  node probe.mjs https://example.com
  node probe.mjs example.com --json --timeout 8000
`);
}

main().catch((e) => {
  console.error("探测过程出错:", e);
  process.exit(1);
});
