# 08 · A 方案：全站收敛到 Miuix 单源（+ 图表迁移规划）

> **状态：Miuix 单源（§1）已全部落地（P0 完成）；§2「图表迁移」只是规划，未落地——报表至今仍是自绘 `Canvas`。**
> 文中 §2 及「MPChartCompose」相关表述是**目标**，不是现状；实况见 `ui/screen/ReportScreen.kt`（`LineChart`/`BarChart`/`DonutChart` 全部 `androidx.compose.foundation.Canvas` 手绘，`app/build.gradle.kts` 无任何图表库依赖）。
> 落地施工记录见 [`10-反模式盘点.md`](10-反模式盘点.md)。

---

## 0. 结论（先看这几条）

1. **全站只在 `ui/theme/AppTheme.kt` 创建一次主题**，`LedgerApp` 是唯一调用点。页面里禁止再包 `MiuixTheme`/`MaterialTheme`（门禁 R1）。
2. **色值单源 `LedgerPalette`**（`LedgerColors`），由 `LedgerMiuixColors.kt` 灌进 Miuix 的 53 个 `Colors` 槽；`LedgerTheme` 只是给 `Canvas`/函数默认参数用的**只读转发**，不是第二色源。
3. **字号单源 `LedgerTextStyles`**（14 个 `textStyles` 槽全量覆盖）。
4. **尺寸/圆角单源 `LedgerDimens`**。
5. **门禁 5→实际 7 条 `grep`**（R1–R7，跑在编译前），详见 §1.8。
6. **图表未迁移**：三套自绘 `Canvas` 仍在，切换图表库是未完成项（§2）。

---

## 1. 全站统一按 Miuix 开发

### 1.1 现状（P0 前）：两套色源

P0 之前项目自绘一套 `LedgerTheme` 色，同时 Miuix 自己算一套 `Colors`，同一屏会出现"自绘浅色 + Miuix 深色"半深半浅。P0 后统一为单源。

### 1.2 唯一主题根：`ui/theme/AppTheme.kt`

```kotlin
@Composable fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    LedgerTheme.dark = dark          // 必须在建 controller 之前写：保证同帧两侧一致
    val controller = remember(mode, dark) {
        ThemeController(
            colorSchemeMode = when (mode) { ThemeMode.LIGHT -> Light; ThemeMode.DARK -> Dark; else -> System },
            lightColors = ledgerLightColors(),
            darkColors = ledgerDarkColors(),
            isDark = dark,
        )
    }
    MiuixTheme(controller) { content() }
}
```

- `ThemeMode` 枚举：`SYSTEM="system"` / `LIGHT="light"` / `DARK="dark"`。
- 为什么 `colorSchemeMode` 传 `System` 同时传 `isDark`：让我们算的深色与 Miuix 算的深色**永远一致**，否则系统切深色会出现自绘浅色 + Miuix 深色的半深半浅。

### 1.3 色彩单源：`LedgerColors` → Miuix 53 槽（映射表）

`LedgerMiuixColors.kt` 用 `lightColorScheme(...)` / `darkColorScheme(...)` 的**命名参数**覆盖 41 个槽（其余 12 个 `disabled*` / `slider*` 保留 Miuix 默认，见 `MiuixSlotsIntentionallyDefault`）。原因：`Colors` 的 53 个 `var` setter 都是 `internal`，外部只能走工厂参数。

| Miuix 槽 | ← LedgerColors |
| --- | --- |
| `primary` / `onPrimary` / `primaryVariant` / `onPrimaryVariant` | `accent` / `onAccent` / `accentPressed` / `onAccent` |
| `primaryContainer` / `onPrimaryContainer` | `tint(accent,0.16)` / `onAccent` |
| `secondary*` 六槽（`secondary`…`onSecondaryContainerVariant`） | `surfaceVariant` / `text` / `textSecondary` |
| `tertiaryContainer` / `onTertiaryContainer` / `tertiaryContainerVariant` | `surfaceVariant` / `text` / `surfaceVariant` |
| `background` / `onBackground` / `onBackgroundVariant` | `page` / `text` / `textSecondary` |
| `surface` / `onSurface` / `surfaceVariant` | `surface` / `text` / `surfaceVariant` |
| `onSurfaceSecondary` / `onSurfaceVariantSummary` | `textSecondary` |
| `onSurfaceVariantActions` | `accent` |
| `disabledOnSurface` | `textTertiary` |
| `surfaceContainer` / `onSurfaceContainer` / `onSurfaceContainerVariant` | `surface` / `text` / `textSecondary` |
| `surfaceContainerHigh` / `onSurfaceContainerHigh` | `surfacePressed` / `text` |
| `surfaceContainerHighest` / `onSurfaceContainerHighest` | `surfacePressed` / `text` |
| `outline` / `dividerLine` / `windowDimming` | `border` / `divider` / `scrim` |
| `error` / `onError` / `errorContainer` / `onErrorContainer` | `danger` / `onAccent` / `tint(danger,0.12)` / `danger` |

注意命名：是 `onSurfaceContainerVariant`，没有 `surfaceContainerVariant`（CI 曾在此报 `Unresolved reference`）。

### 1.4 字体：14 个 Miuix 槽 → 一套阶梯

见 [`06` §2.2](06-UI统一改造方案.md)。要点：`headline1` **不覆盖**（Miuix 列表行/偏好标题占用），大金额走独立 `amountDisplayStyle()`（34sp）；数字组 `tnum` 等宽。

### 1.5 组件白名单：Miuix 有什么就用什么

优先使用 Miuix 提供的组件（列表行、开关、卡片、Snackbar、BottomSheet、TabRow 等）；项目自建组件收敛到 `ui/components/`（`AppDialog` / `CategoryChip` / `SwitchRow`）与 `settings/SettingsUi.kt`，且内部只用 token + Miuix 原子组件。

### 1.6 图标：`MiuixIcons` 优先

底部三页签 `MiuixIcons.Home / Add / GridView`；Miuix 缺失的风格化图标做成 `ui/AiScanIcon.kt`（同风格矢量化），门禁 R3 对该文件豁免命名色。

### 1.7 版式：一套数值，写进一处

间距/圆角全部走 `LedgerDimens`（见 [`06` §2.3](06-UI统一改造方案.md)），组件通过 `LocalDimens` 取用。

### 1.8 CI 门禁（实际 7 条，跑在 lint / 编译之前）

`tools/check-ui-consistency.sh`（CI 在 `assembleDebug` 前执行）：

| 规则 | 检查 | 级别 |
| --- | --- | --- |
| R1 | 禁止 `MiuixTheme(` / `MaterialTheme(`（仅豁免 `ui/theme/AppTheme.kt`） | **error** |
| R2 | 禁止 `Color(0x…)` 字面量 | **error** |
| R3 | 禁止 `Color.White/Black/…` 命名色 | **error** |
| R4 | 字号只走 `textStyles`（禁止 `fontSize = N.sp`） | warn |
| R5 | 字重只走 `textStyles` | warn |
| R6 | 圆角只用合法阶梯 4/8/12/16/24/32/50 | warn |
| R7 | 不新增 `Toast.makeText` | warn |

> 原方案写的"5 条"已扩展为 7 条；原设想的"禁止 AndroidView 互操作"因报表仍是 Canvas 自绘（非 AndroidView）而未纳入。

---

## 2. 图表：三套自绘 Canvas 的现状与迁移规划（**未落地**）

### 2.1 现状：全部自绘 Canvas

`ui/screen/ReportScreen.kt`（1179 行）内含：

| 组件 | 行号 | 实现 |
| --- | --- | --- |
| `SummaryCard` / `SummaryCell` | L302 / L337 | 概览卡（本期/上期/支出/收入） |
| `LineChart`（`LineCard` L351） | L369（Canvas L409） | 折线趋势，`drawPath`/`drawLine` 手绘 |
| `BarChart`（`BarCard` L535） | L553（Canvas L583） | 柱状（最近 6 桶） |
| `DonutChart`（`DonutCard` L704） | L766（Canvas L773） | 环形占比 + `CategoryRow` 分类排行 |
| `toReportData` / `categoryEmoji` / `categoryColor` | L956 / L1032 / L1064 | 纯函数算数据与配色 |
| `CategoryDetailDialog` / `DetailEntryRow` | L1082 / L1139 | 分类钻取明细 |

数据来自 [`ReportRepository`](02-架构与数据流.md)，图表配色读 `LedgerTheme.charts`（`LedgerPalette.chart1..6`）——这正是保留 `LedgerTheme` 转发层的原因（`Canvas` 内拿不到 `CompositionLocal`）。

### 2.2 迁移规划（若将来要做）

- 候选：MPChartCompose（Compose 版）或 Vico。**当前均未引入依赖**（`app/build.gradle.kts` 只有一行遗留注释提到"纯 Compose 图表库"）。
- 注意：`SettingsScreen` 的「关于」页许可文案写的是 `Miuix · Compose · Room · Vico`，但**实际没有 Vico**——属待修正的文案（见 [`10`](10-反模式盘点.md) 待办）。
- 迁移前置：先评估体积与 Compose 互操作，再决定是否用 `AndroidView` 包裹（若用则需新增门禁规则）。

---

## 3. 落地顺序与状态

- P0（已完成）：`LedgerPalette` / `LedgerDimens` / `LedgerTextStyles` / `AppTheme` 唯一根 / `LedgerMiuixColors` 53 槽 / 门禁 R1–R7。
- P1/P2（持续）：逐页把残余自写 `fontSize`/`fontWeight`/非阶梯圆角/Composable Toast 收敛到 token 与 Snackbar（当前 warn 计数见 [`10`](10-反模式盘点.md) §2）。
- 图表迁移：**未排期**。
