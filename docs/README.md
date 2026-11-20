# AutoLedger 项目文档

> 面向维护者的工程文档，基于 2026-11-20 工作区源码逐文件通读整理。所有结论都标注了可核查的文件与行号。

## 索引

| 文档 | 内容 | 适合谁 |
| --- | --- | --- |
| [01 项目总览](01-项目总览.md) | 定位、功能矩阵（含**真实实现状态**）、技术栈与版本、组件清单、构建运行、首次使用路径 | 第一次接触项目 |
| [02 架构与数据流](02-架构与数据流.md) | 分层图、Room 实体与 DDL、DAO 全表、三条迁移链路、导入去重算法、报表聚合、线程约束 | 要改数据层 |
| [03 功能模块详解](03-功能模块详解.md) | 记账/首页/搜索筛选/报表/截屏记账/AI 识图/通知监听/短信记账/提醒/小部件/导入导出/WebDAV 云备份/分类预算/LSPosed/系统权限体检/设置页树/主题 | 要改具体功能 |
| [04 开发与扩展指南](04-开发与扩展指南.md) | 目录约定、构建调试命令、6 组改造配方（加分类/加字段/换 AI/通知监听扩展/模块注入/新提醒）、已知问题清单、路线图 | 要动手写代码 |
| [05 Root / 非 Root 双模式设计](05-Root与非Root双模式设计.md) | LSPosed 模块职责边界（能做/不能做逐条）、非 Root 采集与保活、Root 增强点与失效场景、双模式对比表、默认模式与切换策略；§7 补了既有方案调研（SmartLedger / gkd / AutoStarter / libxposed）与三处设计修订：新增 ADB·Shizuku 模式 C、折叠通知下放非 Root、受限设置阻塞 | 要决定架构方向 |

## 30 秒速览

- **是什么**：单机 Android 记账 App（Kotlin + Compose + Miuix 风格），同时是一个 LSPosed 模块（`scope=system`）。
- **数据**：Room v2 单表 `Bill`（`timestamp` 索引 + `external_id` 唯一索引），DAO 全 Flow；偏好走 SharedPreferences（`AppPrefs` 双向绑定 `mutableStateOf`）。
- **三条自动链路**：① 截屏（无障碍/Root/MediaProjection）→ 多模态模型 → 通知栏确认 → 入账；② 通知监听 → 规则解析 → 直接入库 → 「自动记账」通知（可撤销）；③ 短信广播 `SMS_RECEIVED` → 同一套规则内核 → 同一个通知出口（`BillNotifier`）。
- **自愈（模式 C）**：用户用 adb/Shizuku 授予 `WRITE_SECURE_SETTINGS` 后，`HealthJobService` 每 15 分钟把被 ROM 关掉的「通知使用权 / 无障碍」写回 Secure 设置，设置页「一键修复」也走同一条路径。
- **诊断入口**：设置 → 系统权限与保活，9 项开关状态 + 一键跳转 + 一键修复（有自愈权限时真写回）+ 两条通道的最近入账时间，用来快速判断「为什么没记账」。
- **尚未实现的**：LSPosed 注入（`xposed/LedgerModule` 仅日志，不写 `enabled_notification_listeners`）；折叠通知已在非 Root 用 `activeNotifications` + group 配对补全，hook 方案降为兜底。
- **构建**：AGP 9.2.0 / Kotlin+Compose 2.4.20 / KSP 2.3.12 / compileSdk 37（min 27）/ Java 17；仓库**已内置 Gradle Wrapper 9.4.1**，直接 `./gradlew :app:assembleDebug`（Windows 用 `gradlew.bat`）。
- **主题**：`ui/LedgerTheme.kt` 深浅两套语义色，设置 → 外观 三态切换（跟随系统 / 浅色 / 深色）。
- **云备份**：设置 → 云备份 填 WebDAV（坚果云/群晖），支持立即备份、一键恢复、≥20h 一次的定时备份（保留最近 7 份日备）。

## 代码规模与热点文件

| 文件 | 行数 | 备注 |
| --- | --- | --- |
| `settings/SettingsScreen.kt` | 1460 | 15 个枚举页的主文件，共用组件已抽到 `SettingsUi.kt`，3 页在 `settings/pages/` |
| `ui/screen/SearchScreen.kt` | 370 | 搜索筛选（LIKE + BETWEEN，硬上限 300 条） |
| `ui/screen/ReportScreen.kt` | 1022 | 折线/柱状/环形三套自绘图表 |
| `ui/screen/AddScreen.kt` | 817 | 自绘日历 + 自定义键盘，保存走 `rememberCoroutineScope` |
| `ui/screen/HomeScreen.kt` | 648 | 分页列表 + 预算卡 |
| `ui/LedgerApp.kt` | 444 | 三 tab 壳 + AI 扫一扫 |
| `screenshot/ScreenshotCaptureService.kt` | 276 | VirtualDisplay + ImageReader 抓帧 |
| `data/NotificationRules.kt` | 424 | 通知 + 短信解析规则库（纯函数，可单测） |
| `service/LedgerNotificationListener.kt` | 229 | 折叠摘要补全 + 10s 内容归并 |
| `service/SmsReceiver.kt` | 100 | `SMS_RECEIVED` 广播、长短信合并、goAsync |
| `service/HealthJobService.kt` | 74 | JobScheduler 心跳，写回被关掉的通道 |
| `util/SystemAccess.kt` | 245 | 9 项权限探测 + 跳转 + 自愈写回 |
| `data/CsvImporter.kt` | 268 | 手写 CSV 状态机 + Excel 日期序列（`import` 已改 suspend） |
| `data/LedgerRepository.kt` | 192 | 全 suspend 仓库，聚合与游标分页入口 |
| `data/LedgerDao.kt` | 133 | `pageBefore` 游标查询、`importRows`、三条聚合 SQL |
| `viewmodel/HomeViewModel.kt` | 140 | 游标分页 + 首屏/追加页合并去重 |
| `widget/LedgerWidgetProvider.kt` | 87 | 两规格（2×2 / 4×2）+ `goAsync` + 聚合 SQL |
| `ui/LedgerTheme.kt` | 46 | 深浅两套语义色，普通 getter（Canvas 可用） |
| `data/BackupRunner.kt` | 96 | 备份/恢复编排，20h 节流 + 日备保留 7 份 |
| `data/WebDavClient.kt` | 132 | WebDAV MKCOL/PUT/GET/PROPFIND + Basic Auth |

## 三条最该先修的问题

1. `NotificationRules` 没有单元测试：15 条真实短信/通知文本还只跑在临时脚本里，改词表没人拦（`data/NotificationRules.kt`）。
2. `release` 构建用 debug 签名且未混淆（`app/build.gradle.kts`），当前产物不能直接发布。
3. 主键/分页/`runBlocking` 三项已修（`3d6e5fb`）；剩下 `CREATE UNIQUE INDEX` 无兜底（历史脏单号会首启崩溃）与 AI Key 明文存偏好。

详见 [04 开发与扩展指南 · 已知问题清单](04-开发与扩展指南.md#4-已知问题清单)。