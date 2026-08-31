# TVBoxOS 架构与开发文档

> 面向二次开发者的代码结构说明。基于 `q215613905/TVBoxOS`（Java，包名 `com.github.tvbox.osc`）。
> 本项目是一个 TVBox 影视聚合客户端：从"配置源"加载一批"站点源(sites)"，聚合搜索、点播、直播，
> 站点内容由 Spider（jar / JS / Python 爬虫）或 XML/JSON 接口提供。

## 目录

- [1. 工程与模块](#1-工程与模块)
- [2. 应用启动与初始化](#2-应用启动与初始化)
- [3. 配置系统 (ApiConfig)](#3-配置系统-apiconfig)
- [4. Spider 爬虫系统](#4-spider-爬虫系统)
- [5. UI 结构与导航](#5-ui-结构与导航)
- [6. 搜索流程](#6-搜索流程)
- [7. 点播播放流程](#7-点播播放流程)
- [8. 直播](#8-直播)
- [9. 数据与持久化](#9-数据与持久化)
- [10. 网络层](#10-网络层)
- [11. 其他子系统](#11-其他子系统)
- [12. 构建与打包](#12-构建与打包)
- [13. 二次开发新增功能](#13-二次开发新增功能)
- [14. 常见二次开发切入点](#14-常见二次开发切入点)

---

## 1. 工程与模块

Gradle 多模块工程（见 `settings.gradle`）：

| 模块 | 说明 |
|------|------|
| `:app` | 主应用。所有业务代码在此。 |
| `:player` | 播放器抽象层，基于 doikki/DKPlayer（包名 `xyz.doikki.videoplayer`）。封装 VideoView 与多种播放内核。 |
| `:quickjs` | QuickJS JS 引擎的 Android 绑定（`com.whl.quickjs`），用于运行 JS 类型的 Spider。 |
| `:pyramid` | 内嵌 Python 运行时（Chaquopy），用于运行 `.py` 类型的 Spider。 |

`app` 模块的两个主要 Java 根包：

- `com.github.tvbox.osc.*` —— 应用自身业务代码。
- `com.github.catvod.*` —— 爬虫层（Spider 契约、jar/js 加载器、爬虫专用网络封装）。

### app 包分布（类数量概览）

```
com.github.tvbox.osc
├── api          (2)   ApiConfig 配置加载与解析核心
├── base         (3)   App / BaseActivity / BaseLazyFragment
├── bean         (25)  数据模型（Movie/VodInfo/SourceBean/Live*/Parse* 等）
├── cache        (8)   Room 数据库与 DAO（历史、收藏、通用缓存）
├── callback     (2)   LoadSir 加载态视图
├── data         (2)   AppDataManager 数据库门面
├── dlna         (7)   投屏（UPnP/Cling）
├── event        (4)   EventBus 事件
├── glide/picasso(5)   图片加载适配
├── player       (18)  播放内核适配（ijk/exo/system）、渲染、第三方播放器
├── receiver     (4)   广播接收器（Boot/Push/Search/CustomWeb）
├── server       (8)   本地 HTTP 控制服务器（NanoHTTPD）
├── subtitle     (22)  字幕解析与渲染引擎
├── ui           (83)  activity / fragment / adapter / dialog / tv.widget
├── util         (42)  工具类与 HawkConfig 配置键
└── viewmodel    (2)   SourceViewModel（搜索/点播/详情/分类的数据获取）
```

---

## 2. 应用启动与初始化

入口：`base/App.java`（继承 `MultiDexApplication`）。`onCreate()` 按顺序初始化：

1. `initParams()` —— `Hawk.init()`（键值存储，全局设置，键定义见 `util/HawkConfig`），并写入默认值。
2. `OkGoHelper.init()` —— 配置 OkGo/OkHttp 单例、DoH、Picasso 图片下载器。
3. `EpgUtil.init()` —— 直播 EPG（节目单）名称匹配缓存。
4. `ControlManager.init(this)` —— 本地 HTTP 控制服务器（远程推送配置/投屏）。
5. `AppDataManager.init()` —— 构建 Room 数据库。
6. `LoadSir` —— 注册加载中/空状态视图。
7. `AutoSizeConfig` —— 屏幕适配（使用 MM 子单位，禁用 DP/SP）。
8. `PlayerHelper.init()` —— 播放内核工厂配置。
9. `QuickJSLoader.init()` —— 加载 QuickJS 原生库。

`App` 还持有：静态 `P2PClass`（雷霆/P2P 流，`App.getp2p()`）、共享 `VodInfo`（详情页向 PlayFragment 传递当前影片，避免 Intent 序列化）。`onTerminate()` 调用 `JsLoader.destroy()`。

**启动 Activity**：`HomeActivity`（继承 `BaseActivity`）。启动本地服务器、初始化 `SourceViewModel`、通过 `sourceViewModel.getSort(sourceKey)` 加载首页内容，内部用 ViewPager 承载 UserFragment（首页推荐/历史）与 GridFragment（分类网格）。

---

## 3. 配置系统 (ApiConfig)

`api/ApiConfig.java` 是配置中枢（单例 `ApiConfig.get()`）。

### 关键字段

- `LinkedHashMap<String, SourceBean> sourceBeanList` —— 所有站点源，按 `key` 存储（保序）。
- `List<ParseBean> parseBeanList` —— 解析接口列表。
- `List<LiveChannelGroup> liveChannelGroupList` —— 直播分组。
- `mHomeSource` —— 当前首页选中的站点源。
- 三个 Spider 加载器：`jarLoader` / `jsLoader` / `pyLoader`。

### 加载流程

`loadConfig(useCache, callback, activity)`：

1. 读取 `HawkConfig.API_URL`（当前配置地址）。
2. 命中本地缓存文件（`filesDir/MD5(apiUrl)`）则直接用；否则 `fetchConfigAsync()` 拉取。
3. 解密（`FindResult()`，支持 AES/Base64 加密配置）。
4. `parseJson(apiUrl, json)` 解析。

`parseJson()`：先 `resetConfigData()` 清空，再依次解析 `sites`（→ `buildSourceBean()` → `sourceBeanList`）、`parses`、`lives`、`hosts`、`rules`（嗅探/广告规则）、`doh`、`ads` 等。

> **注意**：`resetConfigData()` 会清空全部配置。多配置合并需在解析末尾追加（见 [§13](#13-二次开发新增功能)）。

### 单站点模型 `bean/SourceBean`

| 字段 | 含义 |
|------|------|
| `key` | 唯一标识 |
| `name` | 显示名 |
| `api` | 接口地址（决定用哪种 Spider） |
| `type` | 0=XML，1=JSON，3=Spider(csp)，4=扩展 HTTP API |
| `searchable` | 是否可搜索（`isSearchable()`） |
| `quickSearch` | 是否可快速搜索 |
| `filterable` | 是否支持分类筛选 |
| `ext` / `jar` / `playerUrl` / `categories` / `timeout` / `clickSelector` / `style` | 扩展参数 |

---

## 4. Spider 爬虫系统

> 如何把一个影视站点做成源（含 JS Spider 模板与配置示例），见 [转源指南.md](./转源指南.md)。

`com.github.catvod.crawler.Spider` 是爬虫契约基类，主要方法：

- `homeContent` / `homeVideoContent` —— 首页
- `categoryContent(tid, pg, filter, extend)` —— 分类页
- `detailContent(ids)` —— 详情
- `searchContent(key, quick[, pg])` —— 搜索
- `playerContent(flag, id, vipFlags)` —— 解析播放地址
- `liveContent(url)` —— 直播频道
- `isVideoFormat` / `manualVideoCheck` —— WebView 嗅探钩子

`SpiderNull` 为空实现兜底。三种加载器各自缓存 `key → Spider`：

| 类型 | 加载器 | 触发条件 | 引擎模块 |
|------|--------|----------|----------|
| JAR (type 3) | `JarLoader` | 默认 | DexClassLoader 加载 `com.github.catvod.spider.*` |
| JS | `JsLoader` + `js/JsSpider` | api 以 `.js` 结尾 | `:quickjs` |
| Python | `pyLoader` (`python/IPyLoader`) | api 含 `.py` | `:pyramid` |

**分发**：`ApiConfig.getCSP(sourceBean)` 按 api 后缀选择加载器。直播变体：`getJsCSP` / `getPyCSP` / `getLiveCSP`。

---

## 5. UI 结构与导航

`ui/` 子结构：

- **`ui/activity/`（10 个）**
  - `HomeActivity` —— 首页/启动页，分类网格 + 导航
  - `DetailActivity` —— 影片详情、剧集列表、内嵌 PlayFragment 播放
  - `LivePlayActivity` —— 直播播放器
  - `SearchActivity` —— 多源搜索（分线程逐源）
  - `FastSearchActivity` —— 快速/聚合搜索（默认模式，`FAST_SEARCH_MODE`）
  - `SettingActivity` —— 设置（承载 `ModelSettingFragment`）
  - `CollectActivity` / `HistoryActivity` —— 收藏 / 历史
  - `LocalFileActivity` —— 本地文件
  - `PushActivity` —— 接收推送的播放地址
- **`ui/fragment/`（5 个）**：`PlayFragment`（核心播放器+解析）、`GridFragment`、`UserFragment`、`ModelSettingFragment`、基类 `BaseLazyFragment`
- **`ui/adapter/`（~34 个）**：各 RecyclerView 适配器
- **`ui/dialog/`（~25 个）**：`BaseDialog` + 各功能对话框
- **`ui/tv/widget/`**：TV 焦点相关自定义控件

**基类**：Activity 继承 `base/BaseActivity`（抽象 `getLayoutResID()` + `init()`，提供 LoadSir 加载态、`jumpActivity()`、全屏沉浸、AutoSize、壁纸）；懒加载 Fragment 继承 `base/BaseLazyFragment`。

> 无独立 PlayActivity —— 点播播放是内嵌于 DetailActivity 的 `PlayFragment`。

---

## 6. 搜索流程

入口 `SearchActivity`（或默认的 `FastSearchActivity`）：

1. `search(title)` → `searchResult()`：取 `ApiConfig.get().getSourceBeanList()`，把首页源移到首位。
2. 过滤：跳过 `!isSearchable()` 的源；若 `mCheckSources != null` 且不含该 key 也跳过（**这就是"指定搜索源/可用源"的过滤点**）。
3. 每个源建一个 `SearchTask`，`type==3`（Spider）标记为 blocking。
4. 线程池并发：非阻塞源直接 `sourceViewModel.getSearch()`；阻塞源按批（`SEARCH_THREAD_COUNT=6`）提交，每源 10s 超时。
5. `SourceViewModel.getSearch(sourceKey, wd, token)` 按 type 发请求：type 3 走 Spider `searchContent`；type 0/1 走 OkGo GET；type 4 带 `extend` 参数。
6. 结果经 LiveData → EventBus `RefreshEvent.TYPE_SEARCH_RESULT` → `SearchActivity.searchData()` 聚合展示。

---

## 7. 点播播放流程

从详情页选集到出画面：

1. `DetailActivity.jumpToPlay()`：`App.setVodInfo(vodInfo)` 暂存影片，构建 Bundle，确保 PlayFragment 存在，`playFragment.setData(bundle)`。
2. `PlayFragment` → `play()` → `SourceViewModel.getPlay(sourceKey, playFlag, progressKey, url, subtitleKey)`。
3. `getPlay()` 按 `type` 分支：
   - **type 3**：线程池取 `getCSP()` 调 `playerContent()`，带每源超时。
   - **type 0/1**：直接构造结果（`parse=0` 直链 / `parse=1` 需解析）。
   - **type 4**：OkGo GET，带 play/flag/extend。
4. 结果经 LiveData 回到 PlayFragment。`parse==0` 直接播；`parse==1` 走解析：`doParse(ParseBean)`。
   - ParseBean type：0=WebView 嗅探；1=JSON 解析接口；2=JSON 扩展聚合；3/4=聚合/super 混合解析。
   - VIP flags（`getVipParseFlags()`）标记需要解析的 flag。
5. 解析出的 URL + headers → `goPlayUrl()` → `:player` 模块。

**`:player` 内核**（`HawkConfig.PLAY_TYPE` 选择）：0=系统 MediaPlayer，1=IjkPlayer，2=ExoPlayer（默认），10=外部播放器（`PlayerHelper.runExternalPlayer`）。ExoPlayer 网络走 OkHttpDataSource（复用 OkGo 的 client）。

---

## 8. 直播

`LivePlayActivity` 为专用直播播放器。`ApiConfig.loadLiveConfig()` 解析直播端点为模型树：

```
LiveChannelGroup（分组）
└── LiveChannelItem（频道，含一或多条线路 URL）
    └── EPG：Epginfo / LiveEpgDate / LiveDayListGroup
```

状态管理 `bean/LivePlayerManager`。直播源也可为 Spider（`getLiveCSP` → `.js`/`.py`），`Spider.liveContent(url)` 返回频道。相关 Hawk 键：`LIVE_API_URL` / `LIVE_CHANNEL` / `LIVE_GROUP_INDEX` / `LIVE_PLAY_TYPE` / `EPG_URL` / `DEFAULT_LOAD_LIVE` 等。

---

## 9. 数据与持久化

- **`bean/`（25 个模型）**
  - 点播：`Movie` + `Movie.Video`，`MovieSort`（分类/筛选），`AbsXml`/`AbsJson`/`AbsSortXml`/`AbsSortJson`（解析结果包装），`VodInfo`(+VodSeries，当前播放项)
  - 源/解析：`SourceBean` / `ParseBean` / `ProxyRule` / `IJKCode` / `SourceTestResult`
  - 直播：`LiveChannelGroup` / `LiveChannelItem` / `LiveSettingGroup` / `LivePlayerManager` / `Epginfo`
  - 字幕弹幕：`Subtitle` / `SubtitleData` / `Danmu` / `DanmuSearchResult`
- **`cache/`（Room）**：`AppDataBase`（version 1），3 张表：
  - `Cache`（通用键/值缓存，`CacheDao`）
  - `VodRecord`（观看历史，`VodRecordDao`）
  - `VodCollect`（收藏，`VodCollectDao`）

  门面：`CacheManager` / `RoomDataManger` / `data/AppDataManager`；历史封装 `util/HistoryHelper`。
- **`util/HawkConfig`**：全部设置键。分组见文件内注释——配置源 / 播放器 / 网络 / 交互 / 搜索 / 直播 / 字幕 / 弹幕。

---

## 10. 网络层

两套 HTTP 栈共享配置：

- **OkGo**（`com.lzy.okgo`）：应用层客户端，`OkGoHelper.init()` 初始化（日志拦截受 `DEBUG_OPEN` 控制、超时、`CustomDns`、代理、信任所有 SSL）。同时初始化 noRedirect client、Exo client、Picasso 下载器。
- **`com.github.catvod.net.OkHttp`**：爬虫层封装，复用 `OkGoHelper.getDefaultClient()`，使 Spider 共享代理/SSL/DoH。提供 `string()` / `newCall()` / `client(timeout)`。
- **DoH**：`OkGoHelper.initDnsOverHttps()`，从 `DOH_JSON` 构建。
- **代理**：`util/Proxy` + `com.github.catvod.Proxy` + `ProxyRule`。
- **广告过滤**：`util/AdBlocker`（WebView 嗅探时拦截广告 host）、`util/VideoParseRuler`（视频 URL 判定规则）、`util/M3u8`（m3u8 去广告）。

---

## 11. 其他子系统

- **`server/`**：本地 HTTP 控制服务器。`ControlManager` 包装 `RemoteServer`（NanoHTTPD）。处理器实现 `RequestProcess`。用途：远程推送配置、遥控、投屏数据接收、Spider 本地代理内容。
- **`dlna/`**：投屏。`DLNACastManager` / `DLNACastService`（Cling/UPnP）/ `CastDevice` / `CastVideo` / 流服务器。UI 侧 `CastDeviceDialog`。
- **`subtitle/`（~22 类）**：字幕解析渲染引擎（SRT/ASS/VTT）。`util/SubtitleHelper` + `ui/dialog/SubtitleDialog` 集成。
- **`event/`**：EventBus 事件。`RefreshEvent`（含大量 `TYPE_*` 常量）、`ServerEvent`（本地服务器事件）、`TopStateEvent`、`HistoryStateEvent`。
- **`receiver/`**：`BootReceiver`（开机自启，受 `BOOT_AUTO_START` 控制）、`PushReceiver`、`SearchReceiver`、`CustomWebReceiver`。

---

## 12. 构建与打包

- AGP **7.2.2**，Gradle **7.5**。CI 用 **JDK 17**（`android-actions/setup-android` 接受许可需要），AGP 7.2.2 在 JDK 17 上可运行。
- 编译目标 `sourceCompatibility = 1.8`（已开启 desugaring，lambda 可跑在低版本）。
- **产品风味（flavor）× minSdk**：
  - `java` / `java32` / `python` / `python32` → `minSdk 19`（Android 4.4）
  - `java64` / `python64` → `minSdk 21`（Android 5.0，64 位 ABI）
- 常用构建任务：
  - Debug（无需签名）：`./gradlew assembleJavaDebug`
  - Release（需签名 + 混淆）：`./gradlew assembleJavaRelease`
- **CI 关键坑**：Chaquopy(Python 插件)在工程评估阶段读取 `local.properties`，缺失会导致
  `Failed to notify project evaluation listener`。CI 中需先写入 `local.properties`（`sdk.dir=$ANDROID_SDK_ROOT`），即使只构建 java 风味。
- Release 签名通过 `gradle.properties` 的 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` 提供（`app/build.gradle` 的 `signingConfigs.release`）。

CI 工作流见 `.github/workflows/build-fork.yml`（推送时构建 debug + 签名 release）。

---

## 13. 二次开发新增功能

以下为本分支相对上游新增的功能，供参考如何扩展：

### 13.1 源测速 / 可用性测试
- `bean/SourceTestResult` —— 单源测试结果（状态/耗时/结果数/是否可用）。
- `util/SourceTester` —— 并发（8 线程）对各源用关键词发计时搜索请求；type 0/1/4 用 `OkHttp.client(timeout)` 同步请求，type 3 用 `getCSP().searchContent()` + Future 超时；统计 JSON(`list/data/videoList/video`)或 XML(`<video>`)结果数。
- `ui/dialog/SourceSpeedTestDialog` + `ui/adapter/SourceSpeedTestAdapter` —— 展示每源状态/耗时/结果数并勾选可用源，自动勾选可用。
- 入口：`SearchActivity` 的"源测速/选可用"按钮（`activity_search.xml` 的 `tvSourceSpeedTestBtn`）。

### 13.2 可用源持久化 & 搜索过滤
- `HawkConfig.USABLE_SOURCES` —— `HashMap<apiUrl, HashMap<sourceKey,"1">>`，按配置源存储勾选的可用源。
- `SearchHelper.getUsableSources()` / `putUsableSources()`。
- `SearchActivity` / `FastSearchActivity` 的 `initCheckedSourcesForSearch()` 优先用可用源集合，从而后续搜索只跑可用源、跳过不可用源。

### 13.3 第二配置源合并
- `HawkConfig.SECONDARY_API_URL` —— 第二配置源地址。
- `ApiConfig.mergeSecondaryConfig(primaryApiUrl)` —— 在 `parseJson()` 末尾调用，拉取并解析第二配置的 `sites`/`parses`，按 key/name 去重合并进主配置（主源优先），并清空 `searchSourceBeanList` 缓存。
- `ui/dialog/SecondaryApiDialog` + 设置页 `llSecondaryApi` 行；变更后 `restartAppAfterConfigChanged()` 重新加载。

### 13.4 开机自启
- `receiver/BootReceiver` —— 监听 `BOOT_COMPLETED` / `QUICKBOOT_POWERON`，开启时拉起 `HomeActivity`。
- `HawkConfig.BOOT_AUTO_START`（默认关闭）+ 设置页 `llBootAutoStart` 开关。
- Manifest：`RECEIVE_BOOT_COMPLETED` 权限 + receiver 注册。

---

## 14. 常见二次开发切入点

| 需求 | 切入位置 |
|------|----------|
| 改搜索逻辑/过滤 | `SearchActivity.searchResult()` / `FastSearchActivity`；过滤集合 `mCheckSources` |
| 改单源请求方式 | `SourceViewModel.getSearch()` / `getPlay()` |
| 站点模型加字段 | `bean/SourceBean` + `ApiConfig.buildSourceBean()` 解析 |
| 配置解析/多源合并 | `ApiConfig.parseJson()` / `mergeSecondaryConfig()` |
| 加设置项 | `util/HawkConfig` 加键 + `res/layout/fragment_model.xml` 加行 + `ModelSettingFragment` 绑定 |
| 加持久化 | `Hawk.put/get`（简单配置）或 Room DAO（结构化数据） |
| 加对话框 | 继承 `ui/dialog/BaseDialog`，参考 `SourceSpeedTestDialog` |
| 加列表 | `BaseQuickAdapter<T, BaseViewHolder>`（chad 库），参考 `SearchAdapter` |
| 播放/解析定制 | `ui/fragment/PlayFragment.doParse()` 及 ParseBean 类型 |
| 新广播 | `receiver/` + Manifest 注册，参考 `BootReceiver` |

> 提示：新增 UI 代码可放心使用 lambda（项目已开启 Java 8 desugaring，兼容 Android 4.4+）。
> 涉及反射的模型（Gson/XStream/EventBus）改动时，注意 `app/proguard-rules.pro` 的混淆保留规则。
