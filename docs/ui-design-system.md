# UI 设计系统

## 1. 设计方向

AutoLedger 使用 Miuix 作为组件与主题基础。界面目标是让金额、分类和周期数据保持高对比、低干扰和稳定布局，避免装饰效果压过内容。

核心规则：

- 数字优先。
- 语义颜色明确。
- 页面层级依靠表面色和细边框。
- 全局背景可以存在，但内容层必须保持可读。
- 不使用实时高斯模糊。
- 通用容器、间距和文字样式必须使用共享 Token。

## 2. 应用骨架

### 2.1 三页签

主界面固定三个底部页签：

| 索引 | 页面 | 顶栏 | 底栏 |
| --- | --- | --- | --- |
| 0 | 记账 | `LedgerTopBar` | 显示 |
| 1 | 记一笔 | `LedgerTopBar` | 隐藏 |
| 2 | 报表 | `ReportTopBar` | 显示 |

页签切换使用约 180ms 的小幅横向位移和淡入淡出。

设置页与搜索页是全屏覆盖层，使用约 200ms 淡入淡出。三页签结构不随功能扩展改变。

### 2.2 根层

`LedgerApp` 根层包含：

- `LedgerBackdrop`：背景图或默认页面背景。
- `LedgerSnackbarHost`：全局提示。
- `AiActionSheet`：统一 AI 入口。
- 主页面或覆盖层内容。

页面根容器使用透明背景，不得再铺一层独立全屏背景。

## 3. 主题入口

`ui/theme/AppTheme.kt` 是唯一主题根：

- 支持 `system / light / dark`。
- 向 `MiuixTheme` 注入色板、`ledgerTextStyles()` 和主题控制器。
- 页面不得在内部另建 `MiuixTheme` 或 `MaterialTheme`。

## 4. 颜色

`ui/theme/LedgerPalette.kt` 是唯一色值源。

### 4.1 主要语义

| Token | 浅色 | 深色 | 用途 |
| --- | --- | --- | --- |
| `page` | `#FAFAFA` | `#09090B` | 页面背景 |
| `surface` | `#FFFFFF` | `#18181B` | 卡片与列表表面 |
| `surfaceVariant` | `#F4F4F5` | `#27272A` | 次级表面 |
| `inset` | `#F4F4F5` | `#202023` | 输入框与内凹区域 |
| `surfacePressed` | `#EFEFF1` | `#27272A` | 按压态 |
| `border` | `#E4E4E7` | `#27272A` | 控件边界 |
| `divider` | `#E4E4E7` | `#3F3F46` | 分割线 |
| `text` | `#09090B` | `#FAFAFA` | 主文字与金额 |
| `textSecondary` | `#71717A` | `#A1A1AA` | 次级说明 |
| `textTertiary` | `#A1A1AA` | `#71717A` | 占位与弱提示 |
| `accent` | `#2563EB` | `#60A5FA` | 主操作、焦点、图表主色 |
| `accentPressed` | `#1D4FD7` | `#3B82F6` | 主色按压态 |
| `income` | `#399E75` | `#70C39D` | 收入与正结余 |
| `expense` | `#D16158` | `#E18B83` | 支出与负向金额 |
| `danger` | `#C6534D` | `#D97870` | 删除与危险操作 |
| `warning` | `#B7791F` | `#D7A94E` | 警告与预算临界 |

### 4.2 使用规则

- `income` 只表示收入或正结余。
- `expense` 表示普通支出或负结余。
- `danger` 表示删除、错误和危险操作。
- 绿色不得表示预算充足。
- 支出、收入和预算状态不能只靠颜色区分。
- 卡片阴影使用带透明度的 `cardShadow`。
- 页面代码不得写 `Color(0x...)` 或命名色。

## 5. 字体

`ui/theme/LedgerTextStyles.kt` 定义 Miuix 文本槽。

| 场景 | 样式 |
| --- | --- |
| 首页核心金额 | `heroAmountStyle()`，32sp / Bold / `tnum` |
| 账目大金额 | `amountDisplayStyle()`，34sp / Bold / `tnum`/ 右对齐 |
| 页面标题 | `title1` |
| 区块标题 | `title2` |
| 列表标题 | `title3` |
| 正文 | `body1` |
| 次要正文 | `body2` |
| 辅助说明 | `footnote1` |

`headline1` 保持 Miuix 默认值，因为设置页偏好标题内部依赖该槽。大金额使用专用函数，不占用 `headline1`。

金额和统计数字启用等宽数字，避免跳动与逐列错位。

## 6. 间距与圆角

`ui/theme/LedgerDimens.kt` 定义：

| Token | 值 | 用途 |
| --- | ---: | --- |
| `xs` | 4dp | 图标与文字 |
| `sm` | 8dp | 紧凑间距 |
| `md` | 12dp | 标准行内间距 |
| `lg` | 16dp | 卡片内边距、页面边距 |
| `xl` | 24dp | 区块间距 |
| `xxl` | 32dp | 大分区间距 |
| `minTouch` | 48dp | Android 最小触控区 |
| `radiusSm` | 8dp | 小标签、键帽、小按钮 |
| `radiusMd` | 12dp | 输入框、小卡片 |
| `radiusLg` | 16dp | 标准卡片 |
| `radiusXl` | 24dp | 特殊容器 |
| `radiusDialog` | 28dp | 对话框 |
| `radiusCapsule` | 50dp | 胶囊按钮 |

`radiusSmall` 是 `LedgerDimens` 实例字段，值为 12dp。`radiusSm` 是 companion 常量，值为 8dp。两者语义不同。

## 7. 统一卡片

`ui/components/ThemeSurfaceCard.kt` 是通用卡片容器：

- 默认圆角 16dp。
- 实色 `surface`。
- 1dp `borderSoft` 边框。
- 填充与边框合并绘制。
- 默认不添加阴影。

需要阴影时由调用方使用 `shadow` 并传入语义化透明色。

页面新增卡片前应先复用 `ThemeSurfaceCard`；只有图表、特殊交互或布局确实不匹配时才单独实现。

## 8. 页面模式

### 8.1 首页

`HomeScreen` 内容顺序：

1. 问候语。
2. 核心资产卡。
3. 最近账目标题与搜索入口。
4. 按天分组列表。

核心资产卡集中展示本月支出、收入、笔数、日均、预算进度和自动记账状态。

### 8.2 记一笔

`AddScreen` 包含：

- 金额输入卡。
- 分类宫格。
- 二级分类联想输入。
- 周期设置。
- 自绘数字键盘。
- 文本 AI 快捷入口。

AI 识图入口统一放在顶栏或全局 AI 面板，页面主体不再重复放相机/相册图标。

### 8.3 报表

`ReportScreen` 支持周、月、年：

- 总览卡。
- 折线趋势。
- 柱状对比。
- 环形分类构成。
- 分类排行和子分类明细。

图表使用 Compose Canvas 自绘。

### 8.4 搜索

`SearchScreen` 使用全屏覆盖层：

- 顶部返回与搜索框。
- 快捷筛选 Chip。
- 重型筛选 BottomSheet。
- 按天分组结果。
- 编辑入口复用 `EntryEditDialog`。

## 9. 设置页

设置页根据 `SettingsPage.parent` 管理层级，不维护重复返回栈。

`SettingsPageColumn` 统一提供：

- 页面水平 16dp 边距。
- 卡片间距 16dp。
- 纵向滚动。
- 底部 82dp 留白。

设置主页由两张大组卡组成：

第一组：

- 自动记账
- 记账设置
- 通知与提醒
- 数据与备份
- 外观与小部件

第二组：

- AI 与高级功能
- 权限与后台
- 关于

组内使用细分割线。复杂配置进入独立子页面，设置主页不再平铺全部字段。

## 10. 交互与无障碍

- 触控目标不小于 48dp。
- 可点击元素必须有按压反馈。
- 图标按钮必须有有意义的 `contentDescription`。
- 装饰图标使用空描述。
- 覆盖层出现时，背景不得继续接收焦点或触控。
- 支持系统字体缩放。
- 关键金额允许换行，不截断。
- 键盘和底栏不得遮挡当前输入项。
- 动画时长通过共享 Motion API 获取。
- 状态必须来自 `AppPrefs`、`CategoryStore` 或 ViewModel，不能保留只改 UI 的假开关。

## 11. UI 一致性门禁

CI 首先运行：

```bash
bash tools/check-ui-consistency.sh
```

| 规则 | 级别 | 当前约束 |
| --- | --- | --- |
| R1 | error | 只允许 `AppTheme` 创建主题 |
| R2 | error | 颜色字面量只允许在设计 Token |
| R3 | error | 禁止命名色直接用于页面 |
| R4 | warn | 字号优先使用文本槽 |
| R5 | warn | 字重优先使用文本槽 |
| R6 | warn | `RoundedCornerShape` 使用合法阶梯值 |
| R7 | warn | Composable 页面优先使用全局 Snackbar |

R1 至 R3 会阻断 CI。R4 至 R7 当前只提示，不代表问题已经全部清零。

非 Composable 宿主，例如 BroadcastReceiver、TileService 和部分前台服务，仍可能使用 Toast。页面级提示应优先使用 `LocalSnackbar`。

## 12. 性能约束

- 禁止实时模糊。
- 自定义背景只解码一次并复用。
- 列表背景保持透明，不重复绘制全屏 Bitmap。
- 不在滚动列表中创建高频 Formatter。
- 图表使用 Compose Canvas，不为单个点引入重型 View 互操作。
- 页面状态提升后避免整页无关重组。

## 13. 禁止模式

- 页面内新建第二套主题。
- 整块高饱和品牌色铺底掩盖金额。
- 每个设置项单独占一张卡片。
- 纯黑硬阴影。
- 金额使用非等宽或手写字体。
- 用绿色表示预算充足。
- 为装饰加入持续动画。
- 通过半透明文字降低关键信息对比度。
