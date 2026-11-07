package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryGroundBattleTest {
    private val base=GalaxyBase(1000,2487,80,0,"Battle",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).exportState()
    private fun stocks(countries:List<Int>,count:Int=1):PlanetStocks {
        var id=1
        val outfits=countries.mapIndexed {index,country ->
            val troops=ActiveGroundUnits(List(count) {ActiveGroundUnit(id++,UnitStack(0),base=base.id)})
            index+1 to OutfitStock(index+1,CountryId(country),OutfitKind.GROUND,base.id,101+countries.indexOf(country),
                units=UnitInventory(troops=troops.quantities()),activeGround=troops,shipStyle=ShipStyleId("empire"))
        }.toMap()
        return PlanetStocks(1,0,mapOf(base.id to PlanetStock(base.id,Allegiance.Country(CountryId(countries.first())),0,0,0,0)),
            outfits=outfits,warehouses=outfits.values.associate {o ->
                o.warehouseId to OutfitWarehouse(o.warehouseId,o.country,OutfitKind.PATROL,base.id,shipStyle=o.shipStyle)
            },deploymentInitialized=true,nextShipId=id)
    }
    private fun advance(stocks:PlanetStocks,elapsed:Long)=GroundBattleOperations.advance(
        session.copy(gameMillis=session.gameMillis+elapsed*1000),galaxy,emptyMap(),stocks,1,mutableListOf())

    @Test fun differentActualCountriesFightEvenWhenTheirTroopsUseTheSameStyle() {
        val before=stocks(listOf(42,43))
        assertEquals(setOf(1,2),GroundBattleView.fighting(before,base))
        val after=advance(advance(before,0),5)
        assertEquals(listOf(800,800),after.outfits.values.map {it.activeGround!!.values.single().strength})
        assertEquals(800,TacticalBaseDefense.strength(after,base))
        assertEquals(800,TacticalBaseDefense.strength(after,base.id))
        assertEquals(before.warehouses,after.warehouses)
        assertSame(after,advance(after,5))
    }

    @Test fun oneActualCountryDoesNotFightItselfAndTheNewOwnerGetsOnlyItsOwnDefense() {
        val friends=stocks(listOf(42,42))
        assertFalse(GroundBattleOperations.contested(friends,base))
        assertSame(friends,advance(friends,20))
        assertEquals(2000,TacticalBaseDefense.strength(friends,base))
        val mixed=stocks(listOf(42,43),2)
        val old=mixed.outfits.getValue(1)
        val wounded=mixed.copy(outfits=mixed.outfits+(1 to old.copy(activeGround=ActiveGroundUnits(
            old.activeGround!!.values.map {it.copy(strength=200)}))))
        assertEquals(400,TacticalBaseDefense.strength(wounded,base))
        val captured=wounded.copy(bases=wounded.bases+(base.id to wounded.bases.getValue(base.id).copy(owner=Allegiance.Country(CountryId(43)))))
        assertEquals(2000,TacticalBaseDefense.strength(captured,base))
        assertEquals(2000,TacticalBaseDefense.strength(captured,base.id))
    }

    @Test fun eachCountryHasItsOwnThirtyUnitBoxAndReserveIsNotDamaged() {
        val before=stocks(listOf(42,43),31)
        assertEquals(60,GroundBattleView.fighting(before,base).size)
        val after=advance(advance(before,0),5)
        for(outfit in after.outfits.values) {
            assertEquals(1000,outfit.activeGround!!.values.maxBy {it.id}.strength)
            assertEquals(30,outfit.activeGround!!.values.count {it.strength==800})
        }
        assertEquals(24_000,TacticalBaseDefense.strength(after,base))
    }

    @Test fun onlyTheDecisiveRoundReportsTheActualSurvivorOrMutualLoss() {
        for (mutual in listOf(false,true)) {
            val before=stocks(listOf(42,43))
            val weak=before.copy(outfits=before.outfits.mapValues {(_,outfit)->
                if(mutual || outfit.country==CountryId(43))outfit.copy(activeGround=ActiveGroundUnits(
                    outfit.activeGround!!.values.map {it.copy(strength=1)})) else outfit
            })
            val events=mutableListOf<GroundBattleResult>()
            val after=GroundBattleOperations.advance(session.copy(gameMillis=session.gameMillis+5000),galaxy,
                emptyMap(),advance(weak,0),1,events)
            assertEquals(GroundBattleConclusion(if(mutual)null else CountryId(42)),events.single().conclusion)
            assertFalse(base.id in after.groundBattles)
            GroundBattleOperations.advance(session.copy(gameMillis=session.gameMillis+10000),galaxy,emptyMap(),after,1,events)
            assertEquals(1,events.size)
        }
    }
}
