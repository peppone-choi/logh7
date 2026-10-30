package org.logh7.persistence

import java.util.Base64
import org.logh7.engine.*

/** STOCK-16 canonical bounded autonomous state. Author: 최병호. */
internal object NpcTacticalCodec {
    fun encode(state:NpcTacticalState):String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        listOf(TacticalMotionCodec.encode(state.motion),state.nextDecisionAt,state.nextFireAt,state.sequence,state.target,
            state.retreat?.let {(listOf(it.destination,it.acceptedAt,it.waitSeconds,it.preparationSeconds)+if(it.membershipChecked)listOf(1) else emptyList()).joinToString(",")} ?: "-")
            .joinToString("|").toByteArray(Charsets.UTF_8))
    fun decode(text:String):NpcTacticalState {
        require(text.length in 60..900)
        val f=String(Base64.getUrlDecoder().decode(text),Charsets.UTF_8).split('|').also {require(it.size==6)}
        return NpcTacticalState(TacticalMotionCodec.decode(f[0]),f[1].toLong(),f[2].toLong(),f[3].toLong(),f[4].toInt(),
            if(f[5]=="-")null else f[5].split(',').also {require(it.size==4 || it.size==5 && it[4]=="1")}.let {
                TacticalRetreat(it[0].toInt(),it[1].toLong(),it[2].toLong(),it[3].toLong(),it.size==5)}).also {require(encode(it)==text)}
    }
}
