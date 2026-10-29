package org.logh7.protocol

import java.nio.ByteBuffer

/** Social revival extension 7f10, speech only. Original 1200 envelope, no invented opcode. Author: 최병호. */
object SocialMessages {
    const val SEARCH=0x7f10
    data class Query(val character:Int,val phase:Int,val ticket:Int,val raw:ByteArray)
    data class View(val ticket:Int,val base:Int,val influence:Int,val approval:Int,val wait:Long,
        val last:Int=0,val at:Long=0,val lastBase:Int=0,val influenceDelta:Int=0,val approvalDelta:Int=0)
    fun matches(body:ByteArray)=body.size>=8 && ByteBuffer.wrap(body).let {it.getShort(0).toInt()==0x1200 &&
        (it.getShort(6).toInt() and 65535)==SEARCH}
    fun query(body:ByteArray):Query {
        require(matches(body) && body.size==31);val b=ByteBuffer.wrap(body)
        val character=b.getInt(2);val phase=b.get(18).toInt() and 255;val ticket=b.getInt(14)
        require(character>0 && b.getShort(8).toInt()==14 && b.getShort(10).toInt()==0 && b.getShort(12).toInt()==0 &&
            phase in 0..1 && (if(phase==0)ticket==0 else ticket>0) && b.getInt(19)==0 && b.getShort(23).toInt()==0 &&
            b.getInt(25)==0 && (b.getShort(29).toInt() and 65535)>0)
        return Query(character,phase,ticket,body.copyOfRange(2,31))
    }
    private fun record(q:Query,status:Int,flag:Int,target:Int,time:Long,value:Int,base:Int):ByteArray {
        require(status in 0..65535 && target>=0 && time in 0..0xffffffffL && value in 0..65535 && base in 0..65535)
        val payload=q.raw.copyOf();val b=ByteBuffer.wrap(payload)
        b.putShort(10,status.toShort());b.putInt(12,target);b.put(16,flag.toByte());b.putInt(17,time.toInt())
        b.putShort(21,value.toShort());b.putInt(23,base)
        return LoginMessages.sessionMessage(0x1200,payload)
    }
    fun response(q:Query,status:Int,changed:Boolean,view:View?):List<ByteArray> {
        val v=view ?: View(0,0,0,0,0)
        require(v.influence in 0..100 && v.approval in 0..100 && v.influenceDelta in -1..1 && v.approvalDelta in -2..2)
        return listOf(record(q,status,10,v.ticket,v.wait,v.influence*128+v.approval,v.base),
            record(q,status,if(changed)12 else 11,v.last,v.at,if(v.last>0)(v.influenceDelta+128)*256+v.approvalDelta+128 else 0,v.lastBase),
            LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
}
