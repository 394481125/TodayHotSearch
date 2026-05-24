# 今日热搜 (Today's Hot Searches) — 实时聚合全网热点榜单 Android 应用

**今日热搜** 是一款基于最新 Android 现代开发技术栈（Jetpack Compose + Room + Coroutines Flow + MVVM）打造的高颜值、高性能实时热点榜单聚合系统。应用集成了**双重数据获取引擎**，通过网络直接向全网各大官方网站或第三方 API 获取最新热搜数据，配合本地 SQLite 离线缓存、丰富的个性化版面定制功能，为用户带来极速、流畅的信息获取体验。

---

## 🎨 核心功能及实现

### 1. 多平台实时热点聚合
系统支持一键轻松浏览包括以下 **16大主流平台** 的实时热搜及热议话题：
*   **微博 (Weibo)**：社会热点、娱乐八卦最新动态
*   **知乎 (Zhihu)**：深度讨论与热门提问
*   **百度 (Baidu)**：每日全国网民搜索热词
*   **哔哩哔哩 (Bilibili)**：视频弹幕网热门视频与流行梗
*   **抖音 (Douyin)**：短视频热门视频热度榜
*   **今日头条 (Toutiao)**：聚合全网时政民生最热头条
*   **IT之家 (ITHome)**：最新数码前沿、科技动态与开发者关注热点
*   **36氪 (36Kr)**：创业投融资、互联网趋势商业风向标
*   **知乎日报 (Zhihu Daily)**：高品质经典日报精选文章
*   **澎湃新闻 (The Paper)**：深度优质原创时政要闻与政经热点
*   **虎扑步行街 (Hupu)**：知名体育及男青年生活娱乐交流社区热帖
*   **虎嗅网 (Huxiu)**：深度前沿科技、商业洞察与社会财经热评
*   **人人都是产品经理 (Woshipm)**：互联网产品设计、运营思维与行业深度干货
*   **豆瓣小组 (Douban)**：文艺及生活兴趣方向的社区群组热议话题
*   **机核 (Gcores)**：资深硬核游戏资讯、独立文化深度文章与播客动态
*   **虫部落 (Chongbuluo)**：优秀搜索、学术资料及快搜社区的精选交流热帖

### 2. 双重智能数据获取引擎 (Scraper & Fallback API)
为确保榜单获取的 **100% 极高稳定性与实时率**，数据仓库（`HotTopicRepository`）设计了双重弹性降级机制：
*   **直接抓取引擎（Direct Engines）**：系统率先使用经过优化的、基于 OkHttp 的轻量化网络解析器。模拟真实浏览器请求头（`User-Agent`/`Referer`）。
    *   **API直连解析**：对类似新浪微博、知乎、知乎日报、机核（Gcores）等平台，直接请求官方的高速公开 JSON 端点进行反序列化（例如：Gcores 的 `gapi/v1/articles` 原生 JSON API），毫秒级直接渲染。
    *   **RSS聚合解析**：对 36氪、澎湃新闻（The Paper）、虎嗅网、人人都是产品经理等平台，利用高度定制的正则表达式，对官方 XML RSS 进行极速解析，提供图文俱佳的新闻封面与摘要。
    *   **HTML正则提取器**：对虎扑步行街、豆瓣小组、快搜虫部落论坛等，直接请求相应的公开热点板块 HTML 源码，运用精心调配的轻量级正则表达式提取帖链接与标题，杜绝繁琐缓慢的 DOM 库依赖。
*   **第三方 Fallback 降级通道**：当目标官方服务器限制严格、网络超时或证书失效时，自动无缝切换至韩小韩备用 API 接口（`https://api.vvhan.com/api/hotlist`），作为最高保障，确保用户在任何环境下都能顺畅刷新榜单。

### 3. 本地轻量离线缓存与动态同步系统 (SQLite Room Database)
基于 Jetpack Room 数据库实现的全本地序列化缓存，不仅能将各平台的更新负荷降到最低，更确保了流畅的用户体验：
*   **数据隔离更新**：按平台（`platform`）进行热度数据独立事务刷新，保证更新过程界面不卡顿、不重叠、无脏数据。
*   **无缝动态 DB 同步**：应用在初始化（`HotTopicViewModel.init`）时，会自动比对代码定义的 `fullPlatformList` 与数据库中已存的 `PlatformSettingEntity`，若检测到代码中新增了平台（如用户升级到带有机核、虫部落版本），会自动在本地数据库静默增量追加新平台条目（保留用户原先的主题顺序及屏蔽状态），彻底避免老版本应用升级导致新增页签“丢失”或不显示的经典痛点。
*   **离线零等待访问**：即使在无网或弱网环境下，打开应用也能瞬间渲染出上一次成功同步的缓存内容，减少无效重复网络请求，省电省流量。
*   **静默重试机制**：系统缓存了所有基础平台元数据。用户刷新时，系统会启动协程进行异步网络请求，若请求成功则写入 Room 并自动驱动 UI 变更。

### 4. 极致现代视觉设计 & 交互系统 (Material Design 3)
*   **全面边缘对齐边缘 (Edge-to-Edge)**：开启 `enableEdgeToEdge()`，通过高度自适应安全区域（`WindowInsets.statusBarsPadding`/`navigationBarsPadding`），背景与状态栏和导航栏平滑过渡。
*   **卡片间距定制**：提供“紧凑”、“标准”、“舒适”三种界面行高与卡片容器内边距切换，适应不同的视野扫描习惯。
*   **动态主题配色**：各平台拥有独属于自身视觉辨识度的强调色主题芯片（例如：知乎蓝、微博红、哔哩哔哩粉等），结合深色模式切换、刷新旋转物理动画、微小高平滑度位移，提供顶级的交互响应感受。
*   **内置个性化平台排序筛选**：点击设置，可自定义对哪些平台进行隐藏或显示，使界面免受冗余信息骚扰，只展示自己感兴趣的平台。

---

## 🛠️ 技术栈 (Technology Stack)

| 模块 / 层次 | 采用的技术方案 / 库组件 | 作用与功能说明 |
| :--- | :--- | :--- |
| **基础语言 (Language)** | **Kotlin** | 完全使用现代 Kotlin 高级语法糖，结构精炼。 |
| **UI 页面 (View)** | **Jetpack Compose** (Material 3) | 声明式 UI 架构。配合 Scaffold 骨架、LazyColumn 动态瀑布流及卡片容器渲染列表。 |
| **状态流机制 (State)** | **Kotlin Coroutines / Flow / StateFlow** | 全面使用生命周期自适应框架 `collectAsStateWithLifecycle` 监听并处理 ViewModel 内部各种状态流（Loading、Error、Success）。 |
| **数据存储 (Database)** | **Jetpack Room (KSP)** | 本地 SQLite 对象映射数据库。使用 `RoomDatabase`/`Dao`，并利用 destructive 解构优雅升级数据库架构。 |
| **网络框架 (Network)** | **Retrofit 2** & **OkHttpClient** | 请求头加持。拦截并注入真实浏览器 UA、Referer，加入安全超时控制，承载全部网络数据互通。 |
| **数据解析 (Parser)** | **Moshi** & **org.json.JSONObject** | 多种格式灵活解密。对结构规范的 fallback 使用 Moshi 全天候高速反序列化；对直爬的复杂嵌套使用 JSON 直接定位解析。 |
| **图像加载 (Image)** | **Coil (Compose-Coil)** | 对部分热词卡片预览图进行自适应轻量异步加载与内存圆角图层裁剪。 |

---

## 🏗️ 架构设计 (MVVM Pattern)

为了保持代码高内聚、低耦合，项划分为了极度规范的 **MVVM 分层架构**：

```
Com.example
 ├─ MainActivity.kt             # UI 主视图入口，定义了 HotSearchScreen、SettingsDialog 等全部 Compose 界面
 ├─ data/
 │   ├─ api/
 │   │   └─ VvhanApiService.kt  # Retrofit 网络备用接口定义
 │   ├─ db/
 │   │   ├─ AppDatabase.kt      # Room数据库实例，采用单例构建并支持无缝降级迁移
 │   │   ├─ HotTopicDao.kt      # SQLite 交互 DAO 接口，包含根据平台查询、更新替换、插入设置等复杂语义
 │   │   ├─ HotTopicEntity.kt   # 热搜话题实体，包含了 rank 标题、简介、大图URL、深度页链接及更新时间等
 │   │   └─ PlatformSettingEntity.kt # 平台个性化排序屏蔽、颜色预设、可见性本地设置实体
 │   └─ repository/
 │       └─ HotTopicRepository.kt    # 数据仓库。融合直接抓取、网络调用和离线数据库缓存
 ├─ viewmodel/
 │   └─ HotTopicViewModel.kt    # 热词领域控制器。调度并合并以上各平台数据流，承载各种UI状态。
 └─ ui/
     └─ theme/                  # M3 动态色彩、高阶排版粗细和基础形状的配置包 (Theme.kt, Color.kt, Type.kt)
```

---

## 📡 接口与解析机制解析

### 1. 备用接口 API 调用格式
```http
GET https://api.vvhan.com/api/hotlist?type={platform}
```
*   `type` 可选值：`wb` (微博)、`zhihu` (知乎)、`baidu` (百度)、`bilibili` (B站)、`douyin` (抖音)、`toutiao` (头条)、`it` (IT之家)、`36kr` (36氪)、`daily` (知乎日报)、`thepaper` (澎湃新闻)、`hupu` (虎扑)、`huxiu` (虎嗅)、`woshipm` (人人都是产品经理)、`douban` (豆瓣)、`gcores` (机核)、`chongbuluo` (虫部落) 等。

### 2. 直爬官方解析模型
以最可靠的**微博热搜**为例：
*   **真实网络接口**：`https://weibo.com/ajax/side/hotSearch`
*   **请求头注入**：
    *   `User-Agent`: `Mozilla/5.0 ... Chrome/124.0.0.0 Safari/537.36`
    *   `Referer`: `https://s.weibo.com`
*   **解析核心**：从 JSON `data.realtime` 中，精确拉取 `note` 作为标题，`num` 换算成几万热度字样，拼接出 `https://s.weibo.com/weibo?q={scheme}` 极速原生跳转。直爬机制对各大平台的数据格式变化具有极高健壮性。

---

## 🧪 验证与运行指导

您可以利用本地测试环境运行应用。
*   项目内置了高质量的 JVM 本地 Robolectric 运行单元测试（`ExampleRobolectricTest.kt`），模拟 Android 虚拟机行为对 Activity 页面装载、依赖注入流程以及 R 属性值进行断言。
*   在命令行运行以下 Gradle 指令以检验所有单元测试：
    ```bash
    gradle :app:testDebugUnitTest
    ```
*   如果您是在 AI Studio 中进行代码变更，只需执行 `compile_applet` 或者让 Android Incremental Build System 自动部署到浏览器端的 Streaming Emulator 即可进行实时直观的操作体验！
