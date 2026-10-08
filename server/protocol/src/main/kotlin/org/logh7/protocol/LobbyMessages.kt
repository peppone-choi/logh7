package org.logh7.protocol

import java.nio.ByteBuffer

/** evidence:client — session-login.md. Unidentified fields use local test values. */
object LobbyMessages {
    const val LOCAL_SESSION_ID = 1
    const val LOCAL_SESSION_NAME = "LOGH7 Local"
    fun loginOk() = LoginMessages.sessionMessage(0x2001, byteArrayOf(0, 0, 0))
    fun noCharacters() = LoginMessages.sessionMessage(0x2004, byteArrayOf(0))
    /** 0043fd60: session information, next-session count, entry state, charged character summary. */
    fun characters(character: CharacterMessages.Generate?): ByteArray {
        if (character == null) return noCharacters()
        val out = ByteBuffer.allocate(512)
        fun u8(value: Int) { out.put(value.toByte()) }
        fun string(value: String) {
            require(value.length <= 12)
            if (value.isEmpty()) { u8(0); return }
            u8(value.length + 1); (value + '\u0000').forEach(out::putChar)
        }
        u8(1)
        val session = sessions()
        out.put(session, 8, session.size - 8) // shared InformationSession record, excluding result/count
        u8(0); u8(2); u8(1) // no next session, existing-character entry state, one charged character
        out.putInt(character.id.toInt()); u8(character.power); u8(character.power); u8(if (character.generated) 1 else 0)
        u8(character.gender); u8(character.birthMonth); u8(character.birthDay)
        out.putInt(Math.multiplyExact(character.age, CharacterMessages.AGE_SECONDS_PER_YEAR)); u8(0)
        character.abilities.forEach { out.putShort(it.toShort()) }
        string(character.surname); string(character.givenName); string(character.surname); string(""); string(character.shipName)
        u8(character.origin); u8(character.rank); out.putInt(character.face); u8(0) // no ending
        return LoginMessages.sessionMessage(0x2004, out.array().copyOf(out.position()))
    }
    fun sessions(selectable: Boolean = true): ByteArray {
        if (!selectable) return LoginMessages.sessionMessage(0x2006, byteArrayOf(0, 0))
        val output = ByteBuffer.allocate(128)
        output.put(0).put(1) // result, session count
        output.putShort(LOCAL_SESSION_ID.toShort()).put(1) // selectable session status (client accepts 1 or 2)
        val name = "$LOCAL_SESSION_NAME\u0000"
        output.put(name.length.toByte()); name.forEach(output::putChar)
        output.put(0).putInt(0) // empty date, unidentified u32
        repeat(2) { output.put(0).putInt(0).putInt(0).putInt(0).put(0) }
        output.put(0) // no ending record
        return LoginMessages.sessionMessage(0x2006, output.array().copyOf(output.position()))
    }
    fun select(bytes: ByteArray): Int {
        require(bytes.size == 4)
        val input = ByteBuffer.wrap(bytes)
        require(input.short.toInt() and 65535 == 0x2009)
        return input.short.toInt() and 65535
    }
    fun selected(address: String, port: Int, token: Long): ByteArray {
        // LGLoginOK contains an extra u16 before the shared address/port/token fields.
        val fields = LoginMessages.success(address, port, token).copyOfRange(8, 18)
        return LoginMessages.sessionMessage(0x200a, fields)
    }
    fun selectionFailed() = LoginMessages.sessionMessage(0x200b, byteArrayOf(1, 0))
    fun gameLoginOk() = LoginMessages.sessionMessage(0x0201, byteArrayOf(0))
}
