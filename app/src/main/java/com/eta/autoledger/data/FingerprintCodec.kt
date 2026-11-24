package com.eta.autoledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Locale

/** 指纹共用的金额与摘要编码，确保不依赖设备语言和 Double 的小数文本。 */
object FingerprintCodec {

    fun amountFen(amount: Double): Long =
        BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()

    /** 指纹文本中的金额分，固定 Locale.ROOT，不依赖设备语言。 */
    fun amountFenToken(amount: Double): String =
        String.format(Locale.ROOT, "%d", amountFen(amount))

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    fun md5Hex(seed: String): String =
        MessageDigest.getInstance("MD5")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .let(::hex)

    private const val HEX = "0123456789abcdef"
}
