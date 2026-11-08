package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalBaseDefenseTest {
    private val troop=UnitStack(0)
    private fun mixed():PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        val own=OutfitStock(1,2,OutfitKind.FLEET,1000,101,
            units=UnitInventory(ships.quantities(),mapOf(troop to 5,UnitStack(3) to 9)),
            activeShips=ships,position=OutfitPosition(2488),activeGround=ActiveGroundUnits(listOf(
                ActiveGroundUnit(2,troop,base=1000),ActiveGroundUnit(3,troop,base=1000),
                ActiveGroundUnit(4,troop,carrier=1),ActiveGroundUnit(5,troop,base=1001),ActiveGroundUnit(6,troop))))
        val enemy=OutfitStock(2,3,OutfitKind.GROUND,1000,102,
            units=UnitInventory(troops=mapOf(UnitStack(4) to 1)),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(7,UnitStack(4),base=1000))))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),
            mapOf(1 to own,2 to enemy),mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1),
                102 to OutfitWarehouse(102,3,OutfitKind.PATROL,1000)),nextShipId=8)
    }
    @Test fun onlyActualAshoreDefendersCountDespiteFleetMovementCrewOrCarrierMembership() {
        val state=mixed()
        assertEquals(2000,TacticalBaseDefense.strength(state,1000))
        assertEquals(0,TacticalBaseDefense.strength(state,1001)) // The landed unit is hostile to this base.
        assertEquals(5,state.outfits.getValue(1).activeGround!!.values.size)
        assertEquals(8,state.nextShipId)
    }
    @Test fun currentOwnershipControlsTheBarAndRegularTroopsDoNotBecomeRebels() {
        val state=mixed();val base=state.bases.getValue(1000)
        val captured=state.copy(bases=state.bases+(1000 to base.copy(owner=Allegiance.fromLegacy(3))))
        assertEquals(1000,TacticalBaseDefense.strength(captured,1000))
        val rebel=captured.copy(bases=captured.bases+(1000 to base.copy(owner=Allegiance.fromLegacy(3),camp=1)))
        assertEquals(0,TacticalBaseDefense.strength(rebel,1000))
        assertEquals(0,TacticalBaseDefense.strength(state.copy(bases=state.bases+(1000 to base.copy(owner=Allegiance.fromLegacy(0)))),1000))
    }
    @Test fun defenseCountsOnlyTheOwnersCampAmongUnitsOfTheSameCountry() {
        val state=mixed()
        val country=Power.EMPIRE.country
        val rebels=state.outfits.getValue(1).copy(commanderId=10000)
        val regulars=state.outfits.getValue(2).copy(country=country)
        val coup=CoupState(mapOf(10000 to Plot(10000,country,0,revolt=5)),loyalty=mapOf(1 to 100))
        val contested=state.copy(outfits=mapOf(1 to rebels,2 to regulars),
            warehouses=state.warehouses+(102 to state.warehouses.getValue(102).copy(country=country)),
            social=state.social.copy(coup=coup))
        val base=GalaxyBase(1000,2488,80,0,"Battle",0,0f,86400,0,0f,2f)
        val owner=contested.bases.getValue(1000)
        assertEquals(1000,TacticalBaseDefense.strength(contested,base))
        assertEquals(1000,TacticalBaseDefense.strength(contested,base.id))
        val captured=contested.copy(bases=contested.bases+(1000 to owner.copy(camp=1)))
        assertEquals(2000,TacticalBaseDefense.strength(captured,base))
        assertEquals(2000,TacticalBaseDefense.strength(captured,base.id))

        val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Here",2)),listOf(base),Power.entries.associateWith {2488})
        val session=SessionSimulation(SessionRules.load()).exportState()
        val events=mutableListOf<GroundBattleResult>()
        val weak=regulars.copy(activeGround=ActiveGroundUnits(regulars.activeGround!!.values.map {it.copy(strength=1)}))
        val fighting=contested.copy(outfits=contested.outfits+(2 to weak),
            tacticalCommands=TacticalCommandState(groundTargets=mapOf(2 to 7)))
        assertTrue(GroundBattleOperations.contested(fighting,base))
        assertNull(BaseOccupationOperations.victor(fighting,base))
        val started=GroundBattleOperations.advance(session,galaxy,emptyMap(),fighting,1,events)
        assertEquals(mapOf(2 to 7),started.tacticalCommands.groundTargets)
        val finished=GroundBattleOperations.advance(session.copy(gameMillis=session.gameMillis+5000),galaxy,emptyMap(),started,1,events)
        assertEquals(listOf(7),events.single().destroyed)
        assertEquals(GroundBattleConclusion(country,insurgent=true),events.single().conclusion)
        assertTrue(finished.tacticalCommands.groundTargets.isEmpty())
        assertNull(BaseOccupationOperations.victor(finished,base))

    }

    @Test fun legacyKnownGroundBaseWorksButUnresolvedFleetTroopsAreNotInventedAshore() {
        val state=mixed();val outfit=state.outfits.getValue(1)
        val legacy=state.copy(outfits=state.outfits+(1 to outfit.copy(activeGround=null)))
        assertEquals(0,TacticalBaseDefense.strength(legacy,1000))
        val ground=OutfitStock(3,2,OutfitKind.GROUND,1000,103,
            units=UnitInventory(troops=mapOf(troop to 5,UnitStack(3) to 9)))
        val next=legacy.copy(outfits=legacy.outfits+(3 to ground),
            warehouses=legacy.warehouses+(103 to OutfitWarehouse(103,2,OutfitKind.PATROL,1000)))
        assertEquals(5000,TacticalBaseDefense.strength(next,1000))
    }
    @Test fun nativeBarSaturatesAtThirtyWithoutRemovingOverflowOrDeclaringCombatComplete() {
        val ground=OutfitStock(1,2,OutfitKind.GROUND,1000,101,units=UnitInventory(troops=mapOf(troop to 65535)))
        val state=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),mapOf(1 to ground),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1000)))
        assertEquals(30_000,TacticalBaseDefense.strength(state,1000))
        assertEquals(65535,state.outfits.getValue(1).units.troops.getValue(troop))
        assertNull(state.outfits.getValue(1).activeGround)
    }
}
