# source-probe · 站点转源探测工具

给定一个视频网站 URL，自动探测它最适合用哪种方式转成 TVBox 源，并给出理由、下一步建议和可参考的 `site` 配置片段。配套 [../../docs/转源指南.md](../../docs/转源指南.md)。

## 运行

需要 Node.js 18+（推荐 20/22+，使用内置 `fetch`），无第三方依赖。

```bash
node probe.mjs <url> [--json] [--timeout 10000] [--ua "..."]
```

示例：

```bash
node probe.mjs https://cj.lziapi.com          # 命中 CMS API → 方式一
node probe.mjs https://example.com --json     # JSON 输出
```

## 探测逻辑

按优先级依次判断（对应转源指南的 4 种方式）：

1. **CMS 采集 API（方式一，最优）**：尝试 `api.php/provide/vod/` 等常见 maccms 路径，命中标准 JSON/XML（含 `vod_name`/`list` 等）→ 直接给出可用的 type 0/1 配置，几乎零开发。
2. **SSR 网页（方式二/三）**：首页已服务端渲染出影片详情链接 → 适合写 JS/Python Spider，并推断详情页路径规律。
3. **SPA / 前端渲染（方式二 + 嗅探）**：首页为空壳、内容动态加载 → 建议先逆向 XHR 接口写 Spider，或用 WebView 嗅探兜底。
4. **信息不足**：首页抓取失败 → 提示人工分析。

同时输出：建站系统识别、渲染方式统计、播放器/接口线索、播放地址处理提示。

## 退出码

- `0`：找到较可行方式（高/中置信度）
- `2`：低置信度，需人工进一步分析
- `1`：参数错误或运行异常

## 目录结构

```
source-probe/
├── probe.mjs          入口 CLI
├── README.md
└── lib/
    ├── http.mjs       URL 规整 + 带超时 fetch
    ├── detectCms.mjs  探测 maccms/CMS 采集 API
    ├── detectHtml.mjs 首页 HTML 分析(建站系统/SSR-SPA/线索)
    └── decide.mjs     综合判定 + 报告渲染
```

## 局限

- 仅做**技术可行性**探测，不代表最终能出源；播放地址的加密/防盗链仍需针对站点手动处理。
- 对需要登录/特定地区/特殊 UA 的站点，可能抓不到有效首页，请配合 `--ua` 或人工分析。
- 请自行确认抓取与转源使用场景的合法合规与站点服务条款。
