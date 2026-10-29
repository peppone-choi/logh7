package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 035a reader 004301d0, formatter 00433250, handler 004beb30.
 * Field widths describe the wire, not the padded 0x434-byte native object.
 * This codec does not infer victory grades or award amounts. */
object SessionEndingMessages {
    data class Evaluation(val previousEvaluation:Long,val finalEvaluation:Long,
        val previousFame:Long,val finalFame:Long,val friendship:Int)
    data class Statistics(val planets:Int,val fortresses:Int,val population:Long,val ships:Long,val troops:Long)
    data class PowerEnding(val leader:String,val leaderCard:Int,val winType:Int,
        val populationOccupied:Int,val hugePotential:Int,val coupEliminated:Int,val empirePlace:Int,
        val statistics:Statistics)
    data class PowerResult(val power:Int,val startYear:Long,val characters:Long,val bases:Long,
        val ending:PowerEnding?)
    data class Session(val id:Int,val state:Int,val name:String,val beginDay:String,val gameSeconds:Long,
        val powers:List<PowerResult>,val ending:Statistics?)

    fun ended(character:CharacterMessages.Generate,evaluation:Evaluation,session:Session,
        pcp:Int,mcp:Int,spot:Int=0,spotOwner:Int=0,returnBase:Int=0,
        offices:List<BootstrapMessages.OfficeCard> = emptyList()):ByteArray {
        require(session.powers.map {it.power}.toSet()==setOf(2,3) && session.powers.size==2)
        val prefix=BootstrapMessages.character(character,pcp,mcp,spot,spotOwner,returnBase,offices)
        val out=ByteBuffer.allocate(prefix.size+1024)
        out.put(prefix,6,prefix.size-6)
        fun u8(value:Int) {require(value in 0..255);out.put(value.toByte())}
        fun u16(value:Int) {require(value in 0..65535);out.putShort(value.toShort())}
        fun u32(value:Long) {require(value in 0..0xffffffffL);out.putInt(value.toInt())}
        fun string(value:String,maximum:Int) {
            require(value.length<=maximum && '\u0000' !in value)
            if(value.isEmpty())u8(0) else {
                u8(value.length+1)
                (value+'\u0000').forEach(out::putChar)
            }
        }
        fun statistics(value:Statistics) {
            u16(value.planets);u16(value.fortresses);u32(value.population);u32(value.ships);u32(value.troops)
        }
        u32(evaluation.previousEvaluation);u32(evaluation.finalEvaluation)
        u32(evaluation.previousFame);u32(evaluation.finalFame);u8(evaluation.friendship)
        u16(session.id);u8(session.state);string(session.name,12);string(session.beginDay,64);u32(session.gameSeconds)
        // Exactly two regular-power records, no count or native alignment bytes.
        session.powers.sortedBy {it.power}.forEach {power->
            u8(power.power);u32(power.startYear);u32(power.characters);u32(power.bases)
            u8(if(power.ending==null)0 else 1)
            power.ending?.let {
                string(it.leader,12);u16(it.leaderCard);u8(it.winType)
                u8(it.populationOccupied);u8(it.hugePotential);u8(it.coupEliminated);u8(it.empirePlace)
                statistics(it.statistics)
            }
        }
        u8(if(session.ending==null)0 else 1)
        session.ending?.let(::statistics)
        return LoginMessages.sessionMessage(0x035a,out.array().copyOf(out.position()))
    }
}
