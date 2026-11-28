# UI 设计系统

## 1. 设计方向

AutoLedger 使用自有的 Oil UI。界面以“数字化账本”为核心，突出金额、分类和周期数据。视觉语言由暖纸色画布、深墨文字、石油蓝强调色和实色表面组成。

核心规则：

- 数字优先，金额和统计值使用等宽数字。
- 页面表面保持不透明，不使用玻璃拟物和实时高斯模糊。
- 分组优先依靠空间和字号，线条只用于真正的边界。
- 普通卡片不叠加多套边框、阴影和强调色。
- 所有视觉值来自 `ui/theme/OilTokens.kt`。

## 2. 应用骨架

主界面包含三个底部页签。

| 索引 | 页面 | 顶栏 | 底栏 |
| --- | --- | --- | --- |
| 0 | 记账 | `OilTopBar` | `OilBottomBar` |
| 1 | 记一笔 | `OilTopBar` | 隐藏 |
| 2 | 报表 | `OilTopBar` + `OilTabBar` | `OilBottomBar` |

设置页和搜索页作为全屏覆盖层。页面切换继续使用 `rememberMotionDuration`，系统关闭动画时时长归零。

## 3. Oil 主题

`ui/theme/AppTheme.kt` 是唯一主题根。它解析 `system / light / dark`，然后调用 `OilThemeProvider` 注入：

- `LocalOilColors`
- `LocalOilDimens`
- `LocalOilTypography`
- `LocalOilShapes`

页面和组件不得自行建立第二套主题。`LedgerTheme` 仅作为 Canvas、图表和非 Composable 绘制代码的兼容读取入口。

## 4. 颜色

色值唯一来源是 `ui/theme/OilTokens.kt`。

| Token | 浅色 | 深色 | 用途 |
| --- | --- | --- | --- |
| `canvas` | `#F1EEE5` | `#0F1412` | 页面底色 |
| `surface` | `#FFFCF5` | `#171D1A` | 标准卡片与导航 |
| `surfaceRaised` | `#FFFFFF` | `#202823` | 弹窗与浮层 |
| `surfaceMuted` | `#E8E2D6` | `#252E29` | 输入框与键帽 |
| `line` | `#D7D0C3` | `#323C36` | 控件边界 |
| `ink` | `#17201D` | `#F4F0E7` | 主文字 |
| `inkMuted` | `#5B655F` | `#B2B8B1` | 次级文字 |
| `accent` | `#0E5C63` | `#70C8CD` | 主操作与图表 |
| `heroSurface` | `#0E5C63` | `#12383A` | 核心资产卡 |
| `onHero` | `#FFFCF5` | `#F4F0E7` | Hero 卡文字 |
| `income` | `#237A57` | `#7BC7A4` | 收入与正结余 |
| `expense` | `#B74332` | `#F18A78` | 支出 |
| `warning` | `#996515` | `#E1B15B` | 预算临界 |

内容表面必须使用实色。透明度只允许用于弹窗遮罩和阴影 Token。深浅模式不能依赖透明度表达层级。
自定义壁纸开启时，正文放在不透明 Oil 账页上，仅让壁纸从页面两侧露出。

## 5. 字体

`OilTypography` 定义固定文字角色：

| 场景 | Token |
| --- | --- |
| 核心金额 | `heroAmount` |
| 统计金额 | `amount` |
| 页面标题 | `pageTitle` |
| 区块标题 | `sectionTitle` |
| 正文 | `body` |
| 强调正文 | `bodyStrong` |
| 辅助说明 | `caption` |
| 标签 | `label` |

金额、百分比、日期和图表刻度使用等宽字体与 `tnum`。不要在本文件外写一次性 `fontSize`。

## 6. 尺寸与形状

尺寸阶梯为 `4 / 8 / 12 / 16 / 24 / 32dp`，最小触控区为 `48dp`。

| Token | 值 | 用途 |
| --- | ---: | --- |
| `control` | 16dp | 输入框与键帽 |
| `card` | 24dp | 标准卡片 |
| `dialog` | 32dp | 弹窗 |
| `capsule` | 50dp | 按钮与标签 |

标准卡片使用 1dp `line` 边界。只有 `Raised` 卡片允许柔和阴影，`Hero` 卡片使用实色石油蓝表面。

## 7. 基础组件

组件位于 `ui/components/`：

- `OilCard`
- `OilButton`
- `OilIconButton`
- `OilTextField`
- `OilTopBar`
- `OilBottomBar`
- `OilTabBar`
- `OilDialog`
- `OilSwitch`
- `OilChoiceChip`
- `OilIconBadge`
- `OilScaffold`
- `OilSettingsGroup` / `OilSettingsRow` / `OilSwitchSettingsRow`

Oil 组件只使用 Compose Foundation、Runtime、UI 和 Animation，不依赖第三方 UI 组件库。

设置页分组不拆成多个独立卡片。组内行使用 60dp 最小高度，主标题为 `ink`，说明为 `inkMuted`，右侧只保留轻量箭头或状态值。

## 8. 页面模式

### 首页

页面顺序为问候语、Hero 资产卡、最近账目标题和账目列表。Hero 卡集中展示本月支出、收入、笔数、日均和预算。每条账目使用 `OilCard(Base)`，卡片间距 16dp。

### 记一笔

页面包含收支类型、记录时间、Hero 金额卡、分类宫格、子分类、周期设置和数字键盘。

数字键盘使用实色键帽，按下时下移 1dp 并切换 `surfacePressed`。系统文字 IME 可见时隐藏自定义数字键区，避免双键盘堆叠。

分类宫格使用低饱和圆底，不给每个分类添加粗边框。AI 快捷入口和保存操作使用 `OilButton`。

### 报表

周、月、年切换使用 `OilTabBar`。总览和图表容器使用 `OilCard(Base)`。图表颜色来自 Oil 色板：

- 支出使用 `expense`
- 收入使用 `income`
- 主趋势使用 `accent`
- 分类构成使用稳定顺序的 `chart1..chart6`

没有数据时显示紧凑 `暂无数据` 状态，不保留空图表高度。

报表分享提供“分享当前图表”和“分享完整报表”两种范围。导出的 PNG 始终铺设不透明的 `page` 背景，并在底部使用 `inkMuted` 水印“由 AutoLedger 生成”。临时图片放在专用缓存子目录，通过 FileProvider 只读分享并延迟清理。

### 搜索与弹窗

搜索页使用 `OilTopBar` 和 `OilTextField`。业务弹窗使用 `OilDialog`，操作按钮使用 `OilButton`。

## 9. 键盘与无障碍

- 页面根节点使用 `OilKeyboardInsetContainer`，通过 `imePadding()` 缩短可用区域。
- `OilTextField` 聚焦后请求滚入视口。
- 自定义数字键盘在系统 IME 可见时自动隐藏。
- 所有可点击元素至少 48dp。
- 纯图标按钮必须有 `contentDescription`。
- 关键金额不依赖透明度传达状态。
- 状态必须来自 `AppPrefs`、`CategoryStore`、Repository 或 ViewModel。

## 10. UI 一致性门禁

CI 运行：

```bash
bash tools/check-ui-consistency.sh
```

| 规则 | 级别 | 约束 |
| --- | --- | --- |
| R1 | error | Oil 主题只在 `AppTheme.kt` 创建 |
| R2 | error | 颜色字面量只允许出现在 `ui/theme/` |
| R3 | error | 禁止命名色直接用于页面 |
| R4 | warn | 字号优先使用 OilTypography |
| R5 | warn | 字重优先使用 OilTypography |
| R6 | warn | 圆角使用合法阶梯值 |
| R7 | warn | 页面提示优先使用全局 Snackbar |

## 11. 性能与禁止模式

- 禁止实时模糊和玻璃拟物。
- 禁止页面内建立第二套主题。
- 禁止给每个普通卡片叠加粗边框和重阴影。
- 禁止用绿色表示预算充足。
- 禁止用透明文字降低关键信息对比度。
- 禁止为装饰加入持续动画。
