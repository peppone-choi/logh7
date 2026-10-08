package org.logh7.protocol

import java.nio.ByteBuffer

/** evidence:client — initial response readers; empty local catalogues, not original game data. */
object BootstrapMessages {
    private fun captainCard(character: CharacterMessages.Generate) = if (character.power == 2) 59 else 195
    private fun cards(): ByteArray {
        val out = ByteBuffer.allocate(128).putShort(3)
        // constmsg group 3: 0=personal, 59=Empire captain, 195=Alliance captain.
        for ((kind, power) in listOf(0 to 0, 59 to 2, 195 to 3)) {
            out.putShort(kind.toShort()).put(if (kind == 0) 0 else 1).put(power.toByte()).put(power.toByte()).put(0)
            out.putShort(0).putShort(0).put(0).put(20).put(0).putShort(0).put(0).putShort(0).put(0)
        }
        return out.array().copyOf(out.position())
    }
    fun characterId(character: CharacterMessages.Generate) = LoginMessages.sessionMessage(0x0204,
        ByteBuffer.allocate(4).putInt(character.id.toInt()).array())

    /** 00417390: wire fields follow the reader, without its 724-byte object padding. */
    fun character(character: CharacterMessages.Generate): ByteArray {
        val out = ByteBuffer.allocate(1024)
        fun u8(value: Int) { out.put(value.toByte()) }
        fun string(value: String) {
            require(value.length <= 12)
            if (value.isEmpty()) { u8(0); return }
            u8(value.length + 1); (value + '\u0000').forEach(out::putChar)
        }
        out.putInt(character.id.toInt())
        u8(character.power); u8(character.power); u8(0); u8(character.gender)
        out.putInt(Math.multiplyExact(character.age, CharacterMessages.AGE_SECONDS_PER_YEAR))
        u8(character.birthMonth); u8(character.birthDay)
        out.putInt(0).putShort(0) // fame, maximum special abilities
        out.putInt(0).putInt(0).putInt(character.id.toInt()).putInt(unitId(character))
        string(character.shipName)
        repeat(3) { out.putInt(0) } // strategy and coup fields
        out.putInt(100).putInt(100).putInt(0) // local PCP, MCP, evaluation
        out.putShort(0); repeat(6) { u8(0) }; u8(1) // mail, AI switches, online
        out.putInt(0).put(ByteArray(16)); u8(0); u8(1) // money, decorations, arrested, parentage count
        u8(1); string(character.surname); string(character.givenName); string(character.surname)
        out.putShort(character.origin.toShort()).putShort(character.rank.toShort()); string("")
        out.putInt(character.face).putInt(0).putInt(0).putInt(0)
        character.abilities.forEach { out.putShort(it.toShort()).putShort(0) }
        u8(0); u8(100); u8(0) // influence, stamina, special count
        u8(2)
        out.putShort(0).putInt(0) // personal card, no institution restriction
        out.putShort(captainCard(character).toShort()).putInt(unitId(character))
        u8(0) // together
        return LoginMessages.sessionMessage(0x0323, out.array().copyOf(out.position()))
    }

    private fun unitId(character: CharacterMessages.Generate) = character.id.toInt() + 1_000_000

    /** 00419ca0: one local flagship unit; its grid and resource values are provisional. */
    fun unit(character: CharacterMessages.Generate): ByteArray {
        val out = ByteBuffer.allocate(64)
        out.putShort(1).putInt(unitId(character)).putShort(character.shipKind.toShort()).put(0)
        out.putInt(2550).putInt(0).putInt(0).put(0) // grid, outfit, boarding ship, troop count
        out.putInt(0).put(100).put(0).putShort(0).putShort(0)
        out.putInt(10_000).putInt(0).putFloat(0f)
        return LoginMessages.sessionMessage(0x0325, out.array().copyOf(out.position()))
    }
    private val emptySizes = mapOf(
        0x0304 to 2, // 0040ee80: card count u16
        0x0306 to 2, // 0040f9f0: card-command count u16
        0x0314 to 4, // 004134e0: two flags u8, byte count u16
        0x0312 to 1, // 00413050: count u8
        0x030a to 1, // 004109a0: ship count u8
        0x0310 to 432, // 00412eb0: 27 * 8 signed u16 ability values
        0x030e to 1, // 00412980: count u8
        0x031c to 2, // 004142e0: count u16
        0x0308 to 1370, // 00410370: fixed power-direction tables (no memory padding)
        0x030c to 1, // 004121f0: count u8
        0x0f00 to 0, 0x0f02 to 0, // 004077c0: acknowledgement has no payload
        0x0f04 to 1, 0x0f06 to 1, // 00482620 / 00484280: empty mail and messenger lists
    )

    fun response(request: ByteArray): ByteArray? {
        require(request.size >= 2)
        val opcode = ((request[0].toInt() and 255) shl 8) or (request[1].toInt() and 255)
        val data = when (opcode) {
            0x0300 -> byteArrayOf(0, 0, 0, 0) // local epoch; game clock follows separately
            0x0304 -> cards()
            else -> emptySizes[opcode]?.let { ByteArray(it) } ?: return null
        }
        require(request.size == 2) { "Initial catalogue request must be empty" }
        return LoginMessages.sessionMessage(opcode + 1, data)
    }
}
