# 05 · Root / 非 Root 双模式设计

> 同一套记账内核（`data/NotificationRules.kt` + `LedgerRepository`），在「无 Root」与「有 Root（LSPosed）」两种宿主能力下采集账单。本文按 2026-11-22 源码把**已实现**与**仅设计**严格分开。

## 0. 结论先行

| 问题 | 结论 |
| --- | --- |
| **当前实际可用** | **非 Root 模式全量可用**：通知监听 + 短信广播 + 截屏记账（无障碍 `takeScreenshot` / MediaProjection / su 三路）。无需任何特殊权限即可上线 |
| **Root / LSPosed** | **仅骨架，未实现**。`xposed/LedgerModule.kt` 21 行只打一行日志，无 hook；`SystemAccess.moduleActive` 读的标志位无人写入，恒为 `false` |
| **模式 C（ADB / Shizuku）** | **已实现**：一次性 `adb shell pm grant … WRITE_SECURE_SETTINGS` 后，App 可自写 `Settings.Secure` 完成免手动授权与自愈。这是当前除手动勾选外**唯一真正可用的"增强"路径** |
| 边界（设计原则） | LSPosed 模块只做宿主能力补洞与投递，不解析文案、不碰 Room、不发通知；解析与入库始终在 App 进程 |
| 截屏与 Root 解耦 | 三条截屏路互不影响，模块不负责截屏 |

## 1. 现行实现基线（实测）

| 能力 | 落点 | 状态 |
| --- | --- | --- |
| 通知采集 | `service/LedgerNotificationListener.kt`（247 行） | ✅ 已实现；需用户在系统勾选通知使用权 |
| 折叠通知补全 | 同上 `collectGroupChildren`（`getGroup()` + `activeNotifications`） | ✅ 已实现（非 Root 即可捞回被折叠的子通知） |
| 短信采集 | `service/SmsReceiver.kt`（112 行，`SMS_RECEIVED` + `RECEIVE_SMS`） | ✅ 已实现；只收开启之后的短信 |
| 规则解析 | `data/NotificationRules.kt`（518 行，纯函数） | ✅ 无 Android 依赖 |
| 入库去重 | `LedgerRepository.addImported` / `addAutoParsed` + `Bill.external_id` 唯一索引 | ✅ 见 [02 §4](02-架构与数据流.md#4-导入去重算法) |
| 入库通知 + 撤销 | `service/BillNotifier.kt`（110）+ `service/UndoBillReceiver.kt`（76） | ✅ 每笔独立通知 id |
| 截屏记账 | `screenshot/*`（无障碍 / MediaProjection / su） | ✅ 三路，见 [03 §5](03-功能模块详解.md#5-截屏记账--screenshot6-个文件) |
| 权限探测与跳转 | `util/SystemAccess.kt`（272） | ✅ |
| 模式 C 自愈 | `SystemAccess.ensureGrants` / `ensureNotificationListener` / `ensureAccessibility` / `adbGrantCommand` | ✅ 已实现 |
| 心跳自检 | `service/HealthJobService.kt`（74，JobScheduler 15 分钟，调 `ensureGrants`） | ✅ |
| **无障碍"通知事件"兜底采集** | 配置只订阅 `typeWindowsChanged`，未订阅 `typeNotificationStateChanged` | ❌ 未实现（设计见 §3.1） |
| **LSPosed hook** | `xposed/LedgerModule.kt`（21，仅日志） | ❌ 仅骨架 |

## 2. 非 Root 模式（实际主力）

### 2.1 采集通道（三条，能力互补）

| 通道 | 状态 | 拿到什么 | 拿不到什么 |
| --- | --- | --- | --- |
| `NotificationListenerService` | ✅ | 标题、`text`、`bigText`、包名、时间；折叠子通知经 `activeNotifications` 补全 | 被 App 主动隐藏的通知；通知使用权未勾选时全部拿不到 |
| `SMS_RECEIVED` 广播 | ✅ | 发件人、正文、时间；长短信分段合并 | 开启前的历史短信；被三方短信 App 接管后 |
| 无障碍 `TYPE_NOTIFICATION_STATE_CHANGED` | ❌ 设计项 | 通知弹出瞬间的 `event.text`（含包名），作为 NLS 兜底 | 只有首屏文本，无 `bigText`；受系统"无障碍自动关闭"影响 |

> 若将来补无障碍兜底：只需 `res/xml/accessibility_screenshot_config.xml` 的 `accessibilityEventTypes` 增加 `typeNotificationStateChanged`，并在 `ScreenshotAccessibilityService.onAccessibilityEvent` 分流该事件（`event.packageName` → 白名单 → 拼 `NotifyRaw` → `parse`）。**不需要** `canRetrieveWindowContent`。

### 2.2 App 保活（按难度/收益分层，均已落地或可落地）

| 层级 | 手段 | 现状 |
| --- | --- | --- |
| L1 系统绑定锚点 | `NotificationListenerService` + `AccessibilityService` 由系统绑定，比自起前台服务更难杀、进程死了系统会重绑 | ✅ 已利用 |
| L2 前台服务 | `ScreenshotCaptureService`（已声明 `mediaProjection`/`dataSync` 类型），只在采图时起、用完即停 | ✅ 已实现，不常驻 |
| L3 系统调度心跳 | `HealthJobService`（JobScheduler 15 分钟）检测并尝试自愈权限/服务绑定 | ✅ 已实现 |
| L4 精确闹钟 | `ReminderScheduler`（`setExactAndAllowWhileIdle`）只用于日报/月报与提醒 | ✅ 已实现，不用于拉活 |
| L5 用户级白名单 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 引导"自启动/后台弹出/省电策略=无限制"（`SystemAccess.openAutoStart`） | ✅ 有入口，国产 ROM 上收益最大 |

**明确不做**：双进程互拉（会被 `PhantomProcessKiller` 杀）、1 像素透明 Activity 保活（Android 12+ 受限且体验差）、`START_STICKY` 死循环重启（会被系统限流）。

### 2.3 无障碍失效风险

无障碍被系统关闭后**不会自动恢复**。现状对策：`ScreenshotAccessibilityService` 维护单例、`SystemAccess.accessibilityGranted` 探测 `enabled_accessibility_services`，`HealthJobService` 周期调用 `ensureAccessibility`（仅在已授 `WRITE_SECURE_SETTINGS` 时能真写回，否则只探测并提示）。`accessibility_screenshot_config.xml` 的 `notificationTimeout=100`。

## 3. Root 模式（LSPosed）——设计，未实现

### 3.1 设计目标（源码注释里的计划）

`LedgerModule` 注释列的三件事，均**尚未写代码**：
1. 自动把本应用写入 `enabled_notification_listeners`（免用户手动勾选）；
2. 覆盖安装后保持通知使用权；
3. 可选：在 SystemUI/SystemServer 侧兜底采集通知文本。

### 3.2 声明文件（已就位）

`app/src/main/resources/META-INF/xposed/`：
- `module.prop`：`id=com.eta.autoledger`、`name=自动记账`、`version=0.4.0`、`versionCode=5`、`author=Eta`、`minApiVersion/targetApiVersion=102`；
- `scope.list`：`system`（作用域是 system_server）；
- `java_init.list`：`com.eta.autoledger.xposed.LedgerModule`。
- 依赖：`compileOnly io.github.libxposed:api:102.0.0`；`packaging { merges += "META-INF/xposed/*" }`。

### 3.3 激活判据（当前恒假）

`SystemAccess.moduleActive` 读 `Settings.Secure` 的 `autoledger_module_active == 1`。但**当前没有任何代码写这个键**（模块只打日志，App 侧也不写）→ `moduleActive` 恒 `false`，`SystemAccess.capabilityLabel` 只会返回「标准（通知 + 短信）」或「标准 + 自愈（adb 已授权）」，永远不会显示「Root 增强（LSPosed）」。将来模块实现时，应在 `onSystemServerStarting` 里写此键，UI 才会切换。

### 3.4 运行前提（当实现后）

设备已 Root + LSPosed；模块勾选本 App 并重启；`scope` 覆盖 `system`。

## 4. 模式 C —— ADB / Shizuku 半 Root（**已实现**）

侧载应用无法在运行时申请 `WRITE_SECURE_SETTINGS`（`signature|privileged`），但可用一条命令授予：

```bash
adb shell pm grant com.eta.autoledger android.permission.WRITE_SECURE_SETTINGS
# 或经 Shizuku 执行同一条 pm grant
```

授予后（`SystemAccess.canWriteSecureSettings` 为真）：
- `ensureNotificationListener(context)`：把本应用组件写回 `Settings.Secure.enabled_notification_listeners`（免手动勾选、覆盖安装后保持）。
- `ensureAccessibility(context)`：把 `ScreenshotAccessibilityService` 写回 `enabled_accessibility_services`。
- `ensureGrants(context)`：返回 `(通知使用权, 无障碍)` 两项最终状态；经 `HealthJobService` 每 15 分钟自愈一次。
- UI（`settings/pages/SystemAccessPage.kt`）用 `adbGrantCommand(context)` 展示应执行的命令，未授权时只探测不写。

**价值定位**：成本一条命令，收益接近 Root 的"免授权 + 可自愈"，且不用每次 OTA 回归 `@hide` hook。**这是本项目当前唯一可用的增强层。**

## 5. 双模式对比

| 维度 | 非 Root（含模式 C） | Root / LSPosed（设计） |
| --- | --- | --- |
| 通知使用权 | 手动勾选；模式 C 可自动写回 | 自动写入 + 覆盖安装保持 |
| 折叠通知 | ✅ `activeNotifications` 补全 | hook 兜底（未实现） |
| 短信 | ✅ 实时；无历史补录 | 设计含历史补录/兜底（未实现） |
| 进程保活 | 系统绑定 + JobScheduler 心跳 + 白名单 | 设计为进程级保活（未实现） |
| 无障碍自愈 | 需模式 C 的 `WRITE_SECURE_SETTINGS` | 设计为 hook 直写（未实现） |
| 截屏 | ✅ 三路（无障碍 / MP / su） | 与 Root 无关 |
| 上手成本 | 低（勾权限即可） | 高（Root + LSPosed + 重启） |
| **状态** | ✅ 可用 | ❌ 骨架，仅声明文件就位 |

## 6. 推荐策略

1. **默认非 Root**：通知监听 + 短信已覆盖 90%+ 场景。
2. **建议开启模式 C**：一次 `adb`/Shizuku `pm grant`，把"手动勾选 + 易失效"两个痛点交给自愈，收益最高。
3. **Root 模式暂不作为卖点**：模块未实现，`moduleActive` 恒假，文档与 UI 应避免暗示"已支持 Root 增强"。

## 7. 既有方案调研（2026-11，保留作设计参考）

> 以下为立项时对现成轮子的调研结论，**属参考背景，不代表已落地**。

### 7.1 现成轮子

| 方案 | 价值 | 结论 |
| --- | --- | --- |
| `NotificationListenerService` + `getActiveNotifications` | 非 Root 补全折叠通知 | ✅ 已采用（§2.1） |
| `WRITE_SECURE_SETTINGS`（adb/Shizuku） | 非 Root 免手动授权 + 自愈 | ✅ 已采用（§4 模式 C） |
| `gkd` 等无障碍方案 | 通知事件兜底 | 参考其事件分流思路，尚未实现 |
| LSPosed `libxposed` API 102 | system_server 侧 hook | 仅声明就位，实现待做 |

### 7.2 三处设计修订（已吸收）

1. **新增模式 C**：ADB/Shizuku 半 Root，已落地（§4）。
2. **折叠通知从"必须 Root"降级为"非 Root 可解"**：`getActiveNotifications` + `groupKey` 已落地（`collectGroupChildren`）。
3. **落地顺序**：先非 Root 全量 → 再模式 C → 最后 Root（Root 仍未启动）。

### 7.3 仍需真机验证的信息缺口

- 各厂商 ROM（MIUI/HyperOS、OriginOS、ColorOS、MagicOS、One UI）下 `enabled_notification_listeners` / `enabled_accessibility_services` 的写入在 OTA、双开、覆盖安装后是否保持。
- Android 16+ 实况通知（`FLAG_PROMOTED_ONGOING`）在目标机型的实际上岛效果，见 [03 §16](03-功能模块详解.md#16-通知出口与超级岛)。
