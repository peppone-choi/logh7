package org.logh7.persistence

import org.logh7.engine.*

/** Canonical integer fields; no floating point or separators used by enclosing records. Author: 최병호. */
internal object TacticalPostureCodec {
    fun encode(state:TacticalPosture):String = (listOf(state.grid,state.mode.wire,state.base)+
        (state.order?.let {listOf(it.character,it.mode.wire,it.base,it.acceptedAt,it.readyAt)} ?: emptyList()))
        .joinToString("~")
    fun decode(text:String):TacticalPosture {
        require(text.length in 5..100)
        val f=text.split('~');require(f.size==3 || f.size==8)
        fun mode(index:Int)=requireNotNull(TacticalShipMode.entries.singleOrNull {it.wire==f[index].toInt()})
        return TacticalPosture(f[0].toInt(),mode(1),f[2].toInt(),if(f.size==8)
            TacticalPostureOrder(f[3].toInt(),mode(4),f[5].toInt(),f[6].toLong(),f[7].toLong()) else null)
            .also {require(encode(it)==text)}
    }
}
