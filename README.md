# 自动记账（AutoLedger）

> 📘 工程文档：[docs/README.md](docs/README.md)（总览 / 架构与数据流 / 功能模块详解 / 开发与扩展指南）

一个用 Kotlin + Jetpack Compose（Miuix 设计体系）写的 Android 记账 App：通知监听自动采集账单、短信/通知/AI 识图自动入账、收支报表可视化，支持 Room + Flow 的响应式数据层。

> 个人自用项目，界面风格参考小米 Miuix，数据全部落在本机数据库，不上传服务器（AI 识图除外，需自行配置模型接口）。

## 功能

**记账**
- 支出 / 收入 / 其他 三段式记账页，自绘日历+时间选择、自定义金额键盘、22 个分类网格、拍照/相册 AI 识图
- 分类管理（增删改，持久化到 SharedPreferences），与记账页共用同一份分类定义
- 预算管理（金额、周期、剩余比例、首页预算卡片）

**自动记账**
- 通知监听：`NotificationListenerService` 解析支付宝/微信/银行/短信应用/外卖出行通知，包名白名单 + 噪声过滤 + 收支判定 + 内容指纹去重，入库弹「自动记账」通知可撤销
- 截屏记账：无障碍服务 `AccessibilityService.takeScreenshot()` 截图 → 多模态模型识别 → 通知栏一键确认入账（不需要 Root，也不需要录屏授权）
- 短信记账：`SMS_RECEIVED` 广播 + 银行/服务号白名单 + 短信专用噪声表（不拦「可用额度」）→ 长短信合并 → 与通知监听共用规则内核与通知出口，入库可撤销
- AI 识别图片：与截屏记账共用「识别 → 通知确认 → 写库」流程

**报表**
- 周报 / 月报 / 年报，周期左右切换，支出 / 收入切换
- 概览卡（本期支出、日均、环比、结余）
- 折线趋势（点按或左右拖动时为气泡跟手，带竖向引导线）
- 柱状对比（选中柱高亮 + 气泡）
- 环形分类构成（马卡龙配色、细白间隙、折线引线、标签自动避让、环心合计金额）
- 分类排行（笔数、占比、金额、进度条）

**通知与桌面**
- 日报 / 周报 / 月报定时汇总（AlarmManager，真实注册）
- 每日记账提醒（时间可选，支持超级岛 / 日历 / 小爱 / 静默）
- 单笔入账通知（超级岛 / 常驻 / 静默）
- 系统权限体检：设置 → 系统权限与保活，集中查看/一键跳转通知使用权、短信权限、无障碍、电池白名单、自启动、精确闹钟、通知渠道，并显示两条采集通道的最近入账时间
- 自愈（可选）：`adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS` 授予一次后，设置页「一键修复」与 15 分钟一次的 `HealthJobService` 心跳会把被 ROM 关掉的「通知使用权 / 无障碍」自动写回
- 桌面小部件：2×2「今日收支」/ 4×2「本月概览」，拖宽自动切版式，点按打开 App

**数据**
- Room（`Bill` 表，`timestamp` 与 `external_id` 唯一索引），DAO 全 Flow
- CSV / XLSX 导入：微信账单（含 Excel 日期序列）、支付宝、通用模板，按单号或「时间+金额+类型」指纹去重
- CSV / XLSX 导出（时间范围可选，默认本月；选格式后走系统分享）
- 游标分页（20 条/页，`(timestamp, id)` keyset），滚到底自动续页，不再 `LIMIT pageSize*pages` 全量重查
- 搜索筛选：关键字（备注/分类/来源）+ 金额区间 + 时间段 + 类型/分类多选，全部落 SQL（`LIKE` + `BETWEEN`），底部筛选面板实时显示「应用（N 条）」
- WebDAV 云备份：坚果云/群晖等，立即备份、一键恢复、定时（≥20h 一次，保留最近 7 份日备）
- 深色模式：跟随系统 / 浅色 / 深色，设置 → 外观；全应用共用一套语义色板（含图表系列色），主色块上的文字自动切 `onAccent`

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Kotlin | 2.4.20 |
| AGP | 9.2.0（要求 Gradle 9.4.1+） |
| compileSdk | 37.2（minSdk 27 / targetSdk 37） |
| UI | Miuix 0.9.5-beta01，Jetpack Compose |
| 数据 | Room + KSP，DataStore 之外的偏好走 SharedPreferences |
| 加固/扩展 | LSPosed 模块入口 `xposed/LedgerModule`（scope=system，当前用于系统级辅助） |

工程结构：

```
app/src/main/java/com/eta/autoledger
├── data/          Room 实体/DAO/仓库、CSV/XLSX 导入、分类与偏好定义
├── ui/            Compose 页面（首页/记账/报表）、主题与通用组件
├── viewmodel/     首页分页与汇总状态
├── service/       通知监听、短信广播、自动记账通知出口
├── reminder/      汇总与提醒的 Alarm 调度
├── screenshot/    无障碍截图 + 前台服务 + 识别 + 通知确认
├── settings/      设置页及二级页面
├── widget/        桌面小部件
├── tile/          快捷设置磁贴
└── xposed/        LSPosed 模块入口
```

## 构建

需要 JDK 17+、Android SDK（platform 37.2、build-tools 37.0.0）与 Gradle 9.4.1。

```bash
# 常规 x86_64 开发机
gradle :app:assembleDebug
```

在 **arm64 / aarch64** 环境下（例如 Termux 里的 chroot），AAPT2 需要换成原生二进制，否则会报语法错误：

```bash
gradle -Pandroid.aapt2FromMavenOverride=$ANDROID_SDK/build-tools/37.0.0/aapt2 :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

`local.properties` 里的 `sdk.dir` 属于本地配置，不要提交。

## 说明

- 通知使用权需要在系统「通知使用权」里手动授予；截屏记账需要开启一次无障碍服务。
- AI 识图/识别需要自己填 Base URL、API Key 和模型名（兼容 OpenAI 的 `/v1/chat/completions` 与 `/v1/models`）。
- 全部账单数据保存在本机 `auto_ledger.db`，卸载即清空，建议定期导出 CSV。