package org.logh7.protocol

import java.nio.ByteBuffer

/** Readers 004065c0 (043d/e), 004aa0d0 (0440); live unit handlers 004c0bc0..004c0c40. Author: Peppone Choi. */
object TacticalStatusMessages {
    fun confusion(seconds:Long,unit:Int,confused:Boolean):ByteArray {
        require(seconds in 0..0xffffffffL && unit>0)
        return LoginMessages.sessionMessage(if(confused)0x43d else 0x43e,
            ByteBuffer.allocate(8).putInt(seconds.toInt()).putInt(unit).array())
    }
    fun morale(seconds:Long,unit:Int,value:Int):ByteArray {
        require(seconds in 0..0xffffffffL && unit>0 && value in 0..100)
        return LoginMessages.sessionMessage(0x440,ByteBuffer.allocate(9).putInt(seconds.toInt()).putInt(unit).put(value.toByte()).array())
    }
}
