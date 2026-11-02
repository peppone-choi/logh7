package org.logh7.protocol

import java.nio.ByteBuffer

/** Academy revival extension 7f11 (受講 93, 兵棋演習 94, 講義 27) in the original 1200 envelope. Author: Peppone Choi. */
object AcademyMessages {
    const val SEARCH=0x7f11
    data class Query(val character:Int,val command:Int,val card:Int,val phase:Int,val ticket:Int,val argument:Int,val raw:ByteArray)
    /** [content] -1 when no lecture applies; [value] 0..100, [experience] 0..99. */
    data class View(val ticket:Int,val base:Int,val content:Int,val lectureRemaining:Long,val usesLeft:Int,
        val ability:Int,val value:Int,val experience:Int,val wait:Long,val gained:Int)
    fun matches(body:ByteArray)=body.size>=8 && ByteBuffer.wrap(body).let {it.getShort(0).toInt()==0x1200 &&
        (it.getShort(6).toInt() and 65535)==SEARCH}
    fun query(body:ByteArray):Query {
        require(matches(body) && body.size==31);val b=ByteBuffer.wrap(body)
        val character=b.getInt(2);val command=b.getShort(8).toInt() and 65535;val card=b.getShort(10).toInt() and 65535
        val phase=b.get(18).toInt() and 255;val ticket=b.getInt(14);val argument=b.getShort(23).toInt() and 65535
        require(character>0 && command in setOf(27,93,94) && card in 0..260 && b.getShort(12).toInt()==0 &&
            phase in 0..1 && (if(phase==0)ticket==0 else ticket>0) && b.getInt(19)==0 && argument in 0..7 &&
            b.getInt(25)==0 && (b.getShort(29).toInt() and 65535)>0)
        return Query(character,command,card,phase,ticket,argument,body.copyOfRange(2,31))
    }
    private fun record(q:Query,status:Int,flag:Int,target:Int,time:Long,value:Int,base:Int):ByteArray {
        require(status in 0..65535 && target>=0 && time in 0..0xffffffffL && value in 0..65535 && base in 0..65535)
        val payload=q.raw.copyOf();val b=ByteBuffer.wrap(payload)
        b.putShort(10,status.toShort());b.putInt(12,target);b.put(16,flag.toByte());b.putInt(17,time.toInt())
        b.putShort(21,value.toShort());b.putInt(23,base)
        return LoginMessages.sessionMessage(0x1200,payload)
    }
    fun response(q:Query,status:Int,changed:Boolean,view:View?):List<ByteArray> {
        val v=view ?: View(0,0,-1,0,0,0,0,0,0,0)
        require(v.content in -1..3 && v.usesLeft in 0..255 && v.ability in 0..7 && v.value in 0..255 && v.experience in 0..99)
        return listOf(record(q,status,10,v.ticket,v.lectureRemaining,(v.content+1)*256+v.usesLeft,v.base),
            record(q,status,if(changed)12 else 11,v.ability,v.wait,v.value*128+v.experience,v.gained),
            LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
}
