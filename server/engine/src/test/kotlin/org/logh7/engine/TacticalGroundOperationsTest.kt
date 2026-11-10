package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalGroundOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.EMPIRE,false,"current")}.exportState()
    private val now=session.gameMillis/1000
    private fun galaxy(model:Int)=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),
        listOf(GalaxyBase(1000,2487,model,0,"Here",0,0f,86400,0,0f,2f),GalaxyBase(1001,2487,80,0,"Enemy",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private var galaxy=galaxy(80)
    private fun motion()=TacticalMotion(2487,now,TacticalPose(TacticalPoint(0f,0f),0f),0f,0f,listOf(TacticalPoint(0f,0f)))
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false;var saves=0
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");saves++;players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state(defenders:Int=0,kind:Int=0):State {
        val ships=ActiveShipUnits(listOf(1,4).map {ActiveShipUnit(it,UnitStack(32),
            tactical=NpcTacticalState(motion(),now,now),posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1000))})
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(kind),carrier=1),ActiveGroundUnit(3,UnitStack(1),carrier=1),
            ActiveGroundUnit(5,UnitStack(0),carrier=4))+(0 until defenders).map {ActiveGroundUnit(100+it,UnitStack(0),base=1000)})
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships.quantities(),troops.quantities()),
            activeShips=ships,activeGround=troops,position=OutfitPosition(2487))
        return State(mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,900,cpPaused=true,
            motion=motion(),hull=FlagshipHull(0,now))),PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),nextShipId=1000))
    }
    private fun production(s:State)=PlanetProduction(galaxy,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
    private fun request(action:TacticalGroundAction=TacticalGroundAction.DEPLOY,ids:List<Int> = listOf(1))=
        NavigationRequest("pilot",10000,Power.EMPIRE,"current",1,shipKind=0,tacticalGround=TacticalGroundCommand(action,ids))
    private fun accept(s:State,n:Navigation,p:PlanetProduction,r:NavigationRequest=request(),time:Long=now)=
        TacticalGroundOperations.accept(r,session.copy(gameMillis=time*1000),galaxy,n,p,p.snapshot(1)!!,s.commit,24)
    private fun settle(s:State,n:Navigation,p:PlanetProduction,time:Long,events:MutableList<TacticalGroundResult>)=
        TacticalCombat.settle(session.copy(gameMillis=time*1000),galaxy,n,p,s.commit,onGround=events::add)
    @Test fun deployAndWithdrawWaitPersistAndKeepTheSameTroopIdentitiesWithoutDuplication() {
        val s=state();var n=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        val accepted=accept(s,n,p);assertNotNull(accepted.player);assertEquals(now+600,accepted.groundReadyAt)
        assertEquals(NavigationRejection.BUSY,accept(s,n,p).rejection)
        val events=mutableListOf<TacticalGroundResult>();settle(s,n,p,now+599,events);assertTrue(events.isEmpty())
        n=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        settle(s,n,p,now+600,events)
        assertEquals(listOf(2,3),events.single().troops);assertFalse(events.single().cancelled)
        assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}.all {it.base==1000 && it.origin==1 && it.carrier==0 && it.order==null})
        assertEquals(2000,TacticalBaseDefense.strength(s.stocks,1000))
        settle(s,n,p,now+700,events);assertEquals(1,events.size)
        // Withdrawal starts from the current clock and keeps the saved return carrier.
        assertNotNull(accept(s,n,p,request(TacticalGroundAction.WITHDRAW),now+700).player)
        settle(s,n,p,now+1300,events);assertEquals(2,events.size)
        assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}.all {it.carrier==1 && it.base==0 && it.origin==0})
        assertEquals(0,TacticalBaseDefense.strength(s.stocks,1000));assertEquals(1000,s.stocks.nextShipId)
    }
    @Test fun onlyWithdrawalOfTheLastOpposingTroopsEndsTheBattleAfterCommit() {
        for ((defenders,opposed) in listOf(0 to true,1 to true,0 to false)) {
            val s=state(defenders)
            val own=s.stocks.outfits.getValue(1)
            val landed=ActiveGroundUnits(own.activeGround!!.values.map {
                if(it.carrier==1)it.copy(carrier=0,base=1000,origin=1) else it
            })
            val enemy=ActiveGroundUnits(listOf(ActiveGroundUnit(50,UnitStack(4),base=1000)))
            val outfits=mapOf(1 to own.copy(activeGround=landed)) + (if(opposed)mapOf(
                9 to OutfitStock(9,3,OutfitKind.GROUND,1000,11,units=UnitInventory(troops=enemy.quantities()),activeGround=enemy)) else emptyMap())
            s.stocks=s.stocks.copy(outfits=outfits,warehouses=s.stocks.warehouses+(11 to OutfitWarehouse(11,3,OutfitKind.GROUND,1000)),
                bases=s.stocks.bases+(1000 to PlanetStock(1000,if(opposed)3 else 2,0,0,0,0)),
                groundBattles=if(opposed)mapOf(1000 to now+1800) else emptyMap())
            val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            assertNotNull(accept(s,n,p,request(TacticalGroundAction.WITHDRAW)).player)
            val battles=mutableListOf<GroundBattleResult>()
            fun tick(time:Long)=TacticalCombat.settle(session.copy(gameMillis=time*1000),galaxy,n,p,s.commit,onGroundBattle=battles::add)
            tick(now+599);assertTrue(battles.isEmpty())
            s.fail=true
            assertFailsWith<java.io.IOException> {tick(now+600)}
            assertTrue(battles.isEmpty())
            s.fail=false
            tick(now+600);tick(now+601)
            assertEquals(if(opposed && defenders==0)listOf(GroundBattleConclusion(CountryId(3),withdrawal=true)) else emptyList(),
                battles.map {it.conclusion})
            assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}.all {it.carrier==1 && it.base==0})
            assertEquals(opposed && defenders>0,1000 in s.stocks.groundBattles)
        }
    }
    @Test fun groundOrdersLoseHostileStationedBerthsButCanUseHostileOrbit() {
        for(mode in listOf(TacticalShipMode.STATIONED,TacticalShipMode.ANCHORED)) {
            val s=state()
            val own=s.stocks.outfits.getValue(1)
            val ships=ActiveShipUnits(own.activeShips!!.values.map {
                it.copy(posture=TacticalPosture(2487,mode,1000))
            })
            s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(activeShips=ships)))
            assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).player)
            val base=s.stocks.bases.getValue(1000)
            s.stocks=s.stocks.copy(bases=s.stocks.bases+(1000 to base.copy(camp=1)))
            val n=Navigation(galaxy,s.nav,NavigationPolicy())
            val p=production(s)
            val events=mutableListOf<TacticalGroundResult>()
            val stationed=mode==TacticalShipMode.STATIONED
            if(stationed)assertEquals(NavigationRejection.BASE,accept(s,n,p,request(ids=listOf(4))).rejection)
            settle(s,n,p,now+600,events)
            assertEquals(stationed,events.single().cancelled)
            val troops=s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}
            assertEquals(setOf(if(stationed)0 else 1000),troops.map {it.base}.toSet())
            assertTrue(troops.all {it.order==null})
        }
    }
    @Test fun onlyEligibleLandedTroopsUseTheLandingCapacityOnASpecialPlanet() {
        galaxy=galaxy(30)
        for(allowed in listOf(false,true)) {
            val s=state(30)
            val outfit=s.stocks.outfits.getValue(1)
            val troops=ActiveGroundUnits(outfit.activeGround!!.values.map {
                if(it.base==1000 && !allowed)it.copy(stack=UnitStack(2)) else it
            })
            s.stocks=s.stocks.copy(outfits=mapOf(1 to outfit.copy(activeGround=troops,
                units=UnitInventory(outfit.units.ships,troops.quantities()))))
            val n=Navigation(galaxy,s.nav,NavigationPolicy())
            val p=production(s)
            val before=s.stocks
            val landing=accept(s,n,p,request(ids=listOf(4)))
            if(allowed) {
                assertEquals(NavigationRejection.GRID,landing.rejection)
                assertEquals(before,s.stocks)
                continue
            }
            assertNotNull(landing.player)
            val events=mutableListOf<TacticalGroundResult>()
            settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+600,events)
            assertFalse(events.single().cancelled)
            val landed=s.stocks.outfits.getValue(1).activeGround!!.values
            assertEquals(1000,landed.single {it.id==5}.base)
            assertEquals(troops.values.filter {it.base==1000},landed.filter {it.id>=100})
        }
    }
    @Test fun capacityIncludesOtherAcceptedLandingsAndMixedInvalidSelectionDoesNotPartiallySave() {
        val s=state(28);val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks
        assertEquals(NavigationRejection.UNIT,accept(s,n,p,request(ids=listOf(1,999))).rejection);assertEquals(before,s.stocks)
        assertNotNull(accept(s,n,p).player)
        assertEquals(NavigationRejection.GRID,accept(s,n,p,request(ids=listOf(4))).rejection)
        assertNull(s.stocks.outfits.getValue(1).activeGround!!.values.single {it.id==5}.order)
    }
    @Test fun withdrawalUsesHullCapacityIncludingTroopsAlreadyAboard() {
        fun checkWithdrawal(kind:Int,landed:Int,aboard:Int,accepted:Boolean) {
            val s=state();val own=s.stocks.outfits.getValue(1)
            val ships=ActiveShipUnits(own.activeShips!!.values.map {if(it.id==1)it.copy(stack=UnitStack(kind)) else it})
            val troops=ActiveGroundUnits(List(landed){ActiveGroundUnit(100+it,UnitStack(0),base=1000,origin=1)}+
                List(aboard){ActiveGroundUnit(200+it,UnitStack(0),carrier=1)})
            s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(activeShips=ships,activeGround=troops,
                units=UnitInventory(ships.quantities(),troops.quantities()))))
            val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            val before=s.stocks;val players=s.players
            val result=accept(s,n,p,request(TacticalGroundAction.WITHDRAW))
            if(accepted) {
                assertNotNull(result.player);assertEquals(now+600,result.groundReadyAt)
                val events=mutableListOf<TacticalGroundResult>();settle(s,n,p,now+600,events)
                assertEquals(landed,events.single().troops.size)
                assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.all {it.carrier==1 && it.base==0 && it.order==null})
                assertEquals(landed+aboard,s.stocks.outfits.getValue(1).activeGround!!.values.size)
            } else {
                assertEquals(NavigationRejection.UNIT,result.rejection)
                assertEquals(before,s.stocks);assertEquals(players,s.players);assertEquals(0,s.saves)
            }
        }
        checkWithdrawal(32,2,0,true)
        checkWithdrawal(32,3,0,false)
        checkWithdrawal(32,2,1,false)
        checkWithdrawal(80,3,0,true)
        checkWithdrawal(80,9,1,true)
        checkWithdrawal(80,10,1,false)
    }
    @Test fun armoredInfantryIsRefusedOnlyAtSpecialPlanetsWithItsOwnReason() {
        galaxy=galaxy(30)
        val s=state(kind=2);val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);val before=s.stocks
        assertEquals(NavigationRejection.CREW,accept(s,n,p).rejection);assertEquals(before,s.stocks)
        galaxy=galaxy(80)
        val h=state(kind=2);assertNotNull(accept(h,Navigation(galaxy,h.nav,NavigationPolicy()),production(h)).player)
    }
    @Test fun anInvalidSavedReservationDoesNotBlockANewLanding() {
        val s=state(defenders=29)
        val outfit=s.stocks.outfits.getValue(1)
        val stale=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,1,1000,now,now+600)
        val roster=ActiveGroundUnits(outfit.activeGround!!.values.map {u->if(u.carrier==1)u.copy(order=stale) else u})
        s.stocks=s.stocks.copy(outfits=mapOf(1 to outfit.copy(activeGround=roster)),
            tacticalCommands=s.stocks.tacticalCommands.copy(morale=mapOf(1 to 15)))
        val nav=Navigation(galaxy,s.nav,NavigationPolicy())
        val p=production(s)
        assertNotNull(accept(s,nav,p,request(ids=listOf(4))).player)
        val events=mutableListOf<TacticalGroundResult>()
        settle(s,nav,p,now+600,events)
        assertTrue(events.single {it.order.carrier==1}.cancelled)
        assertFalse(events.single {it.order.carrier==4}.cancelled)
        assertEquals(30,s.stocks.outfits.getValue(1).activeGround!!.values.count {it.base==1000})
    }

    @Test fun anOverbookedSaveLandsTheFirstValidReservationThatFits() {
        for(invalidLater in listOf(false,true)) {
            val s=state(defenders=29)
            val outfit=s.stocks.outfits.getValue(1)
            val earlier=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,4,1000,now,now+599)
            val later=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,1,1000,now,now+600)
            val roster=ActiveGroundUnits(outfit.activeGround!!.values.map {unit->
                when(unit.carrier) {
                    1 -> unit.copy(order=later)
                    4 -> unit.copy(order=earlier)
                    else -> unit
                }
            })
            s.stocks=s.stocks.copy(outfits=mapOf(1 to outfit.copy(activeGround=roster)),
                tacticalCommands=s.stocks.tacticalCommands.copy(morale=if(invalidLater)mapOf(1 to 15) else emptyMap()))
            val events=mutableListOf<TacticalGroundResult>()
            val after=TacticalGroundOperations.advance(session.copy(gameMillis=(now+600)*1000),galaxy,s.players,s.stocks,events)
            assertEquals(listOf(earlier,later),events.map {it.order})
            assertEquals(listOf(false,true),events.map {it.cancelled})
            val troops=after.outfits.getValue(1).activeGround!!.values
            assertEquals(30,troops.count {it.base==1000})
            assertEquals(2,troops.count {it.carrier==1 && it.order==null})
        }
    }

    @Test fun carrierMustStillShareTheCommandersBattleCamp() {
        val s=state()
        val before=s.stocks
        val revolt=CoupState(plots=mapOf(10000 to Plot(10000,CountryId(2),0,revolt=1)))
        fun withCoup(stocks:PlanetStocks,coup:CoupState)=stocks.copy(social=stocks.social.copy(coup=coup))
        s.stocks=withCoup(before,revolt)
        assertEquals(NavigationRejection.CARD,accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).rejection)
        s.stocks=withCoup(before,revolt.copy(loyalty=mapOf(1 to CoupActions.LOYAL)))
        assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).player)
        s.stocks=withCoup(s.stocks,revolt)
        val events=mutableListOf<TacticalGroundResult>()
        settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+600,events)
        assertTrue(events.single().cancelled)
        assertEquals(before.outfits.getValue(1).activeGround,s.stocks.outfits.getValue(1).activeGround)
    }

    @Test fun disabledOrConfusedCarrierCannotStartOrFinishALanding() {
        for(confused in listOf(false,true)) {
            val s=state()
            val before=s.stocks
            fun disable(stocks:PlanetStocks):PlanetStocks {
                if(confused)return stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(morale=mapOf(1 to 15)))
                val outfit=stocks.outfits.getValue(1)
                val ships=outfit.activeShips!!.values.map {u->if(u.id==1)u.copy(damaged=u.boats) else u}
                return stocks.copy(outfits=stocks.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(ships))))
            }
            s.stocks=disable(before)
            assertEquals(NavigationRejection.ACCESS,accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).rejection)
            s.stocks=before
            assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).player)
            s.stocks=disable(s.stocks)
            val events=mutableListOf<TacticalGroundResult>()
            settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+600,events)
            assertTrue(events.single().cancelled)
            assertEquals(before.outfits.getValue(1).activeGround,s.stocks.outfits.getValue(1).activeGround)
        }
    }

    @Test fun transportWithdrawalReservesSharedSlotsAndRechecksSavedOverbooking() {
        val s=state()
        val original=s.stocks.outfits.getValue(1)
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1000,origin=1),
            ActiveGroundUnit(5,UnitStack(0),base=1000,origin=4)))
        val outfit=original.copy(kind=OutfitKind.TRANSPORT,activeGround=troops,
            units=UnitInventory(original.units.ships,troops.quantities()))
        val cargo=TransportPackage(1,outfit.country,1000,1001,0,
            UnitInventory(troops=mapOf(UnitStack(0) to 3)),1,TransportActions.boats(outfit))
        s.stocks=s.stocks.copy(outfits=mapOf(1 to outfit),
            warehouses=mapOf(10 to s.stocks.warehouses.getValue(10).copy(kind=OutfitKind.TRANSPORT)),
            strategic=s.stocks.strategic.copy(transports=listOf(cargo),lastTransportId=1))
        val n=Navigation(galaxy,s.nav,NavigationPolicy())
        val p=production(s)
        val both=request(TacticalGroundAction.WITHDRAW,listOf(1,4))
        val before=s.stocks
        assertEquals(NavigationRejection.UNIT,accept(s,n,p,both).rejection)
        assertEquals(before,s.stocks)
        assertNotNull(accept(s,n,p,request(TacticalGroundAction.WITHDRAW)).player)
        val reserved=s.stocks
        val reservedOutfit=reserved.outfits.getValue(1)
        val active=assertNotNull(reservedOutfit.activeGround).values
        val order=assertNotNull(active.first().order)
        val overbooked=ActiveGroundUnits(listOf(active.first(),active.last().copy(order=order.copy(carrier=4))))
        s.stocks=reserved.copy(outfits=mapOf(1 to reservedOutfit.copy(activeGround=overbooked)))

        val events=mutableListOf<TacticalGroundResult>()
        settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+600,events)
        assertEquals(listOf(false,true),events.map {it.cancelled})
        val final=assertNotNull(s.stocks.outfits.getValue(1).activeGround).values
        assertEquals(listOf(1,0),final.map {it.carrier})
        assertEquals(listOf(0,1000),final.map {it.base})
        assertTrue(final.all {it.order==null})
        assertEquals(listOf(cargo),s.stocks.strategic.transports)
    }

    @Test fun restoredWithdrawalRechecksTheCurrentCarrierCapacity() {
        val s=state()
        val own=s.stocks.outfits.getValue(1)
        fun ships(kind:Int)=ActiveShipUnits(own.activeShips!!.values.map {it.copy(stack=UnitStack(kind))})
        val troops=ActiveGroundUnits(List(3){ActiveGroundUnit(100+it,UnitStack(0),base=1000,origin=1)})
        val larger=ships(80)
        s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(activeShips=larger,activeGround=troops,
            units=UnitInventory(larger.quantities(),troops.quantities()))))
        assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),
            request(TacticalGroundAction.WITHDRAW)).player)
        val saved=s.stocks.outfits.getValue(1)
        val smaller=ships(32)
        s.stocks=s.stocks.copy(outfits=mapOf(1 to saved.copy(activeShips=smaller,
            units=UnitInventory(smaller.quantities(),troops.quantities()))))
        val events=mutableListOf<TacticalGroundResult>()
        settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+600,events)
        assertTrue(events.single().cancelled)
        assertEquals(troops,s.stocks.outfits.getValue(1).activeGround)
    }

    @Test fun restoredLandingRechecksTheCurrentPlanetType() {
        galaxy=galaxy(80)
        val s=state(kind=2)
        val troops=s.stocks.outfits.getValue(1).activeGround
        assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).player)
        galaxy=galaxy(30)
        val events=mutableListOf<TacticalGroundResult>()
        settle(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),now+600,events)
        assertTrue(events.single().cancelled)
        assertEquals(troops,s.stocks.outfits.getValue(1).activeGround)
    }

    @Test fun aCommanderOrdersItsLandedTroopsAtAnEnemyTroopOnTheSameBaseAndClearsIt() {
        val s=state();val own=s.stocks.outfits.getValue(1)
        val landed=ActiveGroundUnits(own.activeGround!!.values.map {if(it.id==2)it.copy(carrier=0,base=1000,origin=1) else it})
        val enemy=ActiveGroundUnits(listOf(ActiveGroundUnit(50,UnitStack(4),base=1000)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(activeGround=landed))+
            (9 to OutfitStock(9,3,OutfitKind.GROUND,1001,11,10009,UnitInventory(troops=enemy.quantities()),activeGround=enemy)),
            warehouses=s.stocks.warehouses+(11 to OutfitWarehouse(11,3,OutfitKind.GROUND,1001)))
        val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        fun order(units:List<Int>,target:Int)=accept(s,n,p,request().copy(tacticalGround=TacticalGroundCommand(
            if(target>0)TacticalGroundAction.TARGET else TacticalGroundAction.CLEAR,units,target=target)))
        val defeated=session.copy(eliminated=mapOf(Power.ALLIANCE.country to
            CountryDefeat(EndReason.CAPITAL_CAPTURED,now,Power.EMPIRE.country)))
        val before=s.stocks
        val saves=s.saves
        val targetRequest=request().copy(tacticalGround=TacticalGroundCommand(TacticalGroundAction.TARGET,listOf(2),target=50))
        val rejected=TacticalGroundOperations.accept(targetRequest,defeated,galaxy,n,p,p.snapshot(1)!!,s.commit,24)
        assertEquals(NavigationRejection.TACTICAL,rejected.rejection,"a defeated country no longer keeps this grid in tactics")
        assertEquals(before,s.stocks)
        assertEquals(saves,s.saves)
        assertNotNull(order(listOf(2),50).player);assertEquals(mapOf(2 to 50),s.stocks.tacticalCommands.groundTargets)
        assertEquals(NavigationRejection.TARGET,order(listOf(2),3).rejection,"own troops are no target")
        assertEquals(NavigationRejection.UNIT,order(listOf(3),50).rejection,"a troop still aboard has no ground battle")
        assertNotNull(order(listOf(2),0).player);assertTrue(s.stocks.tacticalCommands.groundTargets.isEmpty())
        val changed=p.snapshot(1)!!.let {it.copy(outfits=it.outfits+(1 to it.outfits.getValue(1).copy(commanderId=10001)))}
        p.replace(p.snapshot(1)!!,changed)
        assertEquals(NavigationRejection.CARD,order(listOf(2),50).rejection,"observing another commander does not grant command")
        assertTrue(s.stocks.tacticalCommands.groundTargets.isEmpty())
    }
    @Test fun groundTargetLimitRejectsNewEntriesButAllowsChangingAndClearingSavedOrders() {
        val s=state()
        val own=s.stocks.outfits.getValue(1)
        val landed=ActiveGroundUnits(own.activeGround!!.values.map {if(it.id in 2..3)it.copy(carrier=0,base=1000,origin=1) else it})
        val enemies=ActiveGroundUnits(listOf(50,51).map {ActiveGroundUnit(it,UnitStack(4),base=1000)})
        val orders=(1001..1999).associateWith {50}+(2 to 50)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(activeGround=landed))+
            (9 to OutfitStock(9,3,OutfitKind.GROUND,1001,11,10009,UnitInventory(troops=enemies.quantities()),activeGround=enemies)),
            warehouses=s.stocks.warehouses+(11 to OutfitWarehouse(11,3,OutfitKind.GROUND,1001)),
            tacticalCommands=s.stocks.tacticalCommands.copy(groundTargets=orders))
        val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        fun order(units:List<Int>,target:Int)=accept(s,n,p,request().copy(tacticalGround=TacticalGroundCommand(
            if(target>0)TacticalGroundAction.TARGET else TacticalGroundAction.CLEAR,units,target=target)))
        val before=s.stocks
        val saves=s.saves
        assertEquals(NavigationRejection.BUSY,order(listOf(2,3),51).rejection)
        assertEquals(before,s.stocks);assertEquals(saves,s.saves)
        assertNotNull(order(listOf(2),51).player)
        assertEquals(orders+(2 to 51),s.stocks.tacticalCommands.groundTargets)
        assertNotNull(order(listOf(2),0).player)
        assertNotNull(order(listOf(3),50).player)
        assertEquals(orders-2+(3 to 50),s.stocks.tacticalCommands.groundTargets)
    }

    @Test fun anExistingGarrisonCommanderCanOrderLocalUnassignedGroundDefendersUntilDismissed() {
        val s=state();val defenders=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1000)))
        val enemy=ActiveGroundUnits(listOf(ActiveGroundUnit(50,UnitStack(4),base=1000)))
        s.stocks=s.stocks.copy(bases=s.stocks.bases+(1000 to s.stocks.bases.getValue(1000).copy(garrisonCommanderId=10000)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.GROUND,1000,10,
                units=UnitInventory(troops=defenders.quantities()),activeGround=defenders),
                9 to OutfitStock(9,3,OutfitKind.GROUND,1001,11,units=UnitInventory(troops=enemy.quantities()),activeGround=enemy)),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.GROUND,1000),11 to OutfitWarehouse(11,3,OutfitKind.GROUND,1001)))
        val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertNotNull(accept(s,n,p,request().copy(tacticalGround=TacticalGroundCommand(TacticalGroundAction.TARGET,listOf(2),target=50))).player)
        val before=p.snapshot(1)!!
        p.replace(before,before.copy(bases=before.bases+(1000 to before.bases.getValue(1000).copy(garrisonCommanderId=10001))))
        assertEquals(NavigationRejection.CARD,accept(s,n,p,request().copy(tacticalGround=TacticalGroundCommand(TacticalGroundAction.CLEAR,listOf(2)))).rejection)
        assertEquals(50,p.snapshot(1)!!.tacticalCommands.groundTargets[2])
    }
    @Test fun forgedIdentityConnectionAndNonzeroSkillCannotStartLanding() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);val before=s.stocks
        assertEquals(NavigationRejection.IDENTITY,accept(s,n,p,request().copy(characterId=10001)).rejection)
        assertEquals(NavigationRejection.SESSION,accept(s,n,p,request().copy(connection="old")).rejection)
        assertEquals(NavigationRejection.ACCESS,accept(s,n,p,request().copy(tacticalGround=TacticalGroundCommand(TacticalGroundAction.DEPLOY,listOf(1),1))).rejection)
        assertEquals(before,s.stocks);assertEquals(0,s.saves)
    }
    @Test fun stopClearsTheSameReservationAtomicallyAndNoTroopsMoveLater() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);assertNotNull(accept(s,n,p).player)
        val stopped=TacticalSupportOperations.stop(request().copy(tacticalGround=null,tacticalStop=TacticalStop(listOf(1))),
            session,galaxy,n,p,p.snapshot(1)!!,s.commit)
        assertNotNull(stopped.player)
        assertEquals(setOf(TacticalGroundAction.DEPLOY),stopped.groundStopped)
        assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.all {it.order==null})
        val events=mutableListOf<TacticalGroundResult>();settle(s,n,p,now+600,events);assertTrue(events.isEmpty())
        assertEquals(1,s.stocks.outfits.getValue(1).activeGround!!.values.single {it.id==2}.carrier)
    }
    @Test fun storageFailurePublishesNeitherAcceptanceNorCompletionAndRetryExecutesOnce() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);val before=s.stocks
        s.fail=true;assertFailsWith<java.io.IOException>{accept(s,n,p)};assertEquals(before,p.snapshot(1))
        s.fail=false;assertNotNull(accept(s,n,p).player);val pending=p.snapshot(1)
        val events=mutableListOf<TacticalGroundResult>();s.fail=true
        assertFailsWith<java.io.IOException>{settle(s,n,p,now+600,events)};assertEquals(pending,p.snapshot(1));assertTrue(events.isEmpty())
        s.fail=false;settle(s,n,p,now+600,events);assertEquals(1,events.size)
    }
    @Test fun commanderLossCancelsWithoutInventingALandedOrEmbarkedPosition() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);assertNotNull(accept(s,n,p).player)
        val changed=s.stocks.copy(outfits=s.stocks.outfits.mapValues {(_,o)->o.copy(commanderId=10001)})
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(session,galaxy,s.players,changed,events)
        assertTrue(events.single().cancelled);assertEquals(1,result.outfits.getValue(1).activeGround!!.values.single {it.id==2}.carrier)
        assertTrue(result.outfits.getValue(1).activeGround!!.values.all {it.order==null})
    }
    @Test fun deceasedCommanderCannotIssueGroundOrdersAndSavedLandingIsCancelledAfterReload() {
        val s=state()
        assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).player)
        s.stocks=BattleDeath.apply(s.stocks,listOf(10000),now) {_,_->true}
        val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val commands=listOf(request(),request(TacticalGroundAction.WITHDRAW),request(TacticalGroundAction.CLEAR,listOf(2)),
            request().copy(tacticalGround=TacticalGroundCommand(TacticalGroundAction.TARGET,listOf(2),target=5)))
        val saves=s.saves
        for(command in commands)assertEquals(NavigationRejection.IDENTITY,accept(s,n,p,command).rejection)
        assertEquals(saves,s.saves)
        val events=mutableListOf<TacticalGroundResult>()
        settle(s,n,p,now+600,events)
        assertTrue(events.single().cancelled)
        val troops=s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}
        assertEquals(2,troops.size)
        assertTrue(troops.all {it.carrier==1 && it.base==0 && it.order==null})
    }
    @Test fun eliminatedCountryCancelsSavedLandingWithoutMovingTroops() {
        val s=state()
        val accepted=accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s))
        val readyAt=assertNotNull(accepted.groundReadyAt)
        val outfit=s.stocks.outfits.getValue(1)
        val defeated=session.copy(gameMillis=readyAt*1000,
            eliminated=mapOf(checkNotNull(outfit.country) to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(defeated,galaxy,s.players,s.stocks,events)

        assertTrue(events.single().cancelled)
        assertEquals(outfit.activeGround!!.values.map {it.copy(order=null)},
            result.outfits.getValue(1).activeGround!!.values)
    }

    @Test fun detainedCommanderCancelsSavedLandingWithoutMovingTroops() {
        val s=state()
        val readyAt=assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).groundReadyAt)
        val commander=s.players.getValue("pilot")
        val detention=Detention(commander.characterId,checkNotNull(commander.country),now,readyAt+1)
        s.stocks=s.stocks.copy(social=s.stocks.social.copy(justice=JusticeState(detentions=mapOf(commander.characterId to detention))))
        assertEquals(NavigationRejection.BUSY,accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).rejection)
        assertTrue(TacticalGroundOrder.pending(s.stocks,commander.grid,s.players.values,galaxy,emptySet(),readyAt).isEmpty())
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(session.copy(gameMillis=readyAt*1000),galaxy,s.players,s.stocks,events)
        assertTrue(events.single().cancelled)
        val troops=s.stocks.outfits.getValue(1).activeGround!!.values
        assertEquals(troops.map {it.copy(order=null)},result.outfits.getValue(1).activeGround!!.values)
    }

    @Test fun woundedCommanderLosesTheLandingReservationWithoutMovingTroops() {
        val s=state()
        val readyAt=assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s)).groundReadyAt)
        val commander=s.players.getValue("pilot")
        val wounded=commander.copy(hull=commander.hull!!.copy(woundedUntil=readyAt+1))
        val players=s.players+("pilot" to wounded)
        assertEquals(1,TacticalGroundOrder.pending(s.stocks,commander.grid,s.players.values,galaxy,emptySet(),readyAt).size)
        assertTrue(TacticalGroundOrder.pending(s.stocks,commander.grid,players.values,galaxy,emptySet(),readyAt).isEmpty())
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(session.copy(gameMillis=readyAt*1000),galaxy,players,s.stocks,events)
        assertTrue(events.single().cancelled)
        val troops=s.stocks.outfits.getValue(1).activeGround!!.values
        assertEquals(troops.map {it.copy(order=null)},result.outfits.getValue(1).activeGround!!.values)
    }

    @Test fun shipWithoutMotionUsesItsBerthForCommandRange() {
        for (mode in listOf(TacticalShipMode.STATIONED, TacticalShipMode.ANCHORED)) {
            val s=state();val old=s.stocks.outfits.getValue(1)
            s.stocks=s.stocks.copy(outfits=mapOf(1 to old.copy(activeShips=ActiveShipUnits(old.activeShips!!.values.map {
                if(it.id==1)it.copy(tactical=null,posture=TacticalPosture(2487,mode,1000)) else it
            }))))
            val far=Galaxy(galaxy.systems,galaxy.bases.map {if(it.id==1000)it.copy(radius=100f,angle=90f) else it},galaxy.starts)
            val p=production(s);val before=s.stocks
            val result=TacticalGroundOperations.accept(request(),session,far,Navigation(far,s.nav,NavigationPolicy()),
                p,p.snapshot(1)!!,s.commit,24)
            assertEquals(NavigationRejection.RANGE,result.rejection);assertEquals(before,s.stocks)
            assertNotNull(accept(s,Navigation(galaxy,s.nav,NavigationPolicy()),p).groundReadyAt)
        }
    }
    @Test fun missingBattlefieldBaseCancelsAPersistedLandingWithoutMovingTroops() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);assertNotNull(accept(s,n,p).player)
        val changed=Galaxy(galaxy.systems,galaxy.bases.filterNot {it.id==1000},galaxy.starts)
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(session.copy(gameMillis=(now+600)*1000),changed,s.players,s.stocks,events)
        assertTrue(events.single().cancelled);assertEquals(1,result.outfits.getValue(1).activeGround!!.values.single {it.id==2}.carrier)
    }
}
