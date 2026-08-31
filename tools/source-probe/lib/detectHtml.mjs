/** 分析首页 HTML：建站系统指纹、渲染方式(SSR/SPA)、疑似影片列表结构。 */

/**
 * @returns {{
 *   platform: string|null,        建站系统识别
 *   platformHints: string[],
 *   rendering: 'ssr'|'spa'|'mixed',
 *   renderingReason: string,
 *   detailPathPattern: string|null,  疑似详情页路径规律
 *   playerHints: string[],
 *   apiHints: string[],           页面里出现的接口线索
 * }}
 */
export function analyzeHomepage(body, finalUrl, headers = {}) {
  const html = body || "";
  const lower = html.toLowerCase();

  // 1) 建站系统指纹
  const platformHints = [];
  let platform = null;
  const gen = matchMeta(html, "generator");
  if (gen) platformHints.push(`generator=${gen}`);

  if (/maccms|苹果\s*cms|mac_?cms/i.test(html) || /content=["']maccms/i.test(html)) {
    platform = "maccms";
    platformHints.push("含 maccms 特征");
  } else if (/dedecms|织梦/i.test(html)) {
    platform = "dedecms";
  } else if (/wordpress|wp-content/i.test(lower)) {
    platform = "wordpress";
    platformHints.push("含 wp-content");
  } else if (gen && /cms/i.test(gen)) {
    platform = gen;
  }
  // 常见 maccms 模板路径特征
  if (/\/(vod|type|detail|play)\/(id\/)?\d+/i.test(html)) {
    platformHints.push("含 /vod|/type|/detail|/play 路径特征(疑似 maccms 模板)");
    if (!platform) platform = "maccms?";
  }

  // 2) 渲染方式判定：SSR(页面直接有影片链接) vs SPA(空壳 + 大量 JS)
  const anchors = countMatches(html, /<a\s[^>]*href=/gi);
  const detailLinks = collectDetailLinks(html, finalUrl);
  const scriptTags = countMatches(html, /<script[\s>]/gi);
  const hasAppRoot =
    /<div[^>]+id=["'](app|root|__next|__nuxt)["']/i.test(html) ||
    /window\.__(NUXT|NEXT_DATA)__/i.test(html);
  const textLen = html.replace(/<[^>]+>/g, "").trim().length;

  let rendering = "ssr";
  let renderingReason = "";
  if (detailLinks.length >= 5) {
    rendering = "ssr";
    renderingReason = `首页含 ${detailLinks.length} 个疑似详情链接，服务端已渲染列表`;
  } else if (hasAppRoot && detailLinks.length < 3 && scriptTags >= 3) {
    rendering = "spa";
    renderingReason = "存在 SPA 挂载点(app/root/__next/__nuxt) 且首页几乎无详情链接，内容疑似前端动态加载";
  } else if (detailLinks.length > 0) {
    rendering = "mixed";
    renderingReason = `首页有少量(${detailLinks.length})详情链接，可能部分动态加载`;
  } else {
    rendering = "spa";
    renderingReason = "首页未发现明显详情链接，倾向前端渲染";
  }

  // 3) 详情页路径规律（供写 Spider 时构造 category/detail URL）
  const detailPathPattern = inferDetailPattern(detailLinks, finalUrl);

  // 4) 播放器/接口线索
  const playerHints = [];
  if (/dplayer|artplayer|videojs|hls\.js|flv\.js|ckplayer|jwplayer/i.test(html)) {
    const m = html.match(/dplayer|artplayer|videojs|hls\.js|flv\.js|ckplayer|jwplayer/i);
    playerHints.push(`检测到播放器库: ${m[0]}`);
  }
  if (/\.m3u8/i.test(html)) playerHints.push("页面源码含 .m3u8(可能直出直链)");
  if (/player_data|player_aaaa|encrypt|des\.|aes\./i.test(html)) {
    playerHints.push("疑似有播放数据/加密变量(播放地址可能需解密)");
  }

  const apiHints = collectApiHints(html);

  return {
    platform,
    platformHints,
    rendering,
    renderingReason,
    stats: { anchors, detailLinks: detailLinks.length, scriptTags, textLen, hasAppRoot },
    detailPathPattern,
    playerHints,
    apiHints,
  };
}

function matchMeta(html, name) {
  const re = new RegExp(
    `<meta[^>]+name=["']${name}["'][^>]+content=["']([^"']+)["']`,
    "i"
  );
  const m = html.match(re);
  return m ? m[1] : null;
}

function countMatches(s, re) {
  const m = s.match(re);
  return m ? m.length : 0;
}

/** 收集疑似"影片详情"链接（/detail /vod /play /movie 等 + 数字 id）。 */
function collectDetailLinks(html, baseUrl) {
  const links = new Set();
  const re = /href=["']([^"']+)["']/gi;
  let m;
  const pat = /\/(detail|vod|play|movie|video|dianying|voddetail)\/[^"']*\d+/i;
  while ((m = re.exec(html)) !== null) {
    const href = m[1];
    if (pat.test(href)) {
      try {
        links.add(new URL(href, baseUrl).pathname);
      } catch {
        links.add(href);
      }
    }
    if (links.size > 60) break;
  }
  return [...links];
}

function inferDetailPattern(links, baseUrl) {
  if (!links.length) return null;
  // 取第一个，把数字替换成占位，给出模式示例
  const sample = links[0];
  const pattern = sample.replace(/\d+/g, "{id}");
  return { example: sample, pattern };
}

/** 收集页面里出现的接口线索(ajax/api/json 路径)。 */
function collectApiHints(html) {
  const hints = new Set();
  const re = /["'`](\/[^"'`\s]*?(?:api|ajax|search|vod|json)[^"'`\s]*)["'`]/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const p = m[1];
    if (p.length < 100 && !p.includes("<")) hints.add(p);
    if (hints.size > 15) break;
  }
  return [...hints];
}
