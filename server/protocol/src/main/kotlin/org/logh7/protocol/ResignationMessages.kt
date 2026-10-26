package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0043b960/0043e020: six u32, u8 count and at most 32 moved character IDs. */
object ResignationMessages {
    data class Query(val character:Int,val card:Int)
    fun query(bytes:ByteArray):Query {
        require(bytes.size==27) { "Client resignation cannot supply relocation effects" }
        val b=ByteBuffer.wrap(bytes);require(b.short.toInt()==0x0709)
        b.int;val character=b.int;b.int;b.int;val card=b.int
        require(character>0 && card in 0..260 && b.int==0 && b.get().toInt()==0)
        return Query(character,card)
    }
    fun response(q:Query,seconds:Long,pcp:Int,mcp:Int,accepted:Boolean):ByteArray {
        require(seconds in 0..0xffffffffL && pcp>=0 && mcp>=0)
        val b=ByteBuffer.allocate(25).putInt(seconds.toInt()).putInt(q.character).putInt(pcp).putInt(mcp)
            .putInt(q.card).putInt(0).put(0)
        return LoginMessages.sessionMessage(0x0709,b.array(),if(accepted)0 else 1)
    }
}
