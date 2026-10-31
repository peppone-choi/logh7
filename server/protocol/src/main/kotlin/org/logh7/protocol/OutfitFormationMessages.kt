package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Revival formation editor; original 0903/0906 layouts remain unknown.
 * Uses the recovered simple-directory envelope and two native row shorts.
 * No client-provided stock, cost or authority is trusted. Author: Peppone Choi. */
object OutfitFormationMessages {
    const val SEARCH=0x7f01
    data class Query(val character:Int,val command:Int,val card:Int,val kind:Int,val phase:Int,
        val target:Int,val unit:Int,val amount:Int,val base:Int,val raw:ByteArray)
    data class Row(val code:Int,val name:String,val available:Int,val selected:Int=0)
    fun matches(message:ByteArray)=message.size>=8 && ByteBuffer.wrap(message).let {
        it.getShort(0).toInt()==0x1200 && (it.getShort(6).toInt() and 65535)==SEARCH }
    fun query(message:ByteArray):Query {
        require(matches(message) && message.size==31)
        val b=ByteBuffer.wrap(message)
        val character=b.getInt(2);val command=b.getShort(8).toInt() and 65535
        val card=b.getShort(10).toInt() and 65535;val kind=b.getShort(12).toInt() and 65535
        val target=b.getInt(14);val phase=message[18].toInt() and 255
        val unit=b.getInt(19);val amount=b.getShort(23).toInt() and 65535;val base=b.getInt(25)
        require(character>0 && command in 25..26 && card in setOf(24,25,164,152) && kind in 0..3 && base in 0..65535 && target>=0)
        if(command==26)require(phase in setOf(0,6) && (if(phase==0)target==0 else target>0) && unit==0 && amount==0)
        else {
            require(phase in 0..5 && if(phase==0)target==0 else target>0)
            when(phase) {
                1 -> { // Encoded ship/ground stack, including grade; availability is checked by the actor.
                    require(unit>0 && ((unit-1)/4 in 0..199 || (unit-1)/4 in 1000..1015))
                }
                2 -> require(unit>=0 && amount==0)
                else -> require(unit==0 && amount==0)
            }
        }
        return Query(character,command,card,kind,phase,target,unit,amount,base,message.copyOfRange(2,31))
    }
    fun response(q:Query,status:Int,changed:Boolean,target:Int,base:Int,supplies:Int,crew:Int,
        rows:List<Row>?=null):List<ByteArray> {
        require(status in 0..65535 && target>=0 && base>=0 && supplies>=0 && crew in 0..65535)
        require(rows==null || rows.size<=200)
        val payload=q.raw.copyOf();val b=ByteBuffer.wrap(payload)
        b.putShort(10,status.toShort());b.putInt(12,target)
        payload[16]=(if(rows!=null && status==0)1 else if(changed)2 else 3).toByte()
        b.putInt(17,supplies);b.putShort(21,crew.toShort());b.putInt(23,base)
        val messages=mutableListOf(LoginMessages.sessionMessage(0x1200,payload))
        if(rows!=null && status==0) {
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).use {out ->
                out.writeByte(rows.size)
                for(row in rows) {
                    require(row.code>0 && row.name.length<=12 && '\u0000' !in row.name &&
                        row.available in 0..65535 && row.selected in 0..65535)
                    out.writeInt(row.code);out.writeByte(row.name.length+1)
                    (row.name+'\u0000').forEach {out.writeChar(it.code)}
                    out.writeShort(row.available);out.writeShort(row.selected)
                    out.writeByte(0);out.writeByte(0);out.writeByte(0);out.writeInt(0);out.writeInt(0)
                }
            }
            messages+=LoginMessages.sessionMessage(0x1202,bytes.toByteArray())
        }
        messages+=LoginMessages.sessionMessage(0x1201,byteArrayOf())
        return messages
    }
}
