/** 综合各探测信号，给出推荐的转源方式、理由与下一步建议。 */

/**
 * @param {object} p { base, cms, html, homepage, notes }
 * @returns 结构化结果（含 recommendation）
 */
export function decide(p) {
  const { base, cms, html } = p;
  const reasons = [];
  const nextSteps = [];
  let method, methodType, confidence, sampleConfig;

  // 优先级 1：命中 CMS 采集 API → 方式一（几乎零开发）
  if (cms?.found) {
    method = cms.type === 1 ? "方式一 · CMS JSON 采集 API (type 1)" : "方式一 · CMS XML 采集 API (type 0)";
    methodType = cms.type;
    confidence = "high";
    reasons.push(`命中标准采集接口: ${cms.apiUrl}`);
    if (cms.sample && Object.keys(cms.sample).length) {
      reasons.push(`返回含 CMS 字段样例: ${JSON.stringify(cms.sample)}`);
    }
    nextSteps.push("直接把下面的 site 配置加入 config.json 的 sites 数组即可，无需写爬虫。");
    nextSteps.push("验证: 用 ?wd=关键词 调该接口应能返回搜索结果。");
    sampleConfig = {
      key: keyFromHost(base),
      name: hostName(base),
      type: cms.type,
      api: cms.apiUrl,
      searchable: 1,
      quickSearch: 1,
      filterable: 1,
    };
    return build(p, { method, methodType, confidence, reasons, nextSteps, sampleConfig });
  }

  reasons.push("未探测到 maccms/CMS 标准采集接口(常见路径均未命中)。");

  // 优先级 2：SSR 网页(首页有影片链接) → JS/Python Spider 好写
  if (html && html.rendering === "ssr") {
    method = "方式二 · JS Spider (type 3, .js)  [或方式三 Python]";
    methodType = 3;
    confidence = "medium";
    reasons.push(html.renderingReason);
    if (html.detailPathPattern) {
      reasons.push(`详情页路径规律: ${html.detailPathPattern.pattern} (示例 ${html.detailPathPattern.example})`);
      nextSteps.push(`category/detail 可按该规律构造 URL 抓列表 HTML 再解析。`);
    }
    if (html.playerHints.length) reasons.push("播放线索: " + html.playerHints.join("; "));
    nextSteps.push("按 docs/转源指南.md 的 JS Spider 模板，实现 category/detail/search/play。");
    nextSteps.push("play() 是重点: 优先解析真实直链(parse:0)，解不出再考虑 VIP解析(parse:1) 或 WebView 嗅探。");
    return build(p, { method, methodType, confidence, reasons, nextSteps, sampleConfig: spiderConfig(base) });
  }

  // 优先级 3：SPA / 前端渲染 → 先找 XHR 接口，否则 WebView 嗅探
  if (html && (html.rendering === "spa" || html.rendering === "mixed")) {
    method = "方式二 JS Spider(需先逆向 XHR 接口)  或  WebView 嗅探兜底";
    methodType = 3;
    confidence = "low";
    reasons.push(html.renderingReason);
    if (html.apiHints.length) {
      reasons.push("页面出现的接口线索: " + html.apiHints.slice(0, 8).join(" , "));
      nextSteps.push("用浏览器开发者工具的 Network 面板，找到列表/详情/播放对应的 XHR/接口，直接请求这些接口写 Spider(比解析 HTML 更稳)。");
    } else {
      nextSteps.push("用浏览器 Network 面板抓 XHR，定位数据接口；若无独立接口，走 WebView 嗅探。");
    }
    if (html.playerHints.length) reasons.push("播放线索: " + html.playerHints.join("; "));
    nextSteps.push("WebView 嗅探: Spider 里 isVideoFormat()/manualVideoCheck() 配合 SourceBean.clickSelector，让 app 打开播放页自动嗅探视频请求。");
    return build(p, { method, methodType, confidence, reasons, nextSteps, sampleConfig: spiderConfig(base) });
  }

  // 兜底：首页都没抓到
  method = "需人工分析(首页抓取失败或信息不足)";
  methodType = null;
  confidence = "low";
  if (p.notes?.length) reasons.push(...p.notes);
  nextSteps.push("确认站点可访问、是否需要特定 UA/Cookie/地区；再用浏览器手动分析其接口与渲染方式。");
  return build(p, { method, methodType, confidence, reasons, nextSteps, sampleConfig: null });
}

function spiderConfig(base) {
  return {
    key: keyFromHost(base),
    name: hostName(base),
    type: 3,
    api: "https://你的服务器/spider/" + keyFromHost(base) + ".js",
    ext: "",
  };
}

function build(p, rec) {
  return {
    target: p.base,
    cms: p.cms,
    html: p.html,
    play: p.play || null,
    recommendation: rec,
  };
}

function hostName(base) {
  try {
    return new URL(base).hostname.replace(/^www\./, "");
  } catch {
    return base;
  }
}
function keyFromHost(base) {
  return hostName(base).split(".")[0].replace(/[^a-zA-Z0-9_]/g, "") || "site";
}

/** 渲染人类可读报告。 */
export function renderReport(r) {
  const L = [];
  const rec = r.recommendation;
  L.push("=".repeat(60));
  L.push(`站点: ${r.target}`);
  L.push("=".repeat(60));
  L.push("");
  L.push(`推荐方式: ${rec.method}`);
  L.push(`置信度  : ${confBadge(rec.confidence)}`);
  L.push("");
  L.push("判断依据:");
  rec.reasons.forEach((x) => L.push("  - " + x));
  L.push("");
  if (r.html) {
    L.push("首页分析:");
    L.push(`  - 建站系统: ${r.html.platform || "未识别"}${r.html.platformHints.length ? " (" + r.html.platformHints.join("; ") + ")" : ""}`);
    L.push(`  - 渲染方式: ${r.html.rendering.toUpperCase()}  [详情链接 ${r.html.stats.detailLinks} 个 / script ${r.html.stats.scriptTags} 个]`);
    L.push("");
  }
  if (r.play) {
    L.push("play() 播放地址探测:");
    if (r.play.ran === false) {
      L.push("  - 跳过: " + r.play.reason);
    } else {
      L.push(`  - 难度: ${playBadge(r.play.level)}  (${r.play.reason})`);
      (r.play.signals || []).forEach((s) => L.push("    · " + s));
      if (r.play.directUrls && r.play.directUrls.length) {
        L.push("  - 疑似直链样例:");
        r.play.directUrls.forEach((u) => L.push("    " + u));
      }
      if (r.play.detailUrl) L.push(`  - 探测详情页: ${r.play.detailUrl}`);
      if (r.play.playUrl) L.push(`  - 探测播放页: ${r.play.playUrl}`);
    }
    L.push("");
  }
  L.push("下一步建议:");
  rec.nextSteps.forEach((x, i) => L.push(`  ${i + 1}. ${x}`));
  L.push("");
  if (rec.sampleConfig) {
    L.push("可参考的 site 配置片段(加入 config.json 的 sites):");
    L.push(indent(JSON.stringify(rec.sampleConfig, null, 2), "  "));
    L.push("");
  }
  L.push("提示: 结论为技术可行性探测，具体实现仍需按站点结构调整；请确认使用合规。");
  return L.join("\n");
}

function confBadge(c) {
  return { high: "高 ✅", medium: "中 ⚠", low: "低(需人工) ❓" }[c] || c;
}
function playBadge(l) {
  return {
    easy: "易 ✅ (直链)",
    medium: "中 ⚠ (需解密/接口)",
    hard: "难 ❗ (可能需嗅探)",
    unknown: "未知 ❓",
  }[l] || l;
}
function indent(s, pad) {
  return s.split("\n").map((l) => pad + l).join("\n");
}
