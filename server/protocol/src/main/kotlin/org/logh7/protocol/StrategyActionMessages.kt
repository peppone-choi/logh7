package org.logh7.protocol

import java.nio.ByteBuffer

/** Explicit revival extension of the recovered 0055b3a0 simple-directory codec.
 * Search 0x7f00 is not an original server command. Its native factory is absent for
 * training/suppression/volunteering; the compatibility DLL provides the missing UI.
 * The envelope retains the original 31-byte shape and carries no trusted CP/time/rank.
 * Author: Peppone Choi. */
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
        val market=command==96
        val civic=command in setOf(52,54,55,56)
        val transport=command in setOf(28,29,65)
        // 命令/提案 send (design-proposals-orders.md): card=own card, target=recipient, value=native command, argument=recipient card.
        val proposal=command==98
        // U-25 (design-arrest-judgment.md): 処断 19 value=verdict, 逮捕許可 69 value=charge, 執行命令 70 base=listed person
        // value=0 grant/1 revoke, 逮捕命令 71 (card 0 for a right holder). Previews: 70 value 0 lists the arrest list, 1 grantees.
        // Phase 2: 一斉捜索 68 and 襲撃 73 name only the card and the target.
        val justice=command in setOf(19,68,69,70,71,73,74)
        val argument=b.getInt(19);val amount=b.getShort(23).toInt() and 65535
        require(character>0 && command in setOf(0,1,2,3,4,8,9,10,11,12,13,19,22,23,24,28,29,65,68,69,70,71,73,74,98,46,47,48,49,50,51,52,53,54,55,56,61,84,85,87,96) && card in 0..260 && target>=0 &&
            message[18] in byteArrayOf(0,1))
        require(!preview || personnel || command==24 || market || command in setOf(10,11,12,13,19,29,65,68,69,70,71,73,74,87,98))
        // 会見 87 uses this directory only to list people in the requester's spot; commits travel in 7f12.
        // 会談 12/談話 13 list same-base people of the same side; commits travel in 7f13.
        if(command in setOf(10,11,12,13,87))require(preview && card==0)
        if(!operation && !market && !transport && !proposal)require(argument==0 && amount==0)
        if(civic)require(!preview && value in 0..2 && base==0 && argument==0 && amount==0 &&
            card in setOf(39,44,47,50,59,175,180,183,186,195) &&
            (if(command==55)target==0 && card in setOf(59,195) else value!=0 || target>0))
        else if(personnel)require((preview || target>0) && (command in setOf(3,4) || value==0) &&
            (if(command in setOf(8,9))base in 0..65535 else base==0))
        else if(operation)require((preview || target in 0..4999) && if(command==22)value in 0..2 && amount>0 && argument in 0..127 && base==0
            else value==0 && amount==0 && argument==0 && (if(command==24)!preview && base>0 || preview && base==0 else base==0))
        // Market arguments: 0 buy flagship, 1 offer, 2 accept, 3 cancel, 4 buy guard ship
        // (value = GuardShips index, 0..25), 5 disband the guard unit (U-13).
        else if(market)require(card==0 && base==0 && amount==0 && argument in 0..5 &&
            (if(argument==0)value in 0..3 && (preview || target==0) else if(argument==4)value in 0..25 && (preview || target==0)
             else if(argument==5)!preview && value==0 && target==0 else value==0 &&
                (if(argument in 1..2)preview || target>0 else !preview && target==0)))
        // card/argument 0 lets the server pick the same-institution pair; a preview lists the targets.
        // Dismissal (value 0x0708) also names the third person (base) and that person's card (amount).
        // Previews: value 0 lists recipients; value 0x0708 with base = the recipient lists the seats it may dismiss.
        // Appointment (0x0707): base = the seat and amount = the card; its preview lists the recipient's vacancies.
        else if(proposal)require(card in 0..260 && argument in 0..260 && amount in 0..260 && base>=0 &&
            (if(preview)card==0 && argument==0 && amount==0 && (value==0 && base==0 || value in setOf(0x0707,0x0708) && base>0)
             else target>0 && value in 1..0xffff && (if(value in setOf(0x0707,0x0708))base>0 && amount>0 else base==0 && amount==0)))
        else if(justice)require(card in 0..260 && argument==0 && amount==0 && (if(preview)value in 0..1 && (base==0 || command==70 && value==1 && base>0)
             else target>0 && when(command) {69 -> card>0 && value in 1..7 && base==0;70 -> card>0 && value in 0..1 && base>0
                 71 -> value==0 && base==0;68,73,74 -> card>0 && value==0 && base==0;else -> card>0 && value in 1..3 && base==0}))
        // U-15 transport: 28 base=source, target=destination, amount=supplies, value=unit kind (0xffff none),
        // argument=unit count; 29 target=package; 65 argument=transport outfit, target=package. Previews list packages.
        else if(transport)require(if(command==65)card in setOf(44,180) && argument>0 && value==0 && base==0 && amount==0 &&
                (preview || target>0)
            else card in setOf(24,25,150) && (if(command==28)!preview && base in 1..65535 && target in 1..65535 &&
                (value==0xffff) == (argument==0) && argument in 0..65535 && (value==0xffff || value in 0..199 || value in 1000..1015)
                else value==0 && base==0 && argument==0 && amount==0 && (preview || target>0)))
        else if(command==61)require(value==0 && base==0 && card in setOf(39,44,47,50,59,175,180,183,186,195) &&
            (if(card in setOf(59,195))target==0 else target>0))
        else require(value==0 && base==0 && card in setOf(0,59,195))
        val listed=personnel || operation || market || transport || proposal || justice || command in setOf(10,11,12,13,87)
        return Query(character,command,card,if(command==65)argument else if(listed)0 else target,message.copyOfRange(2,message.size),
            if(listed)target else 0,base,value,preview,amount,argument)
    }
    /** Status lives in the otherwise unused rank field; no original success ACK is forged. */
    data class CivicStatus(val phase:Int,val outfit:Int,val base:Int,val readyAt:Long,val effect:Int)
    fun response(q:Query,status:Int,changed:Boolean?=null,civic:CivicStatus?=null,outcome:Int=0,found:Int=0):List<ByteArray> {
        require(status in 0..65535 && outcome in 0..3 && found>=0)
        val payload=q.raw.copyOf();ByteBuffer.wrap(payload).putShort(10,status.toShort())
        // 逮捕命令 result (U-25) in the argument word: 1 arrested, 2 evaded, 3 failed.
        if(outcome>0)ByteBuffer.wrap(payload).putInt(17,outcome)
        // 一斉捜索's found spot in the base word.
        if(found>0)ByteBuffer.wrap(payload).putInt(23,found)
        if(!q.preview && changed!=null)payload[16]=(if(changed)2 else 3).toByte()
        if(civic!=null && status==0) {
            require(q.command in setOf(52,54,55,56) && civic.phase in -1..5 && civic.outfit>=0 && civic.base in 0..65535 &&
                civic.readyAt in 0..0xffffffffL && civic.effect in 0..65535)
            val buffer=ByteBuffer.wrap(payload)
            payload[16]=(if(civic.phase<0)3 else 10+civic.phase).toByte()
            buffer.putInt(12,civic.outfit);buffer.putInt(17,civic.readyAt.toInt());buffer.putShort(21,civic.effect.toShort())
            buffer.putInt(23,civic.base)
        }
        return listOf(LoginMessages.sessionMessage(0x1200,payload),LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
    fun candidates(q:Query,characters:List<AppointmentDirectoryMessages.Person>):List<ByteArray> {
        require(q.preview)
        return AppointmentDirectoryMessages.people(AppointmentDirectoryMessages.Query(q.character,SEARCH,q.command,q.card,q.base,q.raw),characters)
    }
    data class Selection(val id:Int,val name:String,val rank:Int=0)
    /** Same native 0055ba80 rows, with explicit selection identities instead of forged characters. */
    fun selections(q:Query,rows:List<Selection>):List<ByteArray> {
        require(q.preview && q.command in setOf(10,11,12,13,19,24,29,65,68,69,70,71,73,74,87,96,98) && rows.size<=200)
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
