package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalRetreatTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0)))
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        .let { it.copy(gameMillis=it.gameMillis+10000) }
    private val seconds=session.gameMillis/1000
    private val pose=TacticalPose(TacticalPoint(-300f,0f),1f)
    private val pilot=Navigator(1,10000,Power.ALLIANCE,2487,600,500,900,cpPaused=true,
        energy=TacticalEnergy(listOf(100,50)+List(9){0}),
        motion=TacticalMotion(2487,seconds-5,pose,0f,pose.direction,listOf(pose.position)))
    private val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,captainCard=195,shipKind=89,
        tacticalRetreat=listOf(1010000))
    private class Store(initial:Map<String,Navigator>):NavigationStore {
        var players=initial;var fail=false
        override fun load()=players
        override fun save(players:Map<String,Navigator>){if(fail)throw java.io.IOException("disk");this.players=players.toMap()}
    }
    private fun store(player:Navigator=pilot)=Store(mapOf("pilot" to player,
        "enemy" to pilot.copy(characterId=10001,power=Power.EMPIRE)))
    @Test fun validatesAuthorityWarpMaximumAndRadiusBeforePersistingAndDoesNotExtendExistingDeadline() {
        val store=store();val nav=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.UNIT,nav.apply(request.copy(tacticalRetreat=listOf(1010001)),session,stocks).rejection)
        assertEquals(NavigationRejection.CARD,nav.apply(request.copy(captainCard=59),session,stocks).rejection)
        store.fail=true;assertFailsWith<java.io.IOException>{nav.apply(request,session,stocks)}
        assertNull(nav.snapshot(1).getValue("pilot").retreat);store.fail=false
        val after=nav.apply(request,session,stocks).player!!
        assertEquals(2486,after.retreat!!.destination);assertEquals(seconds+3720,after.retreat.completesAt)
        assertEquals(pilot.pcp,after.pcp);assertEquals(pilot.mcp,after.mcp);assertEquals(pilot.cruise,after.cruise)
        assertEquals(NavigationRejection.BUSY,nav.apply(request,session,stocks).rejection)
        for (variant in listOf(pilot.copy(energy=TacticalEnergy(listOf(100,49)+List(9){0})),
            pilot.copy(motion=pilot.motion!!.copy(origin=pose.copy(position=TacticalPoint(-250f,0f)))))) {
            val decision=Navigation(galaxy,store(variant),NavigationPolicy()).apply(request,session,stocks)
            assertEquals(if(variant.energy!!.values[1]==49)NavigationRejection.ENERGY else NavigationRejection.INSIDE_RADAR,decision.rejection)
        }
    }
    @Test fun preparationSurvivesReloadAllowsStopAndMovesAndCompletesOfflineWithoutMovingPeer() {
        val store=store();var nav=Navigation(galaxy,store,NavigationPolicy())
        val prepared=nav.apply(request,session,stocks).player!!
        nav=Navigation(galaxy,store,NavigationPolicy())
        val stop=request.copy(tacticalRetreat=null,tacticalStop=TacticalStop(listOf(1010000)))
        val stopped=nav.apply(stop,session.copy(gameMillis=session.gameMillis+120000),stocks).player!!
        assertEquals(prepared.retreat,stopped.retreat)
        val moving=nav.apply(request.copy(tacticalRetreat=null,tacticalMove=TacticalMove(listOf(1010000),1f,0f,
            listOf(TacticalPoint(-400f,0f)))),session.copy(gameMillis=session.gameMillis+180000),stocks).player!!
        assertEquals(prepared.retreat,moving.retreat)
        val deadline=prepared.retreat!!.completesAt
        nav.settleRetreats(session.copy(gameMillis=(deadline-1)*1000),stocks)
        assertEquals(2487,nav.snapshot(1).getValue("pilot").grid)
        val offline=session.copy(gameMillis=deadline*1000,participants=emptyMap())
        nav.settleRetreats(offline,stocks)
        val exited=nav.snapshot(1).getValue("pilot")
        assertEquals(2486,exited.grid);assertFalse(exited.cpPaused);assertNull(exited.motion);assertNull(exited.retreat)
        assertEquals(pilot.cruise,exited.cruise);assertEquals(pilot.moves+1,exited.moves)
        assertEquals(2487,nav.snapshot(1).getValue("enemy").grid)
        nav.settleRetreats(offline,stocks);assertEquals(exited,nav.snapshot(1).getValue("pilot"))
        val reduced=Navigation(galaxy,store(),NavigationPolicy())
        reduced.apply(request,session,stocks)
        val energy=reduced.apply(request.copy(tacticalRetreat=null,unitId=1010000,
            energy=TacticalEnergy.ZERO),session,stocks).player!!
        assertNull(energy.retreat)
    }
    @Test fun completedPreparationWaitsForLegalExitAndBattleEndCancelsItWithoutWarping() {
        val store=store();val nav=Navigation(galaxy,store,NavigationPolicy())
        val prepared=nav.apply(request,session,stocks).player!!
        val finished=session.copy(gameMillis=prepared.retreat!!.completesAt*1000)
        val blocker=OutfitStock(500,2,OutfitKind.FLEET,1000,500,500,
            UnitInventory(mapOf(UnitStack(32) to 1)),position=OutfitPosition(2486))
        val blocked=stocks.copy(outfits=mapOf(500 to blocker),
            warehouses=mapOf(500 to OutfitWarehouse(500,2,OutfitKind.FLEET,1000,500)))
        nav.settleRetreats(finished,blocked)
        assertEquals(prepared,nav.snapshot(1).getValue("pilot"))
        store.players=store.players-"enemy"
        val restored=Navigation(galaxy,store,NavigationPolicy())
        restored.settleRetreats(finished,stocks)
        val cancelled=restored.snapshot(1).getValue("pilot")
        assertEquals(2487,cancelled.grid);assertFalse(cancelled.cpPaused);assertNull(cancelled.retreat)
        assertFailsWith<IllegalArgumentException>{TacticalRetreat(2486,Long.MAX_VALUE,120,3600)}
        assertFailsWith<IllegalArgumentException>{prepared.copy(retreat=prepared.retreat.copy(destination=2000))}
    }
}
