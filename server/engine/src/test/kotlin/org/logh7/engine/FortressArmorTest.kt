package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FortressArmorTest {
    private val mobile=GalaxyBase(1001,2487,112,1,"Mobile",0,12f,86400,0,0f,2f,mobile=true)
    private val fixed=GalaxyBase(1002,2487,110,1,"Fixed",0,20f,86400,0,0f,2f)
    private val planet=GalaxyBase(1003,2487,60,0,"Planet",0,28f,86400,0,0f,2f)
    private val stocks=PlanetStocks(1,0,listOf(mobile,fixed,planet).associate {it.id to PlanetStock(it.id,2,0,0,0,0)})
    @Test fun strikesWearArmorBreakMobilePropulsionAndLeavePlanetsAlone() {
        val once=FortressArmor.strike(mobile,stocks,4)
        assertEquals(FortressArmor.ARMOR-2000,FortressArmor.remaining(once,1001));assertFalse(FortressArmor.propulsion(once,1001))
        val fixedHit=FortressArmor.strike(fixed,stocks,10)
        assertTrue(FortressArmor.propulsion(fixedHit,1002),"only a mobile fortress has propulsion")
        assertFalse(FortressArmor.breached(fixedHit,1002))
        assertTrue(FortressArmor.breached(FortressArmor.strike(fixed,fixedHit,10),1002))
        assertEquals(FortressArmor.ARMOR,FortressArmor.strike(fixed,FortressArmor.strike(fixed,fixedHit,10),10).tacticalCommands.fortressDamage[1002])
        assertSame(stocks,FortressArmor.strike(planet,stocks,10))
        val repaired=FortressArmor.restore(once,1001)
        assertEquals(FortressArmor.ARMOR,FortressArmor.remaining(repaired,1001));assertTrue(FortressArmor.propulsion(repaired,1001))
    }
    @Test fun aBreachedFortressSinksItsHoldersStationedShipsAndDockedFlagshipsOnly() {
        val session=SessionSimulation(SessionRules.load()).exportState()
        val home=GalaxyBase(1004,2490,60,0,"Home",0,28f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3),GalaxySystem(4,2490,4,"Home",3)),listOf(fixed,home),Power.entries.associateWith {2487})
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(2,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1002)),
            ActiveShipUnit(3,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1002))))
        val start=PlanetStocks(1,0,mapOf(1002 to PlanetStock(1002,3,0,0,0,0),1004 to PlanetStock(1004,3,0,0,0,0)),
            mapOf(2 to OutfitStock(2,3,OutfitKind.FLEET,1002,102,units=UnitInventory(ships.quantities()),activeShips=ships)),
            mapOf(102 to OutfitWarehouse(102,3,OutfitKind.FLEET,1002,2)),nextShipId=10)
        val docked=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4)
        val enemy=Navigator(1,10002,Power.EMPIRE,2487,1000,1000,900,cpPaused=true)
        val players=mutableMapOf("a" to docked,"e" to enemy);val sunk=mutableListOf<Navigator>()
        assertSame(start,FortressArmor.advance(session,galaxy,players,start,sunk),"armor intact")
        val breached=FortressArmor.strike(fixed,start,20)
        assertTrue(FortressArmor.pending(breached,galaxy,players.values))
        val after=FortressArmor.advance(session,galaxy,players,breached,sunk)
        val units=after.outfits.getValue(2).activeShips!!.values
        assertEquals(0,units.single {it.id==2}.boats,"stationed inside");assertEquals(300,units.single {it.id==3}.boats,"anchored outside")
        assertEquals(listOf(10001),sunk.map {it.characterId});assertNotEquals(1002,players.getValue("a").baseId)
        assertEquals(enemy,players.getValue("e"))
    }
    @Test fun staleFlagshipsAndNonFortressDamageDoNotKeepBreachSettlementPending() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(fixed,planet),Power.entries.associateWith {2487})
        val docked=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,900,baseId=fixed.id,mode=4)
        val breached=FortressArmor.strike(fixed,stocks,20)
        assertFalse(FortressArmor.pending(breached,galaxy,listOf(docked.copy(generation=2))))
        val invalidDamage=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(
            fortressDamage=mapOf(planet.id to FortressArmor.ARMOR)))
        assertFalse(FortressArmor.pending(invalidDamage,galaxy,listOf(docked.copy(baseId=planet.id))))
    }

    @Test fun duringARevoltOnlyTheHoldingCampIsSunk() {
        // PR #214 review: a rebel fortress breach must spare loyal units of the same power stationed there.
        val session=SessionSimulation(SessionRules.load()).exportState()
        val home=GalaxyBase(1004,2490,60,0,"Home",0,28f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3),GalaxySystem(4,2490,4,"Home",3)),listOf(fixed,home),Power.entries.associateWith {2487})
        fun ships(id:Int)=ActiveShipUnits(listOf(ActiveShipUnit(id,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1002))))
        val coup=CoupState(mapOf(10001 to Plot(10001,CountryId(3),0,revolt=5)),loyalty=mapOf(2 to 60))
        val start=PlanetStocks(1,0,mapOf(1002 to PlanetStock(1002,3,1,0,0,0),1004 to PlanetStock(1004,3,0,0,0,0)),
            mapOf(2 to OutfitStock(2,3,OutfitKind.FLEET,1002,102,10001,units=UnitInventory(ships(2).quantities()),activeShips=ships(2)),
                3 to OutfitStock(3,3,OutfitKind.FLEET,1002,103,10003,units=UnitInventory(ships(3).quantities()),activeShips=ships(3))),
            mapOf(102 to OutfitWarehouse(102,3,OutfitKind.FLEET,1002,2),103 to OutfitWarehouse(103,3,OutfitKind.FLEET,1002,3)),nextShipId=10)
            .let {it.copy(social=it.social.copy(coup=coup))}
        val rebel=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4)
        val loyal=Navigator(1,10003,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4)
        val players=mutableMapOf("r" to rebel,"l" to loyal);val sunk=mutableListOf<Navigator>()
        val after=FortressArmor.advance(session,galaxy,players,FortressArmor.strike(fixed,start,20),sunk)
        assertEquals(0,after.outfits.getValue(2).activeShips!!.values.single().boats,"rebel outfit inside")
        assertEquals(300,after.outfits.getValue(3).activeShips!!.values.single().boats,"loyal outfit of the same power")
        assertEquals(listOf(10001),sunk.map {it.characterId});assertEquals(loyal,players.getValue("l"))
        val loyalOnly=FortressArmor.strike(fixed,start,20).let {it.copy(outfits=it.outfits-2,warehouses=it.warehouses-102)}
        assertFalse(FortressArmor.pending(loyalOnly,galaxy,listOf(loyal)),"nothing of the holder's camp is inside")
    }
}
