# A 方案：全站收敛到 Miuix 单源 + 图表换 MPChartCompose

> 版本：2026-11-20 · 适用代码基线 `v0.1.6`（47 个 `.kt` / 10,679 行 / CI 44681166907）
> 本文档所有 API 结论均来自源码与仓库元数据实测，不是记忆：

| 事实 | 实测来源 |
| --- | --- |
| `top.yukonga.miuix.kmp:miuix-ui` latest = **0.9.5-beta01**（lastUpdated 2026-11-17），项目已在用 | `repo1.maven.org/.../miuix-ui/maven-metadata.xml` |
| `MiuixTheme(controller, textStyles, content)`；`ThemeController(colorSchemeMode, lightColors, darkColors, …)` | `miuix-ui/src/commonMain/.../theme/MiuixTheme.kt:23-34`、`ThemeController.kt:145-150` |
| `lightColorScheme(...)` / `darkColorScheme(...)` **全部参数带默认值**，可直接覆盖单个槽 | `theme/Colors.kt:341`、`:451` |
| `Colors` 共 **53 个槽**（`outline`、`dividerLine`、`surfaceContainer/High/Highest`、`windowDimming` …） | `theme/Colors.kt` 字段实测 |
| `ColorSchemeMode { System, Light, Dark, MonetSystem, MonetLight, MonetDark }` — **跟随系统是原生能力** | `theme/ThemeController.kt:51-58` |
| `TextStyles` 14 槽（`main/paragraph/body1/body2/button/footnote1/footnote2/headline1/headline2/subtitle/title1..4`） | `theme/TextStyles.kt` |
| `miuix-ui` 基础组件 41 个（含 `SearchBar`/`TopAppBar`/`TabRow`/`Snackbar`+`SnackbarHost`+`SnackbarHostState`/`PullToRefresh`/`NumberPicker`） | `basic/` 文件清单 |
| `miuix-preference` 19 个（`ArrowPreference`/`SwitchPreference`/`CheckboxPreference`/`RadioButtonPreference`/`SliderPreference`/`*DropdownPreference`/`*SpinnerPreference`） | `miuix-preference/src/.../` 清单 |
| `miuix-icons` 157 个图标，单 set `MiuixIcons` | 文件计数 |
| `miuix-glass` 43 个文件，基于 RuntimeShader/RenderEffect（OS4 玻璃），**独立模块，不引即不耗** | 0.9.5-beta01 release notes + 文件计数 |
| `MPChartCompose` v4.0.1 提供 `LineChart/BarChart/HorizontalBarChart/PieChart/RadarChart/ScatterChart/CandleStickChart/BubbleChart/CombinedChart`；`state: ChartState = rememberChartState()`、`marker: (@Composable (Entry<*>, Highlight) -> Unit)?`、`animateChanges`、`setup`（只跑一次）、`update`（每次重绘前） | `MPChartCompose/.../Charts.kt:41-266`、`ChartState.kt:346` |
| 库自带 `chartLazyList` 的 androidTest（`ChartLazyListTest.kt`）→ 官方支持放列表里 | `MPChartCompose/src/androidTest/.../ChartLazyListTest.kt` |

---

## 0. 结论（先看这 6 条）

1. **Miuix 不需要"升级"，需要"收编"。** 项目已经在 0.9.5-beta01（当前最新），但 47 个 Kotlin 文件里只有 **10 个** import 了 miuix —— 其余页面是纯自绘。风格不一致的机械原因不是版本，是**两套色源并存**：`LedgerTheme`（我们自己的 15 个语义色）管自绘控件，`MiuixTheme` 的内建 `Colors` 管 Miuix 控件，同屏必出两种底色/两种灰。
2. **解法是官方支持的 API，不是 hack**：`MiuixTheme(controller = ThemeController(lightColors = lightColorScheme(…), darkColors = darkColorScheme(…)))` —— 把 `LedgerTheme` 的 15 个值灌进 Miuix 的 53 个槽，此后**全站只有一个色源**，`LedgerTheme` 退化为图表/Canvas 用的调色板常量。
3. **「跟随系统」不用自己写**：`ColorSchemeMode.System` 原生存在，设置里的三态直接映射 `System/Light/Dark`。当前代码里 `isSystemInDarkTheme()` + 手动 `LedgerTheme.dark` 的双轨判断可以删掉。
4. **三套自绘图表 → 库替换 2 套、半替换 1 套**：折线（`ReportScreen.kt:321`）、柱状（`:505`）整体换库；环形（`:674`）换"环体"，环心合计与分类排行保留自绘——库不擅长精确环心排版。
5. **`marker` 是 Compose lambda**，拖动气泡可以直接用 `LedgerTheme` 色渲染，不存在"库画的气泡配色跟主题不一致"的问题。
6. **最大的功耗陷阱是 `miuix-glass`**：OS4 玻璃是 RuntimeShader 每帧上 GPU。本方案**明确不引** `miuix-glass`/`miuix-material`，玻璃感用"纯色 + 1dp 描边 + 22dp 圆角"替代。

---

## 1. 全站统一按 Miuix 开发（第 1 点）

### 1.1 现状：两套色源，10/47 个文件用 Miuix

| 现状项 | 实测 |
| --- | --- |
| Kotlin 文件总数 | 47 个 / 10,679 行 |
| import miuix 的文件 | **10 个** |
| 自绘图表 | 3 个（`ReportScreen.kt:321 LineCard`、`:505 BarCard`、`:674 DonutCard`） |
| 自绘卡片 | 3 个（`HomeScreen.kt:300 BudgetCard`、`:359 OverviewCard`、`settings/SettingsUi.kt:119 SettingsCard`） |
| `Canvas`/`drawLine`/`drawCircle` 调用 | 15 处 |
| `Toast.makeText` | **41 处**（系统 Toast 无法跟主题，是最刺眼的"不统一"） |
| 色源 | `ui/LedgerTheme.kt`（15 色）与 Miuix 内建 `Colors`（53 槽）两套，互不感知 |

### 1.2 唯一主题根：`ui/theme/AppTheme.kt`

```kotlin
// 新增：app/src/main/java/com/eta/autoledger/ui/theme/AppTheme.kt
@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val schemeMode = when (mode) {
        "light" -> ColorSchemeMode.Light
        "dark" -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System          // 跟随系统：Miuix 原生
    }
    val controller = remember(schemeMode) {
        ThemeController(
            colorSchemeMode = schemeMode,
            lightColors = ledgerLightColors(),
            darkColors = ledgerDarkColors(),
        )
    }
    MiuixTheme(
        controller = controller,
        textStyles = ledgerTextStyles(),         // 见 1.4
        content = content,
    )
}
```

调用点只有一处：`LedgerApp.kt` 里把 `MiuixTheme(controller = controller) { … }` 换成 `AppTheme(mode = prefs.themeMode) { … }`。**此后不允许任何页面自己再包一层 `MiuixTheme`/`MaterialTheme`**（CI 门禁查，见 1.8）。

### 1.3 色彩单源：15 个 token → Miuix 53 槽（映射表）

`LedgerTheme` 的浅/深两套值一个都不改，只是改由 Miuix 承载：

```kotlin
// 新增：ui/theme/LedgerColors.kt
private fun ledgerLightColors() = lightColorScheme(
    // 主色
    primary = LedgerPalette.lAccent,            // #2563EB
    onPrimary = LedgerPalette.lOnAccent,        // #FFFFFF
    primaryVariant = LedgerPalette.lAccentPressed,   // #1D4FD7
    onPrimaryVariant = LedgerPalette.lOnAccent,
    primaryContainer = LedgerPalette.lAccentTint,    // accent 12% 叠 surface
    onPrimaryContainer = LedgerPalette.lAccent,
    // 表面与背景
    background = LedgerPalette.lPage,           // #F5F7FB
    onBackground = LedgerPalette.lText,         // #14181F
    onBackgroundVariant = LedgerPalette.lTextSecondary,
    surface = LedgerPalette.lSurface,           // #FFFFFF
    onSurface = LedgerPalette.lText,
    surfaceVariant = LedgerPalette.lSurfaceVariant,  // #EEF2F9
    onSurfaceSecondary = LedgerPalette.lTextSecondary,
    onSurfaceVariantSummary = LedgerPalette.lTextSecondary,
    onSurfaceVariantActions = LedgerPalette.lTextTertiary,
    surfaceContainer = LedgerPalette.lSurface,
    onSurfaceContainer = LedgerPalette.lText,
    surfaceContainerVariant = LedgerPalette.lInset,  // #F0F4FA
    onSurfaceContainerVariant = LedgerPalette.lTextSecondary,
    surfaceContainerHigh = LedgerPalette.lSurfaceVariant,
    onSurfaceContainerHigh = LedgerPalette.lText,
    surfaceContainerHighest = LedgerPalette.lSurfaceVariant,
    onSurfaceContainerHighest = LedgerPalette.lTextSecondary,
    // 线
    outline = LedgerPalette.lBorder,            // #E4EAF3
    dividerLine = LedgerPalette.lDivider,       // #EDF1F7
    windowDimming = LedgerPalette.lScrim,       // #0B1220 @ 60%
    // 语义
    tertiaryContainer = LedgerPalette.lIncomeTint,
    onTertiaryContainer = LedgerPalette.lIncome,     // #0B8A5F
    error = LedgerPalette.lDanger,              // #DC2626
    onError = Color.White,
    errorContainer = LedgerPalette.lDangerTint,
    onErrorContainer = LedgerPalette.lDanger,
    secondary = LedgerPalette.lSurfaceVariant,
    onSecondary = LedgerPalette.lText,
)
// ledgerDarkColors() 同构，槽位一一对应，值取深色表
```

**为什么能保证"页面之间不混用"**：

- 页面里**禁止**出现任何 `Color(0x…)`、`Color.White`、`Color.Black`；要么用 `MiuixTheme.colorScheme.<槽>`（Miuix 控件与自绘控件通用），要么用 `LedgerTheme.<语义名>`（只允许出现在 `drawWithCache`/`Canvas`/图表 `Colors`/`RemoteViews` 这四类拿不到 `CompositionLocal` 的地方）。
- 两套名字指向**同一组色值**（`LedgerTheme.text ≡ colorScheme.onBackground`），所以即使混写也不会出现色差，只会出现"用了别的语义"的 review 问题。
- `LedgerTheme.kt` 保留但**降级**为 `LedgerPalette`（纯常量表，无 `dark` 可变状态），删掉 `LedgerTheme.dark` / `isDark()` 这套运行时切换 —— 深色判定交给 `ThemeController`。

### 1.4 字体：14 个 Miuix 槽 → 8 档编号体系

| Miuix 槽 | 字号/字重 | 用途 | 字体 |
| --- | --- | --- | --- |
| `headline1` | 28sp Bold | 报表大数字 | 系统 + `tnum` |
| `headline2` | 24sp Bold | 二级大数字 | 系统 + `tnum` |
| `headline1` | **34sp Bold** | 记一笔金额 | 系统 + `tnum` |
| `headline2` | **28sp Bold** | 首页今日支出 | 系统 + `tnum` |
| `title2` | **20sp SemiBold** | 列表行金额、本月收支 | 系统 + `tnum` |
| `main` | **17sp Normal** | Miuix 组件默认正文（偏好项标题、列表分类） | 系统 |
| `title1` | 22sp Bold | 页面标题 | LXGW |
| `title2` | 20sp SemiBold | 区块标题 | LXGW |
| `title3` | 17sp SemiBold | 卡片标题 | LXGW |
| `title4` | 15sp Medium | 列表主文字 | LXGW |
| `subtitle` | 14sp Medium | 行副标题 | LXGW |
| `body1` | 15sp Normal | 正文 | LXGW |
| `body2` | 13sp Normal | 说明 | LXGW |
| `paragraph` | 15sp / lineHeight 1.45 | 长备注 | LXGW |
| `button` | 15sp Medium | 按钮 | LXGW |
| `footnote1` | 12sp Normal | 辅助 | LXGW |
| `footnote2` | 11sp Normal | 最小（日期、tag） | 系统 |

字族：`val AppFont = FontFamily(LxgwWenKaiLite, FontFamily.Default)`——**兜底 `Default` 必须有**，因为子集化后生僻字会缺字。

**为什么数字不用楷体**：`0/6/8` 在楷体里形态相近，34sp 上逐笔看会晃眼；且金额必须等宽对齐（`fontFeatureSettings = "tnum"`），楷体没有 tabular 变体。

### 1.5 组件白名单：Miuix 有什么就必须用什么

**必须用 Miuix 的（替代现有自绘）**

| 现有自绘 | 换成 | 位置 |
| --- | --- | --- |
| `TopAppBar` 三种实现（首页自绘行、`ReportTopBar`、`SettingsTopBar`） | `MiuixTopAppBar` + `SmallTitle` | 全部页面 |
| `SettingsCard`（`SettingsUi.kt:119`，自绘圆角+描边） | `Miuix Card` / `Surface` | 设置 15 页 |
| `OverviewCard`（`HomeScreen.kt:359`，蓝色渐变 + 手写 eye 图标） | `Miuix Card` + `colorScheme.primaryContainer` 底 + `MiuixIcons` 的显示/隐藏图标 | 首页 |
| `BudgetCard`（`HomeScreen.kt:300`） | `Miuix Card` + `ProgressIndicator`（进度条保留自绘细调） | 首页 |
| 搜索页自绘搜索框（44dp `BasicTextField` + 手写 🔍） | `Miuix SearchBar` | 搜索页 |
| 报表 tab / 记一笔 tab（自绘下划线） | `Miuix TabRow` | 报表、记一笔 |
| 列表 `ScrollBar` 无 | `Miuix ScrollBar`（`LazyColumn` 配 `ScrollBarAdapter`） | 首页/搜索/报表 |
| **41 处 `Toast.makeText`** | UI 层 → `SnackbarHostState.showSnackbar`；`Receiver`/`Service`（无 Activity 上下文）→ 保留通知或 Toast | 全站 |
| 下拉选择（报表"支出/收入"、设置里的枚举） | `WindowDropdownPreference` / `OverlayDropdownPreference` | 设置、报表 |
| 数字选择（时间 `TimePickerDialog`） | `NumberPicker`（或保留系统 `TimePickerDialog`，见取舍表） | 设置 → 提醒/备份时刻 |

**允许保留自绘的（白名单，仅 6 类）**

1. 图表容器外壳（`Surface` 包一张图表）；
2. 环心合计 + 分类排行小横条；
3. 预算进度条（`ProgressIndicator` 不够细时）；
4. `Canvas` 引导线这类图表装饰；
5. 小部件（`RemoteViews` 拿不到任何 Compose）；
6. 分类九宫格图标圆底（尺寸/描边要求特殊）。

### 1.6 图标：`MiuixIcons` 优先，缺失的补 `AppIcons`

- `MiuixIcons` 157 个，覆盖设置/箭头/开关/搜索/返回/相机/相册等常用项。
- 业务缺口（报表、预算、云备份、分类图标）放 `ui/icons/AppIcons.kt`：24×24 viewport、2dp 描边、单色 `fill = CurrentColor`（由 `Icon(tint = …)` 着色）。
- **禁用**：`material-icons-extended`（已停更、体积大）、`MiuixIcons` 与 `AppIcons` 之外第三套图标、emoji 当图标（现 `CategoryStore` 用 emoji，保留——它是数据不是 UI 图标）。

### 1.7 版式：一套数值，写进一处

| 维度 | 值 | 用途 |
| --- | --- | --- |
| 间距 | 4 / 8 / 12 / 16 / 20 / 24 / 32 dp | 严禁 7/13/18/11 这类"看着差不多"的值 |
| 圆角 | 8（chip）/ 12（按钮、输入）/ 16（卡片）/ 24（大卡、sheet） | 4 档 |
| 触控 | ≥ 48dp（chip 高度 32dp 时**外层**加透明点击区补足） | 无障碍 |
| 描边 | 1dp `colorScheme.outline` | 深色不用阴影（阴影在深色几乎不可见，还多一层 GPU 合成） |
| 卡片内边距 | 16dp；卡片间距 12dp | |
| 页边距 | 16dp | |

### 1.8 CI 门禁（5 条，都是 `grep`，跑在 lint 之前）

```bash
set -e
SRC=app/src/main/java/com/eta/autoledger
# 1) 颜色只能出现在调色板/图标/主题三处
bad=$(grep -rn "Color(0x" $SRC --include=*.kt | grep -v "ui/theme/\|ui/icons/\|data/CategoryStore.kt" || true)
[ -z "$bad" ] || { echo "❌ 硬编码颜色: $bad"; exit 1; }
# 2) 不允许出现第二主题根
grep -rn "MaterialTheme\|MiuixTheme(" $SRC --include=*.kt | grep -v "ui/theme/AppTheme.kt" && exit 1
# 3) UI 层禁止 Toast（Receiver/Service 例外）
grep -rn "Toast.makeText" $SRC --include=*.kt | grep -v "service/\|receiver/\|screenshot/ConfirmEntryReceiver.kt" && exit 1
# 4) 不允许 AndroidView 互操作（图表库要用 Compose 版）
grep -rn "AndroidView(" $SRC --include=*.kt && exit 1
# 5) 间距/圆角只允许白名单值
grep -rnoE "\.dp" $SRC --include=*.kt | grep -vE "\.(4|8|12|16|20|24|32|48|1)\.dp|dimension|spacing|size" | head
```

---

## 2. 图表：三套自绘 → MPChartCompose（第 2 点）

### 2.1 映射表

| # | 现有图表 | 位置 | 换成的类型 | 库提供的 |
| --- | --- | --- | --- | --- |
| 1 | **本月趋势**：折线 + 面积渐变填充 + 虚线网格 + 拖动气泡（跟手）+ 竖向引导线 + 端点圆 | `ReportScreen.kt:321 LineCard` | `LineChart(data: LineData, state, marker = { entry, highlight -> … }, animateChanges = true, setup/update)` | 面积填充、虚线网格、highlight 十字线、`marker` 由 Compose 绘制 |
| 2 | **支出趋势**：本期/上期双序列柱状 + 圆角柱 + 末柱高亮 + y 轴千分位 | `ReportScreen.kt:505 BarCard` | `BarChart(data: BarData)` + `isRoundedBars = true`（`update` 里设） | 分组柱、圆角、轴标签格式化 |
| 3 | **分类构成**：环形 + 环心合计 + 白色细缝 + 引线 + 标签避让 | `ReportScreen.kt:674 DonutCard` | `PieChart(data: PieData, setup = { isDrawHoleEnabled = true; holeRadius = 62f; isTransparentCircleClippedToSlices = true })` | **环体**（含白缝、动画）；环心文字与标签用 Compose 覆盖层 |
| — | 预算进度条 | `HomeScreen.kt:300 BudgetCard` | **不换**（不是数据可视化，是状态条） | — |
| — | 分类排行小横条 | 报表页分类列表 | **不换**（`drawWithCache` 单色条，比引库更省） | — |
| — | 小部件 | `res/layout/widget_*.xml` | **不换**（`RemoteViews` 不支持任何 View） | — |

### 2.2 关键 API 与三个坑

```kotlin
@Composable
fun LineCard(data: ReportData, income: Boolean) {
    val theme = MiuixTheme.colorScheme
    val state = rememberChartState()
    val lineData = remember(data, income) { data.toLineData(theme) }   // 只在数据变了才重建
    MiuixCard {
        LineChart(
            data = lineData,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            state = state,
            marker = { entry, _ -> SelectionBubble(entry) },           // Compose 气泡，直接用主题色
            animateChanges = false,                                     // 首帧不动画，避免打开页面就动画
            setup = {
                description.isEnabled = false
                legend.isEnabled = false
                axisRight.isEnabled = false
                xAxis.setDrawGridLines(false)
            },
            update = {                                                  // 主题色必须放这里
                xAxis.textColor = theme.onBackgroundVariant.toArgb()
                axisLeft.textColor = theme.onBackgroundVariant.toArgb()
                gridLineColor  = theme.dividerLine.toArgb()
            },
        )
    }
}
```

1. **坑 1：`setup` 只跑一次，`update` 每次重绘前跑。** 源码 Javadoc 明写 "runs once after the view is created … so values it reads are frozen"。所以**颜色/主题相关的一切必须写在 `update` 里**，写进 `setup` 会导致切主题后图表还是旧色——这是最容易踩的一条。
2. **坑 2：`state` 一个图一个。** 复用同一 `ChartState` 给两个图，图会"搬走"（同一个 view 被移动）。列表项里必须 `remember(itemId)`。
3. **坑 3：`animateChanges = true` 是 300ms 内置动画。** 首帧设为 `false`，用户交互（切换周/月）才设 `true`，否则每次进报表页都白跑一次动画。

### 2.3 依赖与体积

```kotlin
// app/build.gradle.kts
implementation("com.github.PhilJay.MPAndroidChart:MPChartLib:v4.0.1")
implementation("com.github.PhilJay.MPAndroidChart:MPChartCompose:v4.0.1")
// settings.gradle.kts（CI 也走官方源，见 §5 风险 3）
maven("https://jitpack.io")
```

预计 `release` APK 8.89 MB → **9.6–9.9 MB**（ChartLib ~0.6 MB + Compose 模块 ~0.2 MB，未含 R8 收缩）。同时删掉 `ReportScreen.kt` 里约 350 行自绘代码。

---

## 3. 分阶段迁移（第 3 点）

顺序原则：**先铺地基（不动外观）→ 再拿最独立的页面验证换库 → 再改高频页 → 最后碰最大耦合面 → 收尾删旧轨道**。

| 阶段 | 改什么 | 为什么这个顺序 | 验证方式 |
| --- | --- | --- | --- |
| **P0 地基**<br>4–5 天 | `ui/theme/AppTheme.kt` + `LedgerColors.kt`（53 槽映射）+ `LedgerPalette`（常量表）+ `ledgerTextStyles()`（含 LXGW 子集）+ `ui/icons/AppIcons.kt` + `AppScaffold`（含 `SnackbarHost`）+ 5 条 CI 门禁 + 两条新依赖（先不引用） | 不改任何页面外观，却把"两套色源"这个根因拔掉；门禁一旦生效，后面所有改动都不能回退 | ① `./gradlew :app:assembleDebug` 通过；② 门禁脚本 5 条全绿；③ 深浅两套截图与 `v0.1.6` 逐页比对（应**只有** Miuix 控件底色变化，无布局位移）；④ `adb shell dumpsys gfxinfo com.eta.autoledger` 首屏 p95 ≤ 16ms |
| **P1 报表试点**<br>3–4 天 | `ReportScreen.kt`：`LineCard`/`BarCard` → 库；`DonutCard` 环体 → `PieChart`，环心与排行保留 | 报表 1,017 行、只依赖 `ReportRepository`、与其他页零耦合，是"换库"的最小验证面；一旦库不满足（气泡/环心），此刻回头成本最低 | ① 与旧实现逐值对比：同一 `ReportData` 下 x/y 数组、最大值、总额一致（写个 debug 打印对比）；② 拖动气泡跟手，`adb shell dumpsys gfxinfo` 拖动中 p95 ≤ 16ms；③ 切深浅主题后图表颜色即时变化（验证坑 1）；④ 展开/折叠 tab 无闪白 |
| **P2 首页 + 记一笔 + 搜索**<br>5–6 天 | `OverviewCard`/`BudgetCard` → Miuix `Card`；首页/搜索列表行统一 `Surface` + `Divider`；搜索框 → `Miuix SearchBar`；`FilterSheet` → 统一样式 `Dialog`（Miuix 无 bottom sheet 组件，见取舍）；tab → `TabRow`; UI 层 Toast → `Snackbar` | 高频页先拿到"手感统一"的真实反馈；此时 P0 的 `AppScaffold`+`SnackbarHost` 正好承接 | ① 三页深浅各截 1 张，与 P0 基线比对；② `grep -c "Toast.makeText"` 在 `ui/` 下为 0；③ 首页滚动 1,688 条 `dumpsys gfxinfo` p95 ≤ 16ms、掉帧率 < 1%；④ 记录/搜索/删除功能回归（真机） |
| **P3 设置 15 页**<br>6–8 天 | `SettingsUi` 5 个组件 → Miuix `Card`/`ArrowPreference`/`SwitchPreference`/`CheckboxPreference`/`RadioButtonPreference`；`TimePickerDialog` → `NumberPicker` 或保留（见取舍）；剩余 Toast → `Snackbar` | 最大耦合面（1,460 行 + 3 个子页文件）放最后：前面的沉淀正好一次用上，避免"边改边变规范" | ① 15 个页面逐页深浅截图；② `grep -rn "SettingsCard("` 只剩 Miuix 包装；③ 每个开关的读写仍落在 `AppPrefs`（truth 不变）；④ 无障碍扫一遍（TalkBack 能读出 title/summary） |
| **P4 收尾**<br>3–4 天 | 删 `LedgerTheme` 运行时双轨（保留 `LedgerPalette`）；Lottie 白名单 4 个动效；空态/骨架屏；`grep -miuix` 覆盖率复查；文档同步 | 收尾必须有独立的"删旧"阶段，否则旧 API 会长期寄生 | ① `grep -rn "LedgerTheme\.\(dark\|isDark\)"` = 0；② `./gradlew :app:assembleRelease` + APK ≤ 10.5 MB；③ 20 条手工用例（记账/搜索/导入导出/备份/截屏/通知/短信/小部件×2）全过；④ 90 秒冷启动 → 首页可交互 ≤ 900ms |

---

### 3.1 P0 落地记录（2026-11-20）

| 计划项 | 结果 | 落点 |
| --- | --- | --- |
| `LedgerPalette`（常量表） | ✅ | `ui/theme/LedgerPalette.kt` 137 行：`LedgerColors` 23 个语义色 + `chart1..6`，`LedgerPalette.of(isDark)` / `tint(base, alpha, isDark)` |
| 53 槽映射 | ✅ 34 槽 | `ui/theme/LedgerMiuixColors.kt` 126 行：以 `lightColorScheme()`/`darkColorScheme()` 为底 `apply{}` 逐槽覆盖（不用 53 参数构造，规避默认值漂移）；剩余 19 槽（`disabled*`/`slider*`/`secondaryContainer*`）保留官方推导 |
| `ledgerTextStyles()` | ✅ 未含字体子集 | `ui/theme/LedgerTextStyles.kt` 69 行：14 槽 → 8 档（金额组 headline1/2+title2 = 34/28/20sp `Bold+SemiBold + tnum`；标题组 22/20/17/15/14sp；`main` 还原 17sp 正文，供 Miuix 组件默认使用）。LXGW 子集化与 `fontFamily` 注入留到 P1 |
| `AppTheme` 唯一主题根 | ✅ | `ui/theme/AppTheme.kt` 65 行：`ColorSchemeMode.System/Light/Dark` + `isDark` 强制一致 + 灌两套 `Colors` 与 `textStyles`；`LedgerApp.kt:88` 改为 `AppTheme(mode = prefs.themeMode)` |
| 5 条 CI 门禁 | ✅ 7 条 | `tools/check-ui-consistency.sh`：R1 主题单源 / R2 无 `Color(0x…)` / R3 无命名色 → **error**；R4 字号 / R5 字重 / R6 圆角 / R7 Toast → **warn**（分阶段升 error） |
| `AppScaffold` + `SnackbarHost` | ⏭ 推 P2 | `Toast.makeText` 现有 41 处，P0 建无消费方的 host 只多一层包裹；P2 一次性迁移时一起做 |
| `ui/icons/AppIcons.kt` | ⏭ 推 P2 | 现在 12 处 `Icon(imageVector = MiuixIcons.xxx)` 都带 `tint`，P0 不动；等替换图标集时一次性收口 |
| 两条图表依赖 | ⏭ 推 P1 | P0 不引用任何图表 API，提前加 `JitPack` 只会让 CI 多一个失败点 |

**验证**：`tools/check-ui-consistency.sh` R1–R3 全绿（warn 计数：R4 135 / R5 20 / R6 61 / R7 41）；`.tmp_syntax.py` 模板感知扫描 51 个 `.kt` + 16 个 `.xml` 0 问题；`git diff` 未触及任何布局参数。

**对外行为变化**：无。原 `ThemeController(if (dark) Dark else Light)` 换成 `AppTheme`，`textStyles` 首次由 Miuix 默认值换成项目 8 档（数值接近，观感差异在标题与金额字号）。

---

## 4. 约束与取舍（第 4 点）

### 4.1 流畅（不能掉帧）

| 手段 | 具体 |
| --- | --- |
| 图表不进复用热路径 | 报表页用 `Column(verticalScroll)`，**不放 `LazyColumn`**；若将来放列表，用库自带 `chartLazyList` 支持 + `key = itemId`，且 `state = rememberChartState()` 在 item 作用域内 |
| 减少重组 | `remember(data, income, dark) { data.toLineData(...) }`；`derivedStateOf { … }` 包住筛选结果统计；`update` lambda 里只读 `CompositionLocal`，不读会频繁变的 `state` |
| 减少过度绘制 | 卡片不再叠渐变+阴影+描边三层；深色用描边不用阴影；`Modifier.drawWithCache` 画分类排行条 |
| 动画克制 | 单次 ≤ 300ms；列表项 `animateItem` 只在新增/删除时；首帧图表 `animateChanges = false` |
| 避免 blur | 不引 `miuix-glass`（`RuntimeShader` 每帧重算） |

**验收**：`adb shell dumpsys gfxinfo com.eta.autoledger framestats` 取 p95 ≤ 16ms、p99 ≤ 33ms；`adb shell dumpsys SurfaceFlinger --latency` 掉帧 < 1%。

### 4.2 功耗

| 手段 | 具体 |
| --- | --- |
| 深色近纯黑 | `page #0B0F14`（OLED 上像素近关闭）。**不用 `#000000`**——纯黑会与卡片 `#141A21` 形成"数字抖动"，滚动时 OLED 亮度切换更费眼 |
| 不做常驻动画 | 无循环 Lottie、无无限 shimmer；Lottie 仅 4 个（保存成功 600ms、AI 扫描仅识别期间、空态 400ms、sheet 250ms） |
| 图表不轮询 | 数据只来自 Room `Flow`，无定时刷新；`animateChanges` 只在用户切周期时开 |
| 字体子集 | LXGW WenKai Lite 子集化至 ~2 MB（全量 13.9 MB）；避免为楷体额外驻留大字体缓存 |
| 后台与 UI 解耦 | 备份/自愈走 `JobScheduler`（已有 `BackupJobService`/`HealthJobService`），**不唤醒 UI**；`Snackbar` 不做长驻 |

### 4.3 美观统一 & 一目了然

- **单一色源 + 单一组件源 + 单一字体源**：三处收编后，"不统一"从根上消失，而不是逐页调。
- **数字优先**：每张卡片最多 4 个数字；金额 34sp/26sp，其余 ≤ 17sp；语义色只有三种（收入绿 `income`、危险红 `danger`、对比红 `danger`）。
- **分层靠底色和线，不靠阴影**：`page` → `surface` → `surfaceVariant` 三级 + 1dp `outline`。
- **一次只强调一件事**：页面里 `primary` 只给一个主行动（完成按钮 / 搜索框聚焦 / 当前 tab）。

### 4.4 取舍表（哪条取舍是为了满足哪条约束）

| 约束 | 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- | --- |
| 流畅 | 报表页 `verticalScroll` 而非 `LazyColumn` | "无限滚动的图表流" | 图表在复用容器里重建 `ChartView` 会掉帧；报表本来就一屏内看完 |
| 流畅/功耗 | 不引 `miuix-glass`、不引 `miuix-material` | OS4 玻璃悬浮质感、`GlassTopAppBar`/`GlassBottomSheet` | 玻璃=RuntimeShader 每帧 GPU；中端机滚动会掉帧、耗电上升 |
| 功耗/体积 | LXGW 只子集化 6,763 字 + ASCII | 完整楷体字库（生僻字回退系统字体，字形不统一） | 全量 +13.9 MB，APK 翻倍 |
| 统一 | 41 处 Toast 全改 `Snackbar` | 后台 `Receiver`/`Service` 里的即时反馈（保留 Toast/通知） | 系统 Toast 无法跟主题，是"不统一"最刺眼的来源；后台没有 UI 上下文，强行 Snackbar 会崩 |
| 统一 | 设置页 `TimePickerDialog` 保留系统实现 | 全站纯 Miuix | Miuix `NumberPicker` 做"03:00"这种时刻选择要拼两个 picker + 分隔符，收益不抵复杂度（**这一条是明确的例外，写进白名单**） |
| 美观 | 环形图只换环体 | 库的百分比标签与引线 | 库的标签避让会压字、且不吃主题色；环心合计与排行手写更精确 |
| 一目了然 | 每卡 ≤ 4 个数字 | 报表页原"6 指标一屏" | 指标堆叠让人找不到重点，也给排版留白 |
| 体积 | 图标用 vector 不用图标字体 | 图标字体（几十 KB 换全量图标） | 字体图标无法按需着色/描边，且构建期不可裁剪 |
| 功耗 | 深色底 `#0B0F14` 而非 `#000000` | 极致 OLED 省电（差 ~2%） | 纯黑与 `#141A21` 卡片的对比会造成滚动时数字"抖动"，体验损失更大 |

---

## 5. 风险与回退

| # | 风险 | 缓解 |
| --- | --- | --- |
| 1 | `Colors` 53 槽映射漏项 → 某控件仍是 Miuix 默认蓝 | P0 用"全槽打印"调试页（把 53 个槽画成色块）逐项比对；映射表按 1.3 全量写 |
| 2 | `MPChartCompose` 4.0.1 的 `PieChart` 环心/标签不满足 | 环体换库、环心覆盖层；最坏退回自绘环形（保留 `DonutCard` 旧实现一版，P1 期间不删） |
| 3 | JitPack 冷缓存失败（历史：阿里云镜像 502） | `settings.gradle.kts` 显式 `maven("https://jitpack.io")`，**不放镜像链**；CI 与本地同配置 |
| 4 | 字体子集漏字 | 用真实 1,688 条账单 + 全部分类名 + 全部分设置文案跑缺字检测（脚本 `pyftsubset --text-file`），漏字一律 `FontFamily.Default` 兜底 |
| 5 | 主题切换后图表不变色 | 所有颜色写 `update`（坑 1）；P1 验收里专门有一项"切主题后截图" |
| 6 | 双轨期（`LedgerTheme` 与新 `LedgerPalette` 并存）写错引用 | `LedgerTheme` 在 P0 就改为 `@Deprecated` + 转发到 `LedgerPalette`，IDE 直接划掉 |
| 7 | Snackbar 迁移改变交互节奏（连续操作被排队） | `SnackbarHostState` 用 `DismissCurrent` 策略，同类消息去重（同一文案 1s 内只显示一次） |
| 8 | 15 个设置页一次改太多回归难定位 | P3 内部再按"外观/自动记账/数据管理"三组拆分，每组独立提交、独立 CI |
| 9 | 库与 Compose 版本（BOM）冲突 | `ChartLib v4.0.1` 是 Kotlin 重写，依赖 `androidx.compose` 运行时；先跑 `./gradlew :app:dependencies` 确认无 `compose-bom` 冲突再进 P1 |
| 10 | 回退成本 | P1 结束时打 tag `v0.2.0-ui`；每个阶段独立 commit，`git revert` 单点可回 |

---

## 6. 与 `docs/06`/`docs/07` 的关系

- `docs/06-UI统一改造方案.md` 是**问题清单 + 设计规范**（A1–A8 / B1–B9 / C1–C6、两套色值、组件 token），P0 已落地（`v0.1.6`）。
- `docs/07-UI重构方案.md` 是**六库取舍调研**，结论是"路线 A 渐进"，并否掉了 GearUI Kit（Kuikly 互斥）。
- **本文档是路线 A 的执行版**：把"用 M3 还是 Miuix"这个未定项定死为 **Miuix 单源**，把"图表换不换"定死为 **换**，并补上源码级 API 事实与门禁。
- 冲突时的取信顺序：**本文档 > docs/07 > docs/06**（前者晚于、且更具体）。