package org.logh7.persistence

import org.logh7.engine.*

/** NAV-12 pending command and last absolute loss notification. Author: 최병호. */
internal object TacticalFireCodec {
    fun encode(fire: TacticalFire): String = listOf(fire.sequence.toString(), fire.pending?.let {
        listOf(it.sequence,it.grid,it.shipKind,it.target,it.acceptedAt,it.firesAt).let { fields ->
            (if (it.weapon == TacticalWeapon.BEAM) fields else fields + it.weapon.wire).joinToString(",") }
    } ?: "-", fire.last?.let {
        (listOf(it.sequence,it.grid,it.seconds,it.target,it.destroyed)+
            if(it.damaged!=0 || it.shieldFace!=255)listOf(it.damaged,it.shieldFace,it.shieldDamage) else emptyList()).joinToString(",")
    } ?: "-").joinToString(";")
    fun decode(value: String): TacticalFire {
        val parts = value.split(';').also { require(it.size == 3) }
        val pending = parts[1].takeUnless { it == "-" }?.split(',')?.also { require(it.size in 6..7) }?.let {
            TacticalPendingFire(it[0].toLong(),it[1].toInt(),it[2].toInt(),it[3].toInt(),it[4].toLong(),it[5].toLong(),
                if(it.size == 6) TacticalWeapon.BEAM else requireNotNull(TacticalWeapon.entries.singleOrNull { weapon -> weapon.wire == it[6].toInt() }))
        }
        val last = parts[2].takeUnless { it == "-" }?.split(',')?.also { require(it.size == 5 || it.size == 8) }?.let {
            TacticalHit(it[0].toLong(),it[1].toInt(),it[2].toLong(),it[3].toInt(),it[4].toInt(),
                if(it.size==8)it[5].toInt() else 0,if(it.size==8)it[6].toInt() else 255,if(it.size==8)it[7].toInt() else 0)
        }
        return TacticalFire(parts[0].toLong(),pending,last)
    }
}
