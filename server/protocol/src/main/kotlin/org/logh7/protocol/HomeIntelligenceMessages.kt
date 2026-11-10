package org.logh7.protocol

import java.nio.ByteBuffer

/** Explicit revival query for the viewer's national report on a selected base. Author: Peppone Choi. */
object HomeIntelligenceMessages {
    const val SEARCH=0x7f14
    data class Query(val character:Int,val base:Int,val ticket:Int)
    data class View(val observedAt:Long,val treasury:Long,val approval:Int,val peace:Int,val garrisonStrength:Long?=null)

    fun matches(body:ByteArray)=body.size>=8 && ByteBuffer.wrap(body).let {
        it.getShort(0).toInt()==0x1200 && (it.getShort(6).toInt() and 65535)==SEARCH
    }

    fun query(body:ByteArray):Query {
        require(matches(body) && body.size==31)
        val b=ByteBuffer.wrap(body)
        val character=b.getInt(2)
        val base=b.getShort(8).toInt() and 65535
        val ticket=b.getInt(10)
        require(character>0 && base>0 && ticket>0 && b.getShort(29).toInt()==1 &&
            (14..28).all {body[it]==0.toByte()})
        return Query(character,base,ticket)
    }

    fun responses(q:Query,view:View?):List<ByteArray> {
        if(view==null)return listOf(record(q,0,0,0,0,0))
        require(view.observedAt in 0..0xffffffffL && view.treasury>=0 && view.approval in 0..100 && view.peace in 0..100)
        val report=record(q,1,view.observedAt,view.treasury,view.approval,view.peace)
        val strength=view.garrisonStrength ?: return listOf(report)
        require(strength>=0)
        // Old DLLs already consume unknown statuses without displaying them.
        return listOf(report,record(q,2,view.observedAt,strength,0,0))
    }

    private fun record(q:Query,status:Int,observedAt:Long,value:Long,approval:Int,peace:Int):ByteArray {
        val b=ByteBuffer.allocate(29)
        b.putInt(q.character).putShort(SEARCH.toShort()).putShort(q.base.toShort()).putInt(q.ticket)
            .put(status.toByte()).putInt(observedAt.toInt()).putLong(value).put(approval.toByte()).put(peace.toByte())
            .putShort(1)
        return LoginMessages.sessionMessage(0x1200,b.array())
    }
}
