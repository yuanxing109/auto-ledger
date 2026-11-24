package com.eta.autoledger.data

import java.util.Calendar
import com.eta.autoledger.util.AppDateFormats

/**
 * 周期账单实账的唯一键生成器。
 *
 * 手动创建周期账单与后台 Worker 必须共用同一实现。唯一键格式一旦分叉，
 * 同一模板同一天就可能生成两条实账。
 */
object RecurringExternalIds {

    private const val PREFIX = "rec_"
    fun generate(templateId: Long, calendar: Calendar): String {
        val date = AppDateFormats.format(calendar, AppDateFormats.basicDate)
        return PREFIX + templateId + "_" + date
    }
}
