package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0049f860 (040f), 0049fd50 (0410), 004a01e0 (0411). Author: 최병호. */
object TacticalGroundMessages {
    enum class Action(val opcode:Int) { DEPLOY(0x040f), WITHDRAW(0x0410) }
    /** Skill is an opaque wire byte. Deployment eligibility belongs to the world actor. */
    data class Command(val action:Action,val character:Int,val units:List<Int>,val skill:Int=0) {
        init {
            validate(character,units)
            require(skill in 0..255 && (action==Action.DEPLOY || skill==0))
        }
    }
    /** Posture values and the target's ownership/berth rules must be resolved by the actor. */
    data class Posture(val character:Int,val units:List<Int>,val kind:Int,val base:Int) {
        init {validate(character,units);require(kind in 0..255 && base>=0)}
    }
    private fun validate(character:Int,units:List<Int>) {
        require(character>0 && units.size in 1..32 && units.all {it>0} && units.distinct().size==units.size)
    }
    private fun read(message:ByteArray,opcode:Int,suffix:Int):Pair<ByteBuffer,Pair<Int,List<Int>>> {
        require(message.size in (19+suffix)..(143+suffix))
        val input=ByteBuffer.wrap(message)
        require(input.short.toInt() and 65535==opcode)
        input.int;input.int // Proposed clock/wait never selects the server schedule.
        val character=input.int
        val count=input.get().toInt() and 255
        require(count in 1..32 && input.remaining()==4*count+suffix)
        return input to (character to List(count){input.int})
    }
    fun command(message:ByteArray):Command {
        require(message.size>=2)
        val opcode=ByteBuffer.wrap(message).short.toInt() and 65535
        val action=requireNotNull(Action.entries.singleOrNull {it.opcode==opcode})
        val (input,source)=read(message,opcode,if(action==Action.DEPLOY)1 else 0)
        return Command(action,source.first,source.second,if(action==Action.DEPLOY)input.get().toInt() and 255 else 0)
    }
    fun posture(message:ByteArray):Posture {
        val (input,source)=read(message,0x0411,5)
        return Posture(source.first,source.second,input.get().toInt() and 255,input.int)
    }
    private fun reply(opcode:Int,character:Int,units:List<Int>,tail:ByteArray,seconds:Long,wait:Long,accepted:Boolean):ByteArray {
        require(seconds in 0..0xffffffffL && wait in 0..0xffffffffL-seconds)
        val out=ByteBuffer.allocate(13+4*units.size+tail.size).putInt(seconds.toInt()).putInt(wait.toInt())
            .putInt(character).put(units.size.toByte())
        units.forEach(out::putInt);out.put(tail)
        return LoginMessages.sessionMessage(opcode,out.array()).also {if(!accepted)ByteBuffer.wrap(it).putInt(0,1)}
    }
    fun reply(command:Command,seconds:Long,wait:Long,accepted:Boolean=true)=reply(command.action.opcode,
        command.character,command.units,if(command.action==Action.DEPLOY)byteArrayOf(command.skill.toByte()) else byteArrayOf(),
        seconds,wait,accepted)
    fun reply(command:Posture,seconds:Long,wait:Long,accepted:Boolean=true)=reply(0x0411,command.character,command.units,
        ByteBuffer.allocate(5).put(command.kind.toByte()).putInt(command.base).array(),seconds,wait,accepted)

    /** 004a79b0/004a7f20: direction precedes x/y/z; the trailer is spot and spot_owner. */
    data class PostureUnit(val id:Int,val direction:Float,val x:Float,val y:Float,val z:Float) {
        init {require(id>0 && listOf(direction,x,y,z).all(Float::isFinite))}
    }
    /** Completion notification, distinct from the command's accepted/waiting response. */
    fun postureChanged(seconds:Long,kind:Int,base:Int,units:List<PostureUnit>,spot:Int,spotOwner:Int):ByteArray {
        require(seconds in 0..0xffffffffL && kind in 4..7 && base>=0 && spot>=0 && spotOwner>=0)
        require(units.size in 1..32 && units.map {it.id}.distinct().size==units.size)
        val out=ByteBuffer.allocate(18+20*units.size).putInt(seconds.toInt()).put(kind.toByte())
            .putInt(base).put(units.size.toByte())
        units.forEach {u->out.putInt(u.id).putFloat(u.direction).putFloat(u.x).putFloat(u.y).putFloat(u.z)}
        out.putInt(spot).putInt(spotOwner)
        return LoginMessages.sessionMessage(0x042f,out.array())
    }
}
