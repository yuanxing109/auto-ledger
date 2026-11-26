package com.eta.autoledger.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `以下是提取结果前缀与 json 代码块会被剥离`() {
        val raw = "以下是提取结果：```json\n{\"transactions\":[{\"amount\":12.0}]}\n```"

        val payload = AiClient.parseJson(raw)
        assertTrue(payload is AiJsonPayload.ObjectValue)
        val transactions = (payload as AiJsonPayload.ObjectValue).value.getJSONArray("transactions")
        assertEquals(12.0, transactions.getJSONObject(0).getDouble("amount"), 0.001)
    }

    @Test
    fun `顶层 JSON 数组可被识别`() {
        val payload = AiClient.parseJson("废话\n[{\"amount\":1},{\"amount\":2}]\n结束")

        assertTrue(payload is AiJsonPayload.ArrayValue)
        assertEquals(2, (payload as AiJsonPayload.ArrayValue).value.length())
    }

    @Test
    fun `多个代码块优先采用最后一个最终结果`() {
        val raw = """
            示例：
            ```json
            {"transactions":[{"amount":1}]}
            ```
            最终结果：
            ```json
            {"transactions":[{"amount":12},{"amount":3}]}
            ```
        """.trimIndent()

        val payload = AiClient.parseJson(raw) as AiJsonPayload.ObjectValue
        val transactions = payload.value.getJSONArray("transactions")
        assertEquals(2, transactions.length())
        assertEquals(12.0, transactions.getJSONObject(0).getDouble("amount"), 0.001)
    }

    @Test
    fun `数组包裹和 null 响应安全降级`() {
        assertEquals(12, AiClient.parseJsonObject("""[{"amount":12}]""")?.optInt("amount"))
        assertNull(AiClient.parseJsonObject("null"))
        assertNull(AiClient.parseJsonObject("没有 JSON"))
    }

    @Test
    fun `思考过程含有花括号时仍提取真实 JSON`() {
        val raw = """
            我需要按 {字段} 提取。
            {"amount":19.4,"type":"income","category":"其他","time":"昨天","note":"天津新元"}
            以上是结果。
        """.trimIndent()

        val parsed = AiClient.parseJsonObject(raw)
        assertEquals(19.4, parsed?.optDouble("amount") ?: 0.0, 0.001)
        assertEquals("income", parsed?.optString("type"))
    }

    @Test
    fun `reasoning_content 不会被误当成模型正文`() {
        val body = """
            {
              "choices": [{
                "message": {
                  "content": null,
                  "reasoning_content": "我需要先分析字段，再输出结果。"
                }
              }]
            }
        """.trimIndent()

        val error = assertThrows(IllegalStateException::class.java) {
            AiClient.extractMessageContent(body)
        }
        assertEquals("模型返回为空，请确认该模型支持当前输入", error.message)
    }
}
