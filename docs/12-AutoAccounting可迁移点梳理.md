# 12 AutoAccounting / AutoRuleSubmit 可迁移点梳理

> 面向维护者的**外部项目逆向笔记**。阅读对象是 `AutoAccountingOrg/AutoAccounting`（下称 AA）与 `AutoAccountingOrg/AutoRuleSubmit`（下称 ARS）两个公开仓库的源码，逐条评估「能不能搬到本项目」。
>
> **勘察基线**
>
> | 项 | 值 |
> | --- | --- |
> | AA 仓库 | `https://github.com/AutoAccountingOrg/AutoAccounting` |
> | AA 本地克隆 | `D:\Dev\tmp\aa-repos\AutoAccounting`（45 MB） |
> | AA commit | `ed5c2ad94b152f7351d76fcd33f9099a5f7532eb`（2026-10-23） |
> | AA 版本 | `versionName 4.0.2`（`app/build.gradle.kts:55`） |
> | ARS 仓库 | `https://github.com/AutoAccountingOrg/AutoRuleSubmit` |
> | ARS commit | `0f4bdd500e96e9412a9c41c541dba4ceb0fde512`（2026-11-21） |
> | 规则仓库 | `AutoAccountingOrg/AutoRule` —— **已 404**，clone 报 `repository not found` |
> | 本项目基线 | `D:\个人项目\auto-ledger`，`0.3.0` / `versionCode 4` |
>
> **关于「待补充」**：立项时技术栈与目标平台一栏是空的，但本项目源码已经把答案写死了 —— Android 原生、Kotlin + Jetpack Compose（Miuix 风格）、`com.eta.autoledger`、Room v3 单表 `Bill`、单机无服务端、可作 LSPosed 模块（`scope=system`）。本文全部按这条基线评估，不再假设其他平台。
>
> **关于 AutoRule 下架这件事**：ARS 的 README 写得很直白 —— `由于原 AutoRule 仓库因银行投诉而下架，我们创建了这个新的仓库来继续为用户提供服务`、`原仓库状态: 因银行投诉暂时转为私有（未删除）`、`代码公开: 不再公开规则代码，仅支持功能请求和BUG反馈`。这不是花边新闻，它直接决定了本文第 D 组（规则体系）该怎么落地：**AA 那套 290 条公开规则已经不可获取，任何「照抄它的规则库」的方案都不成立**，能搬的只有「规则的形状 + 分发机制」，具体规则必须本项目自己攒。详见 D6、§9 风险表 R1。

---

## 0. 先看结论

### 0.1 两个项目的定位差异

| 维度 | AutoAccounting | 本项目 auto-ledger |
| --- | --- | --- |
| 形态 | 多模块 Gradle：`app` + `server` + `shell` + `hook` + `tap` + `dex` + `ocr` + `test` | 单模块 `app` |
| 解析在哪跑 | **App 内置 Ktor 服务端**（`LocalNetwork` + `127.0.0.1`），解析逻辑全在 `server` 模块 | 全在 App 进程内，`NotificationRules` 纯函数 + `LedgerRepository` 落库 |
| 规则形态 | 规则 = 一段 **JavaScript**，存 Room 表 `RuleModel.js`，QuickJS 执行 | 规则 = Kotlin 常量与正则，编译期固定 |
| 采集通道 | Xposed Hook（微信/支付宝/系统通知/短信 Intent）**为主**，`NotificationListenerService` + `SMS_RECEIVED` 广播为**非 Root 兜底** | 通知监听 + 短信广播为主，无障碍/Root 仅用于截屏 |
| 权限索取 | 极激进：`QUERY_ALL_PACKAGES`、`READ_SMS`、`SYSTEM_ALERT_WINDOW`、Xposed | 克制：只有 `RECEIVE_SMS`，无 `READ_SMS`、无 `QUERY_ALL_PACKAGES`、无悬浮窗 |
| 规则分发 | 规则包 zip → 私有服务器 `license.ez-book.org` / `rule/update` | 无（规则编译在源码里） |
| 去重 | 内存 `MD5HashTable`（60 s / 300 s TTL） | 内存 LRU `seen` + `merged` 窗口 + Room `external_id` 唯一索引 + 分钟级指纹 |

一句话：**AA 是「能力上限很高、合规风险也很高」的重型方案；本项目是「能力保守、但每一步都能对用户解释清楚」的轻方案。** 迁移的价值不在于把它那套架构搬过来，而在于它踩过的坑和它试出来的参数。

### 0.2 迁移优先级矩阵

改动量口径：**S** ≤ 1 文件 ≤ 50 行；**M** 2–4 文件 50–200 行；**L** ≥ 5 文件或需新增表/服务/协议。

| 编号 | 可迁移点 | 优先级 | 改动量 | 一句话理由 |
| --- | --- | --- | --- | --- |
| A1 | `requestRebind` 监听器自愈 | **P0** | S | 本项目 `onListenerDisconnected` 只打日志，MIUI/HyperOS 杀进程后彻底失聪 |
| A2 | 持久化 TTL 去重表替代纯内存 LRU | P1 | S | 进程重启后 `seen`/`merged` 清零，重复投递会二次入账 |
| A3 | 包名白名单 + 关键词白/黑名单双层过滤 | P1 | M | 本项目包名表硬编码在源码，加一个新银行 App 要发版 |
| A4 | 折叠摘要与子通知的双路径归并 | 已具备 | — | 本项目 `collectGroupChildren` + `merged` 已经比 AA 完整，仅作结论记录 |
| A5 | 文本三级回落（补 `EXTRA_SUB_TEXT`） | P3 | S | 顺序本项目已对，但少了 `subText` 这一档，个别银行把金额放进 subText |
| B1 | `SMS_DELIVER`（Hook）与 `SMS_RECEIVED`（广播）的取舍依据 | P2 | S | 决定 B3 值不值得做，先看这份对照 |
| B2 | `Normalizer.normalize(..., NFC)` 短信正文规范化 | **P0** | S | 银行短信里全角数字/组合字符会让 `AMOUNT_YUAN` 直接失配，成本极低 |
| B3 | 长短信 160 字分片拼装 | 已具备 | — | 本项目 `getMessagesFromIntent` + `groupBy(originatingAddress)` 比 AA 手拼更严谨 |
| B4 | 发信人白名单 `95xxx` + 正文关键词双闸 | 已具备 | — | 本项目 `FINANCE_SENDER` 正则覆盖更全 |
| B5 | 短信专用噪声词表与通知表分离 | 已具备 | — | 本项目 `SMS_BLOCK_WORDS` 独立，注释已写明理由 |
| B6 | 短信「伪造通知」这条路（`com.android.mms` 通知当短信处理） | P2 | S | 部分 ROM 的通知栏会聚合短信，本项目 `SMS_PKGS` 走的是同一条 `parse` 分支 |
| C1 | 无障碍免手动授权（`settings put secure`） | **P0** | M | 本项目已有 `SystemAccess.appendComponent`，只差无障碍这一项没接上 |
| C2 | 覆盖安装后保持通知使用权 | **P0** | M | AA 用 Xposed 绕过；本项目可用 `WRITE_SECURE_SETTINGS` + `HealthJobService` 做非 Root 版 |
| C3 | `Shell` 抽象（Root → Shizuku 自动回退） | P1 | L | 本项目 `RootScreenshot` 只有 Root 一条路，Shizuku 用户完全用不了 |
| C4 | 通知 Hook 双点（`enqueueNotificationInternal` + `notifyCallNotificationEventListenerOnPosted`） | P2 | M | 免通知使用权，但需 LSPosed，且是本项目 `LedgerModule` 注释里的待办 ③ |
| C5 | 短信 Hook（`InboundSmsHandler.dispatchIntent`） | P2 | M | 免 `RECEIVE_SMS` 运行时权限，但只对 Root 用户有意义 |
| C6 | `HookerManifest` / `PartHooker` 插件化 Hook 编排 | P3 | L | AA 有好几个 App 要 hook 才需要；本项目目前只有 system_server 一个点 |
| C7 | `PageSignature` 页面指纹（OCR 触发去重） | P1 | M | 本项目截屏记账靠用户手动触发，加指纹能省一次模型调用 |
| C8 | 微信 `tinker` 目录删除 / `MMKRichtText.appendText` 渲染点 Hook | P3 | M | 只在做「应用内数据 Hook」时才需要，当前不在路线内 |
| D1 | 规则 = 数据（存表 + 可启用/禁用）而非编译期常量 | **P0** | L | 这是两个项目最大的架构差，也是本项目「加一个银行要发版」的根因 |
| D2 | `user` / `system` 两级规则 + 用户规则优先、系统规则兜底 | P1 | M | 与 D1 配套；避免用户改一条规则就把内置规则全废掉 |
| D3 | `DataType` 三态（`OCR` / `DATA` / `NOTICE`） | P1 | S | 本项目把三态压成一条 `parse`，规则匹配时区分不出来源 |
| D4 | AI 兜底的严格 prompt（禁猜测、无关返回 `{}`） | P1 | S | 本项目 `VisionHelper` 只有识图，没有文本兜底；prompt 可直接借鉴 |
| D5 | `DataConvert` 扁平化重试（解决「双重 JSON」） | P3 | S | 本项目解析的是纯文本不是 JSON，暂时用不上 |
| D6 | 规则市场：Issue → CI 校验 → 评论结果 → 关闭 | P2 | L | ARS 那套 290 条规则（31 个包名分节）的规模证明这条路可行，但需自建 CI 与私有测试仓库 |
| D7 | 规则命名规范（`数据/通知/ocr` 三目录 + 包名分节） | P1 | S | 纯约定，零成本，直接照抄目录结构 |
| E1 | `TransferRecognizer` 转账/还款成对合并 | P1 | M | 本项目两笔（转出+转入）会记成两条，报表里转账被算进支出 |
| E2 | `MD5HashTable` 流式过期 + 定期清理 | P2 | S | 比 `LinkedHashMap` LRU 更省内存，但需持久化才有意义（见 A2） |
| E3 | `AssetsMap` 包名 → App 名/图标 | P2 | S | 本项目账单列表只显示 `source` 字符串，没有图标 |
| E4 | `DuplicateDetector` / `BillMerger` | P3 | — | 与本项目 `ParseDedup` + `addImported` 职责重叠，不迁移（见 E4 说明） |
| F1 | 内置 Ktor 服务端 | **不迁移** | — | 单机 App 多开一个 HTTP 服务端是纯负担 |
| F2 | QuickJS 全量 JS 规则引擎 | **不迁移** | — | 本项目 D1 的落地形态应是 Kotlin DSL/脚本而非 JS 引擎 |
| F3 | `tap` 背面敲击（tflite） | **不迁移** | — | 与记账主链路无关 |
| F4 | `QUERY_ALL_PACKAGES` / `READ_SMS` / 悬浮窗 | **不迁移** | — | 合规成本 >> 收益，详见 §F4 |

---

## A. 通知通道

### A1 监听器断开后自愈重绑 `requestRebind`

**原项目怎么实现**

`app/src/main/java/net/ankio/auto/service/NotificationService.kt:69-79`：

```kotlin
override fun onListenerDisconnected() {
    if (WorkMode.isXposed()) return          // 70 行：Xposed 模式跳过，它不依赖监听器
    requestRebind(ComponentName(this, NotificationListenerService::class.java))  // 72 行
}
```

`NotificationListenerService` 被系统解绑（ROM 回收、用户清理、进程被杀后重建失败）时，`onListenerDisconnected` 是唯一还能拿到执行机会的回调，在这里 `requestRebind` 是官方推荐的自愈手段。

**本项目场景**

`app/src/main/java/com/eta/autoledger/service/LedgerNotificationListener.kt:66-70`：

```kotlin
override fun onListenerConnected()    { Log.i(TAG, "notification listener connected") }
override fun onListenerDisconnected() { Log.i(TAG, "notification listener disconnected") }
```

断开后就是永久静默，`HealthJobService` 每 15 分钟跑一次也只会写 Secure Settings，**不会把监听器重新拉起来**。

**接入所需权限 / 改动量**

无需新权限。改动量 **S**：`LedgerNotificationListener.kt` 加 6 行，`onListenerDisconnected` 里 `requestRebind(ComponentName(this, LedgerNotificationListener::class.java))`，与 `LedgerModule` 激活时跳过重绑（模块自己会 hook NMS，重绑反而和 Hook 打架）。

**限制与风险**

- 需配节流：部分 ROM（尤其 HyperOS）会在解绑后立刻拒绝重绑，无节流会变成 1 秒一次的死循环。建议 ≥ 30 s 间隔，并把「连续失败 N 次」写进 `AppPrefs` 用于体检页展示。
- `requestRebind` 返回 `void`，成败无从得知，只能靠 `onListenerConnected` 是否又被调用判断。
- 与 `SystemAccess.appendComponent` 写入 `enabled_notification_listeners` 有交互：重绑成功后可能触发一次系统列表重排，`appendComponent` 的「写完重读校验」逻辑需要能容忍这种抖动（`SystemAccess.kt` 现有的重读校验逻辑已足够，但要确认不会误判成「写入被回滚」）。

---

### A2 持久化 TTL 去重表，替代纯内存 LRU

**原项目怎么实现**

`server/src/main/java/org/ezbook/server/tools/MD5HashTable.kt`（125 行）：

```kotlin
class MD5HashTable(ttlMs: Long = 60_000, clock: () -> Long = { System.currentTimeMillis() }) {
    private val timestamps = ConcurrentHashMap<String, Long>()
    // put 时更新时间戳 = 滑动过期；putCount % cleanupThreshold(100) == 0 才 sweep
    // contains() 一次性检查过期并 remove(k, ts)
}
```

两个实例：App 侧 `NotificationService.kt:39` 用默认 60 s；Xposed 侧 `xposed/core/utils/AnalysisUtils.kt` 用 `MD5HashTable(300_000)`（5 分钟，因为 Hook 侧一次支付会连发好几个事件）。

**本项目场景**

三套去重都在**内存**里，进程一死全丢：

| 机制 | 位置 | 存活期 |
| --- | --- | --- |
| `seen` LRU（`MAX_SEEN = 200`） | `LedgerNotificationListener.kt:38-42`、`:222` | 进程内 |
| `merged` 窗口（`MERGE_WINDOW_MS = 10_000`） | `LedgerNotificationListener.kt:45`、`:225` | 进程内 |
| `ParseDedup`（`WINDOW_MS = 5*60_000`） | `data/ParseDedup.kt` | 进程内 |
| `ParseDedup`（`WINDOW_MS = 5*60_000`，键 = `"%.2f|%s|%d"`，时间截到**分钟**） | `data/ParseDedup.kt:21`、`:32`、`:44`；唯一调用点 `LedgerRepository.kt:220` | 进程内 |
| `external_id` 唯一索引 + `LedgerRepository.fingerprint`（`"$timeMillis|%.2f|$type"`，**毫秒**） | Room `Bill` 表、`LedgerRepository.kt:229-231`、`:184-200` | **持久** |

真正的持久兜底只有 Room 那一层：`LedgerRepository.fingerprint` 用毫秒，本身是对的（`:229` 注释：分钟级会把「同分钟同额同类型」两笔真实账单误判成一笔）；**风险点在于喂给它的 `ParseDedup.fingerprint` 是分钟级** —— 那才是「便利店连刷两次 9.9 被吞一笔」的来源。两者粒度不一致，是这个去重体系里最隐蔽的坑。

**接入所需权限 / 改动量**

无需新权限。改动量 **S**：新增 `data/SeenStore.kt`（Room 一张 `seen_key` 表，`key` 主键 + `at` 时间戳，启动时 `DELETE WHERE at < now - ttl`），把 `seen` 与 `merged` 的写入改为「内存 + 落库」双写，读取时先查内存再查库。

**限制与风险**

- 表会持续增长，必须配定期清理（`HealthJobService` 已有 15 分钟节拍，可直接挂上去）。
- 关键词指纹（AA 用 `"$pkg$title$text"`）比本项目 `dedupeKey`（含毫秒 `postTime`）更稳定 —— `ParseDedup.kt` 的注释已经记录过这个坑（`15:11 那两条一模一样的 46.94`）。**迁移时应把 `dedupeKey` 的 `postTime` 去掉或截断到分钟**，这是独立于持久化的第二个改动点，建议一起做。

---

### A3 包名白名单 + 关键词白/黑名单的双层过滤

**原项目怎么实现**

顺序写在 `NotificationService.kt:80-130` 的 `checkNotification`，每一步都是「不满足就 return」：

```kotlin
if (!apps.contains(pkg)) return                      // ① 包名白名单 PrefManager.appWhiteList
val content = "$title $text".trim()
if (!AnalysisUtils.inWhitelist(content)) return      // ② 关键词白名单：命中任一才继续
if (AnalysisUtils.inBlackList(content)) return       // ③ 关键词黑名单：命中任一即丢
```

`service/AnalysisUtils.kt`（42 行）的实现是「按行 `trim()` 后 `data.contains(line)`」，也就是说**关键词表是一个可编辑的多行文本框**，用户自己能加。默认值来自 `DefaultData.kt:645-668`：`DATA_FILTER` 是一串金融词（`银行,信用卡,借记卡,公积金,元,￥,¥,人民币,消费,支付,支出,...`），`APP_FILTER` 是一串包名。

**本项目场景**

三处全是**源码常量**，加一个银行要发版：

| 常量 | 位置 | 条数 |
| --- | --- | --- |
| `ALIPAY_PKGS` / `WECHAT_PKGS` / `BANK_PKGS` / `SMS_PKGS` / `OTHER_PKGS` | `NotificationRules.kt:60-99` | 1 / 1 / 12 / 4 / 10 |
| `BLOCK_WORDS` | `NotificationRules.kt:102-113` | ~40 |
| `SMS_BLOCK_WORDS` | `NotificationRules.kt:383-393` | 短信专用 |

**好消息**：本项目的关键词过滤已经比 AA 强 —— `BLOCK_WORDS` + `FUTURE_CHARGE_PATTERNS`（4 条预告正则）+ `NOT_CONSUMED_PATTERNS` + `PAID_PROOF_WORDS` + `COMPLETED_WORDS` 构成了一套「先拦预告、再看是否完成」的判定，AA 那边完全没有这一层（它靠 AI prompt 里的「忽略非交易文本」硬规则）。

**要迁的只是「可配置」这件事**，不是词表内容。

**接入所需权限 / 改动量**

无需新权限。改动量 **M**（3 文件）：
1. `AppPrefs.kt` 加 `extraBankPkgs: Set<String>`、`extraBlockWords`、`extraForceWords`（`ignoreKeywords` / `forceKeywords` 已有，见 `AppPrefs.kt:237-257`，可复用同一套 `mutableStateOf` 双向绑定）。
2. `NotificationRules.channelOf` 改成接受「用户包名表」参数，或在 `LedgerNotificationListener.handle` 里先做一次「用户表命中 → 强行走 BANK 渠道」的前置判断（**不改 `channelOf` 的纯函数签名更安全**，因为 `NotificationRules` 是 518 行的纯函数文件，被单测覆盖）。
3. `settings/SettingsScreen.kt` 加一页「通知来源」：包名列表可增删（用 `PackageManager` 拉已安装应用，需要 `<queries>` 声明，见风险）。

**限制与风险**

- **`<queries>` 声明**：Android 11+ 想枚举「有哪些银行 App 装了」，必须在 `AndroidManifest.xml` 加 `<queries><intent><action android:name="android.intent.action.MAIN"/></intent></queries>` 或具体包名列表。本项目当前 Manifest **没有任何 `<queries>` 块**（已确认）。加宽泛 `<queries>` 不是 `QUERY_ALL_PACKAGES`，上架风险可控，但仍会被审核问用途 —— 建议只列已知银行/支付包名（白名单式 `<queries><package android:name="cmb.pb"/></queries>`），不用 MAIN intent 通配。
- 用户乱加关键词会静默漏记（黑名单命中即丢，无提示）。必须配「本条被过滤」的可视化（例如在通知解析日志页显示最近 20 条被丢的通知），否则用户只会觉得「这 App 坏了」。
- 包名白名单一旦用户可改，`channelOf` 的 `confidence` 语义会变（用户加的包名不该享受内置表的高置信度）。

---

### A4 折叠摘要与子通知的双路径归并

**原项目怎么实现**

AA **没有**专门处理这件事 —— 它靠 `MD5HashTable` 的去重 + 每条通知独立解析，折叠摘要那条「你有 2 笔新消息」会被关键词白名单直接滤掉（不含金融词），子通知各自解析后照常入账。也就是说 AA 在折叠场景下是「漏处理但不出错」。

**本项目场景**

本项目做得更完整：`LedgerNotificationListener.kt:88-96` 检测 `FLAG_GROUP_SUMMARY` 后主动 `collectGroupChildren(sbn)`（`:101-108`，用公开的 `Notification.getGroup()` + `activeNotifications` 捞同组），逐个 `handleOne`，再用 `merged`（10 s 窗口，键 = 渠道+方向+金额+note 片段）把「摘要捞一遍」和「系统自己逐条回调」两条路径归并。

**结论：这条不用迁，本项目是领先的。** 记录在此是为了避免后续重复调研。

**唯一可借鉴的细节**：AA 的 `merged` 等价物是 5 分钟 TTL，本项目是 10 秒。10 秒对「摘要→子通知」够用，但**对「微信通知先到、支付宝通知后到」这类跨 App 重复收不到**。跨渠道去重本项目另走 `LedgerRepository.isRecentDuplicate`（`AUTO_IMPORT_WINDOW_MS = 2 * 60_000`，`LedgerRepository.kt:30`、`:170-181`），窗口 2 分钟，是合理的。

**顺带发现的一处源码不一致**（本次勘察实测）：`LedgerNotificationListener.kt:45` 的 KDoc 写的是「**120 秒**窗口内『渠道 + 金额 + 方向』相同只入一次」，但同文件 `:225` 的常量是 `MERGE_WINDOW_MS = 10_000L`（10 秒）。代码是 10 秒、注释是 120 秒，两者必有一处过时。落地 A2 时顺带修正，否则后来的人按注释去调参会把窗口调大 12 倍，导致「同渠道连刷两次同额」被误归并。

两块窗口的分工也要在代码注释里写清楚，否则后来的人会把 `MERGE_WINDOW_MS` 当成全局去重窗口去调大。

---

### A5 `EXTRA_BIG_TEXT` 优先于 `EXTRA_TEXT`

**原项目怎么实现**

`NotificationService.kt:51-59`（`text` 段落为 `:55-57`）：先 `getString(EXTRA_BIG_TEXT)`，空则回落 `getString(EXTRA_TEXT)`，两层都包在 `runCatching` 里。

**本项目场景**：`NotificationRules.bodyOf`（`:421`）是 `raw.bigText.trim().ifBlank { raw.text.trim() }`，顺序一致，且额外做了空白压缩。**顺序一致，交叉验证通过；但少一档回落，见下。**

**可借鉴的一点**：AA 在 `NotificationService` 里用 `getString`，本项目用 `getCharSequence(...)?.toString()`。后者更对 —— `EXTRA_BIG_TEXT` 常是 `SpannableString`（银行通知里带 `¥` 加粗），`getString` 在某些 ROM 上会返回 null 而 `getCharSequence` 不会。本项目写法**优于** AA 的 App 侧。

**但 AA 的 Xposed 侧（`NotificationHooker.processNotification`，`:120-124`）多了一级回落**：`EXTRA_BIG_TEXT` → `EXTRA_TEXT` → **`EXTRA_SUB_TEXT`**。本项目的 `bodyOf`（`:421`）只有两级。值得补第三级 —— 少数 App（尤其部分银行的定制通知）会把金额放在 `subText` 里，主文本只有「交易提醒」。改动量：1 行，`bodyOf` 里加 `?: raw.subText`（同时 `NotifyRaw` 与 `LedgerNotificationListener` 的 `extract()` 各加一个字段），合计 **S ≤ 15 行**。

---

## B. 短信通道

### B1 `SMS_DELIVER`（Hook）与 `SMS_RECEIVED`（广播）的取舍依据

**原项目怎么实现**

两条完全独立的链路：

| 链路 | 入口 | 依赖 |
| --- | --- | --- |
| 非 Root | `app/src/main/java/net/ankio/auto/service/SmsReceiver.kt`（126 行），`BroadcastReceiver` on `SMS_RECEIVED_ACTION` | `RECEIVE_SMS` 运行时权限 |
| Root/Xposed | `app/src/main/java/net/ankio/auto/xposed/hooks/sms/hooks/SmsIntentHooker.kt`（82 行），hook `InboundSmsHandler.dispatchIntent` | LSPosed 激活，**不需要** `RECEIVE_SMS` |

关键差异在广播级的先后：`SMS_DELIVER` 是**发给短信 App 的**（只有默认短信 App 收），`SMS_RECEIVED` 是**发给所有注册者的**（任何持有 `RECEIVE_SMS` 的 App 都收）。AA 在 Hook 侧 hook 的是 `dispatchIntent` 且只放行 `SMS_DELIVER_ACTION`（`SmsIntentHooker.kt:57`），也就是说它截的是「要交给短信 App 的那一次投递」。

**本项目场景**

只有 `SMS_RECEIVED` 一条路（`service/SmsReceiver.kt:112` 行，`android:priority="999"`，`AndroidManifest.xml:97-104`），`permission="android.permission.BROADCAST_SMS"` 已加（防伪造广播，这一点比 AA 更严谨 —— AA 只在代码里校验 action）。

**结论**：本项目不需要 C5 那条 Hook 路线（见 C5），除非将来做 LSPosed 全功能版。**但 B1 有一个对本项目立刻有用的结论**：`SMS_RECEIVED` 的高优先级（999）能抢在系统短信 App 之前拿到，但**不能** abort 广播（否则用户收不到短信），AA 也没这么干。现在 `priority=999` 已经是最优。

---

### B2 `Normalizer.normalize(..., NFC)` 规范化短信正文 ⭐ P0

**原项目怎么实现**

`SmsIntentHooker.kt:65-70`：

```kotlin
val sender = Normalizer.normalize(senderRaw, Normalizer.Form.NFC)
val body   = Normalizer.normalize(bodyRaw,   Normalizer.Form.NFC)
```

注释写明动机：消除全/半角与组合字符差异。银行短信的实际来源极杂（运营商网关、短信中心、不同代工厂的短信 App），同一个「元」可能是 U+5143、可能是全角兼容形式、可能带组合音标；`￥` vs `¥`（U+FFE5 vs U+00A5）是经典坑。

**本项目场景**

`SmsReceiver.kt` 拿到 `body` 后直接 `NotificationRules.parseSms`，中间**没有任何规范化**。`NotificationRules.kt:177-188` 的金额正则：

```kotlin
AMOUNT_YUAN = Regex("""(?:[¥￥]\s*)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?:元|圆)""")
```

正则里 `¥` 与 `￥` 都列了，`元|圆` 都列了 —— 说明作者已经撞过一次。但**全角数字**（`１００`）与**全角逗号**（`，`）没覆盖，这类短信一旦出现就是静默漏记，用户完全无感。

**接入所需权限 / 改动量**

无需权限。改动量 **S**（1 文件 ~10 行）：
1. `SmsReceiver.kt` 在 `parseSms` 前加 `Normalizer.normalize(body, Normalizer.Form.NFKC)`。
2. 顺带在 `NotificationRules.kt` 加一个 `normalizeDigits(text)`：把 `[０-９]`、`[．，]` 映射到半角 —— 因为 `NFKC` **能**把全角数字转半角，但把 `，`(U+FF0C) 转成 `,`(U+002C) 之后，`AMOUNT_YUAN` 的 `\d{1,3}(,\d{3})+` 分支反而能命中，这是净收益。

> **注意 `NFC` vs `NFKC` 的选择**：AA 用 `NFC`（只做合成，不做兼容分解）。对本项目更合适的是 `NFKC`，因为我们要的是「把全角数字变半角」，那属于兼容分解。代价是 `NFKC` 会把 `㎡` 之类的合字拆开 —— 在账单文本里无害。

**限制与风险**

- `NFKC` 会把罗马数字 `Ⅻ` 转成 `XII`、把 `①` 转成 `1`。正文里出现 `①` 之类符号的营销短信本来就会被 `BLOCK_WORDS` 拦掉，风险可忽略。
- 规范化会改变 `dedupeKey` / `parseSms` 的指纹输入，**必须对「规范化之后」的文本算指纹**，否则同一笔短信「规范化前」「规范化后」两条路径会算出两个指纹（当前只有一条路径，但改的时候要注意别在同一次改造里引入第二条）。

---

### B3 长短信 160 字分片拼装

**原项目怎么实现**

`SmsIntentHooker.kt` 走 `Telephony.Sms.Intents.getMessagesFromIntent(intent)` 拿到 `Array<SmsMessage>`，然后 `hooks/sms/utils/SmsMessageUtils.kt`（40 行）手工拼：`SMS_CHARACTER_LIMIT = 160`，单条取 `displayMessageBody`，多条 `StringBuilder` 累加。

**本项目场景**：`service/SmsReceiver.kt:62-73` 同样用 `Telephony.Sms.Intents.getMessagesFromIntent(intent)` 拿到 `List<SmsMessage>`，然后：

```kotlin
messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
    val body = parts.mapNotNull { it.messageBody }.joinToString("")
    ...
}
```

比 AA 多一层 `groupBy(originatingAddress)` —— 一次 PDU 里可能混着不同发信人的分片（彩信回落、多卡并存时会出现），AA 的 `SmsMessageUtils` 直接把整组 body 拼起来，会把两个发信人的短信拼成一条。**本项目这一步更严谨，保持现状。**

**已具备，不需要迁移 B3。**

**AA 为什么要手拼**：它在 Hook 侧拿 `dispatchIntent` 的 `Intent`，虽然也是同一个 API，但它的 `SmsMessageUtils` 是通用工具类，被 OCR/其他路径复用，所以写成了「给一组 SmsMessage 就拼」的形式。属于「工具化过度」，不必学。

---

### B4 发信人白名单 + 正文关键词双闸

**原项目怎么实现**

`sms` 链路只校验 `action == SMS_DELIVER_ACTION`（`SmsIntentHooker.kt:57`），**不校验发信人**；过滤全靠 `AnalysisUtils.inWhitelist(body)` 的正文关键词。也就是说「106 号段发来的营销短信」只要正文含「元」就会进分析。

**本项目场景**

`NotificationRules.FINANCE_SENDER`（`:395`）：

```kotlin
Regex("""^(?:\+?86)?(?:95\d{3,5}|\d{5,6}|1\d{10})$""")
```

配 `plausibleSender`（`:406`）与 `FINANCE_HINTS`（`:397`）做了「发信人必须是 95xxx 短号 / 5-6 位服务号 / 11 位手机号，且正文要带金融提示词」的双闸。**本项目明显更强。**

**可借鉴的补充**：AA 在 `SmsReceiver.kt` 里有一句 `if (!AnalysisUtils.inWhitelist(messageBody)) return`，把「白名单关键词」当**第一道闸**而不是「最后一道」。本项目的顺序是 `plausibleSender`（发信人+提示词）→ 噪声词 → 金额。两种顺序的差异体现在「发信人是 106 营销号但正文恰好含金融词」的情况下：AA 会进分析再被 AI 拦，本项目直接丢。**本项目的顺序更省算力，保持不变。**

---

### B5 短信专用噪声词表与通知表分离 ⭐ 已具备

**原项目怎么实现**：AA 共用一套 `PrefManager.dataFilter` / `dataFilterBlacklist`，短信与通知**不区分**。这是 AA 的一个缺陷 —— 信用卡消费短信几乎必带「可用额度」，而「可用额度」在任何一份为通知写的黑名单里都是必加词（通知里的可用额度提醒确实是营销）。

**本项目场景**：`NotificationRules.kt:383-393` 单独一份 `SMS_BLOCK_WORDS`，`:287` 的 `parseSms` 注释把理由写清楚了：

> 走和通知同一条内核，只是噪声词换成短信专用表 —— 信用卡消费短信常带「可用额度」，通知表会把真实消费当营销提醒误杀。

**这条不用迁，但要写进「反模式」清单**：任何「把两套词表合并成一套」的重构提议都应该被这份注释挡回去。

---

### B6 短信「伪造通知」这条路（`com.android.mms` 通知当短信处理）

**原项目怎么实现**

`NotificationService.kt:113-120`：通知监听里如果 `pkg == "com.android.mms"`，**不当通知处理**，而是构造 `{sender:"", body:text, t:now}` 并以 `DataType.DATA`（即「短信语义」）投给 `com.android.phone` 的规则集：

```kotlin
if (pkg == "com.android.mms") {
    // 按短信 JSON 构造，app 参数传 com.android.phone，让短信规则集接管
    JsAPI.analysis(..., DataType.DATA, smsJson, "com.android.phone")
} else {
    JsAPI.analysis(..., DataType.NOTICE, noticeJson, pkg)
}
```

动机：部分 ROM 或第三方短信 App 把收到的短信在通知栏展示，此时通知监听能拿到正文；但如果只按「通知」语义投给 `pkg` 的规则集，会因为该 App 没有「通知规则」而漏掉 —— 转成短信语义 + 短信规则集就命中了。

**本项目场景**

`SMS_PKGS`（`NotificationRules.kt:80-86`）已包含 `com.android.mms` / `com.google.android.apps.messaging` / `com.samsung.android.messaging` / `com.miui.smsextra`，`channelOf` 把它们归 `NotifyChannel.SMS`，`parse` 走 `parseAs(channel = SMS, ...)`，与 `parseSms` 共用内核。**结构上已经等价**，但有一个真实缺口：

- `parseSms` 会调 `plausibleSender(sender, text)`，而通知路径**拿不到 sender**（它在 `title` 里，格式因 App 而异）。当前 `parseAs` 对 `SMS` 渠道不校验发信人 —— 这是对的（否则全丢），但也意味着**通知来的短信比广播来的短信少一道闸**，106 营销短信如果被短信 App 弹了通知，会绕过发信人校验直接进金额解析。

**接入所需权限 / 改动量**：无需新权限。改动量 **S**：在 `parseAs` 里对 `channel == SMS` 且 `sender 可得`（`title` 能匹配 `FINANCE_SENDER`）时补校验；`title` 匹配不上就退回当前行为（不校验）。这样「能校验的校验，校验不了的保持宽松」，不引入回归。

**限制与风险**：同一个短信可能同时从广播和通知两条路进来 → 现有 `LedgerRepository.isRecentDuplicate`（2 分钟窗口）能兜住，但要确认 `source` 字段两条路一致（都是 `"短信记账"`，`NotificationRules.sourceOf(SMS)`）—— 一致，没问题。

---

## C. 权限与 Hook 增强

> 本组是本项目与 AA 差异最大、也最需要克制的一组。C1/C2 是**非 Root 就能做**的，价值最高；C3 是 Shizuku 用户的刚需；C4–C8 都要求 LSPosed，优先级相应降低。

### C1 无障碍免手动授权（`settings put secure`）⭐ P0

**原项目怎么实现**

`app/src/main/java/net/ankio/auto/service/ocr/OcrTools.kt`，两段配合（`requestPermission()` 在 `:91-108`，`tryEnableViaShell()` 在 `:111-120`，`openSettings()` 在 `:122-126`）：

```kotlin
// :94-99  —— 有 shell 能力才走静默授权，成功后等 800ms
val shell = Shell(BuildConfig.APPLICATION_ID)
val hasShell = shell.rootPermission() || shell.shizukuPermission()
if (hasShell) {
    tryEnableViaShell(shell)
    delay(800) // 等待服务启动
}

// :111-120 —— 真正的写入：先读现状，已含本服务就直接 return（幂等）
val current = shell.exec("settings get secure enabled_accessibility_services")
        .trim().let { if (it == "null") "" else it }     // 关键：系统未设置时返回字面量 "null"
if (current.contains(COMPONENT_NAME)) return
val newList = if (current.isEmpty()) COMPONENT_NAME else "$current:$COMPONENT_NAME"
shell.exec("settings put secure enabled_accessibility_services $newList")
shell.exec("settings put secure accessibility_enabled 1")
```

两个细节值得抄：① `settings get` 在键未设置时返回字符串 `"null"` 而不是空串，不处理就会拼出 `null:com.xxx/.Svc`；② 写完 `delay(800)` 再复查 `hasPermission()`（`:102`），失败才弹设置页（`:103-106`）。

`shell` 来自 `net.ankio.shell.Shell`（Root 优先，失败回退 Shizuku，见 C3）。没有 shell 时退回 `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`（`openSettings()`，`:122-126`）。

**本项目场景**

`util/SystemAccess.kt`（272 行）已经有**完全同构**的能力，但只接了通知监听一项：

- `appendComponent(context, "enabled_notification_listeners", target)`（`:209`）
- `Settings.Secure.putInt(..., Settings.Secure.ACCESSIBILITY_ENABLED, 1)`（`:220`）
- 写完 `Settings.Secure.getString` 重读校验（注释记录了 HyperOS / EMUI 会回滚写入）

**缺的就是把 `appendComponent` 用到 `enabled_accessibility_services` 上**，以及 `SystemAccessPage` 里那个「一键修复」按钮没有覆盖无障碍项。

**接入所需权限 / 改动量**

需要 `WRITE_SECURE_SETTINGS`（Manifest 已有，`AndroidManifest.xml:21-24`，`tools:ignore="ProtectedPermissions"`；运行时靠 `adb shell pm grant com.eta.autoledger android.permission.WRITE_SECURE_SETTINGS` 或 Shizuku）。改动量 **M**（2 文件 ~60 行）：
1. `SystemAccess.kt` 加 `appendAccessibilityService(context)`（复用 `appendComponent` + `sameComponent` 的冒号列表逻辑），键名 `"enabled_accessibility_services"`，同时置 `accessibility_enabled = 1`。
2. `settings/pages/SystemAccessPage.kt` 在无障碍项上加「一键开启」，走到上面那个函数，然后 `delay(800)` + 重读确认（复用现有 1.5 s 轮询节拍即可）。

**限制与风险**

- **HyperOS / EMUI 回滚**：`SystemAccess.appendComponent` 已经踩过这个坑并做了重读校验，但无障碍列表的回滚比通知监听更常见（系统会做「无障碍服务安全审计」）。失败时的兜底必须是「跳到系统设置页并高亮」，不能只弹个 Toast。
- `accessibility_enabled` 是全局开关，**用户为了别的 App 关掉无障碍时，我们不该强行打开**。正确做法：只在「本服务已在列表里但 `accessibility_enabled == 0`」时才置 1；服务不在列表里时不碰这个键。
- 无障碍权限的**审核风险远高于通知监听**（Google Play 对 accessibility API 用途审查严格）。本项目当前是自用/侧载定位还不是问题，一旦上架必须有明确用途说明。

---

### C2 覆盖安装后保持通知使用权 ⭐ P0

**原项目怎么实现**

AA 的思路是**绕过**：用 Xposed hook `NotificationManagerService`（见 C4），根本不依赖「通知使用权」这个授权。它在 `IntroPagePermissionFragment.kt` 里也明确写了这个取舍：

```kotlin
// Xposed 模式下只要求 Xposed 激活，非 Xposed 模式才加短信、通知两项可选权限
XposedModule.active()
```

**本项目场景**

本项目 `xposed/LedgerModule.kt`（21 行）的注释已经把这件事列为待办：

> ① 自动把本应用写入 `enabled_notification_listeners`（免手动授权）
> ② 覆盖安装后保持通知使用权
> ③ 可选 SystemUI 兜底采集

现状：`LedgerModule.onSystemServerStarting` 只有一行 `Log.i`，①②③ 都没实现。

**接入方案（不依赖 Xposed）**

这条是本项目**可以不靠 LSPosed 就做到**的，因为 `WRITE_SECURE_SETTINGS` 已经把「写 Secure Settings」这条路打开了：

1. **覆盖安装后复检**：Android 在 `ACTION_MY_PACKAGE_REPLACED` 时**会**保留 `enabled_notification_listeners`，真正丢权限的是「用户手动关了」和「某些 ROM 在包名变更/签名变更后清理」。所以要做的是「每次启动 + `MY_PACKAGE_REPLACED` 时检查一次，掉了就提示一键修复」，不是「主动重写」。
2. **`HealthJobService` 兜底**：`service/HealthJobService.kt` 已经在每 15 分钟写回被关的通道，把 `appendComponent(..., "enabled_notification_listeners", ...)` 挂进去即可（`SystemAccess.kt:209` 已有该函数）。
3. **Xposed 版**：`LedgerModule` 在 `onSystemServerStarting` 里直接调 `SystemAccess` 的等价逻辑（这是 ①）。

**接入所需权限 / 改动量**

`WRITE_SECURE_SETTINGS`（已有）。改动量 **M**（3 文件 ~80 行）：`HealthJobService.kt` 挂一次复检、`system` 侧 `LedgerModule.kt` 实现 ①、`AppPrefs` 记录「上次复检结果」供体检页展示。

**限制与风险**

- **绝不能每次启动都硬写** —— 用户明确关掉通知使用权是合法意图，反复写回是骚扰，也是很多「系统权限体检」类 App 被差评的原因。策略应是「掉了 → 通知栏提示 → 用户点一下才写」。
- `HealthJobService` 的 15 分钟节拍在某些 ROM 上被 WorkManager 降频到小时级，指望它「实时」修复不现实。

---

### C3 `Shell` 抽象：Root 优先，Shizuku 自动回退

**原项目怎么实现**

`shell` 模块（独立 Gradle module）：`shell/src/main/java/net/ankio/shell/Shell.kt:50-145`。

```kotlin
class Shell(packageName: String) : Closeable {
    private val rootExecutor = RootShell()                       // :51
    private val shizukuExecutor = ShizukuShell(packageName)      // :52

    fun rootPermission(): Boolean = rootExecutor.openShell()                        // :85
    fun shizukuPermission(): Boolean =                                             // :91
        shizukuExecutor.isSupported && !shizukuExecutor.isPermissionDenied
    fun checkPermission(): Boolean = rootPermission() || shizukuPermission()        // :107

    suspend fun exec(command: String): String {                                    // :129
        if (rootPermission()) return rootExecCommand(command)
        else if (!shizukuPermission()) { requestPermission(); return shizukuExecCommand(command) }
        else if (shizukuPermission()) return shizukuExecCommand(command)
        error("no any shell permission")
    }
}
```

配套 `RootShell.kt`（`openShell()` 探活 + `execute()`）、`ShizukuShell.kt`（`isSupported` / `isPermissionDenied` / `requestPermission()` / `runAndGetOutput()`）、`shizuku/UserService.kt`、`aidl/net/ankio/shell/IUserService.aidl`。设计要点：**能力探测（`rootPermission` / `shizukuPermission`）与授权申请（`requestPermission`）分离**，探测绝不弹窗。

**顺带发现 AA 自己的一处缺陷**（本次勘察实测，`Shell.kt:129-137`）：`exec` 的第二个分支 `else if (!shizukuPermission()) { requestPermission(); return shizukuExecCommand(command) }` —— 刚申请完权限**立刻**执行命令，而 Shizuku 授权是异步的用户交互，这一行几乎必然抛异常。真正可用的是第一、第三分支。本项目若实现，**不要照抄这段 `exec`**（正确做法：`requestPermission()` 后 re-check，或直接抛「需要授权」让 UI 层去引导）。

**本项目场景**

- `screenshot/RootScreenshot.kt` —— 只有 Root 一条路（`su` 执行 `screencap`）。
- 模式 C（ADB / Shizuku）在 `docs/05-Root与非Root双模式设计.md` 里有设计，`SystemAccess.kt` 的注释也写了 `adb shell pm grant ... WRITE_SECURE_SETTINGS`，但**代码里没有 Shizuku 依赖**。

结果：Shizuku 用户（有 Root 能力但不想给 Root 的谨慎用户，恰恰是最愿意用记账 App 的那批）拿不到 Root 截图能力。

**接入所需权限 / 改动量**

Shizuku 需引入 `dev.rikka.shizuku:api` + `provider`，并在 Manifest 声明 `ShizukuProvider`。改动量 **L**（新增模块或 4+ 文件，含 AIDL、`UserService`、双实现切换、`SystemAccess` 改造）。

**限制与风险**

- Shizuku 需要用户在 Shizuku App 里对**本项目**单独授权，且每次重启设备后需重新激活 Shizuku 服务。
- Shizuku 的 `UserService` 必须跑在 `android:process=":shizuku"` 独立进程，会引入跨进程序列化成本；`Shell.exec` 若在 `Dispatchers.IO` 之外调用会卡主线程。
- **AA 的 `RootShell` 有一个隐患不宜照抄**：它把 `su` 的 stdout/stderr 直接当字符串返回，没有区分「命令返回非零」与「读到了输出」。本项目若要实现，建议返回 `data class ShellResult(code: Int, out: String, err: String)`，否则 `settings get` 拿到的空串会被当成「值为空」而不是「命令失败」。

---

### C4 通知 Hook 双点（`NotificationManagerService`）

**原项目怎么实现**

`app/src/main/java/net/ankio/auto/xposed/hooks/android/hooks/NotificationHooker.kt`（163 行），两个 Hook 点互补：

| # | Hook 目标 | 拿到的数据 | 行号 |
| --- | --- | --- | --- |
| 1 | `hookEnqueueNotification()` → `Hooker.allMethodsEqBefore(nmsClass, "enqueueNotificationInternal")` | 包名取自 `param.args.getOrNull(0) as? String`，`Notification` 取自 `param.args.filterIsInstance<Notification>().firstOrNull()` | `:47-69`（`allMethodsEqBefore` 在 `:51`） |
| 2 | `hookNotifyPosted()` → `Hooker.allMethodsEqBefore(nmsClass, "notifyCallNotificationEventListenerOnPosted")` | `param.args.firstOrNull()` 的 `NotificationRecord`，再取 `sbn` 字段 | `:71-108`（`allMethodsEqBefore` 在 `:76-79`） |

注释写明选型理由：`enqueueNotificationInternal` 是「Android 8.0+ 稳定存在」的入队入口；点 2 是「发布给监听器时」，数据更完整但版本漂移风险高。

两个回调都 `return null`（不改宿主行为）。取内容的是 `processNotification()`（`:116-136`），文本三级回落 **`EXTRA_BIG_TEXT` → `EXTRA_TEXT` → `EXTRA_SUB_TEXT`**（`:120-124`），比 App 侧 `NotificationService` 的两级多了一档 `EXTRA_SUB_TEXT`；去重走 `MD5HashTable.md5("$pkg$title$text")`（`:126-131`）；随后 `CoroutineUtils.withIO { reportNotification(...) }` 挪到 IO 线程。`reportNotification()`（`:138-163`）再做包名白名单（`SettingAPI.get(Setting.LISTENER_APP_LIST, DefaultData.APP_FILTER).split(",")`）与 `com.android.mms → DataType.DATA / 其余 → DataType.NOTICE` 的分流，最后 `AnalysisUtils.analysisData(targetPkg, dataType, json)`。

**本项目场景**

`xposed/LedgerModule.kt:12-20` 注释的待办 ③「可选 SystemUI 兜底采集」与此相关，但**当前的 `scope=system` 只 serve 了一个空 hook**。

**接入的价值**：绕开「通知使用权」这个用户可见、可被撤销、ROM 会抽查的授权。对 LSPosed 用户来说，装了模块就不需要再给通知权限。

**接入所需权限 / 改动量**

LSPosed 激活（`scope=system`，本项目已是模块）。改动量 **M**（2 文件 ~120 行）：新增 `xposed/NotificationHook.kt` + `LedgerModule.onSystemServerStarting` 里挂载，内容照 AA 的双点写法，但**落库走现有的 `LedgerRepository.addAutoParsed`**，不要引入 JSON 中转。

**限制与风险**

- **双点必须互斥**：两个 Hook 点对同一条通知都会触发，AA 靠 `MD5HashTable(300_000)` 去重。本项目若接入，应只启用点 2（`StatusBarNotification` 信息最全），把点 1 作为「点 2 在某个 ROM 上不存在」的兜底 —— 而不是两个同时开。
- **Hook 系统类 = 升级即碎**。Android 15/16 的 `NotificationManagerService` 内部签名已经改过，`allMethodsEqBefore` 靠方法名匹配，改名就静默失效（AA 的 `runCatching` 会吞掉异常只打日志）。必须在体检页暴露「Hook 是否挂上」的状态，不能静默。
- **合规**：hook 系统服务是 LSPosed 模块的常规用法，但会显著提高「被系统安全中心标记」的概率（尤其国产 ROM 的模块检测）。本项目当前定位是自用，风险可接受；一旦分发要重新评估。

---

### C5 短信 Hook（`InboundSmsHandler.dispatchIntent`）

**原项目怎么实现**

`app/src/main/java/net/ankio/auto/xposed/hooks/sms/hooks/SmsIntentHooker.kt`（82 行）：

```kotlin
TELEPHONY_PACKAGE = "com.android.internal.telephony"
SMS_HANDLER_CLASS = "$TELEPHONY_PACKAGE.InboundSmsHandler"
Hooker.allMethodsEqBefore(cls, "dispatchIntent") { param, _ ->
    val action = intent.action
    if (Telephony.Sms.Intents.SMS_DELIVER_ACTION != action) return@allMethodsEqBefore null
    // getMessagesFromIntent → Normalizer NFC → JsonObject{sender, body, ...}
}
```

挂在 `com.android.phone` 进程（`SmsHooker.kt`：`packageName="com.android.phone"`, `systemApp=true`）。

**本项目场景**：与 C4 同样属于「LSPosed 全功能版」范畴。当前 `LedgerModule` 的 `scope=system` **不含** `com.android.phone`，要做得扩 scope。

**接入的价值**：免 `RECEIVE_SMS` 运行时权限。对一个「单机、不要权限」定位的记账 App，这确实是一条能降低用户心理门槛的路。

**限制与风险**

- `com.android.internal.telephony` 是 **@hide 内部包**，`InboundSmsHandler` 在 AOSP 与各 ROM 上的类名/方法名不完全一致（三星、华为都有自己的 telephony 实现）。AA 的 `runCatching` 会静默失败，用户只会觉得「Root 模式没生效」。
- 扩 `scope` 到 `com.android.phone` 会让模块的「影响面」变大，LSPosed 管理器里的 scope 列表是用户可见的，容易被质疑。
- **收益/成本比不如 C4**：C4 免的是「通知使用权」（撤销率高、用户感知强），C5 免的是 `RECEIVE_SMS`（一次性授予，之后基本不会被撤销）。**建议 C4 优先，C5 延后。**

---

### C6 `HookerManifest` / `PartHooker` 插件化 Hook 编排

**原项目怎么实现**

`xposed/core/api/HookerManifest.kt`：抽象基类，字段 `packageName` / `processName` / `appName` / `systemApp` / `partHookers: MutableList<PartHooker>` / `rules: List<Clazz>`（Dex 适配用）；`hookLoadPackage()` 是入口。各 hook 包实现它：

```
hooks/android/AndroidHooker.kt   → packageName="android",           partHookers=[NotificationHooker()]
hooks/sms/SmsHooker.kt           → packageName="com.android.phone", partHookers=[SmsIntentHooker()]
hooks/wechat/WechatHooker.kt     → packageName="com.tencent.mm",    partHookers=[ChatUserHooker, DatabaseHooker, PayToolsHooker, ...]
hooks/alipay/...                 → packageName="com.eg.android.AlipayGphone", partHookers=[MessageBoxHooker, SecurityHooker, WebViewHooker]
```

`assets/xposed_init` 只有一行：`net.ankio.auto.xposed.core.App` —— 单入口，运行时按「当前进程的 packageName」匹配对应的 `HookerManifest` 实现。`PartHooker` 是一个 hook 点，`HookerManifest` 是一个宿主。

**本项目场景**：只有一个 hook 点（system_server），不需要这套编排。**P3，暂不迁。**

**唯一值得现在抄的**：`HookerManifest` 的「按进程名路由」思路。本项目若做 C4，`LedgerModule.onSystemServerStarting` 会变成「挂通知 hook」，将来若做 C5 需要区分 `system` 与 `com.android.phone` 两个进程 —— 现在就把路由函数写出来（哪怕只有两个分支），比将来改结构便宜。

---

### C7 `PageSignature` 页面指纹（OCR 触发去重）

**原项目怎么实现**

`app/src/main/java/net/ankio/auto/service/ocr/PageSignature.kt`：

```kotlin
data class PageSignature(
    val packageName: String,
    val activityName: String,            // 无障碍模式下可得，可为空
    val structureFingerprint: String = "", // 视图类名骨架的哈希：同一布局恒定，不同页面不同
    val addedAt: Long = System.currentTimeMillis(),
) { fun key(): String = "pkg:Activity:结构指纹" }
```

`PageSignatureManager` 存 `PrefManager.pageSignatures`（JSON 数组），`matches(...)` 用于「下次进同一页面自动触发 OCR」。

注意它记的是**结构指纹**（视图树类名骨架的 hash），不是截图 hash —— 截图 hash 会因为金额变动而变，结构指纹不会。这是它能「记住这个页面是账单页」的原因。

**本项目场景**

`screenshot/ScreenshotAccessibilityService.kt` 用 Android 11+ `takeScreenshot`，触发是用户手动（悬浮按钮 / 快捷方式）。每次触发都调一次模型（`screenshot/VisionHelper.kt`，OpenAI 兼容接口）—— **模型调用是有成本的，重复截同一页是纯浪费**。

**接入所需权限 / 改动量**

无障碍服务（本项目已有）。改动量 **M**（3 文件 ~120 行）：`PageSignature` 数据类 + 存 `AppPrefs` 或新 Room 表 + 在 `ScreenshotAccessibilityService` 触发前 `matches()` 一次。

**限制与风险**

- **结构指纹在 Compose 上不适用**：本项目目标 App（银行）大多数是原生 View 体系还行，但美团/抖音/微信小程序页面已经是自绘，`AccessibilityNodeInfo` 的类名骨架几乎是空的（全是 `android.view.View`）。指纹会退化成「包名 + Activity」，等于没有区分度。
- 更稳的替代：**对截图的「账单区域」做 perceptual hash**（只算金额区域的图像 hash），或直接「同一 Activity 5 分钟内不重复触发」。后者 5 行代码就能实现 90% 的收益 —— **建议先做时间窗，指纹作为二期**。

---

### C8 微信 `tinker` 目录删除 / `MMKRichtText.appendText` 渲染点 Hook

**原项目怎么实现**

- `hooks/wechat/WechatHooker.kt`：`hookLoadPackage` 里删除 `File(application!!.dataDir, "tinker")` 下的 `patch-*` 目录，注释：`腾讯tinker热更新框架在加载后会导致hook无效，最简单的办法是删掉`。
- `hooks/wechat/hooks/PayToolsHooker.kt`：hook `com.tencent.kinda.framework.widget.base.MMKRichText#appendText(String)`，用两个正则从渲染文本里抠字段：

```kotlin
Regex(".*(卡|零钱|经营账户).*").matches(text)   → PAY_TOOLS  (支付方式)
Regex(".*([￥$]).*").matches(text)               → PAY_MONEY  (金额)
```

三样东西（渠道、金额、商户）在 UI 上是**分三次渲染**的，所以配 `MemoryCache.put(key, DURATION_SECONDS = 120L)`，靠 120 秒窗口把它们拼成一条账单。

**本项目场景**：当前不做「应用内数据 Hook」，`WECHAT_PKGS` 只走通知。**P3，不迁。**

**但有两个思路值得记下来**（与微信无关）：

1. **「分片信息靠时间窗拼装」是通用模式**。本项目的 `merged`（10 s）做的是同一件事。若将来支持「金额在通知、商户在短信」的拼接，这套模式可以直接复用。
2. **`tinker` 那个坑**：任何 hook 微信的方案都要先处理热更新替换 dex 的问题。这条写在这里是为了避免将来重新踩。

---

## D. 解析与规则层

> 这一组是本项目**最该动**的地方。现状是「加一个银行 = 改源码 = 发版」，AA 用「规则即数据」把这个成本压到了「用户自己在 App 里加一条」。

### D1 规则 = 数据（存表 + 可启用/禁用），而非编译期常量 ⭐ P0

**原项目怎么实现**

`server/src/main/java/org/ezbook/server/db/model/RuleModel.kt`：

```kotlin
@Entity data class RuleModel(
    var id: Long, var app: String, var type: String,   // type ∈ OCR/DATA/NOTICE
    var js: String,                                     // 规则本体 = 一段 JS
    var name: String, var systemRuleName: String,
    var creator: String,                                // "system" | "user"
    var struct: String,                                 // 3.0 自定义数据结构规则
    var autoRecord: Boolean, var enabled: Boolean, var updateAt: Long,
)
```

执行链（`server/src/main/java/org/ezbook/server/tools/BillService.kt` 的 `analyze()`）：

```
DataType 校验 → 去重(skipDedup = fromAppData || OCR) → 落 AppDataModel
  → analyzeWithRule()                      ← 先规则
      for creator in ["user", "system"]    ← 用户规则优先，系统规则兜底
          RuleGenerator.data(app, type, creator, scope) 生成 JS
          → JsExecutor 执行（QuickJS，每次新建 Runtime/Context）
          → parseBillInfo
  → 规则不中 → analyzeWithAI()             ← AI 兜底
  → categorize()
```

关键点在 `BillService.kt:512-520`：

```kotlin
val rule = Db.get().ruleDao().query(dataType.name, app, rawRuleName)   // 按名字回查规则
auto = rule?.autoRecord ?: false                                       // 逐条规则控制「自动入账 / 待确认」
```

**也就是说：规则不仅能解析，还能决定「这条规则命中的账单是否直接入账」。** 这是 AA 体验上「大部分账单静默入账，少数弹确认」的实现方式。

**本项目场景**

规则全在 `NotificationRules.kt`（518 行）里：

- 包名表（`BANK_PKGS` 12 个等）
- 词表（`BLOCK_WORDS` / `REFUND_WORDS` / `INCOME_WORDS` / `EXPENSE_WORDS` / `EXPENSE_KEYWORDS` …）
- 正则（`AMOUNT_YUAN` / `AMOUNT_SIGN` / `AMOUNT_BARE` / `AMOUNT_NEAR_VERB` / `MERCHANT_BRACKET` / `FUTURE_CHARGE_PATTERNS` / `FINANCE_SENDER`）
- 单一 `parseAs` 内核

**问题不是「规则写得不好」**（这套判定顺序比 AA 的 AI prompt 硬规则更精细），**问题是「用户改不了」**。招商银行的短信模板一改，本项目的用户只能等一个版本。

**接入所需权限 / 改动量**

无需权限。改动量 **L**（新表 + 解析器 + 管理 UI + 导入导出，6+ 文件 400+ 行）：

1. **存储**：Room v4 新增 `Rule` 表 —— `id / app / type / name / creator("user"|"system") / pattern(JSON) / enabled / autoRecord / updatedAt`。**注意本项目是 Room v3，加表要写迁移**（`data/` 下已有 v1→v3 迁移可参照）。
2. **规则表示**：**不要用 JS**（见 F2）。用 Kotlin `data class BillRule`：

   ```kotlin
   data class BillRule(
       val app: String?,            // null = 任意
       val type: RuleType,          // NOTICE / SMS / OCR
       val include: List<String>,   // 命中任一
       val exclude: List<String>,   // 命中即丢
       val amountPattern: String?,  // 正则，group(1) 是金额
       val directionPattern: String?, // 正则，group(1) ∈ in/out
       val category: String?,       // 固定分类，或 null 走关键词推断
       val autoRecord: Boolean,
   )
   ```
3. **执行**：`NotificationRules.parseAs` 改成「先跑内置规则集，再跑用户规则集」，用户规则命中即短路。
4. **UI**：`settings/` 加「规则管理」页 —— 列表 + 启停开关 + 正则自测（输入一段文本看是否命中、抠出什么金额）。
5. **导入导出**：复用现有的 WebDAV / JSON 导出通道。

**限制与风险**

- **正则由用户输入 = 灾难**：恶意/错误正则可以构造灾难性回溯（`(a+)+$`）把主线程卡死。**必须**：① 规则执行放 `Dispatchers.Default` 且带超时（`withTimeout(200)`）；② 拒绝嵌套量词模式（简单检测 `\([^)]*[+*][^)]*\)[+*]`）；③ 单条规则正则长度上限（比如 512 字符）。
- **规则优先级必须固定**：用户规则 > 内置规则 > AI（若引入）。不能「用户规则没命中就当作没规则」，否则用户加一条坏规则会把内置兜底全废掉 —— 这正是 D2 要解决的。
- **迁移是硬门槛**：Room v3 → v4，用户已有数据不能丢。本项目 `docs/02-架构与数据流.md` 记录了 v1→v3 的迁移链，加 v4 要补进文档。
- **内置规则也要进表**：`creator="system"` 且不可删改（只可禁用），启动时 `INSERT OR IGNORE` 播种。这样「内置规则」和「用户规则」走同一条执行路径，不会有双份代码。

---

### D2 `user` / `system` 两级规则 + 用户优先、系统兜底 ⭐ P1

**原项目怎么实现**

`RuleGenerator.kt`（174 行）+ `BillService.analyzeWithRule`：`for (creator in ["user","system"])`，每轮 `loadAllEnabledByCreator(creator, scope)`，用户规则先跑，全不中再跑系统规则。`SettingUtils.ruleMatchIncludeDisabled()` 为真时连禁用的也跑（调试用）。

额外一层容错在 `DataConvert.convert(data)`：**用户规则未命中**时，把输入 JSON 扁平化（`key:【sub,sub】`，嵌套对象展开，内嵌 JSON 字符串递归解析）后**再跑一次用户规则**。这解决的是「银行 App 把账单塞进双层 JSON」的情况。

**本项目场景**：无此结构。`parseAs` 是单趟顺序判定（噪声词 → force/ignore 关键词 → 未消费拦截 → 预告拦截 → 提金额 → 定向 → 分类），任何一步失败就 `return null`。

**要迁的是「两轮」结构**，不是 `DataConvert`（本项目输入是纯文本，不是 JSON）。

**接入所需权限 / 改动量**：随 D1 一起做。改动量 **M**（1 文件 ~40 行）：`NotificationRules` 加一个 `parseWithRules(raw, builtinRules, userRules)`，先跑 `userRules`（含 D1 的 `BillRule` 列表），全不中再跑现有 `parseAs`。

**限制与风险**

- 「用户规则命中」和「内置规则命中」的 `confidence` 不该一样（用户手写的规则更容易错）。建议：用户规则命中 → `confidence = MEDIUM` 且**默认弹确认**；内置规则命中 → `HIGH` 且直接入账。AA 用 `autoRecord` 解决这个问题，思路一致。
- 用户的规则集可能是空列表 —— 空列表必须「跳过」而不是「命中即 null」，否则所有账单都丢。这是最容易写错的一行。

---

### D3 `DataType` 三态（`OCR` / `DATA` / `NOTICE`）⭐ P1

**原项目怎么实现**

`server/src/main/java/org/ezbook/server/constant/DataType.kt`：`enum class DataType { OCR, DATA, NOTICE }`，贯穿全链路：

- `JsAPI.analysis` 的 URL 参数 `type=`
- `RuleModel.type` 字段（规则按数据类型索引）
- `BillService.analyze` 的去重策略（`skipDedup = fromAppData || type == OCR`）
- `AppDataModel.type`

语义：`NOTICE` = 通知栏原文，`DATA` = 应用内结构化数据（Hook 拿的）或短信正文，`OCR` = 截图识别文本。

**本项目场景**

三态压成了两条路：`parse(raw: NotifyRaw, ...)` 与 `parseSms(sender, body, ...)`，靠 `NotifyChannel` 区分来源（`ALIPAY`/`WECHAT`/`BANK`/`SMS`/`OTHER`），**没有「数据类型」这一维**。

后果：
- 规则无法「只对通知生效」或「只对短信生效」（D1 的 `BillRule.type` 需要这一维）。
- 去重策略无法按类型分化（OCR 结果和通知原文的去重逻辑本来就该不同 —— 截图可能同一笔被截两次，通知不会）。

**接入所需权限 / 改动量**

无需权限。改动量 **S**：`LedgerEntry` / `NotifyBill` 加 `dataType: DataType`，`NotificationRules.parse` 传 `NOTICE`、`parseSms` 传 `SMS`（本项目可定义为 `NOTICE`/`SMS`/`OCR` 三值，比 AA 的 `DATA` 更直白）、`VisionHelper` 结果传 `OCR`。

**限制与风险**

- 加字段会动 `LedgerEntry` → Room 实体 → 需要一次迁移（与 D1 的 v4 一起做，别做两次）。
- `SMS` 与 AA 的 `DATA` 语义不完全对应：AA 的 `DATA` 还包含「微信/支付宝 Hook 拿到的结构化数据」。本项目若将来做应用内 Hook，需要区分「短信正文」和「结构化数据」—— 建议直接定义四值 `NOTICE / SMS / APP_DATA / OCR`，一步到位。

---

### D4 AI 兜底的严格 prompt ⭐ P1

**原项目怎么实现**

`server/src/main/java/org/ezbook/server/constant/DefaultData.kt` 的 `AI_BILL_RECOGNITION_PROMPT`（约 121-200 行），骨架：

```
# 角色
你从原始金融文本中提取结构化的交易信息。

# 输出
只返回一个 JSON 对象。不要代码块、不要解释。若任一硬性规则失败，返回 {}。

# 上下文
Source App / Data Type(OCR·DATA·NOTICE)。可用上下文辅助判断，但不得与 Raw Data 冲突，
冲突时以 Raw Data 为准。accountNameFrom 为空则用包名。

# 硬性规则
1) accountNameFrom 非必填
2) 禁止猜测，只能使用输入中明确出现的数据
3) 忽略营销/广告及任何非交易文本（验证码/登录提醒/快递通知/系统提示/聊天/新闻/纯营销），
   无关则返回 {}
```

三条设计要点值得抄：

1. **「返回 `{}` 而不是返回 null/空串」** —— 调用方只需 `json.length() > 0` 一个判断，不用区分「模型拒答」与「解析失败」。
2. **「禁止猜测，只能用输入中明确出现的数据」** —— 这一条直接压住了模型编造商户名/金额的倾向，比任何后处理都有效。
3. **「冲突以 Raw Data 为准」** —— 上下文（App 名、数据类型）只做辅助，不允许覆盖原文。防止模型把「招商银行通知」脑补成别的银行。

`BillService` 侧的顺序也重要：**先规则，规则不中才 AI**。AI 是兜底不是主路径 —— 降低成本，且规则命中时行为可预测。

**本项目场景**

`screenshot/VisionHelper.kt` 只做「截图 → 账单」的多模态识别，**没有文本兜底路径**。而本项目的最大漏记来源恰恰是「通知/短信解析失败」（`handleOne` 里 `parse ?: return` 静默丢弃，只 `Log.d`）。

**接入所需权限 / 改动量**

网络权限（已有 `INTERNET`）。改动量 **S**（1 文件 ~80 行）：新增 `data/TextBillAi.kt`，入参 `(source, dataType, rawText)`，prompt 直接借鉴上面骨架，出参 `NotifyBill?`。挂载点：`handleOne` 与 `SmsReceiver` 在 `parse == null` 时**可选**调用（受 `AppPrefs.aiFallbackEnabled` 开关控制，默认关）。

**限制与风险**

- **隐私**：AA 是「所有数据本地处理」的反面 —— 它把原文发给 DeepSeek（`DefaultData.kt` 里 `API_PROVIDER = "DeepSeek"`）。本项目截图那条链路已经在调外部 API（`VisionHelper`），但通知/短信原文比截图更敏感（含完整银行卡号、余额）。**默认必须关闭**，且开启时要在 UI 上明说「会把通知原文发到 xxx」。
- **成本**：每次漏记都调一次模型 = 用户的通知量直接换算成 token。必须配节流（同一 App 每小时上限）+ 只对「通过了 `plausibleSender` / 金融提示词」的文本调用。
- **模型不可用时的降级**：网络失败/超时必须有超时（10 s）+ 单次重试，失败就当作「没识别」，绝不能阻塞 `SmsReceiver` 的 `goAsync`（前台广播 10 秒超时）。

---

### D5 `DataConvert` 扁平化重试（解决「双重 JSON」）

**原项目怎么实现**

`server/src/main/java/org/ezbook/server/tools/DataConvert.kt`：`convert(data)` 把任意 JSON 压成 `key:【sub,sub】` 的扁平文本，嵌套对象/数组全部展开，**字符串里内嵌的 JSON 会递归解析**。用途：用户规则按扁平文本写正则/关键词，就不用关心原始嵌套结构；某些 App 把账单塞成 `{"data":"{\"amount\":...}"}`，不展开的话规则永远匹配不上。

**本项目场景**：输入是纯文本（`Notification.EXTRA_*` / 短信 body），不是 JSON。**暂不迁。**

**唯一的相似坑**：部分银行通知的 `EXTRA_BIG_TEXT` 里带 HTML（`<b>¥</b>100.00`），`SpannableString.toString()` 会保留标签。若将来遇到「金额抠不出来但肉眼可见」，第一件事是 `Html.fromHtml(...)` 或去掉 `<[^>]+>`。这条值得写进 `docs/11-缺陷清单.md` 的待观察项。

---

### D6 规则市场：Issue → CI 校验 → 评论结果 → 关闭

**原项目怎么实现**

`AutoRuleSubmit` 全套 Node.js 工具（`src/`）+ 三个 workflow（`.github/workflows/`）：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `src/index.js` | 126 | commander CLI，硬编码 `owner=AutoAccountingOrg, repo=AutoRuleSubmit`；命令 `rename <issue>` / `test [issue]` / `release <tag> <prevCommit> <curCommit> <sha>` |
| `src/issue-rename.js` | 153 | 用 `\[Adaptation Request\]\[(.*?)\](.*)` / `\[Bug Report\]\[(.*?)\](.*)` 抠标题 → 改成 `适配请求：${content}` + `addLabels(['adaptation-request'])` |
| `src/issue-test.js` | 346 | **核心**：clone 私有测试仓库 `AutoRule` → 从 issue body 抠 `data:` URI → 写 `src/rule/tests.txt` → `yarn install && yarn rollup -c` → `yarn quickTest` → 用 `/===========START===========([\s\S]*?)===========END===========/` 抠结果 → 评论回 issue 并关闭 |
| `src/release.js` | 574 | clone 私有仓库 → checkout commit → 打包 `dist` 为 `${tag}-dist.zip` → 流式算 MD5 → 上传到 `https://license.ez-book.org/github`（表单字段 `file/tag/changelog/buildTime/repo/commitCount/commits/packageSize/packageMD5`），另有 `BOT_URL` / `BOT_GROUP_ID` 用于群机器人播报 |

三个 workflow 的分工（这一点很值得抄，它用 `repository_dispatch` 把「跨仓库事件」串起来了）：

| workflow | 触发 | 动作 |
| --- | --- | --- |
| `issue-rename.yml` | `issues: [opened, reopened]` | `node src/index.js rename ${{ github.event.issue.number }}`，权限 `issues: write` |
| `issue-test.yml` | `issues: [opened, reopened]` **和** `repository_dispatch: [cross-repo-trigger]` | issue 事件 → `test <number>`（单条）；dispatch 事件 → `test`（跑全部 open issue）。用 `if: github.event_name == ...` 分流 |
| `release.yml` | `repository_dispatch: [tag-pushed]` | `node src/index.js release <tag_name> <previous_tag_commit> <current_tag_commit> ${{ github.sha }}`，即**打 tag 发生在另一个仓库，通过 `repository_dispatch` 通知本仓库发版** |

**对本项目最重要的结论**：AA 的规则库之所以能到 290 条，靠的是「用户上传数据 → CI 自动测 → 回评论」这个闭环。**没有这个闭环，规则数量上不去；规则数量上不去，「支持 100 家银行」就是空话。**

**跨仓库 `repository_dispatch` 这个手法值得单独记**：规则测试必须在私有规则仓里跑（规则代码不公开），而 issue 在公开仓 —— 于是公开仓只负责收 issue 和转发事件，真正的构建/测试/发布都在私有仓侧。本项目若走 D6，同样会面临「规则要私有、issue 要公开」的矛盾，这套两个仓 + dispatch 的结构是现成答案。

**规则库规模**（`rules.md`，449 行，实测统计）：共 **290 条**规则、**31 个包名分节**。按类型：`数据` 233 条 + `通知` 57 条；按路径顶层目录：`app/` 212 条 + `notice/` 57 条 + `ocr/` 21 条。两套口径交叉核对后 `数据 233 = app 212 + ocr 21`、`通知 57 = notice 57` —— 也就是说**规则表里的 `ocr/` 目录规则在类型列被标成了「数据」**，`DataType.OCR` 这一档在 `rules.md` 里没有被单独登记（`type` 列只有「数据」「通知」两个值）。这是一处目录与类型不一致，本项目若设计规则元数据，不要照抄。其中 `app/com.android.phone/`（短信规则）**恰好 50 条**，覆盖上海农商、上饶、中原、云南农信、乐山市商业银行、柳州、泉州公积金等**地方性银行**。这个覆盖广度是单团队不可能手工完成的 —— 只能靠用户众包。

**接入所需权限 / 改动量**

无需 App 权限。改动量 **L**（需建仓库 + CI + 测试基础设施）：一个 GitHub repo（issue 模板）+ 一个 CI workflow（跑测试）+ 一个规则包仓（可以是公开的，因为规则是正则不是代码）。

**限制与风险**

- **ARS 的模式已证明有合规反噬**：规则仓库因为银行投诉下架。本项目的规则都是「正则匹配短信文本」，不含银行商标、不复制银行文案的话，风险比 AA 低（AA 的规则里有大量银行名/产品名作为匹配锚点）。但**规则包里出现「招商银行」「工商银行」这类词本身**就可能被主张商标。建议规则包**不公开托管在能被检索到的地方**，或只存正则不存银行名注释。
- 众包规则的质量不可控：一条过宽的正则（`.*\d+.*`）会把所有通知都匹配成账单。CI 必须包含**负样本**（营销短信、验证码、物流通知），且负样本失败即拒绝合并。
- 隐私红线：用户提交规则时附带的数据样本（`data:` URI）里含真实卡号/余额。**必须**在 issue 模板里写明「请打码」，且 CI 侧做一次「是否含 16-19 位连续数字」的检测，命中就自动打回。

---

### D7 规则命名规范（三目录 + 包名分节）⭐ P1

**原项目怎么实现**

`rules.md` 的组织方式：

```markdown
## cmb.pb
| 规则名称 | 规则类型 | 规则路径 |
| --- | --- | --- |
| 招商银行信用卡消费通知 | 通知 | notice/cmb.pb/招商银行信用卡消费通知.js |

## com.android.phone
| 短信招商银行 | 数据 | app/com.android.phone/短信招商银行.js |
```

三个顶层目录对应三种 `DataType`：`notice/`（通知）、`app/`（应用数据 + 短信）、`ocr/`。二级目录是**包名**。文件名是**人类可读的中文规则名**（同时是 `RuleModel.name`）。

**为什么要这么设计**：`BillService.kt:512` 靠 `query(dataType.name, app, rawRuleName)` 三元组回查规则 —— 也就是说「类型 + 包名 + 规则名」是规则的主键语义。文件名直接编码这三个维度，文件系统和数据库天然一致。

**本项目场景**：无规则文件体系。若 D1 落地，规则存在 Room 里，「文件路径」这一维不存在。

**可借鉴的部分**：**命名规范本身**。即使规则存 DB，也应该：

- 规则名格式：`{应用名}{业务名}`（如「招商银行信用卡消费通知」「短信建设银行动账」），因为用户要在列表里认出它。
- 规则 ID 用 `{type}/{app}/{name}` 的字符串，而不是自增 id —— 导出/导入/规则共享时天然可合并去重。
- 内置规则用 `creator="system"` + 稳定的 `name`，升级时 `INSERT OR IGNORE` 才能正确识别「我已经有这条了」。

**改动量 S**（纯约定 + 播种逻辑）。

---

## E. 数据层工具

### E1 `TransferRecognizer`：转账/还款成对合并 ⭐ P1

**原项目怎么实现**

`server/src/main/java/org/ezbook/server/tools/TransferRecognizer.kt`（175 行）：`recognize(billInfoModel)` 在时间窗内按金额匹配已有账单，识别三类合并：

| 场景 | 合并结果 |
| --- | --- |
| 一个账户支出 + 另一账户收入，金额一致 | 转账（`Transfer`） |
| 支出 + 转账 | 信用卡还款 |
| 转账 + 转账 | 转账 |

受两个开关控制：`SettingUtils.featureAssetManager()`（资产管理功能是否启用）与 `autoTransferRecognition()`。

**本项目场景**

单表 `Bill`，**没有「账户」概念**（没有 `accountId`）。后果：

- 从招行转到支付宝余额宝 → 记两条（招行支出 + 余额宝收入），报表里支出虚高、收入虚高。
- 信用卡还款 → 记两条，当月支出被算重。

**接入所需权限 / 改动量**

无需权限。改动量 **M**（3 文件 ~150 行）：`LedgerRepository` 加 `mergeTransfers(context, windowMs)`，在 `addImported` / `addAutoParsed` 后调用；Room 加 `Bill.transferId: String?`（指向配对的另一条）或统一用「转账」类型 + 净值 0。

**限制与风险**

- **必须有账户维度，否则不能做**。AA 能做是因为它有资产账户表；本项目单表 `Bill` 里只有 `source`（`"招商银行通知"` / `"支付宝通知"`），可以**近似**当账户用，但「同银行两张卡」就分不开。建议：先只做「跨 `source` 同金额、方向相反、时间差 < 5 分钟」的启发式合并，并把结果标记为「疑似转账」让用户确认，而不是自动合并。
- 误合并的代价很高：真有两笔「转出 5000 + 收到 5000」（借给朋友又还回来）会被合成一笔转账，用户账本直接错。**必须可撤销**（复用 `BillNotifier` 的 Undo 通道）。
- 金额一致的判据要留容差吗？AA 不留（精确匹配）。现金转账有手续费时（转 10000 到账 9998）不该合并 —— 所以**精确匹配是对的**，不要加容差。

---

### E2 `MD5HashTable` 的流式过期 + 定期清理

**原项目怎么实现**：见 A2。核心是「按 `put` 次数触发 `cleanup`（每 100 次）」而不是定时器 —— 没有额外线程，且访问越频繁清理越勤。

**本项目场景**：`seen` 是 `LinkedHashMap(64, 0.75f, true)` + `removeEldestEntry(size > 200)`，是**容量**淘汰不是**时间**淘汰。200 条在某些银行 App 高频推送时不够（会被 LRU 挤掉真正重复的条目）。

**接入所需权限 / 改动量**：随 A2 一起做。改动量 **S**。

**限制与风险**：若做 A2 的持久化，AA 的「按 put 次数清理」就不合适了（进程重启后计数归零，可能很久不清理）。持久化版本应改成「每次写入时顺手删过期行」（SQLite 有索引的前提下成本可忽略）。

---

### E3 `AssetsMap`：包名 → App 名 / 图标

**原项目怎么实现**：`server/src/main/java/org/ezbook/server/tools/AssetsMap.kt`（283 行），包名到应用名的映射表 + 图标资源，用于账单列表展示「这笔来自哪个 App」。

**本项目场景**：账单列表用 `source: String`（`"招商银行通知"` / `"短信记账"`，`NotificationRules.sourceOf`），**纯文本，无图标**。视觉上区分度低 —— 银行通知和短信记账在列表里长得一样。

**接入所需权限 / 改动量**

若要真图标，需要用 `PackageManager.getApplicationIcon(pkg)`，那就必须有 `<queries>` 声明（同 A3 的风险）。**不做图标，只做名称映射**则零权限。改动量 **S**：`sourceOf` 改成「渠道 → 显示名」的映射表，并把 `Bank` 渠道细化成「招商银行」「建设银行」等（按 `pkg` 查表）。

**限制与风险**：硬编码的中文银行名表会过期（银行改名、并购）。零成本方案是「只在列表里显示渠道类型（银行/支付/短信）」+「详情页显示原始包名」，把准确性问题降到最低。

---

### E4 `DuplicateDetector` / `BillMerger`（不迁移）

**原项目怎么实现**：`tools/DuplicateDetector.kt`（142 行）、`tools/BillMerger.kt`（347 行），前者判重、后者按规则合并同笔账单（如「下单」与「支付成功」两条通知合成一条）。

**本项目场景**：职责已被两处覆盖：

- 同笔判重：`data/ParseDedup.kt`（5 分钟窗口 + 分钟级指纹）+ `LedgerRepository.isRecentDuplicate`（2 分钟跨渠道）+ `external_id` 唯一索引。
- 「下单 → 支付成功」合并：`NotificationRules.parseAs` 的 `NOT_CONSUMED_PATTERNS` + `PAID_PROOF_WORDS` 已经做到了「下单通知不入账，支付成功才入账」—— 比合并两条更干净。

**结论：不迁移。** `BillMerger` 的 347 行是为「多源数据都要落库再合并」的架构服务的（AA 会把 `AppDataModel` 全落库），本项目是「解析阶段就决定入不入库」，不需要这层。**记录下来是为了避免将来有人看到 AA 有 347 行的 merger 觉得本项目缺了东西。**

**唯一可借鉴的**：AA 的去重键设计（`MD5HashTable` 的 key 是 `"$pkg$title$text"`，不含时间戳）。本项目 `dedupeKey` 含毫秒 `postTime` 是已知缺陷（`ParseDedup.kt` 注释记录过 `15:11 那两条一模一样的 46.94`）。**去时间戳**这个改动独立于「持久化」，可以单独做（也在 A2）。

---

## F. 明确不迁移（及理由）

### F1 内置 Ktor 服务端

AA 把解析逻辑放在 `server` 模块，App 通过 `LocalNetwork.post("js/analysis?...")` 打到 `127.0.0.1`。好处是解析逻辑可以用 Kotlin/JVM 生态调试、规则引擎可以独立升级；代价是：多一个进程/端口、要处理「服务端没起来」的竞态、多一份内存、上架审核要解释「为什么记账 App 要监听本地端口」。

本项目单机、单模块、无账号。**搬这套架构纯粹是负担**。D1 的规则引擎应做成「App 进程内的 Kotlin 实现」，不做 RPC。

### F2 QuickJS 全量 JS 规则引擎

`server/src/main/java/org/ezbook/server/engine/JsExecutor.kt`（65 行）用 `com.shiqi.quickjs.QuickJS`，每次执行新建 `Runtime`/`Context`（避免跨脚本污染），注入 `print` 收集器与全局 `data`。

**不迁的理由**：
1. 引入 QuickJS = 增加 ~2-3 MB 原生库 + 一个 C 层攻击面（规则是用户可写的，等于把任意 JS 执行能力交给用户输入）。
2. 本项目的规则需求（关键词、正则、金额提取、方向判断）用 Kotlin `Regex` 完全覆盖，不需要图灵完备。
3. **JS 规则无法静态校验** —— D6 的 CI 里，正则规则可以做「负样本测试」，JS 规则只能跑一遍看输出，坏规则的检测成本高一个量级。

D1 的规则表示应停在「声明式 JSON（词表 + 正则 + 固定字段）」，**不要**滑向「可执行脚本」。

### F3 `tap` 模块（背面敲击，tflite）

`tap` 模块用 tflite 模型识别「敲击手机背面」的手势，用于唤起记账面板。

**不迁**：与「从通知/短信解析账目」这条主链路无关；需要常驻加速度计采样（耗电）；且本项目已有 `screenshot/` 的触发方式（悬浮按钮 / 快捷方式 / 无障碍触发）。**如果要做「快速唤起」，更轻的方案是 QS Tile**（`AndroidManifest.xml:161-169` 已经有 `QS_Tile` 声明）。

### F4 `QUERY_ALL_PACKAGES` / `READ_SMS` / 悬浮窗

AA 的 `app/src/main/AndroidManifest.xml` 里有 `SYSTEM_ALERT_WINDOW`（`:4`）、`QUERY_ALL_PACKAGES`（`:11`，带 `tools:ignore`）、`RECEIVE_SMS`（`:31`）+ `READ_SMS`（`:32`）。

本项目当前 Manifest **三者都没有**（已确认：只有 `RECEIVE_SMS`，无 `READ_SMS`、无 `QUERY_ALL_PACKAGES`、无 `SYSTEM_ALERT_WINDOW`）。

**不迁，且这是本项目的优势**：
- `READ_SMS` 在 Google Play 是**需要申报例外**的敏感权限，且审核极严（几乎只有「默认短信 App」能过）。本项目只读**新到**短信（`RECEIVE_SMS`），不读历史，语义上更干净。
- `QUERY_ALL_PACKAGES` 同理（Google 有专门的政策页）。若真需要包名枚举，用 A3 说的白名单式 `<queries>`。
- 悬浮窗（`SYSTEM_ALERT_WINDOW`）是国产 ROM 上最容易被用户反感的权限，且本项目**不需要** —— 截屏记账的通知栏确认（`BillNotifier` + `ConfirmEntryReceiver`）已经覆盖了交互需求。

---

## 9. 落地路线

按「能不能在不发版的前提下让用户自己解决漏记」排序。

### 第一期（P0 里的「小改动快赢」，约 3 天）

矩阵里标 P0 的共 5 项。其中 D1 是 **L**（要加表、要迁移、要改解析入口），不能塞进几天里，所以第一期只做另外 4 项：

| # | 改动 | 文件 | 验收 |
| --- | --- | --- | --- |
| 1 | A1 监听器自愈重绑（带 ≥30 s 节流） | `LedgerNotificationListener.kt` | 手动 `adb shell cmd notification ...` 解绑后 30 s 内 `onListenerConnected` 再次触发 |
| 2 | B2 `NFKC` 规范化 + 全角数字/逗号归一 | `SmsReceiver.kt`、`NotificationRules.kt` | 构造含 `１００．００元` 的短信，能抠出 `100.0` |
| 3 | C2 覆盖安装后复检（`MY_PACKAGE_REPLACED` + `HealthJobService`） | 新 receiver、`HealthJobService.kt` | 覆盖安装后 `enabled_notification_listeners` 含本项目 |
| 4 | C1 无障碍免手动授权（复用 `SystemAccess.appendComponent` 写 `enabled_accessibility_services`） | `SystemAccess.kt`、`SystemAccessPage.kt` | `WRITE_SECURE_SETTINGS` 已授予时，一键开启无障碍成功且重启后仍生效 |

> 顺序建议：**A1 → B2 → C2 → C1**。A1 改动最小、收益最直接（失聪是当前最严重的漏记原因）；B2 一行 `Normalizer` 就能救回一批全角短信；C1 放最后，因为它的验证最依赖真机 ROM。

### 第二期（P0 的大件 + P1，约 1–2 周）

| # | 改动 | 验收 |
| --- | --- | --- |
| 5 | **D1 + D2 + D3 规则表（Room v4）+ 用户规则优先** | 加一家新银行规则不发版；坏规则不废掉内置兜底 |
| 6 | A2 持久化去重表（`seen_key` 表）+ 同时去掉 `dedupeKey` 的毫秒 `postTime` | 杀进程后重复投递不再二次入账；同一条通知 5 ms 内投两次只入一次 |
| 7 | A3 包名/关键词可配置（白名单式 `<queries>`） | 用户在 UI 里加一个 App 包名即刻生效 |
| 8 | D4 文本 AI 兜底（默认关 + 严格 prompt + 节流） | 关闭时零网络请求；开启时漏记有兜底且不阻塞广播 |
| 9 | E1 转账成对合并（启发式 + 可撤销） | 「转出 5000 / 收入 5000」被标记为疑似转账而非两条收支 |

> A2 排在这里而不是第一期：它要新表，而第五项的 Room v4 迁移本来就该一次性做完 —— 两件事共用一次 schema 变更，省一次迁移窗口（也是 R7 的缓解手段）。D7（规则命名规范）无独立工期，跟着第五项一起定稿即可。

### 第三期（P1 尾巴 + P2 / P3，看定位）

第 10、11 两项虽是 P1，但卡在前置条件上（Shizuku 依赖、无障碍链路），所以排到这里；其余是「要不要做」取决于产品定位的部分。

| # | 改动 | 前置条件 |
| --- | --- | --- |
| 10 | C3 Shizuku 回退（`Shell` 抽象，P1/L） | 引入 `dev.rikka.shizuku:api` |
| 11 | C7 页面指纹（先做时间窗，二期做指纹，P1/M） | 无障碍已通 |
| 12 | C4 通知 Hook（双点，互斥启用，P2/M） | LSPosed，扩 scope 到 `system` |
| 13 | C5 短信 Hook（P2/M） | 扩 scope 到 `com.android.phone`，**优先级最低** |
| 14 | B6 mms 通知转短信通道（P2/S） | 无 |
| 15 | E2 / E3 内存表与包名映射（P2/S） | 无 |
| 16 | D6 规则众包（Issue → CI，P2/L） | 规则体系已数据化（D1） |
| 17 | A5 补 `subText`、C6 Hook 编排、C8 应用内 Hook、D5 扁平化（P3） | 看定位 |

---

## 10. 风险总表

| # | 风险 | 触发条件 | 缓解 |
| --- | --- | --- | --- |
| R1 | **规则库的合规反噬** | 规则包里大量出现银行名/产品名作为匹配锚点 | 规则只存正则与通用词；规则包不公开可检索；不做「银行图标/品牌」展示 |
| R2 | **Hook 系统类升级即碎** | Android 版本升级、ROM 定制 | 体检页暴露「Hook 是否挂上」；不静默失败；C4 双点互斥而非同时开 |
| R3 | **用户正则造成灾难性回溯**（D1） | 用户输入 `(a+)+$` 类模式 | 执行超时 200 ms、拒绝嵌套量词、长度上限 512、跑在 `Dispatchers.Default` |
| R4 | **敏感权限上架审核** | 加 `READ_SMS` / `QUERY_ALL_PACKAGES` / 无障碍 | 三者都不加；包名枚举用白名单 `<queries>`；无障碍用途写清 |
| R5 | **AI 兜底泄露敏感原文**（D4） | 默认开启 | 默认关闭；开启时明示「原文发往 xxx」；只对通过 `plausibleSender` 的文本调用 |
| R6 | **持久化去重表无限增长**（A2） | 长期运行 | 每次写入顺手删过期行；`HealthJobService` 兜底清理 |
| R7 | **Room 迁移丢数据**（D1/D3） | v3 → v4 | 迁移单测 + 迁移前自动导出 JSON 快照 |
| R8 | **重复写回通知使用权骚扰用户**（C2） | 每次启动都硬写 | 只在用户点击「一键修复」时写；`HealthJobService` 只提示不写 |
| R9 | **`NFKC` 改变指纹输入**（B2） | B2 与 A2 同时改 | 统一对规范化后的文本算指纹，只保留一条路径 |
| R10 | **转账误合并**（E1） | 真有两笔反向等额 | 标记为「疑似」而非自动合并；走 Undo 通道可撤销 |

---

## 11. 待核实清单

以下结论在本次勘察中以「源码通读 + 关键路径推断」得出，未做运行时验证，落地前需实测：

1. **AA 的 `enqueueNotificationInternal` 在 Android 14+ 是否仍存在** —— `NotificationHooker.kt` 的注释只写到「Android 8.0+ 稳定存在」，未覆盖 14/15/16。
2. **`requestRebind` 在 HyperOS 上的实际成功率** —— `SystemAccess.set` 的注释已记录 Secure Settings 会被回滚，但 rebind 是另一条路径，需在真机验证。
3. **`NFKC` 对本项目现有词表的影响面** —— 需跑一遍现有短信样本集，确认没有把本来能匹配的文本改坏（尤其含 `①` `㎡` 的营销短信，虽然会被 `BLOCK_WORDS` 拦掉，但要确认拦截顺序不受影响）。
4. **AA 的 `autoRecord` 默认值** —— `RuleModel.autoRecord = false`（默认待确认），但 `BillService.kt:517` 是 `rule?.autoRecord ?: false`，即「规则查不到就 false」。需确认系统规则里有多少条是 `autoRecord=true`（打包的规则库已下架，无法核实）。
5. **`RulesRoute` / 规则包下载链路**（`RuleUpdateHelper.kt` → `https://license.ez-book.org/github`）的服务端行为未知，且域名指向的私有服务不可访问 —— 本项目不打算复用，仅作记录。
6. **AA 的「翻转触发 / 页面切换自动触发」OCR** —— 立项截图里写的是「无障碍模式下支持翻转触发、页面切换自动触发」，本次在 `OcrTools.kt` / `OcrViews.kt` 里**没有找到**翻转的传感器逻辑，可能在被改造过的 AOSP `com/google/android/accessibility/selecttospeak/` 包里，也可能宣传超前于实现。**本条未能核实**。本项目若要做「摇一摇/翻转唤起」，不必参考 AA —— `SensorManager` 监听 `TYPE_ACCELEROMETER` 加阈值判定，成本低于读它那套代码。
7. ~~**ARS 的 CI workflow 具体配置** —— 未看到 `.github/workflows/`~~ **已核实**：三个 workflow 都在（`issue-rename.yml` / `issue-test.yml` / `release.yml`），内容已收录进 D6 的表格。此前误判是因为只列了 `src/` 与根目录，没进 `.github/`。

---

## 附：本次勘察读过但未采纳的文件（供后续检索）

| 文件 | 行数 | 一句话内容 |
| --- | --- | --- |
| `server/.../tools/MemoryCache.kt` | 122 | 带 TTL 的 KV 缓存，被 `PayToolsHooker` 用来 120 s 内拼装支付信息 |
| `server/.../tools/CurrencyService.kt` | 225 | 多币种汇率（`BillService.kt:495-508` 用它构造 `CurrencyModel`） |
| `server/.../tools/DateUtils.kt` | 346 | 日期解析/格式化工具集 |
| `server/.../tools/StatisticsService.kt` | 591 | 报表聚合（本项目报表在 `data/` 内，未对比） |
| `app/.../ocr/OcrViews.kt` | — | OCR 结果的可视化层 |
| `app/.../xposed/wechat/hooks/{ChatUserHooker,DatabaseHooker,RedPackageHooker,TransferHooker,WebViewHooker}.kt` | — | 微信侧其余 5 个 hook 点（不在本次范围） |
| `app/.../xposed/alipay/hooks/{SecurityHooker,WebViewHooker}.kt` | — | 支付宝侧其余 2 个 hook 点 |
| `app/.../ui/fragment/intro/IntroPagePermissionFragment.kt` | — | 权限清单化引导（`PermItem` 列表 + `isRequired` 标记） |
| `app/.../update/RuleUpdateHelper.kt` | — | 云端规则版本检查 → 下载 zip → 解压 → `rule/update` |
| `app/src/main/assets/rule/{jquery-1.11.2.min.js,regex_1.js,regex_2.js}` | — | 规则编辑器用的 JS 库（CodeMirror 打包），与规则本体无关 |