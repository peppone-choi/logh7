package org.logh7.persistence

import org.logh7.engine.TacticalSupportAction
import org.logh7.engine.TacticalSupportOrder
import java.util.Base64

/** Bounded canonical URL-base64 avoids the active-unit record delimiters. Author: Peppone Choi. */
internal object TacticalSupportCodec {
    fun encode(value:TacticalSupportOrder):String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        listOf(value.action.name,value.character,value.target,value.grid,value.acceptedAt,value.readyAt)
            .joinToString("|").toByteArray(Charsets.UTF_8))
    fun decode(text:String):TacticalSupportOrder {
        require(text.length in 1..200)
        val parts=Base64.getUrlDecoder().decode(text).toString(Charsets.UTF_8).split('|')
        require(parts.size==6)
        return TacticalSupportOrder(TacticalSupportAction.valueOf(parts[0]),parts[1].toInt(),parts[2].toInt(),
            parts[3].toInt(),parts[4].toLong(),parts[5].toLong()).also {require(encode(it)==text)}
    }
}
