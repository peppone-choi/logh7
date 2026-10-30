package org.logh7.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

enum class TacticalWeapon(val wire: Int) { BEAM(0), GUN(1), MISSILE(2) }

data class TacticalWeaponMount(val profile: Int, val angles: Int, val power: Int, val consumption: Int) {
    init {
        require((profile in 0..26 || profile == 255) && angles in 0..63 && power in 0..65535 && consumption in 0..65535)
        require((profile == 255) == (power == 0) && (angles == 0) == (power == 0))
    }
}

/** Designed replacement for lost server range tables, not original balance data. Author: 최병호.
 * Manual destructive power and absent weapons remain authoritative. Profile IDs and arcs
 * are assigned here for native initialization and the server range evaluator below.
 * Personal beam fire uses this evaluator; native range-preview equivalence is still unverified.
 */
object TacticalArmaments {
    const val RANGE_STEP = 1.0 // 004c7820 stores sample-count radii; 004f1180 compares them directly to map distance.
    val profiles: List<List<Int>> = List(27) { index -> when(index) {
        0 -> listOf(100, 100, 85, 65, 40, 15, 0, 0) // beam: medium range
        1 -> listOf(100, 85, 50, 10, 0, 0, 0, 0) // gun: close range
        2 -> listOf(60, 80, 100, 100, 80, 60, 40, 20) // missile: long range
        else -> List(8) { 0 } // Unassigned profiles are not fictitious additional weapons.
    } }
    fun mount(ship: ShipSpecification, weapon: TacticalWeapon): TacticalWeaponMount {
        val column = when(weapon) {
            TacticalWeapon.BEAM -> "ビーム兵装破壊力:ビーム兵装破壊力"
            TacticalWeapon.GUN -> "ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資"
            TacticalWeapon.MISSILE -> "ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資"
        }
        val power = ship.number(column)
        if (power == 0) return TacticalWeaponMount(255, 0, 0, 0)
        return TacticalWeaponMount(weapon.wire, when(weapon) {
            TacticalWeapon.BEAM -> 1 // provisional forward sector
            TacticalWeapon.GUN -> 0x23 // forward and its two adjacent sectors
            TacticalWeapon.MISSILE -> 0x3f
        }, power, if (weapon == TacticalWeapon.BEAM) 0 else ship.number(column, 1))
    }
    /** Native heading zero faces positive wire y (native z), not positive wire x.
     * Full six-sector boundary equivalence remains pending native preview comparison.
     */
    fun effectiveness(mount: TacticalWeaponMount, origin: TacticalPose, target: TacticalPoint): Int {
        if (mount.profile == 255) return 0
        val dx = target.x.toDouble() - origin.position.x
        val dy = target.y.toDouble() - origin.position.y
        val distance = hypot(dx, dy)
        if (distance == 0.0 || distance >= 8 * RANGE_STEP) return 0
        val circle = 2 * PI
        val angle = ((atan2(dx, dy) - origin.direction + PI / 6) % circle + circle) % circle
        val sector = floor(angle / (PI / 3)).toInt().coerceIn(0, 5)
        if (mount.angles and (1 shl sector) == 0) return 0
        return profiles[mount.profile][floor(distance / RANGE_STEP).toInt()]
    }
}
