package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalArmamentsTest {
    @Test fun reviewedWeaponPowerAndAbsenceArePreservedForEveryHull() {
        for (ship in UnitSpecifications.ships) {
            for ((weapon, column) in listOf(TacticalWeapon.BEAM to "ビーム兵装破壊力:ビーム兵装破壊力",
                TacticalWeapon.GUN to "ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資",
                TacticalWeapon.MISSILE to "ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資")) {
                val mount = TacticalArmaments.mount(ship, weapon)
                assertEquals(ship.number(column), mount.power)
                if (mount.power == 0) { assertEquals(255, mount.profile); assertEquals(0, mount.angles) }
                else { assertTrue(mount.profile in 0..26); assertTrue(mount.angles > 0) }
            }
        }
        val battleship = UnitSpecifications.ships.single { it.kind == 32 }
        assertEquals(48, TacticalArmaments.mount(battleship, TacticalWeapon.BEAM).power)
        assertEquals(1, TacticalArmaments.mount(battleship, TacticalWeapon.GUN).consumption)
        assertEquals(5, TacticalArmaments.mount(battleship, TacticalWeapon.MISSILE).consumption)
        val unarmed = UnitSpecifications.ships.single { it.kind == 88 }
        assertEquals(255, TacticalArmaments.mount(unarmed, TacticalWeapon.BEAM).profile)
    }
    @Test fun fireChecksDistinguishCloseMediumLongRangeAndShipHeading() {
        val ship = UnitSpecifications.ships.single { it.kind == 32 }
        val beam = TacticalArmaments.mount(ship, TacticalWeapon.BEAM)
        val gun = TacticalArmaments.mount(ship, TacticalWeapon.GUN)
        val missile = TacticalArmaments.mount(ship, TacticalWeapon.MISSILE)
        val origin = TacticalPose(TacticalPoint(0f, 0f), 0f)
        assertTrue(TacticalArmaments.effectiveness(gun, origin, TacticalPoint(0f, 0.5f)) > 0)
        assertEquals(0, TacticalArmaments.effectiveness(gun, origin, TacticalPoint(0f, 4f)))
        assertTrue(TacticalArmaments.effectiveness(beam, origin, TacticalPoint(0f, 5f)) > 0)
        assertEquals(0, TacticalArmaments.effectiveness(beam, origin, TacticalPoint(0f, 6f)))
        assertTrue(TacticalArmaments.effectiveness(missile, origin, TacticalPoint(0f, -7.99f)) > 0)
        assertEquals(0, TacticalArmaments.effectiveness(missile, origin, TacticalPoint(0f, 8f)))
        assertEquals(0, TacticalArmaments.effectiveness(beam, origin, TacticalPoint(0f, -0.5f)))
        assertTrue(TacticalArmaments.effectiveness(beam, origin.copy(direction = Math.PI.toFloat()), TacticalPoint(0f, -0.5f)) > 0)
        assertEquals(0, TacticalArmaments.effectiveness(beam, origin, origin.position))
        for(power in Power.entries) {
            val pose=TacticalDeployment.formation(0,power)
            val other=TacticalDeployment.formation(0,if(power==Power.EMPIRE)Power.ALLIANCE else Power.EMPIRE)
            val direction=kotlin.math.atan2((other.position.x-pose.position.x).toDouble(),
                (other.position.y-pose.position.y).toDouble()).toFloat()
            assertEquals(direction,pose.direction)
        }
    }
}
