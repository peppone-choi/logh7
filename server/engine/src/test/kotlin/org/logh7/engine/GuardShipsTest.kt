package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** update03 guard-ship catalogue. Author: Peppone Choi. */
class GuardShipsTest {
    @Test fun eachFactionHasThirteenOfficialPricesAndRanks() {
        for(power in 2..3) {
            val list=GuardShips.forPower(power)
            assertEquals(13,list.size)
            assertEquals(listOf(20000L,500L),listOf(list.first().price,list.last().price))
            assertEquals(7,list.first().maximumRank);assertEquals(20,list.last().maximumRank)
            assertTrue(list.zipWithNext().all {(a,b)->a.price>=b.price})
        }
        assertEquals((0..25).toList(),GuardShips.options.map {it.index})
        assertEquals(8,GuardShips.option(16)!!.maximumRank)
    }
    @Test fun baseHullsBelongToTheBuyersFactionAndKeepAWeapon() {
        for(o in GuardShips.options) {
            val base=UnitSpecifications.ships.single {it.kind==o.baseKind}
            assertTrue(o.modifier.weapons.isNotEmpty())
            assertTrue(ProductionUnits.definitions.single {it.kind==base.kind && it.ship}.power==o.power)
        }
    }
}

/** Guard modifiers in combat. Author: Peppone Choi. */
class GuardCombatTest {
    private val stack=UnitStack(48)
    private fun outfit(index:Int)=OutfitStock(1,2,OutfitKind.PATROL,1000,5,10000,UnitInventory(mapOf(stack to 1)),
        activeShips=ActiveShipUnits(listOf(ActiveShipUnit(7,stack))),guard=mapOf(7 to index))
    @Test fun weaponsAttackArmorAndSpeedFollowTheGuardModifier() {
        val mount=TacticalWeaponMount(5,63,100,1)
        assertEquals(mount,GuardCombat.mount(mount,TacticalWeapon.BEAM,null,7))
        assertEquals(90,GuardCombat.mount(mount,TacticalWeapon.BEAM,outfit(4),7).power)
        assertEquals(90,GuardCombat.mount(mount,TacticalWeapon.BEAM,outfit(6),7).power)
        assertEquals(0,GuardCombat.mount(mount,TacticalWeapon.GUN,outfit(6),7).power)
        assertEquals(50,GuardCombat.mount(mount,TacticalWeapon.MISSILE,outfit(7),7).power)
        assertEquals(36,GuardCombat.armor(24,outfit(7),7));assertEquals(24,GuardCombat.armor(24,outfit(7),8))
        assertEquals(1.1f,GuardCombat.speed(1f,outfit(5),7),1e-6f)
    }
}
