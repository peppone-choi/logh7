package org.logh7.protocol

import java.nio.ByteBuffer

/** Explicit revival extension of the recovered 0055b3a0 simple-directory codec.
 * Search 0x7f00 is not an original server command. Its native factory is absent for
 * training/suppression/volunteering; the compatibility DLL provides the missing UI.
 * The envelope retains the original 31-byte shape and carries no trusted CP/time/rank.
 * Author: 최병호. */
object StrategyActionMessages {
    const val SEARCH=0x7f00
    data class Query(val character:Int,val command:Int,val card:Int,val outfit:Int,val raw:ByteArray,
        val target:Int=0,val base:Int=0,val value:Int=0,val preview:Boolean=false,val amount:Int=0,val argument:Int=0)
    fun matches(message:ByteArray)=message.size>=8 && ByteBuffer.wrap(message).let {
        it.getShort(0).toInt()==0x1200 && (it.getShort(6).toInt() and 65535)==SEARCH }
    fun query(message:ByteArray):Query {
        require(matches(message) && message.size==31)
        val b=ByteBuffer.wrap(message)
        val character=b.getInt(2);val command=b.getShort(8).toInt() and 65535
        val card=b.getShort(10).toInt() and 65535;val target=b.getInt(14)
        val value=b.getShort(12).toInt() and 65535;val base=b.getInt(25)
        val preview=message[18]==1.toByte()
        val personnel=command in setOf(0,1,2,3,4,8,9)
        val operation=command in 22..24
        val argument=b.getInt(19);val amount=b.getShort(23).toInt() and 65535
        require(character>0 && command in setOf(0,1,2,3,4,8,9,22,23,24,48,50,53,84,85) && card in 0..260 && target>=0 &&
            message[18] in byteArrayOf(0,1))
        require(!preview || personnel || command==24)
        if(!operation)require(argument==0 && amount==0)
        if(personnel)require((preview || target>0) && (command in setOf(3,4) || value==0) &&
            (if(command in setOf(8,9))base in 0..65535 else base==0))
        else if(operation)require((preview || target in 0..4999) && if(command==22)value in 0..2 && amount>0 && argument in 0..127 && base==0
            else value==0 && amount==0 && argument==0 && (if(command==24)!preview && base>0 || preview && base==0 else base==0))
        else require(value==0 && base==0 && card in setOf(0,59,195))
        return Query(character,command,card,if(personnel || operation)0 else target,message.copyOfRange(2,message.size),
            if(personnel || operation)target else 0,base,value,preview,amount,argument)
    }
    /** Status lives in the otherwise unused rank field; no original success ACK is forged. */
    fun response(q:Query,status:Int):List<ByteArray> {
        require(status in 0..65535)
        val payload=q.raw.copyOf();ByteBuffer.wrap(payload).putShort(10,status.toShort())
        return listOf(LoginMessages.sessionMessage(0x1200,payload),LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
    fun candidates(q:Query,characters:List<AppointmentDirectoryMessages.Person>):List<ByteArray> {
        require(q.preview)
        return AppointmentDirectoryMessages.people(AppointmentDirectoryMessages.Query(q.character,SEARCH,q.command,q.card,q.base,q.raw),characters)
    }
    data class Selection(val id:Int,val name:String,val rank:Int=0)
    /** Same native 0055ba80 rows, with explicit selection identities instead of forged characters. */
    fun selections(q:Query,rows:List<Selection>):List<ByteArray> {
        require(q.preview && q.command==24 && rows.size<=200)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use {out->
            out.writeByte(rows.size)
            for(row in rows) {
                require(row.id>0 && row.name.length<=12 && '\u0000' !in row.name && row.rank in 0..20)
                out.writeInt(row.id);out.writeByte(row.name.length+1);(row.name+'\u0000').forEach {out.writeChar(it.code)}
                out.writeShort(row.rank);out.writeShort(0);out.writeByte(0);out.writeByte(0);out.writeByte(0)
                out.writeInt(0);out.writeInt(0)
            }
        }
        return listOf(LoginMessages.sessionMessage(0x1200,q.raw),LoginMessages.sessionMessage(0x1202,bytes.toByteArray()),
            LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
}
