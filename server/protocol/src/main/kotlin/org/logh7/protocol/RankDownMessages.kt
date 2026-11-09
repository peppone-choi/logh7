package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0043d080: four u32, target rank byte, four u32, and a count of relocated characters. */
object RankDownMessages {
    fun mailPair(executor: Int, target: Int, targetRank: Int, mcp: Int, pcp: Int): ByteArray {
        require(executor > 0 && target > 0 && targetRank in 0..20 && mcp >= 0 && pcp >= 0)
        // Time zero pairs this message with the native mail row. No live rank change or relocation is reported.
        val body = ByteBuffer.allocate(34).putInt(0).putInt(executor).putInt(pcp).putInt(mcp)
            .put(targetRank.toByte()).putInt(target).putInt(0).putInt(0).putInt(0).put(0)
        return LoginMessages.sessionMessage(0x0706, body.array())
    }
}
