package org.logh7.protocol

import java.nio.ByteBuffer

/** CD writer 00464f70: clock, character, five AI switches (operation is not sent).
 * Order follows native character offsets 5f..63. Author: 최병호. */
object OfflineDirectionMessages {
    data class Settings(val character:Int,val switches:List<Int>) {
        init {require(character>0 && switches.size==5 && switches.all {it in 0..1})}
        val mask get()=switches.withIndex().sumOf {(i,v)->v shl i}
    }
    fun settings(message:ByteArray):Settings {
        require(message.size==15)
        val input=ByteBuffer.wrap(message)
        require(input.short.toInt()==0x0f18)
        input.int
        return Settings(input.int,List(5){input.get().toInt() and 255})
    }
    fun reply(settings:Settings,seconds:Long,accepted:Boolean=true):ByteArray {
        require(seconds in 0..0xffffffffL)
        return LoginMessages.sessionMessage(0x0f18,ByteBuffer.allocate(13).putInt(seconds.toInt())
            .putInt(settings.character).put(settings.switches.map {it.toByte()}.toByteArray()).array())
            .also {if(!accepted)ByteBuffer.wrap(it).putInt(0,1)}
    }
}
