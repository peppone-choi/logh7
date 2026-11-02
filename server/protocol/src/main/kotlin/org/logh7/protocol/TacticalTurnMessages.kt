package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 00493030/00493c70 writers and 0049b040/0049c020 readers. Author: Peppone Choi. */
object TacticalTurnMessages {
    /** 004bf970: a committed reverse uses the native in-place rotation executor. */
    fun turned(seconds:Long,unit:Int,direction:Float):ByteArray {
        require(seconds in 0..0xffffffffL && unit>0 && direction.isFinite())
        return LoginMessages.sessionMessage(0x0424,ByteBuffer.allocate(12).putInt(seconds.toInt()).putInt(unit).putFloat(direction).array())
    }
    data class Unit(val id:Int,val direction:Float) {
        init { require(id>0 && direction.isFinite()) }
    }
    /** Reverse's trailing byte is preserved without assigning an unverified meaning. */
    data class Command(val character:Int,val units:List<Unit>,val direction:Float?,val reverseByte:Int=0) {
        init {
            require(character>0 && units.size in 1..32 && units.map {it.id}.distinct().size==units.size)
            require(direction==null || direction.isFinite())
            require(reverseByte in 0..255 && (direction==null || reverseByte==0))
        }
        val reverse get()=direction==null
    }
    /** Client clocks/headings are decoded but must not become authoritative server state. */
    fun command(message:ByteArray):Command {
        require(message.size in 24..275)
        val input=ByteBuffer.wrap(message)
        val opcode=input.short.toInt() and 65535
        require(opcode==0x0401 || opcode==0x0403)
        input.int;input.int
        val character=input.int
        val count=input.get().toInt() and 255
        require(count in 1..32 && input.remaining()==count*8+if(opcode==0x0401)4 else 1)
        val units=List(count) {Unit(input.int,input.float)}
        return if(opcode==0x0401)Command(character,units,input.float)
            else Command(character,units,null,input.get().toInt() and 255)
    }
    fun reply(command:Command,seconds:Long,waitSeconds:Long,accepted:Boolean=true):ByteArray {
        require(seconds in 0..0xffffffffL && waitSeconds in 0..0xffffffffL-seconds)
        val out=ByteBuffer.allocate(13+command.units.size*8+if(command.reverse)1 else 4)
            .putInt(seconds.toInt()).putInt(waitSeconds.toInt()).putInt(command.character).put(command.units.size.toByte())
        command.units.forEach {out.putInt(it.id).putFloat(it.direction)}
        if(command.reverse)out.put(command.reverseByte.toByte()) else out.putFloat(checkNotNull(command.direction))
        return LoginMessages.sessionMessage(if(command.reverse)0x0403 else 0x0401,out.array()).also {
            if(!accepted)ByteBuffer.wrap(it).putInt(0,1)
        }
    }
}
