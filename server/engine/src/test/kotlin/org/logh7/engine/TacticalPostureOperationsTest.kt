package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalPostureOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.EMPIRE,false,"current")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",2)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,0f,86400,0,0f,2f),
            GalaxyBase(1001,2487,80,0,"Enemy",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private fun motion(y:Float)=TacticalMotion(2487,now,TacticalPose(TacticalPoint(0f,y),0f),0f,0f,listOf(TacticalPoint(0f,y)))
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false;var saves=0
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");saves++;players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state():State {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion(0.8f),now,now)),
            ActiveShipUnit(2,UnitStack(32),tactical=NpcTacticalState(motion(1f),now,now))))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships=ships.quantities()),
            activeShips=ships,position=OutfitPosition(2487))
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,900,cpPaused=true,motion=motion(0.5f),hull=FlagshipHull(0,now))
        return State(mapOf("pilot" to player),PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),
            mapOf(1 to outfit),mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),nextShipId=3))
    }
    private fun production(s:State)=PlanetProduction(galaxy,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
    private fun request(mode:TacticalShipMode=TacticalShipMode.BATTLE,base:Int=0,ids:List<Int> = listOf(1010000,1,2))=
        NavigationRequest("pilot",10000,Power.EMPIRE,"current",1,captainCard=59,shipKind=0,
            tacticalPosture=TacticalPostureChange(ids,mode,base))
    private fun accept(s:State,n:Navigation,p:PlanetProduction,r:NavigationRequest=request())=
        TacticalPostureOperations.accept(r,session,galaxy,n,p,p.snapshot(1)!!,s.commit,24)
    private fun settle(s:State,n:Navigation,p:PlanetProduction,at:Long,out:MutableList<TacticalPostureResult>)=
        TacticalCombat.settle(session.copy(gameMillis=at*1000),galaxy,n,p,s.commit,onPosture=out::add)

    @Test fun mixedSelectionWaitsTenRealSecondsReopensAndCompletesExactlyOnce() {
        val s=state();var n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val result=accept(s,n,p);assertNotNull(result.player);assertEquals(now+240,result.postureReadyAt)
        assertEquals(TacticalShipMode.SAILING,s.players.getValue("pilot").posture!!.mode)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture!!.order!!.readyAt==now+240})
        assertEquals(NavigationRejection.BUSY,accept(s,n,p).rejection)
        val out=mutableListOf<TacticalPostureResult>();settle(s,n,p,now+239,out);assertTrue(out.isEmpty())
        n=Navigation(galaxy,s.nav,NavigationPolicy());settle(s,n,p,now+240,out)
        assertEquals(3,out.size);assertEquals(setOf(1010000,1,2),out.flatMap {it.units.keys}.toSet())
        assertTrue(out.all {!it.cancelled && it.recipients==mapOf(10000 to "current")})
        assertEquals(TacticalPosture(2487,TacticalShipMode.BATTLE),s.players.getValue("pilot").posture)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture==TacticalPosture(2487,TacticalShipMode.BATTLE)})
        assertEquals(1000,s.players.getValue("pilot").mcp)
        settle(s,n,p,now+480,out);assertEquals(3,out.size)
    }
    @Test fun deceasedCommanderCannotChangePostureAndSavedStationingIsCancelled() {
        val s=state()
        val command=request(TacticalShipMode.STATIONED,1000)
        assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),command).player)
        s.stocks=BattleDeath.apply(s.stocks,listOf(10000),now) {_,_->true}
        val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val saves=s.saves
        assertEquals(NavigationRejection.IDENTITY,accept(s,n,p,command).rejection)
        assertEquals(saves,s.saves)
        val out=mutableListOf<TacticalPostureResult>();settle(s,n,p,now+240,out)
        assertEquals(setOf(1010000,1,2),out.flatMap {it.units.keys}.toSet())
        assertTrue(out.all {it.cancelled})
        val player=s.players.getValue("pilot")
        assertEquals(0,player.baseId);assertTrue(player.cpPaused)
        assertEquals(TacticalPosture(2487),player.posture)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture==TacticalPosture(2487)})
    }
    @Test fun eliminatedCountryCancelsSavedStationingForFlagshipAndFleet() {
        val s=state()
        val command=request(TacticalShipMode.STATIONED,1000)
        val readyAt=assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),command).postureReadyAt)
        val defeated=session.copy(gameMillis=readyAt*1000,participants=emptyMap(),
            eliminated=mapOf(Power.EMPIRE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        val out=mutableListOf<TacticalPostureResult>()
        TacticalCombat.settle(defeated,galaxy,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),
            s.commit,onPosture=out::add)

        assertEquals(setOf(1010000,1,2),out.flatMap {it.units.keys}.toSet())
        assertTrue(out.all {it.cancelled})
        val pilot=s.players.getValue("pilot")
        assertEquals(0,pilot.baseId)
        assertEquals(TacticalPosture(2487),pilot.posture)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture==TacticalPosture(2487)})
    }

    @Test fun mixedStationingCompletesForShipsEvenWhenTheirCommanderEntersTheBase() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertNotNull(accept(s,n,p,request(TacticalShipMode.STATIONED,1000)).player)
        val out=mutableListOf<TacticalPostureResult>();settle(s,n,p,now+240,out)
        val player=s.players.getValue("pilot")
        assertEquals(1000,player.baseId);assertEquals(4,player.mode);assertFalse(player.cpPaused)
        assertTrue(out.all {!it.cancelled});assertEquals(3,out.size)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture!!.mode==TacticalShipMode.STATIONED})
        assertNull(TacticalCombat.target(1,2487,Camp(Power.ALLIANCE,false),s.stocks,galaxy,s.players))
    }
    @Test fun rebelStationingChecksCurrentBaseAndOutfitCamp() {
        for(change in listOf("none","base","outfit")) {
            val s=state()
            val home=s.stocks.bases.getValue(1000).copy(camp=1)
            val regular=s.stocks.bases.getValue(1001).copy(owner=Allegiance.fromLegacy(2))
            val plot=Plot(10000,Power.EMPIRE.country,0,revolt=5,bases=setOf(1000))
            val coup=CoupState(mapOf(10000 to plot),loyalty=mapOf(1 to 100))
            s.stocks=s.stocks.copy(bases=mapOf(1000 to home,1001 to regular),social=s.stocks.social.copy(coup=coup))
            val n=Navigation(galaxy,s.nav,NavigationPolicy())
            val p=production(s)
            assertEquals(NavigationRejection.BASE,accept(s,n,p,request(TacticalShipMode.STATIONED,1001)).rejection)
            if(change=="none") {
                val orbit=state()
                orbit.stocks=s.stocks
                assertNotNull(accept(orbit,Navigation(galaxy,orbit.nav,NavigationPolicy()),production(orbit),
                    request(TacticalShipMode.ANCHORED,1001)).player)
            }
            assertNotNull(accept(s,n,p,request(TacticalShipMode.STATIONED,1000)).player)
            if(change=="base")s.stocks=s.stocks.copy(bases=s.stocks.bases+(1000 to home.copy(camp=0)))
            if(change=="outfit")s.stocks=s.stocks.copy(social=s.stocks.social.copy(coup=coup.copy(loyalty=emptyMap())))
            val out=mutableListOf<TacticalPostureResult>()
            settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+240,out)
            assertEquals(3,out.size)
            val expected=when(change) {
                "base" -> setOf(1010000,1,2)
                "outfit" -> setOf(1,2)
                else -> emptySet()
            }
            assertEquals(expected,out.filter {it.cancelled}.flatMap {it.units.keys}.toSet())
        }
    }
    @Test fun selectionOwnershipBaseCampAndProximityAreCheckedBeforeAnySave() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.players;val stocks=s.stocks
        for((r,rejection) in listOf(request(ids=listOf(1010000,999)) to NavigationRejection.UNIT,
            request(TacticalShipMode.STATIONED,1001) to NavigationRejection.BASE,
            request(TacticalShipMode.ANCHORED,999) to NavigationRejection.BASE,
            request().copy(characterId=10001) to NavigationRejection.IDENTITY,
            request().copy(connection="stale") to NavigationRejection.SESSION)) {
            assertEquals(rejection,accept(s,n,p,r).rejection);assertEquals(before,s.players);assertEquals(stocks,s.stocks)
        }
        assertNotNull(accept(s,n,p,request(TacticalShipMode.ANCHORED,1001)).player) // Enemy orbit is allowed.
        val fresh=state();val own=fresh.stocks.outfits.getValue(1)
        fresh.stocks=fresh.stocks.copy(outfits=mapOf(1 to own.copy(commanderId=10001)))
        assertEquals(NavigationRejection.CARD,accept(fresh,Navigation(galaxy,fresh.nav,NavigationPolicy()),production(fresh),request(ids=listOf(1))).rejection)
        val far=state();far.players=far.players.mapValues {(_,v)->v.copy(motion=motion(20f))}
        assertEquals(NavigationRejection.BASE,accept(far,Navigation(galaxy,far.nav,NavigationPolicy()),production(far),
            request(TacticalShipMode.ANCHORED,1000,listOf(1010000))).rejection)
    }
    @Test fun mixedDockingUsesThePlanetApproachMarginAndKeepsTheFortressLimit() {
        for(mode in listOf(TacticalShipMode.ANCHORED,TacticalShipMode.STATIONED))
            for((kind,distance,allowed) in listOf(Triple(0,8f,true),Triple(0,8.001f,false),Triple(1,5.5f,false))) {
                val local=Galaxy(galaxy.systems,galaxy.bases.map {if(it.id==1000)it.copy(kind=kind) else it},galaxy.starts)
                val s=state()
                s.players=s.players.mapValues {(_,p)->p.copy(motion=motion(distance))}
                val own=s.stocks.outfits.getValue(1)
                s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(activeShips=ActiveShipUnits(own.activeShips!!.values.map {
                    if(it.id==1)it.copy(tactical=it.tactical!!.copy(motion=motion(distance))) else it
                }))))
                val n=Navigation(local,s.nav,NavigationPolicy())
                val production=PlanetProduction(local,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
                val result=TacticalPostureOperations.accept(request(mode,1000,listOf(1010000,1)),session,local,n,production,
                    production.snapshot(1)!!,s.commit,24)
                if(!allowed) {
                    assertEquals(NavigationRejection.BASE,result.rejection);assertEquals(0,s.saves)
                } else {
                    assertNull(result.rejection);assertEquals(now+240,result.postureReadyAt)
                    val out=mutableListOf<TacticalPostureResult>()
                    TacticalCombat.settle(session.copy(gameMillis=(now+240)*1000),local,n,production,s.commit,onPosture=out::add)
                    assertEquals(2,out.size);assertTrue(out.all {!it.cancelled && it.mode==mode})
                }
            }
    }
    @Test fun stopClearsThePendingMixedOrderAndCommandGatePreventsRacingMovement() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        accept(s,n,p)
        val move=request().copy(tacticalPosture=null,tacticalRetreat=listOf(1010000,1))
        assertEquals(NavigationRejection.BUSY,TacticalPostureOperations.guard(move,session,n,p.snapshot(1)!!)!!.rejection)
        val stop=request().copy(tacticalPosture=null,tacticalStop=TacticalStop(listOf(1010000,1,2)))
        assertNotNull(TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+24)*1000),galaxy,n,p,p.snapshot(1)!!,s.commit).player)
        assertNull(s.players.getValue("pilot").posture!!.order)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture!!.order==null})
        val out=mutableListOf<TacticalPostureResult>();settle(s,n,p,now+240,out);assertTrue(out.isEmpty())
    }
    @Test fun lostTargetOwnershipCancelsAtExecutionAndAChangedConnectionReceivesOnlyCurrentEvents() {
        val s=state();var n=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        accept(s,n,p,request(TacticalShipMode.STATIONED,1000))
        s.stocks=s.stocks.copy(bases=s.stocks.bases+(1000 to s.stocks.bases.getValue(1000).copy(owner=Allegiance.fromLegacy(3))))
        n=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        val out=mutableListOf<TacticalPostureResult>()
        val changed=session.copy(gameMillis=(now+240)*1000,participants=session.participants.mapValues {(_,m)->m.copy(connection="new")})
        TacticalCombat.settle(changed,galaxy,n,p,s.commit,onPosture=out::add)
        assertEquals(3,out.size);assertTrue(out.all {it.cancelled && it.recipients==mapOf(10000 to "new")})
        assertEquals(0,s.players.getValue("pilot").baseId)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture!!.mode==TacticalShipMode.SAILING && it.posture.order==null})
    }
    @Test fun failedAcceptanceOrExecutionPublishesNeitherPostureNorNotification() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val players=s.players;val stocks=s.stocks;s.fail=true
        assertFailsWith<java.io.IOException>{accept(s,n,p)}
        assertEquals(players,n.snapshot(1));assertEquals(stocks,p.snapshot(1))
        s.fail=false;accept(s,n,p);val accepted=n.snapshot(1);val inventory=p.snapshot(1);s.fail=true
        val out=mutableListOf<TacticalPostureResult>()
        assertFailsWith<java.io.IOException>{settle(s,n,p,now+240,out)}
        assertEquals(accepted,n.snapshot(1));assertEquals(inventory,p.snapshot(1));assertTrue(out.isEmpty())
    }
    @Test fun acceptedPostureContinuesOfflineWithoutAddressingANullConnection() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        accept(s,n,p,request(ids=listOf(1010000)))
        val offline=session.copy(gameMillis=(now+240)*1000,participants=session.participants.mapValues {(_,m)->m.copy(online=false,connection=null)})
        val out=mutableListOf<TacticalPostureResult>()
        TacticalCombat.settle(offline,galaxy,n,p,s.commit,onPosture=out::add)
        assertEquals(TacticalShipMode.BATTLE,s.players.getValue("pilot").posture!!.mode)
        assertTrue(out.single().recipients.isEmpty());assertFalse(out.single().cancelled)
    }
    @Test fun stationedShipsSortieOneAtATimeAndRestoreTheirSavedSchedule() {
        val s=state()
        val own=s.stocks.outfits.getValue(1)
        s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(activeShips=ActiveShipUnits(own.activeShips!!.values.map {
            it.copy(posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000))}))))
        var n=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        val sortie=request(ids=listOf(1,2)).copy(tacticalPosture=TacticalPostureChange(listOf(1,2),TacticalShipMode.ANCHORED,0,true))
        val accepted=accept(s,n,p,sortie)
        assertEquals(now+45*24,accepted.postureReadyAt)
        val due=s.stocks.outfits.getValue(1).activeShips!!.values.map {it.posture!!.order!!.readyAt}.sorted()
        assertEquals(listOf(now+25*24,now+45*24),due)
        n=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        val out=mutableListOf<TacticalPostureResult>()
        settle(s,n,p,now+25*24-1,out);assertTrue(out.isEmpty())
        settle(s,n,p,now+25*24,out);assertEquals(1,out.size);assertTrue(out.single().sortie)
        assertEquals(1,s.stocks.outfits.getValue(1).activeShips!!.values.count {it.posture!!.mode==TacticalShipMode.STATIONED})
        settle(s,n,p,now+45*24,out);assertEquals(2,out.size)
        assertTrue(out.all {it.sortie && !it.cancelled && it.base==1000})
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.posture==TacticalPosture(2487,TacticalShipMode.ANCHORED,1000)})
        settle(s,n,p,now+60*24,out);assertEquals(2,out.size)
    }
    @Test fun sortieRejectsSailingUnitsAndMixedPersonalSelectionWithoutSaving() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks
        val command=TacticalPostureChange(listOf(1),TacticalShipMode.ANCHORED,0,true)
        assertEquals(NavigationRejection.BUSY,accept(s,n,p,request(ids=listOf(1)).copy(tacticalPosture=command)).rejection)
        assertEquals(NavigationRejection.UNIT,accept(s,n,p,request().copy(tacticalPosture=command.copy(units=listOf(1010000)))).rejection)
        assertEquals(before,s.stocks);assertEquals(0,s.saves)
    }
    @Test fun nonzeroRadiusUsesTheCurrentOrbitAndRechecksItsScheduledCompletionPosition() {
        for(cycle in listOf(86400,1000)) {
            val moving=Galaxy(galaxy.systems,galaxy.bases.map {it.copy(radius=20f,cycle=cycle)},galaxy.starts)
            val s=state()
            val at=TacticalBasePosition.at(moving.bases.first(),now)
            fun located(offset:Float)=TacticalMotion(2487,now,TacticalPose(TacticalPoint(at.x,at.y+offset),0f),0f,0f,
                listOf(TacticalPoint(at.x,at.y+offset)))
            s.players=s.players.mapValues {(_,v)->v.copy(motion=located(-.5f))}
            val own=s.stocks.outfits.getValue(1)
            s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(activeShips=ActiveShipUnits(own.activeShips!!.values.map {
                it.copy(tactical=NpcTacticalState(located(.5f),now,now))}))))
            val n=Navigation(moving,s.nav,NavigationPolicy())
            val p=PlanetProduction(moving,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
            assertNotNull(TacticalPostureOperations.accept(request(TacticalShipMode.ANCHORED,1000,listOf(1)),
                session,moving,n,p,p.snapshot(1)!!,s.commit,24).player)
            val out=mutableListOf<TacticalPostureResult>()
            TacticalCombat.settle(session.copy(gameMillis=(now+480)*1000),moving,n,p,s.commit,onPosture=out::add)
            assertEquals(cycle==1000,out.single().cancelled)
            assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.first().posture!!.order)
            if(cycle==86400) {
                val unit=s.stocks.outfits.getValue(1).activeShips!!.values.first()
                assertEquals(now+240,unit.tactical!!.motion.acceptedAt)
                assertEquals(unit.tactical.motion.pose(now+480,unit.posture,moving),out.single().units.getValue(1))
                assertTrue(out.single().units.getValue(1).position.x>at.x)
            }
        }
    }
}
