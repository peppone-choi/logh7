package org.logh7.protocol

import java.nio.ByteBuffer

/** 会見 revival extension 7f12 (command 87) in the original 1200 envelope. Author: Peppone Choi. */
object MeetingMessages {
    const val SEARCH=0x7f12
    data class Query(val character:Int,val phase:Int,val ticket:Int,val target:Int,val raw:ByteArray)
    /** [friendship] 0..100; [gained] the change of the last commit. */
    data class View(val ticket:Int,val target:Int,val friendship:Int,val wait:Long,val gained:Int)
    fun matches(body:ByteArray)=body.size>=8 && ByteBuffer.wrap(body).let {it.getShort(0).toInt()==0x1200 &&
        (it.getShort(6).toInt() and 65535)==SEARCH}
    fun query(body:ByteArray):Query {
        require(matches(body) && body.size==31);val b=ByteBuffer.wrap(body)
        val character=b.getInt(2);val phase=b.get(18).toInt() and 255;val ticket=b.getInt(14);val target=b.getInt(25)
        require(character>0 && b.getShort(8).toInt()==87 && b.getShort(10).toInt()==0 && b.getShort(12).toInt()==0 &&
            phase in 0..1 && (if(phase==0)ticket==0 else ticket>0) && b.getInt(19)==0 && b.getShort(23).toInt()==0 &&
            target>0 && target!=character && (b.getShort(29).toInt() and 65535)>0)
        return Query(character,phase,ticket,target,body.copyOfRange(2,31))
    }
    private fun record(q:Query,status:Int,flag:Int,target:Int,time:Long,value:Int,base:Int):ByteArray {
        require(status in 0..65535 && target>=0 && time in 0..0xffffffffL && value in 0..65535 && base>=0)
        val payload=q.raw.copyOf();val b=ByteBuffer.wrap(payload)
        b.putShort(10,status.toShort());b.putInt(12,target);b.put(16,flag.toByte());b.putInt(17,time.toInt())
        b.putShort(21,value.toShort());b.putInt(23,base)
        return LoginMessages.sessionMessage(0x1200,payload)
    }
    fun response(q:Query,status:Int,changed:Boolean,view:View?):List<ByteArray> {
        val v=view ?: View(0,q.target,0,0,0)
        require(v.friendship in 0..100 && v.gained in 0..100)
        return listOf(record(q,status,10,v.ticket,v.wait,v.friendship,v.target),
            record(q,status,if(changed)12 else 11,v.target,v.wait,v.friendship,v.gained),
            LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
}
