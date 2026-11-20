package com.eta.autoledger.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles

/**
 * 全站字号阶梯：Miuix 的 14 个 `textStyles` 槽位，一个都不多、一个都不少。
 *
 * 页面里只允许 `MiuixTheme.textStyles.xxx`，不允许自己写 `fontSize = 13.sp` ——
 * 这是「风格统一」在排版维度的落点（`tools/check-ui-consistency.sh` 里有 grep 门禁）。
 *
 * 三组职责：
 * - 数字组（`headline1/2`、`main`）：34/28/24sp Bold + `tnum`（等宽数字，避免金额跳动）
 * - 标题组（`title1..4`、`subtitle`）：22/20/17/15/14sp
 * - 正文组（`body1/2`、`paragraph`、`button`、`footnote1/2`）：15/13/15/15/12/11sp
 *
 * 为什么这些槽位要 `copy()` 而不是新建 `TextStyle`：Miuix 的默认槽位里带了
 * `fontFamily`、`letterSpacing`、`platformStyle` 等我们不想动的字段，`copy()` 只改
 * 字号/字重/行高，其余继承，换 Miuix 版本时不会突然丢样式。
 *
 * 字体族暂用系统默认（`FontFamily.Default`）。LXGW 文楷的接入点是这里的 `fontFamily`：
 * 参见 `docs/08` §1.4 与 `tools/subset_font.py`，子集化产物落地后再逐槽补
 * `fontFamily = AppFont`，数字组**永远不换字体**（楷体的 0/6/8 在 34sp 上难分辨）。
 */
@Composable
fun ledgerTextStyles(): TextStyles {
    val base = MiuixTheme.textStyles
    return remember(base) {
        base.copy().apply {
            // ---- 数字组：等宽 + 加粗，金额专用 ----
            headline1 = base.headline1.copy(
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            )
            headline2 = base.headline2.copy(
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            )
            main = base.main.copy(
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            )

            // ---- 标题组 ----
            title1 = base.title1.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            title2 = base.title2.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            title3 = base.title3.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
            title4 = base.title4.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            subtitle = base.subtitle.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

            // ---- 正文组 ----
            body1 = base.body1.copy(fontSize = 15.sp, lineHeight = 22.sp)
            body2 = base.body2.copy(fontSize = 13.sp, lineHeight = 19.sp)
            paragraph = base.paragraph.copy(fontSize = 15.sp, lineHeight = 22.sp)
            button = base.button.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
            footnote1 = base.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp)
            footnote2 = base.footnote2.copy(fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}