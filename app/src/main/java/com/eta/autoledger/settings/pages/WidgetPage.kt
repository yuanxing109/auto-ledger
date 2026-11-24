package com.eta.autoledger.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import com.eta.autoledger.widget.LedgerWidgetProvider

@Composable
internal fun WidgetPage() {
    val context = LocalContext.current
    SettingsPageColumn {
        HintText("点一下会直接在桌面钉上对应规格；也可以长按桌面空白处 → 「添加小部件」→ 「自动记账」，两种规格都能选。")
        SettingsCard {
            ArrowPreference(title = "今日收支", summary = "2×2 · 今日支出大数字 + 本月收支", onClick = {
                pinWidget(context, wide = false)
            })
            RowDivider()
            ArrowPreference(title = "本月概览", summary = "4×2 · 本月收支并排 + 今日支出与笔数", onClick = {
                pinWidget(context, wide = true)
            })
        }
    }
}

/** 请求把对应规格的小部件钉到桌面，并给出明确结果提示（部分桌面不支持一键添加）。 */
private fun pinWidget(context: Context, wide: Boolean) {
    val ok = LedgerWidgetProvider.requestPin(context, wide)
    Toast.makeText(
        context,
        if (ok) "已请求添加到桌面，请在桌面确认"
        else "当前桌面不支持一键添加，请长按桌面空白处 →「小部件」→「自动记账」",
        Toast.LENGTH_LONG,
    ).show()
}
