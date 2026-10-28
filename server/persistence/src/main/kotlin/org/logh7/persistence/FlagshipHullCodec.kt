package org.logh7.persistence

import org.logh7.engine.FlagshipHull

/** NAV-14: kind, shield clock, hull, injury deadline, scale and six shield pools. Author: 최병호. */
internal object FlagshipHullCodec {
    fun encode(hull:FlagshipHull) = (listOf(hull.kind,hull.at,hull.hp,hull.woundedUntil,hull.clockScale)+hull.shields).joinToString(",")
    fun decode(value:String):FlagshipHull {
        val fields=value.split(',').also {require(it.size==11)}
        return FlagshipHull(fields[0].toInt(),fields[1].toLong(),fields[2].toInt(),fields[3].toLong(),
            fields[4].toLong(),fields.drop(5).map(String::toLong))
    }
}
