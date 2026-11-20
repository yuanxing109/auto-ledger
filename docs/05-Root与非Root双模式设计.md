# 05 · Root / 非 Root 双模式设计说明

> 目标：同一套记账内核（`data/NotificationRules.kt` + `LedgerRepository`），在「无 Root」与「有 Root（LSPosed）」两种宿主能力下都能自动采集账单，并**明确 LSPosed 模块的职责边界**——模块只做宿主能力的补洞与投递，不做解析、不做入库。
>
> 现状基线：`xposed/LedgerModule.kt` 只有 22 行骨架（`onSystemServerStarting` 里一行日志），`res/META-INF/xposed/scope.list` 为 `system`，`module.prop` 声明 `minApiVersion/targetApiVersion = 102`。非 Root 侧的两条通道（通知监听、短信广播）已实现并可用。

---

## 0. 结论先行

| 问题 | 结论 |
| --- | --- |
| 默认模式 | **非 Root**。通知监听 + 短信广播已覆盖 90% 以上真实场景，无需任何特殊权限即可上线 |
| Root 的价值 | 不是"多记账"，而是**免授权 + 免折叠 + 保活**：把「用户手动勾选通知使用权」「系统折叠通知抓不到」「进程被省电策略杀」三个洞补掉。其中"免折叠"与"免授权"两项在 §7 调研后部分下放到非 Root（`getActiveNotifications` 补全 / `WRITE_SECURE_SETTINGS` 自授权），Root 的独占价值收窄为**短信历史补录 + 常驻通知 + 进程级保活** |
| 边界 | LSPosed 模块是**采集前端 + 权限豁免器**，绝不碰 Room、绝不解析文案、绝不发通知；一切解析与入库仍在 App 进程里跑 |
| 截屏记账 | 与 Root 解耦。三条路（无障碍 `takeScreenshot` / su `screencap` / MediaProjection）互不影响，模块**不负责截屏** |
| 切换策略 | 运行时探测模块是否激活 → 自动选择采集源 → UI 显示「当前模式」，失效时静默降级到非 Root 通道 |
| **第三模式（调研后新增）** | 在非 Root 与 LSPosed 之间还有一层：**`adb`/Shizuku 一次性授予 `WRITE_SECURE_SETTINGS`**，让 App 自己写 `Settings.Secure` 完成免手动授权与自愈。成本一条命令，收益接近 Root 的「免授权 + 可自愈」，且不需要每次 OTA 回归 `@hide` hook → 见 §7.2 |
| **折叠通知（调研后修订）** | 不再是"只能靠 hook"：`getActiveNotifications()` + `groupKey` 在 NLS 侧即可补全被折叠的子通知（§7.2.2）；hook 退化为兜底 |

---

## 1. 现有实现基线（决定"增强"从哪里开始）

| 能力 | 现有落点 | 状态 |
| --- | --- | --- |
| 通知采集 | `service/LedgerNotificationListener.kt`（153 行，`NotificationListenerService`） | ✅ 已实现，**需用户在系统里勾选通知使用权** |
| 短信采集 | `service/SmsReceiver.kt`（100 行，`SMS_RECEIVED`）+ `RECEIVE_SMS` 运行时权限 | ✅ 已实现，只收开启之后的短信 |
| 规则解析 | `data/NotificationRules.kt`（424 行，`parse` / `parseSms` / `plausibleSender` / `dedupeId`） | ✅ 纯函数，无 Android 依赖 |
| 入库去重 | `LedgerRepository.addImported` + `Bill.external_id` 唯一索引（前缀 `ntf` / `sms`） | ✅ 已实现 |
| 入库通知 + 撤销 | `service/BillNotifier.kt`（92 行）+ `service/UndoBillReceiver.kt`（32 行，按 externalId 删） | ✅ 已实现 |
| 无障碍服务 | `screenshot/ScreenshotAccessibilityService.kt`，配置只订阅 `typeWindowsChanged`，`canTakeScreenshot=true` | ✅ 仅用于截图，**未订阅通知事件** |
| Root 截屏 | `screenshot/RootScreenshot.kt`（`su -c screencap`） | ✅ 已实现（可选路径） |
| LSPosed 模块 | `xposed/LedgerModule.kt`（22 行，仅日志） | ❌ 骨架 |

结论：Root 模式的全部工作集中在 `xposed/` 目录 + 一条 App 侧的投递入口，不需要改规则库与数据层。

---

## 2. LSPosed 模块的职责边界

### 2.1 一句话定位

> 模块运行在 `system_server`（`scope.list = system`），以 **uid 1000（system）** 身份做事。它拿到的不是"更多权限"，而是**系统进程本来就有的能力**：读写 `Settings.Secure`、看 `NotificationManagerService` 内部的每一条通知、看到达 `InboundSmsHandler` 的每一条短信、决定谁不能被 `ActivityManagerService` 杀掉。

因此模块的职责被严格限定为三件事：**豁免授权、补全采集、维持存活**。它投递出去的永远是「原始文本」，不是账单。

### 2.2 它做什么（逐条）

#### A. 免授权：自动写入通知使用权

- 做什么：把 `com.eta.autoledger/.service.LedgerNotificationListener` 追加进 `Settings.Secure.enabled_notification_listeners`（系统里就是"通知使用权"列表，格式是用 `:` 连接的 `pkg/cls`）。
- 实现：`onSystemServerStarting` 之后需要等 `SettingsProvider` 可用，所以要延迟到 `ActivityManagerService.systemReady` 之后（或在 `Handler` 上 `postDelayed` 一次 + 幂等校验）。写入前先读回、已存在就跳过，避免每次开机都写脏值。
- 收益：用户装完模块重启即生效，不用去「设置 → 通知使用权」里手动勾选；卸载 App 后不会留下僵尸授权（模块可顺手清理不存在的包名）。
- 风险点：Android 12+ 对 `enabled_notification_listeners` 的写入不检查调用者身份（因为是 `Settings.Secure` 普通键），但**部分 ROM 会把该键缓存进 `NotificationManagerService` 内存**，只写 Settings 不一定立刻生效——需要在写入后触发一次 `NMS` 的 `SettingsObserver` 刷新（hook 其 `updateListenerHintsLocked`/`updateNotificationListeners` 或直接反射调用）。

#### B. 免折叠：直接读 NMS 内部通知

- 做什么：非 Root 的 `NotificationListenerService` 会收到 `FLAG_GROUP_SUMMARY` 的**汇总通知**，子通知可能被折叠掉了；模块在 `system_server` 里可以直接看到 `enqueueNotificationInternal` 的每一条 `Notification` 与完整的 `StatusBarNotification`（含 `extras` 里的 `EXTRA_BIG_TEXT`、`EXTRA_TEXT_LINES`）。
- 实现：hook `com.android.server.notification.NotificationManagerService#enqueueNotificationInternal`（Android 14 起有带 `tag`/`id` 的重载，需按版本挑签名；Android 15/16 需复核）。取 `pkg / opPkg / userId / n.extras / n.when / n.getChannelId()`，组装成与 `NotifyRaw` 同构的数据。
- 边界：**仍然受 `prefs` 开关约束**——模块会把包名投递给 App，由 App 侧 `isChannelEnabled` 再判一次；模块不解析、不判断收支。
- 补的洞：`FLAG_GROUP_SUMMARY` 折叠、`FLAG_ONGOING_EVENT` 的银行常驻通知（非 Root 侧被主动过滤，Root 侧可只在"文本变化"时采一次）。

#### C. 兜底采集：短信与银行/支付文案的另一条路

- 做什么：hook 短信落库链路（`com.android.internal.telephony.InboundSmsHandler#processMessagePart` 或更靠近交付的 `deliverSmsToApp` / `dispatchSmsDeliveryIntent`，按 ROM 版本挑点），拿到 `pkg`、发件人 `originatingAddress`、正文、时间戳；也可以以 system uid 直接查 `content://sms`（`Telephony.Sms`）做**历史补录**。
- 收益：不依赖 `RECEIVE_SMS` 运行时权限；不受"短信 App 被停用/被三方接管"影响；能补录开启之前的历史短信。
- 边界：补录必须走**一次性导入**语义（用户点击"从短信补录 N 天"），不能开机自动全量灌库——否则用户换机/首次激活会突然多出几百条账单。

#### D. 保活：让采集进程不被省电策略杀掉

- 做什么：在 `ActivityManagerService` / `OomAdjuster` 侧对本包豁免：`cleanUpApplicationRecordLocked` 不杀、`setOomAdjLocked` 提升到 `PERCEPTIBLE_APP_ADJ`，或直接 keep-alive 一个 `ContentProvider` 连接。等价于"系统级白名单"。
- 收益：非 Root 侧最痛的问题（MIUI/EMUI/ColorOS 的"省电策略"把 `NotificationListenerService` 解绑）被绕过。
- 边界：只提升优先级与豁免清理，**不伪造前台服务**、不修改 `Notification`（避免"常驻通知无法关闭"的体验灾难）。

#### E. 多用户 / 多开与开机即启

- 做什么：按 `userId` 逐个写入 `enabled_notification_listeners`（工作资料、应用分身各一份）；在 `systemReady` 阶段（早于 `BOOT_COMPLETED`）就把 App 的采集进程拉起来，省掉"开机后半分钟才记账"的空窗。

### 2.3 它不做什么（同样逐条，附原因）

| 不做 | 原因 |
| --- | --- |
| **不解析文案、不判收支、不选分类** | 解析必须留在 `data/NotificationRules.kt`：纯函数、可单测、改词表不用重启 system_server；模块里写业务规则 = 每次调参都要重启手机 |
| **不写 Room、不直连数据库** | `system_server` 与 App 是两个 uid、两个进程，跨进程写 SQLite 会锁冲突；去重也必须由 `Bill.external_id` 唯一索引在 App 侧统一裁决 |
| **不发"自动记账"通知** | 通知文案与撤销按钮属于 UI 语义，`BillNotifier` 已存在；模块发通知会绕开 `singleMuted`/`persistentSingle` 等偏好 |
| **不负责截屏记账** | `SurfaceControl.screenshot` 在 system_server 内理论可用，但属另一条产品线（MediaProjection/无障碍/Root 三选一已经覆盖）；塞进模块会让"通知模块"承担截图权限风险 |
| **不能免掉 `POST_NOTIFICATIONS`** | Android 13+ 通知权限是用户级开关，模块改了就是越权改用户偏好；入库通知发不出时只在 App 内提示 |
| **不能读别的 App 私有数据** | 支付宝/微信的 `/data/data/<pkg>` 仍是沙箱，`system_server` 也没有读权限；想拿订单详情只能走通知/短信/截屏 |
| **不能绕过锁屏与生物识别** | 与 Keyguard 无关，模块不碰 `KeyguardService` |
| **不能保证跨版本可用** | NMS/AMS/InboundSmsHandler 都是 `@hide` 内部实现，Android 15/16 有方法签名与拆分（`NotificationManagerService` 部分逻辑下沉到 `NotificationManagerServiceImpl`）；每次大版本升级都要回归 |
| **不做双进程互拉** | Android 12+ 的 `PhantomProcessKiller` 会直接杀子进程，互拉方案已失效，别写进设计 |
| **不改 `enabled_accessibility_services`** | 无障碍是用户显式授权的高危能力，代写等于绕过告知；如需无障碍兜底，引导用户手动开启 |

### 2.4 与现有通知监听 / 短信的调用关系

```
                        ┌───────────────────────────── App 进程 ─────────────────────────────┐
system_server           │                                                                     │
┌──────────────┐        │  ModuleFeedReceiver ──► NotificationRules.parse / parseSms          │
│ LedgerModule │        │        │                          │                                 │
│  (uid 1000)  │  IPC   │        └──── NotifyRaw ───────────┘                                 │
│              │ ─────► │                     │                                               │
│ hook NMS     │  广播  │                     ▼                                               │
│ hook SMS     │        │        LedgerRepository.addImported ──► Bill(external_id 唯一索引)    │
└──────────────┘        │                     │                                               │
                        │                     ▼                                               │
                        │        BillNotifier.notifyAdded（可撤销，id 9100）                   │
                        └─────────────────────────────────────────────────────────────────────┘
```

- **IPC 契约**：模块 `sendBroadcastAsUser(Intent(ACTION_MODULE_FEED).setPackage("com.eta.autoledger"))`，动作 `com.eta.autoledger.action.MODULE_FEED`，附带 `source`（`notify` / `sms` / `sms_history`）、`pkg`、`title`、`text`、`bigText`、`postTime`（long）。
- **权限**：App 侧声明 `<permission android:name="com.eta.autoledger.permission.MODULE_FEED" android:protectionLevel="signature|privileged" />` + `<uses-permission>`，`ModuleFeedReceiver` 用 `android:permission` 绑定它。system uid 属 privileged，所以能发；普通三方 App 发不进来。
- **复用边界**：接收器只做一件事——`NotifyRaw` / `parseSms` 的**入参转换**，之后与现有代码路径完全重合（`addImported` → 通知 → 撤销）。这样"模块上线"不会带来第二条业务分支。
- **去重**：模块投递的数据走 `dedupeId(..., prefix = "xpd")`，与 `ntf` / `sms` 分前缀；同一条通知既能被 NLS 抓到也能被模块抓到，两个前缀会各插一次——所以**模块激活时应当关闭 NLS 通道**（见第 6 节切换策略），而不是两路并行。
- **开关一致性**：模块投递前读一次 App 侧偏好不可行（跨进程读 SharedPreferences 有缓存风险），所以开关判断全部放在 App 侧 `isChannelEnabled`；模块只管投递，App 关了就不入库。

### 2.5 运行前提与激活判据

- 前提：解锁 Bootloader + Magisk / KernelSU + Zygisk（或 Riru）+ LSPosed；模块 scope 勾选 **系统框架（system）**；`libxposed api 102`（`module.prop` 已声明 `minApiVersion=102`）；Android 11～16 之一。
- 激活判据（App 侧判断，用于 UI 与切换）：
  1. 模块启动时写一个标志：`Settings.Secure.putInt("autoledger_module_active", 1)`（或 `SystemProperties`），App 读它；
  2. App 再校验 `enabled_notification_listeners` 是否已被代写；
  3. 二者都成立 → 显示「Root 增强模式已激活」；任一不成立 → 降级为非 Root 并显示原因。
- 版本适配矩阵（每次系统升级必须复核的三处）：`NotificationManagerService#enqueueNotificationInternal` 签名、`InboundSmsHandler` 的交付方法名、`OomAdjuster`/`AMS` 的清理入口。

---

## 3. 非 Root 模式

### 3.1 采集通道（三条，能力互补）

| 通道 | 现有 | 拿到什么 | 拿不到什么 |
| --- | --- | --- | --- |
| `NotificationListenerService` | ✅ `service/LedgerNotificationListener.kt` | 标题、`text`、`bigText`、包名、时间；覆盖支付宝/微信/银行全部通知 | 折叠的 `FLAG_GROUP_SUMMARY` 子通知、被 App 主动隐藏的通知、通知使用权未勾选时全部拿不到 |
| `SMS_RECEIVED` 广播 | ✅ `service/SmsReceiver.kt` | 发件人、正文、时间；长短信多段合并 | 开启之前的历史短信、被三方短信 App 接管/停用后的短信 |
| 无障碍 `TYPE_NOTIFICATION_STATE_CHANGED` | ❌ 需新增（扩 `ScreenshotAccessibilityService`） | 通知弹出的**那一刻**的 `event.text`（含包名），可作为 NLS 的兜底 | 只有首屏文本，没有 `bigText`/`textLines`；被系统"无障碍 30 分钟自动关闭"策略影响 |

无障碍兜底的关键改动只有两处：`res/xml/accessibility_screenshot_config.xml` 的 `accessibilityEventTypes` 加 `typeNotificationStateChanged`（现有只订阅 `typeWindowsChanged`），以及 `ScreenshotAccessibilityService.onAccessibilityEvent` 里分流该事件（`event.packageName` → 过滤白名单 → 拼 `NotifyRaw` → `parse`）。**不需要** `canRetrieveWindowContent`，通知事件的文本随事件本身下发。

### 3.2 App 保活（按"难度/收益"分层）

| 层级 | 手段 | 说明 |
| --- | --- | --- |
| L1 系统绑定锚点 | `NotificationListenerService` + `AccessibilityService` | 这两个由系统绑定，**比自己拉的前台服务更难被杀**；进程死了系统会重绑。设计上应把"常驻逻辑"挂在它们身上，而不是新起 Service |
| L2 前台服务 | `ScreenshotCaptureService`（已声明 `mediaProjection|dataSync`） | 只在真正采图时起，用完即停；不要为了保活常驻——Android 14+ 对前台服务类型审查严格 |
| L3 系统调度 | `JobScheduler` / `WorkManager` 周期任务（15 分钟内不允许更短） | 用于"心跳自检"：检测 NLS 是否还绑定、无障碍是否还被启用，失效就发本地提示 |
| L4 精确闹钟 | `AlarmManager.setExactAndAllowWhileIdle`（`ReminderScheduler` 已实现） | 只用于日报/月报与自检，不要用来拉活进程 |
| L5 用户级白名单 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 引导「自启动/后台弹出/省电策略=无限制」 | 国产 ROM 上这一条的收益远大于任何代码技巧 |

**明确不做**：双进程互拉（`PhantomProcessKiller` 会杀）、1 像素透明 Activity 保活（Android 12+ 受限且体验差）、`START_STICKY` 死循环重启（会被系统限流）。

### 3.3 无障碍保活与失效风险

- 无障碍一旦被系统关闭，**不会自动恢复**（`AccessibilityManager` 只发 `DISABLE_ACCESSIBILITY` 类事件）。所以：
  1. 在 `ScreenshotAccessibilityService.onUnbind`/`onDestroy` 里记一笔"被动关闭"，下次 App 启动时提示重开；
  2. 用 L3 心跳检测 `Settings.Secure.enabled_accessibility_services` 是否仍含本服务；
  3. 小米/华为等 ROM 的「无障碍超时自动关闭」需用户在开发者选项/无障碍设置里关掉，UI 上给明确指引（不能代改，见 2.3）。
- 无障碍的**能力边界**：能读通知首屏文本，读不到 `bigText`；能截屏（`takeScreenshot`，Android 11+），但金融类 App 会设 `FLAG_SECURE`，截出来是黑图。

### 3.4 适用条件与失效风险一览

| 场景 | 非 Root 是否可用 | 失效表现 |
| --- | --- | --- |
| 通知使用权未勾选 | ❌ | 一条通知都收不到（UI 必须把"通知使用权"置顶，已实现） |
| 通知被系统折叠成组 | ⚠️ 部分 | 只收到汇总文案，可能缺金额 |
| 短信 App 被停用/换三方 | ⚠️ 部分 | `SMS_RECEIVED` 收不到，只能靠通知 |
| 省电策略激进（MIUI/EMUI/ColorOS） | ⚠️ 不稳 | NLS 被解绑，记账停摆，需白名单 |
| 无障碍被系统自动关闭 | ⚠️ | 截图/无障碍兜底失效，通知主通道仍可用 |
| 双卡 + 短信被分身接管 | ⚠️ | 部分短信漏采 |

---

## 4. Root 模式（LSPosed）

### 4.1 实现前提

1. 解锁 Bootloader，刷 Magisk ≥ 26 或 KernelSU；
2. 安装 LSPosed（Zygisk 版），启用模块，scope 勾选**系统框架**；
3. `module.prop` 的 `minApiVersion/targetApiVersion = 102` 与当前 `libxposed api` 匹配（已声明）；
4. 系统版本在适配矩阵内（11～16），升级后回归第 2.5 节的三处 hook 点。

### 4.2 相对非 Root 的增强点

| 增强 | 具体收益 | 依赖的原生能力 |
| --- | --- | --- |
| 免授权 | 装机即生效，不用勾"通知使用权"；覆盖安装后授权不丢 | `Settings.Secure` 直写 + NMS 观察者刷新 |
| 见折叠 | 拿得到 `FLAG_GROUP_SUMMARY` 下的每条子通知（微信"3 条新消息"背后的真实账单） | NMS `enqueueNotificationInternal` |
| 短信兜底 | 不依赖 `RECEIVE_SMS`；短信 App 被停用也能收到；可一次性补录历史 | `InboundSmsHandler` hook / `content://sms` |
| 保活 | 采集进程不被省电策略杀；开机即启 | AMS/OomAdjuster 豁免 |
| 多用户 | 工作资料、应用分身各自记账 | 按 `userId` 写监听器 |
| 常驻通知可采 | `FLAG_ONGOING_EVENT` 的银行常驻卡片只在文本变化时采一次 | 模块侧文本 diff |

### 4.3 失效场景

| 场景 | 表现 | 兜底 |
| --- | --- | --- |
| 系统 OTA 后内部方法改名/拆类（如 15→16 的 NMS 重构） | hook 抛 `NoSuchMethodError` 或被跳过，采集静默失效 | 模块侧 try/catch + 写 `autoledger_module_active=0`，App 自动回退 NLS |
| LSPosed 未启用模块 / scope 未勾系统框架 | 模块不加载，无任何日志 | App 探测标志缺失 → 显示"Root 增强未激活"并走非 Root |
| Magisk DenyList（Shamiko）把本 App 或 system_server 隐藏 | 注入失败 | 同上 |
| 系统进安全模式 | 模块全部不加载 | 同上 |
| 厂商 ROM 把 `enqueueNotificationInternal` 内联/裁剪 | hook 点不存在 | 只保留免授权与保活，采集退回 NLS |
| 用户手动取消通知使用权且模块未写回 | 采集断 | 模块开机重写 + App 心跳检测 |
| App 被卸载/冻结 | 投递广播无人接收 | 模块检测包不存在则停止投递并清理授权 |

---

## 5. 双模式对比表

| 维度 | 非 Root（默认） | Root / LSPosed（增强） | 覆盖状态 |
| --- | --- | --- | --- |
| **授权要求** | 通知使用权（手动勾选）+ 短信运行时权限 + 无障碍（可选，手动开启） | 仅需刷入模块并重启；通知使用权/短信权限由模块代写 | 非 Root 已实现；Root 需新增（`xposed/LedgerModule` A 项） |
| **采集范围·通知** | 非折叠通知的 title/text/bigText；折叠摘要可用 `getActiveNotifications()` 按 `groupKey` 补全（§7.2.2） | 全部通知（含组内子通知、常驻通知、被 App 主动隐藏的） | 非 Root 已实现；补全与 Root 采集均需新增 |
| **采集范围·短信** | 开启之后的短信（`SMS_RECEIVED`） | 全量到达短信 + 可选历史补录 | 非 Root 已实现；Root 需新增 |
| **采集范围·截屏** | 无障碍 `takeScreenshot` / MediaProjection / su（Root 但不是模块） | 同左（模块不参与） | 三条路均已实现 |
| **保活手段** | 系统绑定（NLS/无障碍）+ JobScheduler 心跳 + 电池白名单 | AMS/OomAdjuster 级豁免 + systemReady 提前拉起 | 非 Root 需新增心跳与引导；Root 需新增 |
| **可靠性** | 中（受 ROM 省电策略、通知折叠、短信接管影响） | 高（折叠/权限/保活三个洞都补） | — |
| **失效恢复** | 需用户手动重开权限/加白名单 | 模块自动重写授权，OTA 后需人工适配 | — |
| **维护成本** | 低：只跟 Android 公开 API 与 App 文案变 | 高：每次系统大版本要复核 3 处 `@hide` hook，且需真机矩阵回归 | — |
| **风险面** | 用户可见（权限由用户授予） | 模块持有系统进程能力，写错会拖垮 system_server（如死循环 hook） | — |
| **调试成本** | `adb logcat` 即可 | 需 LSPosed 日志 + 模块内 try/catch + 崩溃后 system_server 重启排查 | — |

### 5.1 已覆盖 / 需新增一览

**已由现有实现覆盖**
- 通知监听采集、短信采集、长短信合并、发送方白名单、噪声过滤、金额抽取、收支判定、分类映射、幂等去重、入库通知、按 `externalId` 撤销；
- 三条截屏路径（无障碍 / su / MediaProjection）与 AI 识图确认流；
- 通知使用权状态显示与跳转（`LedgerNotificationListener.isEnabled/openSettings`）、短信权限申请与回滚。

**需要新增**
| 项 | 文件 | 优先级 |
| --- | --- | --- |
| 模块投递入口 | `service/ModuleFeedReceiver.kt`（新增，`MODULE_FEED` + privileged 权限） | P0 |
| 模块：免授权写监听器 | `xposed/LedgerModule.kt`（A 项）+ `xposed/SettingsHook.kt` | P0 |
| 激活探测与模式标识 | `xposed/LedgerModule.kt` 写标志 + `AppPrefs` 新增 `rootModeActive` + 设置页显示 | P0 |
| 模块：NMS 通知采集（含折叠） | `xposed/NotificationHook.kt` | P1 |
| 模块：短信 hook 与历史补录 | `xposed/SmsHook.kt` + 设置页「从短信补录 N 天」 | P1 |
| 模块：保活豁免 | `xposed/KeepAliveHook.kt` | P2（先靠白名单引导） |
| 无障碍通知兜底 | `screenshot/ScreenshotAccessibilityService.kt` + `accessibility_screenshot_config.xml` 加 `typeNotificationStateChanged` | P1 |
| 心跳自检 | `service/HealthWorker.kt`（WorkManager，15 分钟） | P1 |
| 模式切换与降级逻辑 | `xposed/ModeGate.kt`（App 侧）+ 设置页「当前模式」卡片 | P0 |

---

## 6. 推荐的默认模式与切换策略

### 6.1 默认

**默认非 Root。** 理由：
1. 通知监听 + 短信广播在真实设备上已覆盖绝大多数支付场景（微信/支付宝/银行的支付通知与短信几乎必然到达）；
2. 非 Root 的授权是**用户可见、可撤销**的，符合记账类 App 的信任模型；
3. Root 模块的维护成本（每次系统升级回归 3 处 `@hide` hook）远高于它带来的边际收益，只对"折叠通知漏采/被省电策略杀"这两类用户才值得开。

### 6.2 切换策略（单向优先级 + 自动降级）

```
启动时：
  probe = moduleActive(标志存在) && 通知使用权已被代写
  if (probe) 采集源 = XPOSED（NMS hook）
  else      采集源 = NLS（系统绑定）
  短信源 = moduleActive ? XPOSED_SMS : SMS_RECEIVED
  无障碍兜底 = 用户开启且 NLS 处于"最近 24h 内零采集"状态时启用
```

- **同一时刻只跑一路采集源**，避免同一条通知被 `ntf` 与 `xpd` 两个前缀各插一次（前缀不同，唯一索引挡不住）。
- **自动降级**：模块侧任何 hook 失败 → 写 `autoledger_module_active=0` → App 下次启动回退 NLS，并在设置页显示「Root 增强已失效（原因：…）」。
- **不自动升级**：不允许在运行中从 NLS 切到 XPOSED（会造成一段时间的采集空窗），只在**进程启动时**决定。
- **用户可见**：设置 → 自动记账 页顶部显示「当前模式：标准 / Root 增强」与各通道最后采集时间（便于排查"为什么没记账"）。
- **回滚**：关掉模块 scope 或卸模块 → 下次启动自动回退，无需清数据；已入库账单与模式无关（`external_id` 前缀不同但唯一索引仍有效）。

### 6.3 落地顺序建议

1. **P0**：`ModuleFeedReceiver` + 免授权写监听器 + 模式探测/显示 → 这一步就能让 Root 用户"免勾选、覆盖安装不丢授权"。
2. **P1**：NMS 通知 hook（解决折叠）+ 无障碍通知兜底 + 心跳自检 → 采集完整性与可观测性。
3. **P2**：短信 hook 与历史补录、保活豁免 → 体验提升，失败也不影响主链路。

> §7 调研后对上面三档做了重排：**模式 C（adb/Shizuku 自授权）跑到 P0**，模块相关的 P0 顺延到 P1 —— 因为「免手动勾选 + 被 ROM 关掉后自愈」这件事，一条 `pm grant` 就能拿到，不必先写 hook。详见 §7.2.3。

---

## 7. 既有方案调研（2026-11）与设计修订

> 这一节回答"哪些轮子已经有了、我们该借哪一段"。结论：**三类现成方案直接可借**——通知折叠补全（SmartLedger）、`WRITE_SECURE_SETTINGS` 自授权与自愈（gkd / FMD / Tasker 一路的做法）、厂商自启动页清单（AutoStarter）。本项目真正要写的只有"把它们的做法接到现有 `NotificationRules` + `LedgerRepository` 单入口"，而不是重造采集层。

### 7.1 现成轮子清单

| 来源 | 借它什么 | 落到我们哪 | 结论 |
| --- | --- | --- | --- |
| [SmartLedger](https://github.com/huanghhcri/SmartLedger) | ① 系统把通知折叠成「[N条]」时，从**同组子通知**补全金额 ② 跨渠道同笔交易合并 ③ 金额不全/置信度低时弹窗确认再入账 | ① `service/LedgerNotificationListener.kt` 的 `handle()` ② `data/NotificationRules.kt` 的 `dedupeId` ③ `service/BillNotifier.kt` 增加「待确认」动作 | ①立即 ②③P2 |
| [OtpAutoCopy-LSPosed](https://github.com/shirenran/OtpAutoCopy-LSPosed) | 「通知访问服务 + 系统框架 Hook」**双通道并存**的成熟先例（含 hook 失败回退） | 与 §2 的双通道设计一致，可照抄其回退写法 | 已采用（§2/§4） |
| [gkd](https://github.com/gkd-kit/gkd)（issue #108） | 拿到 `WRITE_SECURE_SETTINGS` 后用 `Settings.Secure.putString(ENABLED_ACCESSIBILITY_SERVICES, componentName.flattenToShortString())` **自己开关无障碍**；Shizuku 路线引导 | 新增「模式 C」+ `util/SystemAccess.kt` 加自愈动作 | 立即（§7.2.1） |
| [AutoStarter](https://github.com/judemanutd/AutoStarter) | 11 家 ROM 的自启动管理页 `ComponentName` 清单 + `isAutoStartPermissionAvailable()` 探测 | `util/SystemAccess.openAutoStart()` 现有 6 家 → 按它的清单补齐 | 立即 |
| [FMD 文档](https://fmd-foss.org/docs/fmd-android/granting-secure-settings-access) / [GalaxyMaxHz wiki](https://github.com/tribalfs/GalaxyMaxHzPub/wiki/How-to-grant-WRITE_SECURE_SETTINGS) / MacroDroid | `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` 的标准引导文案与"授权后自动生效、无需再操作"的 UX 范式 | 体检页加一张「ADB 一次性授权」卡片（可复制命令） | 立即 |
| [AutoBookKeepingBeta](https://github.com/FuShengDuoBuYu/AutoBookKeepingBeta) | 把通知文本丢给云端 LLM 解析、用 prompt 做防误判；App 侧只保留来源过滤 + 去重 + 结果校验 | 对照项：我们走**离线规则法**（零成本、离线、可解释）。可选把 LLM 当"规则全落空"时的兜底 | 暂不（作为 §4 增强候选） |
| [libxposed/api 102](https://libxposed.github.io/api/io/github/libxposed/api/XposedModuleInterface.html) / [YukiHook 迁移说明](https://highcapable.github.io/YukiHook/en/config/migration/libxposed-api.html) | `onSystemServerStarting(SystemServerStartingParam)` 生命周期、`param.getClassLoader()`、`XposedInterface.hook` 的现代写法（旧 `XC_MethodHook` 已过时） | `xposed/LedgerModule.kt` 骨架补实时的 API 基线 | P1 附带 |
| [Ripple 保活指南](https://ripplefocus.github.io/keep-alive-guide/) / [gkd issue #357](https://github.com/gkd-kit/gkd/issues/357) / [华为官方：无障碍开关自动关闭](https://consumer.huawei.com/cn/support/content/zh-cn00410039/) | 小米 HyperOS / ColorOS / vivo OriginOS 的「无障碍被自动关闭」是**厂商行为**，官方文档承认；给出受限设置解锁 + 各系统保活路径 | §3 的保活 L1~L5 与体检页文案 | 已覆盖，需补文案 |

### 7.2 三处设计修订

#### 7.2.1 修订一：新增「模式 C — ADB / Shizuku 半 Root」

原设计只有"非 Root（手动授权）"和"Root（模块代授权）"两档，中间其实还有一层，性价比最高：

```
一次性（电脑 adb 或 Shizuku，1 条命令）：
  adb shell pm grant com.eta.autoledger android.permission.WRITE_SECURE_SETTINGS

之后 App 自己就能（无需任何框架）：
  Settings.Secure.putString(cr, "enabled_notification_listeners", 旧串 + ":com.eta.autoledger/.service.LedgerNotificationListener")
  Settings.Secure.putString(cr, "enabled_accessibility_services", 旧串 + ":com.eta.autoledger/.screenshot.ScreenshotAccessibilityService")
  Settings.Secure.putInt(cr, "accessibility_enabled", 1)
```

- **Manifest 必须先声明**，否则 `pm grant` 直接拒绝（"permission not requested"）：
  ```xml
  <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
      tools:ignore="ProtectedPermissions" />
  ```
- **写入必须在旧串基础上追加**：`enabled_accessibility_services` 是冒号分隔的全量列表，直接覆盖会把用户其它无障碍服务（如输入法、手势工具）一起关掉。规范做法：读原串 → 按 `:` split → 过滤已存在项 → 追加 `ComponentName(context, X::class.java).flattenToShortString()` → `joinToString(":")` → 写回 → 立刻读回校验。
- **自愈**：`HealthWorker`（15 分钟）与体检页「一键修复」都走同一函数 `SystemAccess.ensureGrants(context): Boolean`；条件为 `canWriteSecureSettings() && (!notificationListenerGranted() || !accessibilityGranted())`。这正好补上 §3 里"被 HyperOS 静默关闭只能靠用户手动重开"的洞。
- **代价评估**：一条命令 vs 刷 Magisk + LSPosed + 勾 scope + 每次 OTA 回归 hook。除"折叠通知 + 短信历史"这类 `@hide` 能力外，模式 C 已经拿到 Root 收益的绝大部分。
- **失效**：卸载重装后授权丢失（需重做）；部分 ROM 对 `enabled_accessibility_services` 写入有额外校验可能回滚（纳入真机矩阵）；`WRITE_SECURE_SETTINGS` 属 development 级权限，未来 Android 版本若收紧 `pm grant` 的 development 白名单则失效。

#### 7.2.2 修订二：折叠通知从"必须 Root"降级为"非 Root 可解"

原设计把「折叠通知抓不到」列为 Root 的核心价值之一，实际 NLS 侧就能补：

```kotlin
// handle() 里原本 if (sbn.isGroupSummary) return  →  改为：
if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
    val group = sbn.group            // 同一 groupKey 的子通知才是带金额的那条
    val children = runCatching { activeNotifications }
        .getOrDefault(emptyArray())
        .filter { it.group == group && it.key != sbn.key }
    if (children.isEmpty()) return   // 真没子通知（纯摘要）才放弃
    children.forEach { child -> parseAndStore(child) }
    return
}
```

- 关键 API：`NotificationListenerService.getActiveNotifications()`、`StatusBarNotification.getGroup()`、`Notification.getGroup()`。
- **限制（必须写进实现注释）**：Android 15 起 `getActiveNotifications()` 受 listener 的 `FLAG_FILTER_TYPE_*` 影响（`android:value="conversations|alerting"` 可扩大可见面）；被 `setSilent`/`setGroupAlertBehavior` 处理的子通知可能不出现；`getActiveNotifications()` 有数量与时效窗口。→ **hook 仍保留为兜底**，但不再是"没有它就漏采"。
- 与去重的关系：summary 与子通知可能都被解析到（同一笔），必须在 `dedupeId` 里加"同金额 + ±90 秒 + 同渠道"的归并，否则一次支付会插两条 —— 这也是 SmartLedger 强调"跨渠道同笔合并"的原因。

#### 7.2.3 修订三：落地顺序调整

| 档 | 原计划 | 修订后 |
| --- | --- | --- |
| P0 | `ModuleFeedReceiver` + 模块免授权写监听器 + 模式探测 | **模式 C（adb 自授权 + 自愈）** + **Android 13+ 受限设置引导** + **折叠补全** —— 这三项都不需要写 hook，却解决用户实际碰到的 80% 问题 |
| P1 | NMS 通知 hook + 无障碍通知兜底 + 心跳 | `ModuleFeedReceiver` + 模块免授权 + NMS hook + 无障碍通知兜底 + 心跳（模块相关合并成一档） |
| P2 | 短信 hook + 保活豁免 | 短信历史补录 + 保活豁免 + 跨渠道同笔合并 + 待确认弹窗 |

### 7.3 Android 13+「受限设置」：比保活更前置的阻塞

侧载安装（含 `adb install` 之外的第三方商店/文件管理器安装）的 App，在 Android 13+ 上打开「通知使用权」或「无障碍」时，开关是**灰的**，点一下提示「受限设置：出于安全考虑，此设置当前不可用」。

- 影响面：本项目自用的 APK 必然侧载 → **通知监听、无障碍截屏两条路默认开不起来**。这比"进程被省电策略杀"更靠前，原 docs/05 未覆盖。
- 用户侧解法（约 30 秒）：设置 → 应用 → 自动记账 → 右上角 ⋮ → **允许受限设置** → 返回再开开关。Android 15 起菜单位置不变但多一道确认。
- 代码侧：**没有可直接查询的系统键**（`Settings.Secure` 无对应项），只能做引导：体检页「通知使用权」一行在 `!granted` 时把 summary 改成"若开关灰化，先允许受限设置"，并给 `openAppDetails()` 直达按钮。检测手段只有"用户点了开关但仍未授权"的间接信号。
- 另一条已知绕法：用 `adb install -r` 重装后多数 ROM 不再标受限（installer 记为 shell）；不保证所有 ROM，也不写进主流程。

### 7.4 仍需真机验证的信息缺口

| 缺口 | 为什么重要 | 验证方法 |
| --- | --- | --- |
| `getActiveNotifications()` 在 Android 15/16 的实际可见范围 | 决定折叠补全能否完全替代 hook | 真机发组通知，打印 `activeNotifications.group` 与 `FLAG_FILTER_TYPE_*` 行为 |
| 折叠 summary 不带金额、子通知被 `setSilent` 时能否取到 | 微信/支付宝部分场景的漏采根因 | 用 `adb shell cmd notification post` 构造组通知复现 |
| HyperOS / ColorOS 对 `enabled_accessibility_services` 写入是否回滚 | 决定模式 C 的自愈是否真能生效 | 写入后杀进程、息屏 1 小时再读回 |
| LSPosed 在 Android 16 的注入与 `enqueueNotificationInternal` 签名 | 决定 §4 的 hook 是否还成立 | LSPosed 内测版 + AOSP 16 源码对照 |
| `pm grant WRITE_SECURE_SETTINGS` 在 Android 16 的可用性 | 模式 C 的地基 | 真机执行 `pm grant` 看返回 |

### 7.5 P0 落地记录（2026-11-20）

P0 三项已写进代码（工作区未提交）：

| 项 | 落点 | 行为 |
| --- | --- | --- |
| 模式 C 自授权 | `util/SystemAccess.kt`（245 行）+ `AndroidManifest.xml` 声明 `WRITE_SECURE_SETTINGS`（`tools:ignore="ProtectedPermissions"`） | `canWriteSecureSettings` → `ensureNotificationListener` / `ensureAccessibility` / `ensureGrants`；追加不覆盖其它条目，写后读回校验，专门对付 HyperOS / EMUI 的回滚 |
| 自愈心跳 | `service/HealthJobService.kt`（74 行），由 `MainActivity.kt:17` 调度 | JobScheduler 15 分钟一次，只调 `ensureGrants`；未授权时空转不耗电。选 JobService 是因为进程被杀后系统会重新拉起，顺带给 NLS 一次重绑机会 |
| 折叠补全 | `service/LedgerNotificationListener.kt:77-107` | 摘要改用 `collectGroupChildren`（`activeNotifications` + group 配对）捞子通知，再逐个 `handleOne` |
| 内容归并 | `service/LedgerNotificationListener.kt:176-196` | 10 秒窗口按「渠道 · 方向 · 金额 · 备注前 12 字」归并，避免摘要补全与逐条回调重复入账；带备注是为了不吞掉连刷的同额真账 |
| 设置页入口 | `settings/SettingsScreen.kt`（1649 行） | 体检页顶部「一键修复」卡（有权限真写回、无权限灰化）+「adb 授权自愈」卡（点一下复制 `pm grant` 命令） |
| 受限设置引导 | 体检页「通知使用权」一行 | 未授权时 summary 提示"若开关是灰的，先到应用信息页「允许受限设置」" |

验证：Python 复刻的 5 组归并场景全通过（摘要补全 2 笔→入 2；同批逐条回调→全 skip；同额不同商家→都入；同额同商家隔 40 分钟→都入；同额同商家 10s 连点→第二笔 skip，属预期，长期由 `external_id` 兜底）。37 个 `.kt` + 13 个 XML 静态体检 0 问题。真机验证缺口仍见 §7.4。

### 7.6 参考链接

- libxposed API 102 · `XposedModuleInterface`：<https://libxposed.github.io/api/io/github/libxposed/api/XposedModuleInterface.html>
- LSPosed 官方仓库与使用说明：<https://github.com/LSPosed/LSPosed>
- SmartLedger（通知聚合补全 / 跨渠道去重）：<https://github.com/huanghhcri/SmartLedger>
- OtpAutoCopy-LSPosed（双通道抓取先例）：<https://github.com/shirenran/OtpAutoCopy-LSPosed>
- gkd `WRITE_SECURE_SETTINGS` 自授权（issue #108）：<https://github.com/gkd-kit/gkd/issues/108>
- AutoStarter（厂商自启动页）：<https://github.com/judemanutd/AutoStarter>
- FMD：授予 `WRITE_SECURE_SETTINGS` 的 adb 流程：<https://fmd-foss.org/docs/fmd-android/granting-secure-settings-access>
- Android 13+ 受限设置说明：<https://docs.lawnchair.app/troubleshooting/restricted-settings>
- `NotificationListenerService` 官方文档（`getActiveNotifications` / `Ranking`）：<https://developer.android.com/reference/android/service/notification/NotificationListenerService>
- 华为官方：第三方无障碍服务开关被自动关闭：<https://consumer.huawei.com/cn/support/content/zh-cn00410039/>