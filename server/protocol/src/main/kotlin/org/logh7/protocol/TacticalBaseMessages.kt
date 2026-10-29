package org.logh7.protocol

import java.nio.ByteBuffer

/** Native fortress/base notifications; separate layouts, no guessed shared clock. Author: 최병호. */
object TacticalBaseMessages {
    /** Successful 041e reader: time, zero wait, character, base. Also clears a cancelled charge. */
    fun stopped(seconds:Long,character:Int,base:Int):ByteArray {
        require(seconds in 0..0xffffffffL && character>0 && base in 1..65535)
        return LoginMessages.sessionMessage(0x41e,ByteBuffer.allocate(16).putInt(seconds.toInt()).putInt(0)
            .putInt(character).putInt(base).array())
    }
    /** Completion command: the compatibility ACK wrapper holds the beam during a future wait. */
    fun fired(seconds:Long,character:Int,base:Int,direction:Float):ByteArray {
        require(seconds in 0..0xffffffffL && character>0 && base in 1..65535 && direction.isFinite())
        return LoginMessages.sessionMessage(0x419,ByteBuffer.allocate(20).putInt(seconds.toInt()).putInt(0)
            .putInt(character).putInt(base).putFloat(direction).array())
    }
    /** Reader 004a8c10: time, base, u8 count, IDs. Native 004bfb70 destroys every listed ship.
     * Only actual complete losses belong here; partial losses use the explicit base-source hit. */
    fun destroyed(seconds:Long,base:Int,units:List<Int>):ByteArray {
        require(seconds in 0..0xffffffffL && base in 1..65535 && units.size in 1..32 &&
            units.all {it>0} && units.distinct().size==units.size)
        val out=ByteBuffer.allocate(9+units.size*4).putInt(seconds.toInt()).putInt(base).put(units.size.toByte())
        units.forEach(out::putInt)
        return LoginMessages.sessionMessage(0x436,out.array())
    }
    /** 0438 uses the four-u32 reader 0043e9e0. Text formatter 004a9410 names these balances.
     * target 0 advertises the opened base pool without fabricating a transfer. */
    fun emergency(base:Int,target:Int,available:Int,targetSupplies:Int):ByteArray {
        require(base in 1..65535 && target>=0 && available>=0 && targetSupplies>=0 && (target>0 || targetSupplies==0))
        return LoginMessages.sessionMessage(0x438,ByteBuffer.allocate(16).putInt(base).putInt(target)
            .putInt(available).putInt(targetSupplies).array())
    }
}
