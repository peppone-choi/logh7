package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OutfitShipLimitsTest {
    @Test fun transportRolesCannotBorrowEachOthersUnusedRoom() {
        fun units(combat:Int,cargo:Int)=UnitInventory(mapOf(UnitStack(32) to combat,UnitStack(76) to cargo))
        OutfitShipLimits.validate(OutfitKind.TRANSPORT,units(3,20))
        assertFailsWith<IllegalArgumentException> { OutfitShipLimits.validate(OutfitKind.TRANSPORT,units(4,19)) }
        assertFailsWith<IllegalArgumentException> { OutfitShipLimits.validate(OutfitKind.TRANSPORT,units(2,21)) }
        OutfitShipLimits.validate(OutfitKind.TRANSPORT,UnitInventory(mapOf(UnitStack(32) to 3,
            UnitStack(76) to 10,UnitStack(80,1) to 10)))
        assertFailsWith<IllegalArgumentException> { OutfitShipLimits.validate(OutfitKind.TRANSPORT,
            UnitInventory(mapOf(UnitStack(76) to 10,UnitStack(80,1) to 11))) }
    }
    @Test fun navalAndGroundFormationsDoNotSilentlyReclassifyOtherShipRoles() {
        for ((kind,wrong) in listOf(OutfitKind.FLEET to 76,OutfitKind.PATROL to 84,
                OutfitKind.TRANSPORT to 84,OutfitKind.GROUND to 32)) {
            assertFailsWith<IllegalArgumentException> { OutfitShipLimits.validate(kind,UnitInventory(mapOf(UnitStack(wrong) to 1))) }
        }
        OutfitShipLimits.validate(OutfitKind.GROUND,UnitInventory(mapOf(UnitStack(84) to 3)))
    }
}
