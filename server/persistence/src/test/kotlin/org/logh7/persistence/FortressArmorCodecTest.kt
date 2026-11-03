package org.logh7.persistence

import org.logh7.engine.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class FortressArmorCodecTest {
    @Test fun fortressDamageAndBrokenPropulsionSurviveAndOlderStatesKeepTheirVersion() {
        val state=TacticalCommandState(morale=mapOf(1 to 75),fortressDamage=mapOf(1001 to 2500,1040 to FortressArmor.ARMOR),brokenPropulsion=setOf(1001))
        assertEquals(state,TacticalCommandStateCodec.decode(TacticalCommandStateCodec.encode(state)))
        val old=TacticalCommandState(morale=mapOf(1 to 75))
        assertEquals(old,TacticalCommandStateCodec.decode(TacticalCommandStateCodec.encode(old)))
        assertFails {TacticalCommandState(fortressDamage=mapOf(1001 to FortressArmor.ARMOR+1))}
        val targets=state.copy(groundTargets=mapOf(1801 to 1902,1802 to 1902))
        assertEquals(targets,TacticalCommandStateCodec.decode(TacticalCommandStateCodec.encode(targets)))
        assertFails {TacticalCommandState(groundTargets=mapOf(5 to 5))}
    }
}
