package com.eta.autoledger.data

import com.eta.autoledger.screenshot.VisionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入去重与识图链路的纯函数断言。
 *
 * 这里刻意只测「不碰 Android 运行时」的部分：JVM 单测里的 org.json 与
 * Context 都是桩，真跑 JSONObject 只会拿到默认值，测了等于没测。
 * 涉及 JSONObject 的解析路径交给 CI 的仪器测试与真机验证。
 */
class ImportSafetyTest {

    // ── VisionHelper.stripDataUriPrefix ────────────────────────────────

    @Test
    fun 纯base64原样返回() {
        val raw = "/9j/4AAQSkZJRgABAQAAAQ=="
        assertEquals(raw, VisionHelper.stripDataUriPrefix(raw))
    }

    @Test
    fun 剥掉data_uri前缀() {
        val raw = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
        assertEquals("/9j/4AAQSkZJRg==", VisionHelper.stripDataUriPrefix(raw))
    }

    @Test
    fun 重复拼接的前缀只留最内层正文() {
        // 相册与截屏两条路径各拼过一次时的真实形态
        val raw = "data:image/png;base64,data:image/png;base64,iVBORw0KGgo="
        assertEquals("iVBORw0KGgo=", VisionHelper.stripDataUriPrefix(raw))
    }

    @Test
    fun 剥前缀时清掉换行与空格() {
        val raw = "data:image/jpeg;base64,/9j/4AAQ\nSkZJRg\r\n=="
        assertEquals("/9j/4AAQSkZJRg==", VisionHelper.stripDataUriPrefix(raw))
    }

    // ── VisionHelper.strictPrompt ─────────────────────────────────────

    @Test
    fun 严格提示词追加禁止代码块的约束() {
        val out = VisionHelper.strictPrompt("只输出 JSON")
        assertTrue(out.startsWith("只输出 JSON"))
        assertTrue(out.contains("不要 Markdown 代码块标记"))
        assertTrue(out.contains("amount"))
    }

    @Test
    fun 重复调用不会叠加约束() {
        val once = VisionHelper.strictPrompt("只输出 JSON")
        val twice = VisionHelper.strictPrompt(once)
        assertEquals(once, twice)
    }

    // ── ImportDedup.nameScore ─────────────────────────────────────────

    @Test
    fun 商户名完全相同得满分() {
        assertEquals(1.0, ImportDedup.nameScore("美团", "美团"), 0.0001)
    }

    @Test
    fun 一边包含另一边按长度比例给分() {
        // 2 字商户名落在 6 字描述里 = 0.1 + 0.9 * 2/6 ≈ 0.4，低于 0.45 阈值。
        // 这正是「同金额 5 分钟内」宽松规则存在的理由：这种形态靠名字判不出来。
        val score = ImportDedup.nameScore("美团外卖订单", "美团")
        assertTrue("score=$score", score > 0.3)
        assertFalse("score=$score", score > 0.45)
    }

    @Test
    fun 毫不相干的商户名低于阈值() {
        val score = ImportDedup.nameScore("滴滴出行", "便利蜂")
        assertFalse("score=$score", score > 0.45)
    }

    // ── ImportDedup.Plan.summary ──────────────────────────────────────

    @Test
    fun 预览文案包含三个计数() {
        val plan = ImportDedup.Plan(fresh = emptyList(), suspects = emptyList(), skipped = 3)
        assertEquals("共解析 3 条，跳过老数据 3 条，发现 0 条疑似与其他来源重复", plan.summary)
    }
}
