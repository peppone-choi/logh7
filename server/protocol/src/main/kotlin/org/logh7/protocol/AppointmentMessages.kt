package org.logh7.protocol

import java.nio.ByteBuffer

/** Original 0043b190 writer: six u32, u16 card kind, three u32; no C++ padding. */
object AppointmentMessages {
    data class Query(val character: Int,val target: Int,val card: Int,val outfit: Int,val base: Int)
    fun query(message: ByteArray): Query {
        require(message.size==40)
        val input=ByteBuffer.wrap(message);require(input.short.toInt()==0x0707)
        input.int;val actor=input.int;input.int;input.int
        val target=input.int;val card=input.int
        input.short;input.int // Returned card structure is not client authority.
        val outfit=input.int;val base=input.int
        require(actor>0 && target>0 && card in 0..260 && base in 1..65535 && outfit==0)
        return Query(actor,target,card,outfit,base)
    }
    fun response(q: Query,seconds: Long,pcp: Int,mcp: Int,accepted: Boolean,changed: Boolean): ByteArray {
        require(seconds in 0..0xffffffffL && pcp>=0 && mcp>=0 && (!changed || accepted))
        // 004c5580 appends a card even on failure/replay. Target 0 suppresses that side effect;
        // a separate 0356 carries the complete authoritative role list to the actual target.
        val out=ByteBuffer.allocate(38).putInt(seconds.toInt()).putInt(q.character).putInt(pcp).putInt(mcp)
            .putInt(if (changed) q.target else 0).putInt(q.card).putShort(if (changed) q.card.toShort() else 0)
            .putInt(if (changed) q.base else 0).putInt(0).putInt(q.base)
        return LoginMessages.sessionMessage(0x0707,out.array(),if (accepted) 0 else 1)
    }
}
