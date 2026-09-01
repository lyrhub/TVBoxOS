/**
 * play() 探测（--play 开启）：进详情页 → 播放页，分析真实播放地址的线索，
 * 预判播放地址解析难度。这是转源里最难的一环。
 *
 * 难度分级（easy/medium/hard/unknown）对应 play() 实现策略：
 *   easy   —— 源码直接含 .m3u8/.mp4 直链，play 返回 parse:0 即可
 *   medium —— 有加密的播放数据变量(player_aaaa 等)，需解密后得直链
 *   medium —— 有独立播放接口(XHR)，请求该接口即可拿地址
 *   hard   —— 无以上线索，大概率需 WebView 嗅探(isVideoFormat/manualVideoCheck)
 */

import { fetchText, join } from "./http.mjs";
import { collectPlayLinks } from "./detectHtml.mjs";

export async function probePlay(base, homepageHtml, html, { timeout, ua }) {
  const trail = []; // 探测足迹
  const signals = [];
  let level = "unknown";
  let detailUrl = null;
  let playUrl = null;

  // 1) 找一个详情页
  const detailCandidates = (html?.detailLinkList || []).map((p) => join(base, p));
  if (detailCandidates.length === 0) {
    return {
      ran: true,
      level: "unknown",
      reason: "首页未找到详情链接，无法进入详情/播放页做 play 探测",
      signals: [],
      trail,
    };
  }

  let detailHtml = "";
  for (const url of detailCandidates.slice(0, 3)) {
    try {
      const res = await fetchText(url, { timeout, ua });
      trail.push({ step: "detail", url, status: res.status });
      if (res.ok && res.body) {
        detailUrl = res.finalUrl || url;
        detailHtml = res.body;
        break;
      }
    } catch (e) {
      trail.push({ step: "detail", url, error: errName(e) });
    }
  }

  // 2) 从详情页找播放页；找不到就直接分析详情页本身
  let pageForAnalysis = detailHtml;
  let analyzedUrl = detailUrl;
  const playLinks = detailHtml ? collectPlayLinks(detailHtml, detailUrl || base) : [];
  if (playLinks.length > 0) {
    for (const url of playLinks.slice(0, 3)) {
      try {
        const res = await fetchText(url, { timeout, ua });
        trail.push({ step: "play", url, status: res.status });
        if (res.ok && res.body) {
          playUrl = res.finalUrl || url;
          pageForAnalysis = res.body;
          analyzedUrl = playUrl;
          break;
        }
      } catch (e) {
        trail.push({ step: "play", url, error: errName(e) });
      }
    }
  } else if (detailHtml) {
    signals.push("详情页未见独立播放页链接(可能播放数据直接内嵌在详情页)");
  }

  if (!pageForAnalysis) {
    return {
      ran: true,
      level: "unknown",
      reason: "详情/播放页抓取失败",
      signals,
      trail,
      detailUrl,
      playUrl,
    };
  }

  // 3) 分析播放线索
  const found = analyzePlayPage(pageForAnalysis);
  signals.push(...found.signals);

  // 4) 定级（就高原则：直链最好，其次接口/加密，最后嗅探）
  if (found.hasDirectM3u8 || found.hasDirectMp4) {
    level = "easy";
  } else if (found.hasPlayApi || found.hasEncryptedData) {
    level = "medium";
  } else {
    level = "hard";
  }

  return {
    ran: true,
    level,
    reason: levelReason(level),
    signals,
    directUrls: found.directUrls.slice(0, 3),
    detailUrl,
    playUrl,
    analyzedUrl,
    trail,
  };
}

function analyzePlayPage(html) {
  const signals = [];
  const directUrls = [];

  // 直链
  const m3u8 = [...html.matchAll(/https?:\/\/[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*/gi)].map((x) => x[0]);
  const mp4 = [...html.matchAll(/https?:\/\/[^\s"'<>\\]+\.mp4[^\s"'<>\\]*/gi)].map((x) => x[0]);
  const hasDirectM3u8 = m3u8.length > 0;
  const hasDirectMp4 = mp4.length > 0;
  if (hasDirectM3u8) {
    signals.push(`发现 .m3u8 直链 ${m3u8.length} 个(play 可 parse:0 直出)`);
    directUrls.push(...m3u8);
  }
  if (hasDirectMp4) {
    signals.push(`发现 .mp4 直链 ${mp4.length} 个`);
    directUrls.push(...mp4);
  }

  // maccms 播放数据变量(通常需解密)
  const hasPlayerVar = /player_aaaa|player_data|MacPlayer|player_config/i.test(html);
  const hasEncryptHint = /"encrypt"\s*:\s*[12]|base64|aes|des|crypto-js|unescape\(/i.test(html);
  const hasEncryptedData = hasPlayerVar && hasEncryptHint;
  if (hasPlayerVar) {
    signals.push("发现播放数据变量(player_aaaa/player_data 等)");
    // 尝试从 player_aaaa 里抠出 url 字段
    const pv = html.match(/player_aaaa\s*=\s*(\{[\s\S]*?\})\s*<\/script>/i)
      || html.match(/player_aaaa\s*=\s*(\{[\s\S]*?\});/i);
    if (pv) {
      const urlIn = pv[1].match(/"url"\s*:\s*"([^"]+)"/i);
      if (urlIn) {
        const raw = urlIn[1];
        if (/\.m3u8|\.mp4|^https?:/i.test(raw.replace(/\\\//g, "/"))) {
          signals.push("player_aaaa.url 疑似明文/半明文地址");
          directUrls.push(raw.replace(/\\\//g, "/").slice(0, 200));
        } else {
          signals.push("player_aaaa.url 疑似编码/加密(需解码)");
        }
      }
    }
  }
  if (hasEncryptedData) signals.push("播放数据疑似加密(base64/aes/des 等，play 需解密)");

  // 独立播放接口
  const playApi =
    /["'`](\/(?:player|api|parse|play)[^"'`\s]*)["'`]/i.test(html) ||
    /url\s*[:=]\s*["'][^"']*\/(?:player|api|parse)\//i.test(html);
  if (playApi) signals.push("疑似存在独立播放/解析接口(可直接请求该接口)");

  // 播放器库
  const pm = html.match(/dplayer|artplayer|videojs|hls\.js|flv\.js|ckplayer|jwplayer/i);
  if (pm) signals.push(`播放器库: ${pm[0]}`);

  return {
    signals,
    directUrls,
    hasDirectM3u8,
    hasDirectMp4,
    hasEncryptedData,
    hasPlayApi: playApi,
  };
}

function levelReason(level) {
  switch (level) {
    case "easy":
      return "播放页含直链，play() 大概率可直接 parse:0 返回，最易实现";
    case "medium":
      return "需解密播放数据或请求独立接口才能拿到地址，中等难度";
    case "hard":
      return "未发现直链/接口/加密变量线索，可能需 WebView 嗅探，较难";
    default:
      return "线索不足";
  }
}

function errName(e) {
  return e?.name === "AbortError" ? "timeout" : e?.message || "error";
}
