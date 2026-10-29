package org.logh7.protocol

import java.nio.ByteBuffer

/** Packed command families from 00496050..0049a340 and 004b4370..004b4850.
 * Client time, wait, formation origins and ability bytes are never server authority. Author: 최병호. */
object TacticalCommandMessages {
    val opcodes=setOf(0x0407,0x0408,0x0409,0x040b,0x040d,0x040e,0x0419,0x041b,0x041c,0x041d,0x041e,0x041f,0x0420,0x0421,0x0422)
    data class Point(val x:Float,val y:Float,val z:Float=0f) {
        init {require(listOf(x,y,z).all {it.isFinite() && kotlin.math.abs(it)<=100000})}
    }
    data class Command(val opcode:Int,val character:Int,val units:List<Int> = emptyList(),val base:Int=0,
        val kind:Int=0,val targetKind:Int=0,val target:Int=0,val velocity:Float=0f,val direction:Float=0f,
        val points:List<Point> = emptyList(),val payload:List<Int>) {
        init {require(opcode in opcodes && character>0 && units.size<=32 && units.all {it>0} &&
            units.distinct().size==units.size && base>=0 && target>=0 && payload.size in 16..800 && payload.all {it in 0..255})}
    }
    fun command(message:ByteArray):Command {
        require(message.size in 18..802)
        val input=ByteBuffer.wrap(message)
        val op=input.short.toInt() and 65535;require(op in opcodes)
        input.long
        val character=input.int
        var units=emptyList<Int>();var base=0;var kind=0;var targetKind=0;var target=0
        var velocity=0f;var direction=0f;var points=emptyList<Point>()
        fun count():Int {require(input.hasRemaining());return (input.get().toInt() and 255).also {require(it in 1..32)}}
        fun ids():List<Int> {val n=count();require(input.remaining()>=4*n);return List(n){input.int}}
        when(op) {
            0x0407 -> {
                // 004b4370 carries native ability/status bytes between source and target.
                // Their values are opaque and ignored by the authority/effect evaluator.
                require(message.size in 22..38);units=listOf(input.int)
                input.position(message.size-4);target=input.int
            }
            0x0408 -> {require(input.remaining()==10);units=listOf(input.int);kind=input.get().toInt() and 255;targetKind=input.get().toInt() and 255;target=input.int}
            0x0409,0x041d,0x041e -> {require(input.remaining()==4);val id=input.int;if(op==0x0409)units=listOf(id) else base=id}
            0x040b -> {units=listOf(input.int);points=emptyList();val targets=ids();target=units.single();units=targets}
            0x040d -> {
                val n=count();require(input.remaining()==n*20+1)
                units=List(n){val id=input.int;input.float;Point(input.float,input.float,input.float);id}
                kind=input.get().toInt() and 255
            }
            0x040e -> {units=ids();require(input.remaining()==5);kind=input.get().toInt() and 255;target=input.int}
            0x0419 -> {require(input.remaining()==8);base=input.int;direction=input.float;require(direction.isFinite())}
            0x041b,0x041c -> {base=input.int;units=ids()}
            0x041f -> {require(input.remaining()>=33);base=input.int;velocity=input.float;require(velocity.isFinite() && velocity in 0f..1f)
                Point(input.float,input.float,input.float) // Ignore proposed current position.
                val n=count();require(input.remaining()==12*n);points=List(n){Point(input.float,input.float,input.float)}}
            0x0420 -> {units=ids();require(input.remaining()==4);target=input.int}
            0x0421 -> {units=ids();require(input.remaining()==6);kind=input.get().toInt() and 255;targetKind=input.get().toInt() and 255;target=input.int}
            0x0422 -> {require(input.remaining()==8);base=input.int;input.int /* native trailing control word is opaque */}
        }
        require(!input.hasRemaining())
        require(units.isNotEmpty() || base>0)
        return Command(op,character,units,base,kind,targetKind,target,velocity,direction,points,message.drop(2).map {it.toInt() and 255})
    }
    /** Preserve native opaque fields, replacing only the two server scheduling words. */
    fun reply(command:Command,seconds:Long,wait:Long,accepted:Boolean=true):ByteArray {
        require(seconds in 0..0xffffffffL && wait in 0..0xffffffffL-seconds)
        val payload=command.payload.map {it.toByte()}.toByteArray()
        ByteBuffer.wrap(payload).putInt(seconds.toInt()).putInt(wait.toInt())
        return LoginMessages.sessionMessage(command.opcode,payload).also {if(!accepted)ByteBuffer.wrap(it).putInt(0,1)}
    }
    /** Native 004a6570/004c1130. Injury status is separate from a destroyed-ship counter. */
    fun fought(seconds:Long,source:Int,target:Int,status:Int=1,injury:Int=1):ByteArray {
        require(seconds in 0..0xffffffffL && source>0 && target>0 && status in 0..255 && injury in 0..65535)
        return LoginMessages.sessionMessage(0x0427,ByteBuffer.allocate(15).putInt(seconds.toInt()).putInt(source)
            .putInt(target).put(status.toByte()).putShort(injury.toShort()).array())
    }
    /** Native 004a6790/004c0c80. Absolute damage counters and morale. */
    fun airBattle(seconds:Long,source:Int,target:Int,damaged:Int,destroyed:Int,face:Int=255,shield:Int=0,morale:Int=100):ByteArray {
        require(seconds in 0..0xffffffffL && source>0 && target>0 && damaged in 0..300 && destroyed in 0..300 &&
            (face in 0..5 || face==255) && shield in 0..65535 && morale in 0..100)
        return LoginMessages.sessionMessage(0x0428,ByteBuffer.allocate(22).putInt(seconds.toInt()).putInt(source).putInt(target)
            .put(0).put(1).putShort(damaged.toShort()).putShort(destroyed.toShort()).put(face.toByte()).putShort(shield.toShort()).put(morale.toByte()).array())
    }
    /** Native 0435 is time, base, then x/y/z. */
    fun fortressMoved(seconds:Long,base:Int,point:Point):ByteArray {
        require(seconds in 0..0xffffffffL && base>0)
        return LoginMessages.sessionMessage(0x0435,ByteBuffer.allocate(20).putInt(seconds.toInt()).putInt(base)
            .putFloat(point.x).putFloat(point.y).putFloat(point.z).array())
    }
}
