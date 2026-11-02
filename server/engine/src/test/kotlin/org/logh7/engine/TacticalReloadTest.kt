package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalReloadTest {
    @Test fun independentDeadlinesRespectClockScaleAndNeverResetOtherWeapons() {
        for(scale in listOf(1L,24L,1000L)) {
            var reload=TacticalReload()
            for(weapon in TacticalWeapon.entries) {
                reload=reload.fired(weapon,100,scale)
                val deadline=100+TacticalReload.seconds(weapon)*scale
                assertFalse(reload.ready(weapon,deadline-1));assertTrue(reload.ready(weapon,deadline))
                assertFails {reload.fired(weapon,100,scale)}
            }
            assertEquals(TacticalReload(100+10*scale,100+15*scale,100+20*scale),reload)
        }
        assertFails {TacticalReload(-1)};assertFails {TacticalReload(gun=0x100000000L)}
        assertFails {TacticalReload().fired(TacticalWeapon.MISSILE,0xffffffffL,24)}
        assertFails {TacticalReload().fired(TacticalWeapon.BEAM,100,0)}
    }
}
