package org.logh7.persistence

import org.logh7.engine.FlagshipHull

/** NAV-14 hull state with an optional NAV-24 market acquisition marker. Author: Peppone Choi. */
internal object FlagshipHullCodec {
    fun encode(hull:FlagshipHull):String {
        val fields=listOf(hull.kind,hull.at,hull.hp,hull.woundedUntil,hull.clockScale)+hull.shields
        return fields.joinToString(",")+if(hull.marketAcquired)",market" else ""
    }
    fun decode(value:String,marketMarkerAllowed:Boolean):FlagshipHull {
        val fields=value.split(',')
        val marketAcquired=marketMarkerAllowed && fields.size==12 && fields.last()=="market"
        require(fields.size==11 || marketAcquired)
        // Older saves have no acquisition history and retain the rank/model fallback.
        return FlagshipHull(fields[0].toInt(),fields[1].toLong(),fields[2].toInt(),fields[3].toLong(),
            fields[4].toLong(),fields.subList(5,11).map(String::toLong),marketAcquired)
    }
}
