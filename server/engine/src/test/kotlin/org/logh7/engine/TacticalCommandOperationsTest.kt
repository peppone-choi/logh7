package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalCommandOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {
        it.join("pilot",Power.EMPIRE,false,"one");it.join("ally",Power.EMPIRE,false,"two")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Battle",2)),
        listOf(GalaxyBase(1000,2488,80,1,"Fortress",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2488})
    private fun motion(x:Float=0f,y:Float=0f)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,y),0f),0f,0f,listOf(TacticalPoint(x,y)))
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val stock=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state():State {
        val carrier=UnitStack(64);val repair=UnitStack(72);val enemy=UnitStack(119)
        fun unit(id:Int,stack:UnitStack,y:Float,supplies:Int)=ActiveShipUnit(id,stack,tactical=NpcTacticalState(motion(0f,y),now,now),supplies=supplies)
        val ships=ActiveShipUnits(listOf(unit(1,carrier,.4f,100),unit(2,repair,.8f,0)))
        val enemyShips=ActiveShipUnits(listOf(unit(99,enemy,1.5f,0)))
        val players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,cpPaused=true,
            hull=FlagshipHull(0,now),motion=motion(),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM)),
            "ally" to Navigator(1,10001,Power.EMPIRE,2488,100,100,1000,cpPaused=true,hull=FlagshipHull(0,now),
                motion=motion(100f),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM)))
        return State(players,PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1000,0,0,garrisonCommanderId=10000)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships.quantities()),supplies=100,
                activeShips=ships,position=OutfitPosition(2488)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,units=UnitInventory(enemyShips.quantities()),
                    activeShips=enemyShips,position=OutfitPosition(2488))),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100))
    }
    private class Run(val state:State,val navigation:Navigation,val production:PlanetProduction)
    private fun run(s:State=state()):Run=Run(s,Navigation(galaxy,s.nav,NavigationPolicy(),clockScale=1),
        PlanetProduction(galaxy,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)})
    private fun request(c:TacticalCommand,account:String="pilot")=NavigationRequest(account,if(account=="pilot")10000 else 10001,
        Power.EMPIRE,if(account=="pilot")"one" else "two",1,captainCard=59,shipKind=0,rank=if(account=="pilot")10 else 20,
        tacticalCommand=c,tacticalRanks=mapOf(10000 to 10,10001 to 20))
    private fun accept(r:Run,c:TacticalCommand,account:String="pilot")=TacticalCommandOperations.accept(request(c,account),session,galaxy,
        r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1)
    private fun settle(r:Run,at:Long,events:MutableList<TacticalCommandResult> = mutableListOf(),impacts:MutableList<TacticalImpact> = mutableListOf())=
        TacticalCombat.settle(session.copy(gameMillis=at*1000),galaxy,r.navigation,r.production,r.state.commit,scale=1,onCommand=events::add).also {impacts+=it}

    @Test fun formationWaitsSavesAndMovesRealUnitsAfterReloadWithoutRepeatedOrders() {
        val r=run();assertNotNull(accept(r,TacticalCommand(0x40d,listOf(1,2),kind=6)).player)
        assertEquals(0f,r.state.stocks.outfits.getValue(1).activeShips!!.values[0].tactical!!.motion.origin.position.x)
        val events=mutableListOf<TacticalCommandResult>();settle(r,now+4,events);assertTrue(events.isEmpty())
        val restored=run(r.state);settle(restored,now+5,events)
        assertEquals(1,events.size);assertFalse(events.single().cancelled)
        assertEquals(6,r.state.stocks.tacticalCommands.formations[10000]);assertTrue(r.state.stocks.tacticalCommands.orders.isEmpty())
        assertEquals(2,events.single().motions.size)
        assertNotEquals(TacticalPoint(0f,.8f),r.state.stocks.outfits.getValue(1).activeShips!!.values[1].tactical!!.motion.waypoints.single())
        settle(restored,now+20,events);assertEquals(1,events.size)
    }
    @Test fun carrierLaunchSpendsExactlyTenAndLandingRestoresTargetSpeed() {
        val s=state();val foe=s.stocks.outfits.getValue(2)
        val moving=foe.activeShips!!.values.single().let {it.copy(tactical=it.tactical!!.copy(motion=motion(0f,1.5f).copy(velocity=.5f,waypoints=listOf(TacticalPoint(0f,40f)))))}
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to foe.copy(activeShips=ActiveShipUnits(listOf(moving)))))
        val r=run(s);assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits)
        assertEquals(90,s.stocks.outfits.getValue(1).supplies);assertTrue(hits.single().airBattle)
        assertEquals(.25f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
        // CRC regrowth is required for another squadron command.
        val later=session.copy(gameMillis=(now+20)*1000)
        assertNotNull(TacticalCommandOperations.accept(request(TacticalCommand(0x40e,listOf(1),kind=2)),later,galaxy,
            r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+25)
        assertTrue(s.stocks.tacticalCommands.air.isEmpty());assertEquals(90,s.stocks.outfits.getValue(1).supplies)
        assertEquals(.5f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
    }
    @Test fun invalidAuthorityInsufficientCpAndDiskFailureNeverPublishSuccess() {
        val r=run();val c=TacticalCommand(0x40d,listOf(1,2),kind=1)
        assertEquals(NavigationRejection.CARD,accept(r,c,"ally").rejection)
        assertEquals(NavigationRejection.UNIT,accept(r,TacticalCommand(0x40d)).rejection)
        val before=r.state.stocks;val players=r.navigation.snapshot(1)
        r.state.fail=true;assertFailsWith<java.io.IOException> {accept(r,c)}
        assertEquals(before,r.state.stocks);assertEquals(players,r.navigation.snapshot(1));assertTrue(r.state.stocks.tacticalCommands.orders.isEmpty())
        r.state.fail=false
        val low=state();low.players=low.players.mapValues {(_,p)->p.copy(pcp=0)}
        low.stocks=low.stocks.copy(tacticalCommands=TacticalCommandState(morale=mapOf(1 to 50)))
        assertEquals(NavigationRejection.CP,accept(run(low),TacticalCommand(0x409,listOf(1010000))).rejection)
    }
    @Test fun fortressSupplyConservesStockAndStopCancelsPendingCannon() {
        val r=run();val supplies=r.state.stocks.bases.getValue(1000).supplies+r.state.stocks.outfits.getValue(1).supplies
        assertNotNull(accept(r,TacticalCommand(0x41c,listOf(2),base=1000)).player)
        settle(r,now+5)
        assertEquals(supplies,r.state.stocks.bases.getValue(1000).supplies+r.state.stocks.outfits.getValue(1).supplies)
        assertTrue(OutfitSupplies.distribution(r.state.stocks.outfits.getValue(1)).getValue(2)>0)
        val later=session.copy(gameMillis=(now+6)*1000)
        val cannon=request(TacticalCommand(0x419,base=1000,direction=0f))
        assertNotNull(TacticalCommandOperations.accept(cannon,later,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1).player)
        val stop=cannon.copy(tacticalCommand=TacticalCommand(0x41e,base=1000))
        val stopped=TacticalCommandOperations.accept(stop,later,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1)
        assertTrue(stopped.commandNotifications.any {it.cancelled});assertTrue(r.state.stocks.tacticalCommands.orders.isEmpty())
        assertTrue(settle(r,now+60).isEmpty())
    }
    @Test fun supremeMissionAndSubordinateProposalRemainDistinctDurableEffects() {
        val r=run();val order=TacticalCommand(0x421,listOf(1010001),kind=1,target=1000)
        assertNotNull(accept(r,order).player);settle(r,now+5)
        assertEquals(1,r.state.stocks.tacticalCommands.missions[10001]?.mission)
        val proposal=TacticalCommand(0x421,listOf(1010001),kind=5)
        assertNotNull(accept(r,proposal,"ally").player);settle(r,now+10)
        assertEquals(5,r.state.stocks.tacticalCommands.proposals[10001]?.mission)
        assertEquals(1,r.state.stocks.tacticalCommands.missions[10001]?.mission)
        settle(r,now+5+TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS)
        assertEquals(1L,r.state.stocks.tacticalCommands.merits[10001])
    }
    @Test fun confusionBlocksRegularCommandsAndEncouragementActuallyRecoversMorale() {
        val s=state();s.stocks=s.stocks.copy(tacticalCommands=TacticalCommandState(morale=mapOf(1 to 15)))
        val r=run(s);val order=request(TacticalCommand(0x40d,listOf(1),kind=0))
        assertEquals(NavigationRejection.ACCESS,TacticalCommandOperations.guard(order,session,r.navigation,s.stocks)?.rejection)
        assertNotNull(accept(r,TacticalCommand(0x409,listOf(1010000))).player);settle(r,now+5)
        assertEquals(35,s.stocks.tacticalCommands.morale[1]);assertFalse(s.stocks.tacticalCommands.confused(1))
        assertEquals(90,s.players.getValue("pilot").pcp)
    }
    @Test fun regularRetreatMovesOnlySelectedStableIdsAndLeavesLandedTroopsBehind() {
        val s=state();s.players=s.players.mapValues {(account,p)->if(account=="pilot")p.copy(motion=motion(260f)) else p}
        val o=s.stocks.outfits.getValue(1)
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(1),base=1000,origin=1)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to o.copy(activeGround=ground,
            units=UnitInventory(o.units.ships,ground.quantities()),activeShips=ActiveShipUnits(o.activeShips!!.values.map {
                it.copy(tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now))}))))
        val r=run(s);val req=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        assertNotNull(TacticalFleetRetreat.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+5)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.retreat!!.membershipChecked)
        settle(r,now+154);assertEquals(2,s.stocks.outfits.getValue(1).activeShips!!.values.size)
        settle(r,now+155)
        val moved=s.stocks.outfits.values.single {it.activeShips?.values?.any {u->u.id==1}==true}
        assertNotEquals(2488,moved.positionIn(galaxy).grid)
        assertEquals(listOf(2),s.stocks.outfits.getValue(1).activeShips!!.values.map {it.id})
        assertEquals(1000,s.stocks.outfits.getValue(1).activeGround!!.values.single().base)
        assertEquals(10000,TacticalAuthority.commander(moved,moved.activeShips!!.values.single(),s.stocks))
        assertEquals(100,s.stocks.outfits.values.sumOf {it.supplies})
    }
    @Test fun fortressCannonHitsFriendlyAndEnemyShipsAlongTheRay() {
        val r=run();val own=r.state.stocks.outfits.getValue(1).activeShips!!.values.first().boats
        val enemy=r.state.stocks.outfits.getValue(2).activeShips!!.values.single().boats
        assertNotNull(accept(r,TacticalCommand(0x419,base=1000,direction=0f)).player)
        val impacts=mutableListOf<TacticalImpact>();settle(r,now+34,impacts=impacts);assertTrue(impacts.isEmpty())
        settle(r,now+35,impacts=impacts)
        assertTrue(r.state.stocks.outfits.getValue(1).activeShips!!.values.first().boats<own)
        assertTrue(r.state.stocks.outfits.getValue(2).activeShips!!.values.single().boats<enemy)
        assertTrue(impacts.any {it.hit.target==1});assertTrue(impacts.any {it.hit.target==99})
    }
    @Test fun baseStopAlsoCancelsTheActualMovingFortressWithoutSnappingToItsSeed() {
        val r=run();assertNotNull(accept(r,TacticalCommand(0x41f,base=1000,velocity=.5f,points=listOf(TacticalPoint(20f,0f)))).player)
        settle(r,now+20)
        val current=TacticalBasePosition.at(galaxy.bases.single(),now+20,r.state.stocks)
        assertEquals(TacticalPoint(7.5f,0f),current) // five-second wait, then fifteen seconds at .5
        val req=request(TacticalCommand(0x41e,base=1000)).copy(tacticalCommand=null,tacticalStop=TacticalStop(emptyList(),listOf(1000)))
        assertNotNull(TacticalSupportOperations.stop(req,session.copy(gameMillis=(now+20)*1000),galaxy,r.navigation,r.production,
            r.production.snapshot(1)!!,r.state.commit).player)
        assertEquals(current,TacticalBasePosition.at(galaxy.bases.single(),now+60,r.state.stocks))
    }
    @Test fun arrivingFortressKeepsTheAnchoredShipOffsetWhenItsPathIsReplacedByAStop() {
        val s=state();val outfit=s.stocks.outfits.getValue(1)
        val anchored=outfit.activeShips!!.values.map {if(it.id==1)it.copy(posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,1000)) else it}
        val fortress=motion().copy(velocity=.5f,waypoints=listOf(TacticalPoint(20f,0f)),waitSeconds=0)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(anchored))),
            tacticalCommands=TacticalCommandState(fortresses=mapOf(1000 to TacticalFortressMotion(2488,fortress))))
        val r=run(s);settle(r,now+40)
        val ship=s.stocks.outfits.getValue(1).activeShips!!.values.single {it.id==1}
        assertEquals(TacticalPoint(20f,.4f),ship.tactical!!.motion.pose(now+40,ship.posture,galaxy,s.stocks).position)
        assertEquals(TacticalPoint(20f,.4f),ship.tactical!!.motion.pose(now+60,ship.posture,galaxy,s.stocks).position)
        assertEquals(0f,s.stocks.tacticalCommands.fortresses.getValue(1000).motion.velocity)
    }
    @Test fun emergencySupplyOpensTheSelectedBaseAndDoesNotInventOrTeleportCargo() {
        val r=run();val before=r.state.stocks.bases.getValue(1000).supplies
        assertNotNull(accept(r,TacticalCommand(0x422,base=1000)).player);settle(r,now+5)
        assertEquals(2,r.state.stocks.tacticalCommands.emergencySupplies[1000])
        assertEquals(before,r.state.stocks.bases.getValue(1000).supplies)
        assertEquals(100,r.state.stocks.outfits.getValue(1).supplies)
        assertEquals(90,r.state.players.getValue("pilot").pcp)
        assertEquals(NavigationRejection.BUSY,accept(r,TacticalCommand(0x422,base=1000)).rejection)
    }
    @Test fun boardingRequiresAssaultFlagshipAndInjuresTheCaptainWithoutReplacingHisHull() {
        val s=state();val source=s.players.getValue("pilot").copy(hull=FlagshipHull(4,now))
        val target=Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,hull=FlagshipHull(89,now),motion=motion(0f,.5f))
        s.players=s.players+("pilot" to source)+("enemy" to target)
        val r=run(s);val command=TacticalCommand(0x407,listOf(source.unitId),target=target.unitId)
        val req=request(command).copy(shipKind=4)
        assertNotNull(TacticalCommandOperations.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits)
        assertTrue(hits.single().boarding);assertEquals(0,hits.single().hit.destroyed)
        assertEquals(89,s.players.getValue("enemy").hull!!.kind)
        assertTrue(s.players.getValue("enemy").hull!!.woundedUntil>now+5)
        assertEquals(target.supplies,s.players.getValue("enemy").supplies)
    }
}
