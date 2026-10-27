package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalContactTest {
    @Test fun energyRequiresOwnFlagshipActiveBattleAndOutputBudgetAndPersistsWithoutCpCharge() {
        val store=object:NavigationStore {
            var state=mapOf("pilot" to pilot,"enemy" to pilot.copy(characterId=10001,power=Power.EMPIRE))
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
        store.state=store.state-"enemy";navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.TACTICAL,navigation.apply(request,session,stocks).rejection)
    }
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Enemy",2)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f),
            GalaxyBase(1001,2488,80,0,"Enemy",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0),1001 to PlanetStock(1001,2,0,0,0,0)))
    private val pilot=Navigator(1,10000,Power.ALLIANCE,2487,600,600,1000,cpRecoveryAt=0)
    @Test fun onlyHostilePresenceInTheSameGridStartsContactAndFacilitiesDoNotParticipate() {
        val enemy=pilot.copy(characterId=10001,power=Power.EMPIRE)
        assertFalse(TacticalContact.active(galaxy,pilot,listOf(pilot),stocks))
        assertTrue(TacticalContact.active(galaxy,pilot,listOf(pilot,enemy),stocks))
        assertFalse(TacticalContact.active(galaxy,pilot,listOf(pilot,enemy.copy(grid=2488)),stocks))
        assertFalse(TacticalContact.active(galaxy,pilot.copy(baseId=1000,mode=4),listOf(enemy),stocks))
        assertTrue(TacticalContact.active(galaxy,pilot.copy(grid=2488),emptyList(),stocks))
        assertFalse(TacticalContact.active(galaxy,pilot.copy(grid=2488),emptyList(),
            stocks.copy(bases=stocks.bases+(1001 to stocks.bases.getValue(1001).copy(power=4)))))
    }
    @Test fun contactStopsRecoveryAndStrategicWarpAndSurvivesReloadThenEndsWithoutBackdatedCp() {
        val store=object:NavigationStore {
            var state=mapOf("pilot" to pilot,"enemy" to pilot.copy(characterId=10001,power=Power.EMPIRE))
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
