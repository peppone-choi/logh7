package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 00551340/00555530: seven u32 followed by two bounded unit/supply result lists. Author: 최병호. */
object CompletenessSupplyMessages {
    data class Query(val character:Int,val from:Int,val to:Int)
    data class UnitSupply(val unit:Int,val supplies:Int)

    fun query(bytes:ByteArray):Query {
        require(bytes.size>=32)
        val b=ByteBuffer.wrap(bytes);require(b.short.toInt()==0x0c01)
        b.int;val character=b.int;b.int;b.int
        val from=b.int;val to=b.int;b.int // Claimed result supplies are never authoritative.
        fun skipResults(maximum:Int) {
            require(b.hasRemaining())
            val count=b.get().toInt() and 255
            require(count<=maximum && b.remaining()>=count*8)
            repeat(count) {b.int;b.int} // Client-provided outcome fields cannot mutate unit stock.
        }
        skipResults(26);skipResults(70)
        require(!b.hasRemaining() && character>0 && from>0 && to>0 && from!=to)
        return Query(character,from,to)
    }

    fun response(q:Query,seconds:Long,pcp:Int,mcp:Int,toSupplies:Int,
        fromUnits:List<UnitSupply>,toUnits:List<UnitSupply>,accepted:Boolean):ByteArray {
        require(seconds in 0..0xffffffffL && pcp>=0 && mcp>=0 && toSupplies>=0)
        require(q.character>0 && q.from>0 && q.to>0 && q.from!=q.to)
        require(fromUnits.size<=26 && toUnits.size<=70)
        val units=fromUnits+toUnits
        require(units.all {it.unit>0 && it.supplies>=0} && units.distinctBy {it.unit}.size==units.size)
        require(accepted || (toSupplies==0 && units.isEmpty()))
        val b=ByteBuffer.allocate(30+units.size*8).putInt(seconds.toInt()).putInt(q.character).putInt(pcp).putInt(mcp)
            .putInt(q.from).putInt(if(accepted)q.to else 0).putInt(toSupplies)
        for(list in listOf(fromUnits,toUnits)) {
            b.put(list.size.toByte());list.forEach {b.putInt(it.unit).putInt(it.supplies)}
        }
        return LoginMessages.sessionMessage(0x0c01,b.array(),if(accepted)0 else 1)
    }
}
