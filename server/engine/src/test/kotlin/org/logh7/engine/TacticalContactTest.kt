package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalContactTest {
    @Test fun anEliminatedOwnersBaseStopsCombatButStillRestrictsSoloEntry() {
        val enemy = Power.EMPIRE.country
        val battle = pilot.copy(grid = 2488, cpPaused = true)
        val players = mutableMapOf("pilot" to battle)
        val eliminated = setOf(enemy)
        assertTrue(TacticalContact.active(galaxy, battle, players.values, stocks))
        TacticalContact.endUncontested(galaxy, players, stocks, 100)
        assertEquals(battle, players.getValue("pilot"))

        assertFalse(TacticalContact.active(galaxy, battle, players.values, stocks, eliminated))
        val forces = GridForces.collect(galaxy, 1, players.values, stocks, eliminated)
        assertFalse(TacticalContact.contested(forces.getValue(2488)))
        TacticalContact.endUncontested(galaxy, players, stocks, 101, eliminated)
        val recovered = players.getValue("pilot")
        assertFalse(recovered.cpPaused)
        assertEquals(101L, recovered.cpRecoveryAt)
        assertEquals(battle.mcp, recovered.mcp)

        val entry = GridEntry(Camp(Power.ALLIANCE.country), 1, soloShip = true)
        for (defeated in listOf(emptySet(), eliminated)) {
            val beforeEntry = GridForces.collect(galaxy, 1, emptyList(), stocks, defeated)
            assertEquals(GridEntryVerdict.SOLO_SHIP_BLOCKED,
                GridEntryRules.evaluate(GridForces.state(galaxy, 2488, beforeEntry), entry))
            val withAlly = GridForces.collect(galaxy, 1, listOf(recovered), stocks, defeated)
            assertEquals(GridEntryVerdict.ALLOWED,
                GridEntryRules.evaluate(GridForces.state(galaxy, 2488, withAlly), entry))
        }
        assertEquals(enemy, stocks.bases.getValue(1001).country)
    }

    @Test fun deceasedEnemyFlagshipNoLongerKeepsTheBattleActive() {
        val allied=pilot.copy(cpPaused=true)
        val enemy=pilot.copy(characterId=10001,country=Power.EMPIRE.country,
            shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE),hull=FlagshipHull(0,100,woundedUntil=86500))
        val players=mutableMapOf("pilot" to allied,"enemy" to enemy)
        assertTrue(TacticalContact.active(galaxy,allied,players.values,stocks))

        val afterDeath=BattleDeath.apply(stocks,listOf(enemy.characterId),100) {_,_->true}
        val forces=GridForces.collect(galaxy,1,players.values,afterDeath)
        assertEquals(1L,forces.getValue(allied.grid).sumOf {it.units})
        assertFalse(TacticalContact.active(galaxy,allied,players.values,afterDeath))
        TacticalContact.endUncontested(galaxy,players,afterDeath,100)
        assertFalse(players.getValue("pilot").cpPaused)
        assertEquals(100L,players.getValue("pilot").cpRecoveryAt)
        assertEquals(enemy,players.getValue("enemy"))
    }

    @Test fun excludedFlagshipsLeaveTacticsWhileTheOtherCountriesKeepFighting() {
        val pilotInBattle=pilot.copy(cpPaused=true,commandRange=TacticalCommandRange(90))
        val enemy=pilot.copy(characterId=10001,country=Power.EMPIRE.country,
            shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE),cpPaused=true)
        val third=enemy.copy(characterId=10002,country=CountryId(4))
        val dead=BattleDeath.apply(stocks,listOf(pilot.characterId),100) {_,_->true}
        val detention=Detention(pilot.characterId,Power.ALLIANCE.country,100,100+Justice.DAY,base=1000)
        val held=stocks.copy(social=stocks.social.copy(justice=JusticeState(detentions=mapOf(pilot.characterId to detention))))
        assertTrue(TacticalContact.active(galaxy,pilotInBattle,listOf(enemy,third),stocks))

        for ((inventory,eliminated) in listOf(stocks to setOf(Power.ALLIANCE.country),dead to emptySet(),held to emptySet())) {
            val players=mutableMapOf("pilot" to pilotInBattle,"enemy" to enemy,"third" to third)
            assertFalse(TacticalContact.active(galaxy,pilotInBattle,players.values,inventory,eliminated))
            TacticalContact.endUncontested(galaxy,players,inventory,101,eliminated)
            assertEquals(pilotInBattle.copy(cpPaused=false,cpRecoveryAt=101,commandRange=null),players.getValue("pilot"))
            assertEquals(enemy,players.getValue("enemy"))
            assertEquals(third,players.getValue("third"))
        }
    }

    @Test fun survivingHostileGroundUnitsRetainBattleAndZeroInventoryEndsIt() {
        val battle=pilot.copy(grid=2017,cpPaused=true)
        val crew=UnitSpecifications.crews.first {it.category!=19}
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,1,units=UnitInventory(troops=mapOf(UnitStack(crew.kind) to 1)),
            position=OutfitPosition(2017))
        val inventory=stocks.copy(outfits=mapOf(1 to outfit),warehouses=mapOf(1 to OutfitWarehouse(1,2,OutfitKind.FLEET,1000,1)))
        val players=mutableMapOf("pilot" to battle)
        TacticalContact.endUncontested(galaxy,players,inventory,100)
        assertTrue(players.getValue("pilot").cpPaused)
        TacticalContact.endUncontested(galaxy,players,inventory.copy(outfits=emptyMap(),warehouses=emptyMap()),101)
        assertFalse(players.getValue("pilot").cpPaused)
        assertEquals(101L,players.getValue("pilot").cpRecoveryAt)
    }
    @Test fun energyRequiresOwnFlagshipActiveBattleAndOutputBudgetAndPersistsWithoutCpCharge() {
        val store=object:NavigationStore {
            var state=mapOf("pilot" to pilot,"enemy" to pilot.copy(characterId=10001,country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE)))
            override fun load()=state
            override fun save(players:Map<String,Navigator>){state=players.toMap()}
        }
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        var navigation=Navigation(galaxy,store,NavigationPolicy())
        val energy=TacticalEnergy(listOf(40,0,20,60,20)+List(6){20})
        val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,unitId=1010000,captainCard=195,
            energy=energy,shipKind=89)
        val accepted=navigation.apply(request,session,stocks).player!!
        assertEquals(energy,accepted.energy);assertEquals(pilot.mcp,accepted.mcp);assertEquals(140,energy.reserve(400))
        navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(energy,navigation.snapshot(1).getValue("pilot").energy)
        assertEquals(NavigationRejection.UNIT,navigation.apply(request.copy(unitId=1010001),session,stocks).rejection)
        assertEquals(NavigationRejection.UNIT,navigation.apply(request.copy(shipKind=0),session,stocks).rejection)
        assertEquals(NavigationRejection.ENERGY,navigation.apply(request.copy(energy=TacticalEnergy(List(11){100})),session,stocks).rejection)
        assertEquals(energy,store.state.getValue("pilot").energy)
        val detention=Detention(pilot.characterId,Power.ALLIANCE.country,0,Justice.DAY,base=1000)
        val held=stocks.copy(social=stocks.social.copy(justice=JusticeState(detentions=mapOf(pilot.characterId to detention))))
        val saved=store.state
        assertEquals(NavigationRejection.BUSY,navigation.apply(request.copy(energy=TacticalEnergy.ZERO),session,held).rejection)
        assertEquals(saved,store.state)
        assertEquals(TacticalEnergy.ZERO,navigation.apply(request.copy(energy=TacticalEnergy.ZERO),session,stocks).player?.energy)
        assertEquals(TacticalEnergy.ZERO,store.state.getValue("pilot").energy)
        store.state=store.state-"enemy";navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.TACTICAL,navigation.apply(request,session,stocks).rejection)
    }
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Enemy",2)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f),
            GalaxyBase(1001,2488,80,0,"Enemy",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0),1001 to PlanetStock(1001,2,0,0,0,0)))
    private val pilot=Navigator(1,10000,Power.ALLIANCE,2487,600,600,1000,cpRecoveryAt=0)
    @Test fun onlyHostilePresenceInTheSameGridStartsContactAndFacilitiesDoNotParticipate() {
        val enemy=pilot.copy(characterId=10001,country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE))
        assertFalse(TacticalContact.active(galaxy,pilot,listOf(pilot),stocks))
        assertTrue(TacticalContact.active(galaxy,pilot,listOf(pilot,enemy),stocks))
        assertFalse(TacticalContact.active(galaxy,pilot,listOf(pilot,enemy.copy(grid=2488)),stocks))
        assertFalse(TacticalContact.active(galaxy,pilot.copy(baseId=1000,mode=4),listOf(enemy),stocks))
        assertTrue(TacticalContact.active(galaxy,pilot.copy(grid=2488),emptyList(),stocks))
        assertFalse(TacticalContact.active(galaxy,pilot.copy(grid=2488),emptyList(),
            stocks.copy(bases=stocks.bases+(1001 to stocks.bases.getValue(1001).copy(owner=Allegiance.fromLegacy(4))))))
    }
    @Test fun contactStopsRecoveryAndStrategicWarpAndSurvivesReloadThenEndsWithoutBackdatedCp() {
        val store=object:NavigationStore {
            var state=mapOf("pilot" to pilot,"enemy" to pilot.copy(characterId=10001,country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE)))
            override fun load()=state
            override fun save(players:Map<String,Navigator>) {state=players.toMap()}
        }
        var navigation=Navigation(galaxy,store,NavigationPolicy())
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,unitId=1010000,captainCard=195)
        val entered=navigation.apply(request,session,stocks).player!!
        assertTrue(entered.cpPaused);assertEquals(600,entered.mcp)
        navigation=Navigation(galaxy,store,NavigationPolicy())
        val later=session.copy(gameMillis=session.gameMillis+8*3600_000L)
        assertEquals(NavigationRejection.TACTICAL,navigation.apply(request.copy(destination=2489),later,stocks).rejection)
        assertEquals(600,store.state.getValue("pilot").mcp)
        store.state=store.state-"enemy"
        navigation=Navigation(galaxy,store,NavigationPolicy())
        val ended=navigation.apply(request,later,stocks).player!!
        assertFalse(ended.cpPaused);assertEquals(600,ended.mcp)
        assertEquals(675,navigation.apply(request,later.copy(gameMillis=later.gameMillis+7200_000),stocks).player!!.mcp)
    }
}
