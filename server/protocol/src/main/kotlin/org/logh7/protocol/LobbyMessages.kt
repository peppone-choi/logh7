package org.logh7.protocol

import java.nio.ByteBuffer

/** evidence:client — session-login.md. Unidentified fields use local test values. */
object LobbyMessages {
    const val LOCAL_SESSION_ID = 1
    const val LOCAL_SESSION_NAME = "LOGH7 Local"
    fun loginOk() = LoginMessages.sessionMessage(0x2001, byteArrayOf(0, 0, 0))
    fun noCharacters() = LoginMessages.sessionMessage(0x2004, byteArrayOf(0))
    fun sessions(): ByteArray {
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
