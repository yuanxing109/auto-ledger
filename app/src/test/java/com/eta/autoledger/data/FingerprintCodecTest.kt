package com.eta.autoledger.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FingerprintCodecTest {

    @Test
    fun `金额分与十六进制不依赖设备语言`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val germanFen = FingerprintCodec.amountFen(19.40)
            val germanHex = FingerprintCodec.hex(byteArrayOf(0, 15, 16, -1))

            Locale.setDefault(Locale.US)
            assertEquals(germanFen, FingerprintCodec.amountFen(19.40))
            assertEquals("1940", FingerprintCodec.amountFenToken(19.40))
            assertEquals(germanHex, FingerprintCodec.hex(byteArrayOf(0, 15, 16, -1)))
            assertEquals("000f10ff", germanHex)
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun `金额四舍五入到分后指纹稳定`() {
        assertEquals(1940L, FingerprintCodec.amountFen(19.4))
        assertEquals(30L, FingerprintCodec.amountFen(0.1 + 0.2))
        assertEquals(268L, FingerprintCodec.amountFen(2.675))
    }
}
