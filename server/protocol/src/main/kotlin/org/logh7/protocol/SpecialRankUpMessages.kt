package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 0705: 32 bytes, plus eight per merit update and four per relocated character. */
object SpecialRankUpMessages {
    fun mailPair(executor: Int, target: Int, newRank: Int, mcp: Int): ByteArray {
        require(executor > 0 && target > 0 && newRank in 0..20 && mcp >= 0)
        // A time-zero mail pair has no merit-list or relocation effects.
        val body = ByteBuffer.allocate(32).putInt(0).putInt(executor).putInt(0).putInt(mcp)
            .putInt(target).put(newRank.toByte()).putInt(0).putShort(0).putInt(0).put(0)
        return LoginMessages.sessionMessage(0x0705, body.array())
    }
}
