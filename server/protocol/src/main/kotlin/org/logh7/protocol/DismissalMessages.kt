package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0043b4f0/0043b6c0: seven u32, u8 count, then at most 32 moved people. */
object DismissalMessages {
    data class Query(val character:Int,val target:Int,val card:Int)
    fun query(bytes:ByteArray):Query {
        require(bytes.size==31) { "Client dismissal cannot supply relocation effects" }
        val b=ByteBuffer.wrap(bytes);require(b.short.toInt()==0x0708)
        b.int;val actor=b.int;b.int;b.int;val target=b.int;val card=b.int
        require(actor>0 && target>0 && card in 0..260 && b.int==0 && b.get().toInt()==0)
        return Query(actor,target,card)
    }
    fun response(q:Query,seconds:Long,pcp:Int,mcp:Int,accepted:Boolean):ByteArray {
        require(seconds in 0..0xffffffffL && pcp>=0 && mcp>=0)
        val b=ByteBuffer.allocate(29).putInt(seconds.toInt()).putInt(q.character).putInt(pcp).putInt(mcp)
            .putInt(if(accepted)q.target else 0).putInt(q.card).putInt(0).put(0)
        return LoginMessages.sessionMessage(0x0708,b.array(),if(accepted)0 else 1)
    }
}
