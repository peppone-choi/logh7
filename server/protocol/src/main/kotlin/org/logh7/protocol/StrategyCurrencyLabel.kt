package org.logh7.protocol

import java.nio.ByteBuffer

/** Optional labels in the fixed-size native 1200 envelope. Author: Peppone Choi. */
data class StrategyCurrencyLabel(val name: String, val symbol: String) {
    init {
        require(name.isNotBlank() && name.length <= 12 && symbol.length <= 4)
        require((name + symbol).none { it.isISOControl() || it.isSurrogate() })
    }

    internal fun records(raw: ByteArray): List<ByteArray> = listOf(name.take(7), name.drop(7), symbol).mapIndexed { flag, text ->
        val payload = raw.copyOf()
        val fields = ByteBuffer.wrap(payload).putShort(10, 0).put(16, (16 + flag).toByte())
        // Seven UTF-16 units in the existing pair of integers, short and base integer.
        listOf(12, 14, 17, 19, 21, 23, 25).forEachIndexed { index, offset ->
            fields.putChar(offset, text.getOrElse(index) { '\u0000' })
        }
        LoginMessages.sessionMessage(0x1200, payload)
    }
}
