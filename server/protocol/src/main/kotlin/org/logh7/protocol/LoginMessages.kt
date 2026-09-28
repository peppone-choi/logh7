package org.logh7.protocol

import java.nio.ByteBuffer

/** evidence:client — login-messages.md, direction-specific wire headers. */
object LoginMessages {
    data class Request(val account: String, val credential: String)
    private fun ByteBuffer.u16() = short.toInt() and 65535
    fun request(bytes: ByteArray): Request {
        require(bytes.size >= 13)
        val input = ByteBuffer.wrap(bytes)
        require(input.u16() == 0x7000)
        require(input.int == 0x47494e37 && input.u16() == 1 && input.u16() == 0)
        require(input.get().toInt() == 0)
        fun string(max: Int): String {
            require(input.hasRemaining())
            val count = input.get().toInt() and 255
            require(count in 1..max && input.remaining() >= count * 2)
            val units = CharArray(count) { input.u16().toChar() }
            require(units.last() == '\u0000' && units.dropLast(1).none { it == '\u0000' })
            return units.concatToString(0, count - 1)
        }
        val result = Request(string(31), string(11))
        require(!input.hasRemaining()) { "Unexpected login trailing bytes" }
        return result
    }
    fun success(address: String, port: Int, token: Long): ByteArray {
        require(port in 1..65535 && token in 1..0xffffffffL)
        val octets = address.split('.').map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 })
        val ip = octets.foldIndexed(0) { i, acc, b -> acc or (b shl (i * 8)) }
        return ByteBuffer.allocate(18).putShort(0x7001).putShort(0).putShort(0)
            .putShort(0).putInt(ip).putShort(port.toShort()).putInt(token.toInt()).array()
    }
    fun failure(code: Int = 1): ByteArray {
        require(code in 0..255)
        return ByteBuffer.allocate(11).putShort(0x7002).putShort(0).putShort(0)
            .putShort(0).put(code.toByte()).putShort(0).array()
    }
    fun sessionToken(bytes: ByteArray): Long {
        require(bytes.size == 6)
        val input = ByteBuffer.wrap(bytes); require(input.u16() == 0x20)
        return input.int.toLong() and 0xffffffffL
    }
    fun sessionMessage(opcode: Int, body: ByteArray, field50: Int = 0): ByteArray =
        ByteBuffer.allocate(6 + body.size).putInt(field50).putShort(opcode.toShort()).put(body).array()
}
