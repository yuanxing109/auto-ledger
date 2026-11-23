# AutoLedger 项目文档

> 面向维护者的工程文档，基于 **2026-11-23 工作区源码逐文件通读**整理（版本 `0.3.0` / `versionCode 4`）。
> 所有结论都标注了可核查的文件与行号；无法核实的写「未核实」，不臆造。

## 索引

| 文档 | 内容 | 适合谁 | 状态 |
| --- | --- | --- | --- |
| [01 项目总览](01-项目总览.md) | 定位、功能矩阵（含**真实实现状态**）、技术栈与版本、组件清单、构建运行、首次使用路径、代码规模 | 第一次接触项目 | 现行 |
| [02 架构与数据流](02-架构与数据流.md) | 分层图、Room 实体与 DDL（v3）、DAO 全表、三条迁移链路、导入去重算法、报表聚合、线程约束 | 要改数据层 | 现行 |
| [03 功能模块详解](03-功能模块详解.md) | 记账/首页/搜索筛选/报表/截屏记账/AI 识图/通知监听/短信记账/提醒/小部件/导入导出/WebDAV 云备份/分类预算/LSPosed/系统权限体检/设置页树/主题 | 要改具体功能 | 现行 |
| [04 开发与扩展指南](04-开发与扩展指南.md) | 目录约定、构建调试命令、UI 门禁与 CI、6 组改造配方、已知问题清单、路线图 | 要动手写代码 | 现行 |
| [05 Root / 非 Root 双模式设计](05-Root与非Root双模式设计.md) | LSPosed 模块职责边界、非 Root 采集与保活、Root 增强点与失效场景、双模式对比表、模式 C（ADB·Shizuku） | 要决定架构方向 | 现行 |
| [06 UI 统一改造方案](06-UI统一改造方案.md) | 问题清单、浅深两套色值表、token 规范、搜索页重设计、P0–P2 落地顺序 | 要动界面 | 历史方案（P0 已落地，见文首状态说明） |
| [07 UI 重构方案](07-UI重构方案.md) | M3 / GearUI / Lottie / MPAndroidChart / Compose Icons / LXGW WenKai 取舍、迁移顺序、路线 A/B | 要换技术栈或重构 UI | 历史方案（部分被 08 A 方案取代） |
| [08 A 方案·Miuix 单源与图表迁移](08-A方案-Miuix单源与图表迁移.md) | Miuix `ThemeController` 自定义 `Colors` 53 槽、token→槽映射、14 字体槽、Toast→Snackbar、CI 门禁；**图表迁移部分未落地，见文首修正** | 要按 A 方案动手改 UI | 部分落地（图表章节为规划，非现状） |
| [09 设计系统](09-设计系统.md) | `LedgerPalette` 唯一色源、`LedgerTheme` 转发层、字号/圆角/间距 token、组件规范 | 要统一视觉 | 现行 |
| [10 反模式定稿台账](10-反模式盘点.md) | 门禁基线（R1–R7 实测）、反模式判据、已闭环清单、剩余 warn 明细、未决 TODO、可复用规则、重构总结（逐轮日志归档到 git 历史） | 要守门禁 / 追溯历史 | 现行（已精简） |
| [11 缺陷清单](11-缺陷清单.md) | 31 条按严重度排序的缺陷（明文存 Key、周期账单无事务、零测试、骨架模块、连接不释放等），每条含 `文件:行号` 与修复建议 | 要修 bug | 现行 |
| [12 AutoAccounting 可迁移点梳理](12-AutoAccounting可迁移点梳理.md) | 外部项目逆向笔记：通知/短信 Hook 与权限、解析与规则体系、数据层工具共 30+ 条，逐条给「原项目实现 / 本项目场景 / 权限与改动量 / 限制与风险」，附 P0–P3 落地路线与风险总表 | 要扩展采集能力 / 设计规则体系 | 现行（外部参考，含待核清单） |

## 30 秒速览

- **是什么**：单机 Android 记账 App（Kotlin + Compose + Miuix 风格），同时可作 LSPosed 模块（`scope=system`）。
- **数据**：Room **v3** 单表 `Bill`（`timestamp` 索引 + `external_id` 唯一索引），DAO 全 Flow；偏好走 SharedPreferences（`AppPrefs`，`mutableStateOf` 双向绑定）。
- **三条自动链路**：① 截屏（无障碍截图 / Root / MediaProjection）→ 多模态模型 → 结果通知「记一笔/忽略」→ 入账；② 通知监听 → 规则解析 → 直接入库 →「自动记账」通知（可撤销）；③ 短信广播 `SMS_RECEIVED` → 同一套规则内核 → 同一个通知出口 `BillNotifier`。
- **自愈（模式 C）**：用户用 adb/Shizuku 授予 `WRITE_SECURE_SETTINGS` 后，`HealthJobService` 每 15 分钟把被系统关掉的通道写回。
- **UI 主题**：`ui/theme/LedgerPalette.kt` 是**唯一色值真源**，`LedgerTheme` 只做非 Compose 场景的转发层；`AppTheme` 是唯一主题根。玻璃（GlassHost）体系已全部退役。
- **图表**：**仍是三套自绘 Canvas**（折线/柱状/环形），未引入 Vico / MPChartCompose / 任何第三方图表库。

## 关键文件行数（实测 `wc -l`，共 67 个 `.kt` / 14468 行）

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `settings/SettingsScreen.kt` | 2069 | 设置页树 + 全部子页（含分类管理） |
| `ui/screen/ReportScreen.kt` | 1179 | 报表三 tab + 三套 Canvas 图表 + 钻取 |
| `ui/screen/AddScreen.kt` | 1055 | 记一笔（自绘键盘 + 金额卡 + 分类宫格 + 周期） |
| `ui/screen/SearchScreen.kt` | 810 | 搜索与多维筛选 |
| `ui/screen/HomeScreen.kt` | 673 | 分页列表 + 预算卡 + 概览卡 + 编辑弹窗 |
| `ui/LedgerApp.kt` | 569 | 三 tab 壳 + 覆盖层 + 全局 Snackbar + AI 扫一扫 |
| `data/NotificationRules.kt` | 518 | 通知 + 短信解析规则库（纯函数，可单测） |
| `settings/pages/SystemAccessPage.kt` | 352 | 权限与后台页 |
| `data/LedgerRepository.kt` | 343 | 仓库：聚合/分页/搜索/导入去重入口 |
| `settings/pages/ExportPage.kt` | 338 | 导入/导出/分享 |
| `notification/IslandNotifier.kt` | 304 | 超级岛/灵动岛/实况窗统一出口 |
| `screenshot/ScreenshotCaptureService.kt` | 284 | 前台服务：无障碍截图 / VirtualDisplay 抓帧 |
| `data/LedgerDao.kt` | 278 | DAO：游标分页、聚合 SQL、搜索 |
| `util/SystemAccess.kt` | 272 | 权限探测 + 跳转 + 模块激活探测 |
| `data/CsvImporter.kt` | 267 | 手写 CSV 解析 + Excel 日期序列 |
| `AppPrefs.kt` | 259 | SharedPreferences 全量偏好（mutableStateOf） |
| `service/LedgerNotificationListener.kt` | 247 | 通知监听 + 跨路径归并 |
| `screenshot/VisionHelper.kt` | 222 | 图像 → Base64 → OpenAI 兼容接口 |
| `ui/model/LedgerGrouping.kt` | 203 | 按天分组（EntryUi / DayGroupUi） |
| `widget/LedgerWidgetProvider.kt` | 196 | 两规格小部件（2×2 / 4×2）+ goAsync |
| `viewmodel/HomeViewModel.kt` | 195 | 游标分页 + 首屏/追加页增量合并 |
| `data/ReportRepository.kt` | 190 | 报表聚合（周期/上期/桶/分类） |
| `ui/theme/LedgerPalette.kt` | 167 | **唯一色值真源**（LedgerColors 双主题） |
| `service/RecurringWorker.kt` | 166 | 周期记账 Worker |
| `data/WebDavClient.kt` | 142 | WebDAV MKCOL/PUT/GET/PROPFIND + Basic Auth |
| `ui/theme/LedgerMiuixColors.kt` | 141 | LedgerColors → Miuix 53 槽映射 |
| `data/XlsxExporter.kt` | 131 | 手写 OOXML（inlineStr，无 POI） |
| `ui/LedgerTheme.kt` | 125 | LedgerPalette 转发层（Canvas/非 Composable 用） |
| `data/CategoryStore.kt` | 116 | 分类默认表 + 持久化 + emoji |
| `reminder/ReminderScheduler.kt` | 112 | 日/周/月提醒排期 |
| `service/SmsReceiver.kt` | 112 | `SMS_RECEIVED`、长短信合并、goAsync |
| `screenshot/ConfirmEntryReceiver.kt` | 112 | 结果通知「记一笔/忽略」动作 |
| `service/BillNotifier.kt` | 110 | 自动记账统一通知出口（每笔独立 id + 撤销） |
| `ui/theme/LedgerDimens.kt` | 98 | 尺寸 token |
| `reminder/ReminderReceiver.kt` | 95 | 提醒触发 + 重排下一次 |
| `data/XlsxImporter.kt` | 93 | 纯 regex 解析 xlsx → 委托 CSV |
| `data/RecurringRule.kt` | 91 | 周期规则（周/月/年，存字符串） |
| `data/LedgerDatabase.kt` | 89 | Room 单例 + 迁移 1→2→3 |
| `data/BackupRunner.kt` | 87 | 备份/恢复编排，20h 节流 + 日备 7 份 |
| `ui/theme/LedgerTextStyles.kt` | 84 | 覆盖 Miuix 14 个 textStyles 槽 |
| `data/BackupCodec.kt` | 81 | 备份 JSON 编解码（version 2） |
| `tile/ScreenshotTileService.kt` | 78 | 快捷设置磁贴 |
| `service/UndoBillReceiver.kt` | 76 | 撤销刚入账账单 |
| `data/LedgerDbHelper.kt` | 76 | 旧 SQLite 迁移辅助 |
| `ui/components/AppDialog.kt` | 74 | 通用对话框 |
| `service/HealthJobService.kt` | 74 | 15 分钟心跳，写回被关渠道 |
| `screenshot/ScreenshotRequestActivity.kt` | 63 | 请求 MediaProjection 授权 |
| `data/ParseDedup.kt` | 64 | 分钟级指纹去重（5 分钟窗口） |
| `service/BackupJobService.kt` | 59 | 定时备份 Job |
| `ui/components/SwitchRow.kt` | 57 | 开关行 |
| `ui/AiScanIcon.kt` | 52 | AI 扫一扫图标 |
| `MainActivity.kt` | 49 | 入口：起服务/Worker + 通知权限 |
| `ui/components/CategoryChip.kt` | 49 | 分类 chip |
| `data/LedgerEntity.kt` | 47 | `Bill` 实体 |
| `screenshot/RootScreenshot.kt` | 39 | `su` 截图兜底 |
| `ui/LocalDimens.kt` | 28 | 尺寸 CompositionLocal |
| `notification/IslandDismissReceiver.kt` | 22 | 岛通知 dismiss |
| `reminder/ReminderBootReceiver.kt` | 22 | 开机重排提醒 |
| `ui/AppRoot.kt` | 21 | 根容器（仅铺纯色背景） |
| `xposed/LedgerModule.kt` | 21 | LSPosed 模块骨架 |

> 其余 `ui/components/*`、`ui/theme/*`、`settings/pages/*` 中的小文件与上表重叠者不重复列。完整清单见 [01 §7 代码规模](01-项目总览.md#7-代码规模)。

## 本次重写修正的过时结论

旧版 docs 声明"基于 2026-11-20 源码"，本轮逐项核对后修正如下（旧结论 → 实际）：

1. **「仓库无 Gradle wrapper」→ 已有**：`gradlew` / `gradlew.bat` / `gradle/wrapper/*` 齐全，`distributionUrl=gradle-9.4.1-bin.zip`。见 [01 §5](01-项目总览.md#5-构建与运行)。
2. **「Room v2」→ v3**：`LedgerDatabase` `version = 3`，新增 `MIGRATION_2_3`（周期记账列 `is_recurring` / `recurring_rule`）。见 [02 §3](02-架构与数据流.md#3-迁移链路)。
3. **「玻璃 / GlassHost 体系」→ 已退役**：`ui/AppRoot.kt` 只铺 `Modifier.background(LedgerPalette.dark.page)`，壁纸/压暗/HSV 染色全部删除。
4. **「`LedgerTheme` 深/浅两套语义色（第二色源）」→ 单一色源**：P0 后 `ui/theme/LedgerPalette.kt` 是唯一色值真源，`LedgerTheme` 只是转发层。见 [09 设计系统](09-设计系统.md)。
5. **「三套自绘 Canvas → MPChartCompose v4.0.1 迁移」→ 未迁移**：`app/build.gradle.kts` 无任何图表库依赖（`activity-compose` 上方那句"纯 Compose 图表库…替换三套自绘 Canvas"是**遗留注释**，依赖并未加入）；`ReportScreen.kt` 仍用 `androidx.compose.foundation.Canvas` 自绘折线/柱状/环形。`AboutPage` 里"开源许可：Miuix · Compose · Room · **Vico**"也是**文案错误**（无 Vico 依赖）。见 [08 文首修正](08-A方案-Miuix单源与图表迁移.md)。
6. **「release 用 debug 签名且未混淆」→ 已加固**：`release` 已 `isMinifyEnabled=true` / `isShrinkResources=true`；签名在存在 `keystore.properties` 时用真实 release 证书，否则回退 debug（不阻塞本地构建）。
7. **「通知监听 10s 内容归并」→ 确为 10 秒窗口（键含 note 前 12 字）**：`LedgerNotificationListener` 的 `merged` 表以「渠道 + 方向 + 金额 + note 前 12 字」为键、10 秒窗口去重；另配 `seen`（同通知不重入）与跨渠道 `isRecentDuplicate`（2 分钟、同额同向不同源）。旧文档把它写成 120 秒/仅渠道+金额+方向，已按源码订正。
8. **「MainActivity 注册 ActivityResult 截图授权」→ 已移除**：MediaProjection 授权改由 `screenshot/ScreenshotRequestActivity` 承担。
9. **关键文件行数**：旧表普遍偏小（如 SettingsScreen 1460→2069、ReportScreen 1017→1179、AddScreen 821→1055、LedgerApp 444→569、LedgerRepository 192→343、LedgerDao 133→278），本文档已按 `wc -l` 实测更新。

## 三条最该先修的问题（截至本轮核对）

1. **`NotificationRules` 仍无单元测试**：规则词表（518 行、20+ 分类关键词组）改动无人拦，建议补 JVM 单测（纯函数、无 Android 依赖，天然可测）。见 `data/NotificationRules.kt`。
2. **`external_id` 唯一索引无兜底**：`CREATE UNIQUE INDEX` 在历史脏单号（重复 `external_id`）上会让首启迁移抛异常；迁移前应先 `DELETE`/`UPDATE` 去重。见 `data/LedgerDatabase.kt` `MIGRATION_1_2`。
3. **AI Key 明文存偏好**：`AppPrefs.ai_key` 存 SharedPreferences 明文（`allowBackup=false` 已缓解备份泄露，但仍建议加密或走 Keystore）。见 `AppPrefs.kt`。

详见 [04 开发与扩展指南 · 已知问题清单](04-开发与扩展指南.md#4-已知问题清单)。
