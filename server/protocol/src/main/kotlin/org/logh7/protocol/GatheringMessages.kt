package org.logh7.protocol

import java.nio.ByteBuffer

/** 会談(12)/談話(13) revival extension 7f13 in the original 1200 envelope (same 31-byte shape as 7f12).
 * value 0 invites [target], 1 accepts and 2 declines the invitation from host [target]. Author: Peppone Choi. */
object GatheringMessages {
    const val SEARCH=0x7f13
    data class Query(val character:Int,val command:Int,val phase:Int,val ticket:Int,val value:Int,val target:Int,val raw:ByteArray)
    /** [grade] 0 none, 1 great, 2 success, 3 failure; [invite] the open invitation's host (0 none) and its remaining seconds. */
    data class View(val ticket:Int,val target:Int,val friendship:Int,val wait:Long,val invite:Int,val inviteWait:Long,
        val grade:Int,val hostInfluence:Int,val guestInfluence:Int,val friendship_delta:Int)
    fun matches(body:ByteArray)=body.size>=8 && ByteBuffer.wrap(body).let {it.getShort(0).toInt()==0x1200 &&
        (it.getShort(6).toInt() and 65535)==SEARCH}
    fun query(body:ByteArray):Query {
        require(matches(body) && body.size==31);val b=ByteBuffer.wrap(body)
        val character=b.getInt(2);val command=b.getShort(8).toInt();val value=b.getShort(12).toInt()
        val phase=b.get(18).toInt() and 255;val ticket=b.getInt(14);val target=b.getInt(25)
        require(character>0 && command in setOf(12,13) && b.getShort(10).toInt()==0 && value in 0..2 &&
            phase in 0..1 && (if(phase==0)ticket==0 else ticket>0) && b.getInt(19)==0 && b.getShort(23).toInt()==0 &&
            target>0 && target!=character && (b.getShort(29).toInt() and 65535)>0)
        return Query(character,command,phase,ticket,value,target,body.copyOfRange(2,31))
    }
    private fun record(q:Query,status:Int,flag:Int,target:Int,time:Long,value:Int,base:Int):ByteArray {
        require(status in 0..65535 && target>=0 && time in 0..0xffffffffL && value in 0..65535)
        val payload=q.raw.copyOf();val b=ByteBuffer.wrap(payload)
        b.putShort(10,status.toShort());b.putInt(12,target);b.put(16,flag.toByte());b.putInt(17,time.toInt())
        b.putShort(21,value.toShort());b.putInt(23,base)
        return LoginMessages.sessionMessage(0x1200,payload)
    }
    /** Deltas travel biased by 128 in one word: host<<16 | guest<<8 | friendship. */
    fun packDeltas(host:Int,guest:Int,friendship:Int):Int {
        require(listOf(host,guest,friendship).all {it in -128..127});return ((host+128) shl 16) or ((guest+128) shl 8) or (friendship+128)
    }
    fun response(q:Query,status:Int,changed:Boolean,view:View?):List<ByteArray> {
        val v=view ?: View(0,q.target,0,0,0,0,0,0,0,0)
        require(v.friendship in 0..100 && v.grade in 0..3 && v.invite>=0)
        return listOf(record(q,status,10,v.ticket,v.wait,v.friendship,v.target),
            record(q,status,if(changed)12 else 11,v.invite,v.inviteWait,v.grade,packDeltas(v.hostInfluence,v.guestInfluence,v.friendship_delta)),
            LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
    /** Pushed to an invited guest: flag 13, command, host id and the remaining seconds. */
    fun invited(guest:Int,command:Int,host:Int,wait:Long):ByteArray {
        require(guest>0 && host>0 && command in setOf(12,13) && wait in 0..0xffffffffL)
        val b=ByteBuffer.allocate(29);b.putInt(guest);b.putShort(SEARCH.toShort());b.putShort(command.toShort())
        b.putShort(0);b.putShort(0);b.putInt(0);b.put(13);b.putInt(wait.toInt());b.putShort(0);b.putInt(host);b.putShort(1)
        return LoginMessages.sessionMessage(0x1200,b.array())
    }
}
