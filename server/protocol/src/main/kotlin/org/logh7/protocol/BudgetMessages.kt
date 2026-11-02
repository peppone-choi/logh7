package org.logh7.protocol

import java.nio.ByteBuffer

/** Explicit 7f02 extension in the existing native 1200 envelope, not a recovered opcode. Author: Peppone Choi. */
object BudgetMessages {
    const val SEARCH=0x7f02
    data class Query(val character:Int,val command:Int,val card:Int,val phase:Int,val base:Int,val rate:Int,val quote:Int,val raw:ByteArray)
    data class View(val base:Int,val national:Long,val local:Long,val remittance:Int,
        val quote:Int=0,val amount:Long=0,val phase:Int=0,val rate:Int=0)
    fun matches(body:ByteArray)=body.size>=8 && ByteBuffer.wrap(body).let {it.getShort(0).toInt()==0x1200 &&
        (it.getShort(6).toInt() and 65535)==SEARCH}
    fun query(body:ByteArray):Query {
        require(matches(body) && body.size==31);val b=ByteBuffer.wrap(body)
        val character=b.getInt(2);val command=b.getShort(8).toInt() and 65535;val card=b.getShort(10).toInt() and 65535
        val phase=b.get(18).toInt() and 255;val base=b.getInt(25);val rate=b.getShort(12).toInt() and 65535;val quote=b.getInt(14)
        require(character>0 && command in setOf(16,18) && card in setOf(8,136) && phase in 0..3 && base in 0..65535 &&
            rate in 0..100 && b.getInt(19)==0 && b.getShort(23).toInt()==0 && (b.getShort(29).toInt() and 65535)>0 &&
            (if(phase in 0..1)quote==0 else quote>0) && (phase==0 || base>0) && (phase!=0 || rate==0))
        return Query(character,command,card,phase,base,rate,quote,body.copyOfRange(2,31))
    }
    private fun record(q:Query,status:Int,flag:Int,value:Long,extra:Int,base:Int):ByteArray {
        require(status in 0..65535 && value>=0 && extra in 0..65535 && base>=0)
        val payload=q.raw.copyOf();val b=ByteBuffer.wrap(payload)
        b.putShort(10,status.toShort());b.put(16,flag.toByte());b.putInt(12,(value ushr 32).toInt());b.putInt(17,value.toInt())
        b.putShort(21,extra.toShort());b.putInt(23,base)
        return LoginMessages.sessionMessage(0x1200,payload)
    }
    fun response(q:Query,status:Int,view:View?):List<ByteArray> {
        val v=view ?: View(q.base,0,0,0)
        require(v.base in 0..65535 && v.remittance in 0..100 && v.quote>=0 && v.phase in 0..3 && v.rate in 0..100)
        return listOf(record(q,status,10,v.national,v.remittance,v.base),record(q,status,11,v.local,0,v.base),
            record(q,status,12,v.amount,v.phase+v.rate*4,v.quote),LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
    /** Private finance observer snapshots do not replace a quote or acknowledge a pending request. */
    fun update(character:Int,card:Int,view:View):List<ByteArray> {
        val raw=ByteBuffer.allocate(29).putInt(character).putShort(SEARCH.toShort()).putShort(16).putShort(card.toShort()).array()
        val q=Query(character,16,card,0,view.base,0,0,raw)
        return listOf(record(q,0,14,view.national,view.remittance,view.base),record(q,0,15,view.local,0,view.base))
    }
}
