package com.eta.autoledger.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiClientTest {

    @Test
    fun `Markdown 和解释文字中的首个 JSON 对象可被清理`() {
        val raw = """
            好的，结果如下：
            ```json
            {"amount":18.5,"note":"包含 {花括号} 的备注","nested":{"ok":true}}
            ```
            以上。
        """.trimIndent()

        val parsed = AiClient.parseJsonObject(raw)
        assertEquals(18.5, parsed?.optDouble("amount") ?: 0.0, 0.001)
        assertEquals("包含 {花括号} 的备注", parsed?.optString("note"))
    }

    @Test
    fun `数组包裹和 null 响应安全降级`() {
        assertEquals(12, AiClient.parseJsonObject("""[{"amount":12}]""")?.optInt("amount"))
        assertNull(AiClient.parseJsonObject("null"))
        assertNull(AiClient.parseJsonObject("没有 JSON"))
    }
}
