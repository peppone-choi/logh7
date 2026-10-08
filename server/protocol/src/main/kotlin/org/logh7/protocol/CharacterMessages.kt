package org.logh7.protocol

import java.nio.ByteBuffer

/** CD codec 0x00405a50; strings contain a terminating UTF-16BE NUL. */
object CharacterMessages {
    data class Generate(
        val category: Int, val id: Long, val power: Int, val origin: Int, val gender: Int,
        val surname: String, val givenName: String, val age: Int, val birthMonth: Int,
        val birthDay: Int, val face: Int, val abilities: List<Int>, val bonus: Int,
        val special: Int, val title: Int, val rank: Int, val shipType: Int, val shipKind: Int,
        val shipName: String, val check: Int,
    )
    fun generate(bytes: ByteArray): Generate {
        val input = ByteBuffer.wrap(bytes)
        fun u8() = input.get().toInt() and 255
        fun string(): String {
            val count = u8()
            require(count <= 13 && input.remaining() >= count * 2)
            if (count == 0) return ""
            val units = CharArray(count) { input.char }
            require(units.last() == '\u0000' && units.dropLast(1).none { it == '\u0000' || it.isSurrogate() })
            return String(units, 0, count - 1)
        }
        require(input.short.toInt() and 65535 == 0x1008)
        val message = Generate(u8(), input.int.toLong() and 0xffffffffL, u8(), u8(), u8(),
            string(), string(), input.int, u8(), u8(), input.int, List(8) { u8() },
            u8(), u8(), u8(), u8(), u8(), input.short.toInt() and 65535, string(), u8())
        require(!input.hasRemaining())
        return message
    }
    fun generateReply(message: Generate): ByteArray {
        val output = ByteBuffer.allocate(128)
        fun u8(value: Int) { require(value in 0..255); output.put(value.toByte()) }
        fun string(value: String) {
            require(value.length <= 12 && value.none { it == '\u0000' || it.isSurrogate() })
            if (value.isEmpty()) { u8(0); return }
            u8(value.length + 1); (value + '\u0000').forEach(output::putChar)
        }
        require(message.id in 0..0xffffffffL && message.abilities.size == 8 && message.shipKind in 0..65535)
        u8(message.category); output.putInt(message.id.toInt())
        u8(message.power); u8(message.origin); u8(message.gender)
        string(message.surname); string(message.givenName)
        output.putInt(message.age); u8(message.birthMonth); u8(message.birthDay); output.putInt(message.face)
        message.abilities.forEach(::u8)
        listOf(message.bonus, message.special, message.title, message.rank, message.shipType).forEach(::u8)
        output.putShort(message.shipKind.toShort()); string(message.shipName); u8(message.check)
        return LoginMessages.sessionMessage(0x1008, output.array().copyOf(output.position()))
    }
}
