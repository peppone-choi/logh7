package org.logh7.persistence

import org.logh7.engine.*

/** NAV-12 pending command and last absolute loss notification. Author: 최병호. */
internal object TacticalFireCodec {
    fun encode(fire: TacticalFire): String = listOf(fire.sequence.toString(), fire.pending?.let {
        listOf(it.sequence,it.grid,it.shipKind,it.target,it.acceptedAt,it.firesAt).let { fields ->
            (if (it.weapon == TacticalWeapon.BEAM) fields else fields + it.weapon.wire).joinToString(",") }
    } ?: "-", fire.last?.let {
        listOf(it.sequence,it.grid,it.seconds,it.target,it.destroyed).joinToString(",")
    } ?: "-").joinToString(";")
    fun decode(value: String): TacticalFire {
        val parts = value.split(';').also { require(it.size == 3) }
        val pending = parts[1].takeUnless { it == "-" }?.split(',')?.also { require(it.size in 6..7) }?.let {
            TacticalPendingFire(it[0].toLong(),it[1].toInt(),it[2].toInt(),it[3].toInt(),it[4].toLong(),it[5].toLong(),
                if(it.size == 6) TacticalWeapon.BEAM else requireNotNull(TacticalWeapon.entries.singleOrNull { weapon -> weapon.wire == it[6].toInt() }))
        }
        val last = parts[2].takeUnless { it == "-" }?.split(',')?.also { require(it.size == 5) }?.let {
            TacticalHit(it[0].toLong(),it[1].toInt(),it[2].toLong(),it[3].toInt(),it[4].toInt())
        }
        return TacticalFire(parts[0].toLong(),pending,last)
    }
}
