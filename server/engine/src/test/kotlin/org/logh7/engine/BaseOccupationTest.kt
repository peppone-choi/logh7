package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class BaseOccupationTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).exportState()
    private val now=session.gameMillis/1000
    private val base=GalaxyBase(1000,2487,80,0,"Battle",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),listOf(base),Power.entries.associateWith {2487})
    private fun stocks():PlanetStocks {
        val attacker=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(0),base=1000)))
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(2,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000)),
            ActiveShipUnit(3,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1000))))
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(4,UnitStack(4),carrier=2),ActiveGroundUnit(5,UnitStack(4),carrier=3)))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,200,300,
            units=UnitInventory(mapOf(UnitStack(64) to 2),work=mapOf("S64" to 5,"T4" to 3)),
            governorId=10001,garrisonCommanderId=10001,spareShips=SpareShipBoats(mapOf(UnitStack(64) to 42)))),
            mapOf(1 to OutfitStock(1,2,OutfitKind.GROUND,1000,101,units=UnitInventory(troops=attacker.quantities()),activeGround=attacker),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1000,102,units=UnitInventory(ships.quantities(),troops.quantities()),
                    activeShips=ships,activeGround=troops)),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1000),
                102 to OutfitWarehouse(102,3,OutfitKind.FLEET,1000,2,UnitInventory(mapOf(UnitStack(64) to 3)),supplies=900)),
            deploymentInitialized=true,nextShipId=6)
    }
    private fun advance(s:PlanetStocks,players:MutableMap<String,Navigator> = mutableMapOf(),
        events:MutableList<BaseOccupation> = mutableListOf(),state:SessionState=session,world:Galaxy=galaxy,cap:Int=Int.MAX_VALUE,
        commands:MutableList<TacticalCommandResult> = mutableListOf())=
        BaseOccupationOperations.advance(state,world,players,s,cap,1,events,commands)

    private fun additionalCountries():PlanetStocks=stocks().let {s -> s.copy(
        bases=s.bases.mapValues {(_,b)->b.copy(owner=Allegiance.Country(CountryId(43)))},
        outfits=s.outfits.mapValues {(_,o)->o.copy(country=CountryId(if(o.country==CountryId(2))42 else 43))},
        warehouses=s.warehouses.mapValues {(_,w)->w.copy(country=CountryId(if(w.country==CountryId(2))42 else 43))})}

    @Test fun troopsFromAnEliminatedCountryCannotCaptureABaseInAContinuingSession() {
        val original=additionalCountries()
        val defeated=session.copy(eliminated=mapOf(CountryId(42) to
            CountryDefeat(EndReason.CAPITAL_CAPTURED,now,CountryId(43))))
        val events=mutableListOf<BaseOccupation>()
        assertFalse(BaseOccupationOperations.pending(original,galaxy,defeated.eliminated.keys))
        assertSame(original,advance(original,events=events,state=defeated))
        assertTrue(events.isEmpty())
        assertEquals(CountryId(42),advance(original).bases.getValue(base.id).country)
    }

    @Test fun additionalCountryCapturesOnlyTheDefendersPropertyAndExpelsOnlyItsCharacters() {
        val original=additionalCountries()
        val loser=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321,baseId=1000,mode=4,
            spotId=10000001,returnBase=1000).copy(country=CountryId(43))
        val ally=loser.copy(characterId=10002,country=CountryId(42))
        val outsider=loser.copy(characterId=10003,country=CountryId(44),baseId=0,mode=5,spotId=0,originBase=1000)
        val independent=outsider.copy(characterId=10004,country=null)
        val incoming=loser.copy(characterId=10005,baseId=0,mode=5,spotId=0,returnBase=0,originBase=1000)
        val travel=NavigationWork.pipeline(incoming,NavigationPolicy())
        travel.submit(NavigationWork.DOCK,listOf(NavigationWork.CAPTAIN),session.gameMillis,false)
        val reserved=incoming.copy(dockTarget=1000,strategy=travel.checkpoint(),mcp=travel.balance.mcp.toInt())
        val elsewhere=reserved.copy(characterId=10006,dockTarget=1001)
        val players=mutableMapOf("loser" to loser,"ally" to ally,"outsider" to outsider,"independent" to independent,
            "incoming" to reserved,"elsewhere" to elsewhere)
        val events=mutableListOf<BaseOccupation>()
        val after=advance(original,players,events)
        assertEquals(CountryId(42),after.bases.getValue(1000).country)
        assertEquals(0,after.outfits.getValue(2).activeShips!!.values.single {it.id==2}.boats)
        assertEquals(300,after.outfits.getValue(2).activeShips!!.values.single {it.id==3}.boats)
        assertEquals(original.outfits.getValue(1),after.outfits.getValue(1))
        assertEquals(0,after.warehouses.getValue(102).supplies)
        assertEquals(original.warehouses.getValue(101),after.warehouses.getValue(101))
        assertEquals(0,players.getValue("loser").baseId)
        assertEquals(0,players.getValue("loser").returnBase)
        assertEquals(reserved.copy(dockTarget=null,strategy=null),players["incoming"])
        assertEquals(elsewhere,players["elsewhere"])
        assertEquals(listOf(777,555),listOf(players.getValue("loser").pcp,players.getValue("loser").mcp))
        assertEquals(ally,players["ally"])
        assertEquals(outsider.copy(returnBase=0),players["outsider"])
        assertEquals(independent.copy(returnBase=0),players["independent"])
        assertEquals(Allegiance.Country(CountryId(43)),events.single().previousOwner)
        assertEquals(CountryId(42),events.single().country)
        assertEquals(setOf("loser"),events.single().expelled.keys)
        assertSame(after,advance(after,players))
    }

    @Test fun foreignOccupationSettlesLocalUnladenTransportStockExactlyOnce() {
        val original=stocks()
        val old=original.bases.getValue(1000)
        val remote=base.copy(id=1001,grid=2488)
        val world=Galaxy(galaxy.systems+GalaxySystem(4,2488,4,"Remote",3),listOf(base,remote),galaxy.starts)
        val marines=UnitStack(4)
        val crew=UnitStack(UnitSpecifications.crews.first {it.power==3 && it.type==3}.kind)
        val hull=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==3 && it.name.startsWith("輸送艦")}.kind)
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(6,hull)))
        val carrier=OutfitStock(3,3,OutfitKind.TRANSPORT,1001,103,
            units=UnitInventory(ships.quantities()),activeShips=ships,position=OutfitPosition(2488,1001))
        val held=TransportPackage(9,Power.ALLIANCE.country,1000,1001,700,
            UnitInventory(mapOf(hull to 1),mapOf(marines to 5,crew to 7)))
        val boats=TransportActions.boats(carrier)
        val aboard=held.copy(id=1,carrier=3,carrierBoats=boats,survivingBoats=boats)
        val elsewhere=held.copy(id=2,source=1001,destination=1000)
        val winner=held.copy(id=3,country=Power.EMPIRE.country)
        val untouched=listOf(aboard,elsewhere,winner)
        val before=original.copy(bases=mapOf(1000 to old.copy(units=UnitInventory(old.units.ships,
            mapOf(marines to 65534),old.units.work)),1001 to old.copy(baseId=1001)),
            outfits=original.outfits+(3 to carrier),
            warehouses=original.warehouses+(103 to OutfitWarehouse(103,3,OutfitKind.TRANSPORT,1001,3)),
            strategic=original.strategic.copy(transports=untouched+held),nextShipId=7)
        val events=mutableListOf<BaseOccupation>()
        val captured=advance(before,events=events,world=world,cap=1200)
        val local=captured.bases.getValue(1000)
        assertEquals(Power.EMPIRE.country,local.country)
        assertEquals(1200,local.supplies)
        assertEquals(UnitInventory(troops=mapOf(marines to 65535,crew to 7)),local.units)
        assertEquals(untouched,captured.strategic.transports)
        assertEquals(9,captured.strategic.lastTransportId)
        assertEquals(before.bases.getValue(1001),captured.bases.getValue(1001))
        assertSame(captured,advance(captured,events=events,world=world,cap=1200))
        assertEquals(1,events.size)

        val defeated=captured.outfits.getValue(1).copy(activeGround=ActiveGroundUnits(emptyList()),units=UnitInventory())
        val defenders=captured.outfits.getValue(2)
        val returned=ActiveGroundUnits(assertNotNull(defenders.activeGround).values.map {it.copy(carrier=0,base=1000)})
        val counterattack=captured.copy(outfits=captured.outfits+mapOf(1 to defeated,2 to defenders.copy(activeGround=returned)))
        val retaken=advance(counterattack,events=events,world=world,cap=1200)
        assertEquals(Power.ALLIANCE.country,retaken.bases.getValue(1000).country)
        assertEquals(local.supplies,retaken.bases.getValue(1000).supplies)
        assertEquals(UnitInventory(troops=mapOf(marines to 65535,crew to 14)),retaken.bases.getValue(1000).units)
        assertEquals(listOf(aboard,elsewhere),retaken.strategic.transports)
        assertEquals(9,retaken.strategic.lastTransportId)
        assertEquals(2,events.size)
    }

    @Test fun rebelCaptureAndRegularRecapturePreserveSameCountryWarehouses() {
        val original=stocks()
        val country=Power.ALLIANCE.country
        val rebels=original.outfits.getValue(1).copy(country=country,commanderId=10000)
        val coup=CoupState(mapOf(10000 to Plot(10000,country,0,revolt=5)),loyalty=mapOf(1 to 100))
        val neighboring=base.copy(id=1001)
        val world=Galaxy(galaxy.systems,listOf(base,neighboring),galaxy.starts)
        val otherBase=original.bases.getValue(1000).copy(baseId=1001)
        val openSupply=mapOf(1000 to country.value,1001 to country.value)
        val queued=TacticalQueuedCommand(10001,2487,now,now+30,TacticalCommand(0x422,base=1000))
        val neighboringOrder=queued.copy(command=queued.command.copy(base=1001))
        val packageAshore=TransportPackage(1,country,1000,1001,50)
        val before=original.copy(outfits=original.outfits+(1 to rebels),
            strategic=original.strategic.copy(transports=listOf(packageAshore),lastTransportId=1),
            bases=original.bases+(1001 to otherBase),
            tacticalCommands=original.tacticalCommands.copy(emergencySupplies=openSupply,orders=listOf(queued,neighboringOrder)),
            warehouses=original.warehouses+(101 to original.warehouses.getValue(101).copy(country=country)),
            social=original.social.copy(coup=coup))
        val regular=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321,baseId=1000,mode=4,returnBase=1000)
        val rebel=regular.copy(characterId=10000)
        val players=mutableMapOf("regular" to regular,"rebel" to rebel)
        val events=mutableListOf<BaseOccupation>()
        val commands=mutableListOf<TacticalCommandResult>()
        val captured=advance(before,players,events,world=world,commands=commands)
        assertEquals(listOf(neighboringOrder),captured.tacticalCommands.orders)
        assertEquals(queued,commands.single().order)
        assertTrue(commands.single().cancelled)
        assertEquals(1,captured.bases.getValue(1000).camp)
        assertEquals(openSupply-1000,captured.tacticalCommands.emergencySupplies)
        assertEquals(setOf(1000),captured.social.coup.plots.getValue(10000).bases)
        assertEquals(1,events.single().camp)
        assertEquals(setOf("regular"),events.single().expelled.keys)
        assertEquals(0,players.getValue("regular").returnBase)
        assertEquals(rebel,players.getValue("rebel"))
        assertEquals(before.warehouses,captured.warehouses)
        assertEquals(listOf(packageAshore),captured.strategic.transports)
        assertEquals(rebels,captured.outfits.getValue(1))
        assertSame(captured,advance(captured,players))

        val routed=rebels.copy(activeGround=ActiveGroundUnits(emptyList()),units=UnitInventory())
        val defenders=captured.outfits.getValue(2)
        val returned=ActiveGroundUnits(defenders.activeGround!!.values.map {it.copy(carrier=0,base=1000)})
        val counterattack=captured.copy(outfits=mapOf(1 to routed,2 to defenders.copy(activeGround=returned)))
        events.clear()
        val retaken=advance(counterattack,players,events,world=world,commands=commands)
        assertEquals(listOf(neighboringOrder),retaken.tacticalCommands.orders)
        assertEquals(1,commands.size)
        assertEquals(0,retaken.bases.getValue(1000).camp)
        assertEquals(openSupply-1000,retaken.tacticalCommands.emergencySupplies)
        assertTrue(retaken.social.coup.plots.getValue(10000).bases.isEmpty())
        assertEquals(setOf("rebel"),events.single().expelled.keys)
        assertEquals(before.warehouses,retaken.warehouses)
        assertEquals(listOf(packageAshore),retaken.strategic.transports)
    }

    @Test fun multipleAttackingCountriesMustResolveTheirBattleBeforeOccupation() {
        val before=additionalCountries()
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(6,UnitStack(0),base=1000)))
        val third=before.outfits.getValue(1).copy(id=3,country=CountryId(44),warehouseId=103,
            units=UnitInventory(troops=troops.quantities()),activeGround=troops)
        val contested=before.copy(outfits=before.outfits+(3 to third),nextShipId=7,
            warehouses=before.warehouses+(103 to OutfitWarehouse(103,CountryId(44),OutfitKind.PATROL,1000,shipStyle=third.shipStyle)))
        assertNull(BaseOccupationOperations.victor(contested,base))
        assertSame(contested,advance(contested))
    }

    @Test fun neutralFezzanIsDistinctFromAnIndependentCharacterAndAnotherCountrysWarehouse() {
        val world=Galaxy(galaxy.systems,listOf(base.copy(name=TradeActions.FEZZAN_NAME)),galaxy.starts)
        val before=additionalCountries().let {s -> s.copy(bases=s.bases.mapValues {(_,b)->
            b.copy(owner=Allegiance.Neutral,governorId=0,garrisonCommanderId=0)})}
        val independent=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321).copy(country=null)
        val players=mutableMapOf("independent" to independent)
        val events=mutableListOf<BaseOccupation>()
        val after=advance(before,players,events,world=world)
        assertEquals(CountryId(42),after.bases.getValue(1000).country)
        assertEquals(100,after.bases.getValue(1000).supplies,"no country owned the neutral base's foreign warehouse")
        assertEquals(before.warehouses,after.warehouses)
        assertEquals(before.outfits,after.outfits)
        assertEquals(independent,players["independent"])
        assertEquals(Allegiance.Neutral,events.single().previousOwner)
        assertEquals(CountryId(42),events.single().country)
    }

    @Test fun independentBaseCountsAsItsOwnCampAndOccupationExpelsOnlyItsController() {
        val owner=Allegiance.Independent(10001)
        val before=stocks().let {s->s.copy(bases=s.bases.mapValues {(_,b)->
            b.copy(owner=owner,governorId=0,garrisonCommanderId=0)})}
        val controller=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321,baseId=1000,mode=4,
            returnBase=1000).copy(country=null)
        val visitor=controller.copy(characterId=10002,baseId=0,mode=5,returnBase=0)
        val players=mutableMapOf("controller" to controller,"visitor" to visitor)
        val forces=GridForces.collect(galaxy,1,players.values,before).getValue(2487)
        assertTrue(forces.single {it.camp==Camp.of(controller)}.base)
        assertFalse(forces.single {it.camp==Camp.of(visitor)}.base)
        val events=mutableListOf<BaseOccupation>()
        val after=advance(before,players,events)
        assertEquals(CountryId(2),after.bases.getValue(1000).country)
        assertEquals(owner,events.single().previousOwner)
        assertEquals(setOf("controller"),events.single().expelled.keys)
        assertEquals(0,players.getValue("controller").baseId)
        assertEquals(0,players.getValue("controller").returnBase)
        assertEquals(visitor,players.getValue("visitor"))
    }

    @Test fun unopposedLandingCapturesOnceDestroysStationedShipsAndClearsAppointments() {
        val original=stocks();val events=mutableListOf<BaseOccupation>();val captured=advance(original,events=events)
        val owner=captured.bases.getValue(1000)
        assertEquals(2,owner.power);assertEquals(1000,owner.supplies)
        assertEquals(0,owner.governorId);assertEquals(0,owner.garrisonCommanderId)
        assertTrue(owner.units.ships.isEmpty());assertTrue(owner.spareShips.values.isEmpty())
        assertTrue(owner.units.work.isEmpty())
        val outfit=captured.outfits.getValue(2)
        assertEquals(0,outfit.activeShips!!.values.single {it.id==2}.boats)
        assertEquals(300,outfit.activeShips!!.values.single {it.id==3}.boats)
        assertEquals(listOf(4,5),outfit.activeGround!!.values.map {it.id}) // Surviving carrier retains the legacy evacuation policy.
        assertEquals(0,outfit.activeGround!!.values.single {it.id==4}.carrier)
        val warehouse=captured.warehouses.getValue(102)
        assertEquals(0,warehouse.supplies);assertTrue(warehouse.units.ships.isEmpty());assertEquals(3,warehouse.power)
        assertEquals(mapOf(2 to 300),events.single().destroyedShips)
        assertEquals(original.nextShipId,captured.nextShipId)
        assertSame(captured,advance(captured,events=events));assertEquals(1,events.size)
    }
    @Test fun captureDropsThePreviousOwnersPendingManualShipOrder() {
        val s=stocks().let {it.copy(social=it.social.copy(production=ProductionState(orders=mapOf(1000 to ShipOrder(64,now+3600)))))}
        val next=advance(s)
        assertEquals(2,next.bases.getValue(1000).power);assertTrue(next.social.production.orders.isEmpty())
    }
    @Test fun bothSidesGoneAndEligibleDefenderPreventOccupation() {
        val original=stocks();val attacker=original.outfits.getValue(1)
        val empty=original.copy(outfits=original.outfits+(1 to attacker.copy(units=UnitInventory(),activeGround=ActiveGroundUnits(emptyList()))))
        assertSame(empty,advance(empty))
        val defender=original.outfits.getValue(2)
        val landed=defender.activeGround!!.values.map {it.copy(carrier=0,base=1000)}
        val contested=original.copy(outfits=original.outfits+(2 to defender.copy(activeGround=ActiveGroundUnits(landed))))
        assertSame(contested,advance(contested))
        val ended=session.copy(ended=SessionEnd(EndReason.TIME_LIMIT,Power.entries.toSet(),now))
        assertSame(original,advance(original,state=ended))
    }
    @Test fun expelledOfflineCharacterKeepsFlagshipAndCpButLosesRoomAndInvalidReturnBase() {
        val original=stocks();val hull=FlagshipHull(89,now)
        val loser=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321,baseId=1000,mode=4,
            spotId=10000001,returnBase=1000,hull=hull)
        val players=mutableMapOf("offline" to loser,"remote" to loser.copy(characterId=10002,baseId=0,mode=5,spotId=0))
        val events=mutableListOf<BaseOccupation>();advance(original,players,events)
        val expelled=players.getValue("offline")
        assertEquals(0,expelled.baseId);assertEquals(0,expelled.spotId);assertEquals(5,expelled.mode)
        assertEquals(TacticalShipMode.ANCHORED,expelled.posture!!.mode);assertEquals(1000,expelled.posture!!.base)
        assertEquals(0,expelled.returnBase);assertEquals(hull,expelled.hull)
        assertEquals(listOf(777,555,321),listOf(expelled.pcp,expelled.mcp,expelled.cruise))
        assertTrue(expelled.cpPaused);assertNotNull(expelled.motion)
        assertEquals(loser.copy(characterId=10002,baseId=0,mode=5,spotId=0,returnBase=0),players["remote"])
        assertEquals(setOf("offline"),events.single().expelled.keys)
    }
    @Test fun occupationExpelsDockedFlagshipsBesideTheMovingFortress() {
        val fortress=base.copy(kind=1,mobile=true)
        val world=Galaxy(galaxy.systems,listOf(fortress),galaxy.starts)
        val capturedAt=now+50
        val motion=TacticalMotion(2487,now,TacticalPose(TacticalPoint(30f,40f),0f),TacticalFortressMotion.VELOCITY,0f,
            listOf(TacticalPoint(60f,40f)),waitSeconds=0)
        val original=stocks()
        val prepared=original.copy(tacticalCommands=original.tacticalCommands.copy(
            fortresses=mapOf(1000 to TacticalFortressMotion(2487,motion))))
        val loser=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321,baseId=1000,mode=4)
        val players=mutableMapOf("first" to loser,"second" to loser.copy(characterId=10002))
        val after=advance(prepared,players,state=session.copy(gameMillis=capturedAt*1000),world=world)
        assertEquals(CountryId(2),after.bases.getValue(1000).country)
        assertEquals(listOf(TacticalPoint(39f,40f),TacticalPoint(41f,40f)),
            players.values.map {it.motion!!.origin.position})
        assertEquals(listOf(TacticalPoint(41f,40f),TacticalPoint(43f,40f)),
            players.values.map {it.motion!!.pose(capturedAt+20,it.posture,world,after).position})
    }

    @Test fun fortressIneligibleGarrisonIsEliminatedAndWarehouseOverflowIsBounded() {
        val fortress=base.copy(kind=1)
        val world=Galaxy(galaxy.systems,listOf(fortress),galaxy.starts)
        val original=stocks();val defender=ActiveGroundUnits(listOf(ActiveGroundUnit(6,UnitStack(6),base=1000)))
        val garrison=OutfitStock(3,3,OutfitKind.GROUND,1000,103,units=UnitInventory(troops=defender.quantities()),activeGround=defender)
        val prepared=original.copy(outfits=original.outfits+(3 to garrison),
            warehouses=original.warehouses+(103 to OutfitWarehouse(103,3,OutfitKind.PATROL,1000,supplies=Int.MAX_VALUE)),nextShipId=7)
        val result=advance(prepared,world=world,cap=500)
        assertEquals(2,result.bases.getValue(1000).power);assertEquals(500,result.bases.getValue(1000).supplies)
        assertTrue(result.outfits.getValue(3).activeGround!!.values.isEmpty())
    }
    @Test fun fortressCaptureClearsOnlyTheLosingFleetsLandedReservesAtThatBase() {
        val fortress=base.copy(kind=1)
        val remoteBase=base.copy(id=1001)
        val world=Galaxy(galaxy.systems,listOf(fortress,remoteBase),galaxy.starts)
        val original=stocks()
        val fleet=original.outfits.getValue(2)
        val aboard=fleet.activeGround!!.values.single {it.carrier==3}
        val reserve=ActiveGroundUnit(6,UnitStack(6),base=fortress.id)
        val remote=ActiveGroundUnit(7,UnitStack(6),base=remoteBase.id)
        val roster=ActiveGroundUnits(listOf(aboard,reserve,remote))
        val crew=UnitStack(UnitSpecifications.crews.first {it.type==3 && it.power==fleet.shipStyle.nativePower}.kind)
        val prepared=original.copy(bases=original.bases+(remoteBase.id to PlanetStock(remoteBase.id,3,0,0,0,0)),
            outfits=original.outfits+(fleet.id to fleet.copy(activeGround=roster,
                units=UnitInventory(fleet.units.ships,roster.quantities()+(crew to 2)))),nextShipId=8)

        val captured=advance(prepared,world=world)
        val remaining=captured.outfits.getValue(fleet.id)
        assertEquals(Power.EMPIRE.country,captured.bases.getValue(fortress.id).country)
        assertEquals(listOf(aboard,remote),remaining.activeGround!!.values)
        assertEquals(mapOf(aboard.stack to 1,remote.stack to 1,crew to 2),remaining.units.troops)
        assertEquals(fleet.units.ships,remaining.units.ships)
        assertEquals(listOf(3),remaining.activeShips!!.values.filter {it.boats>0}.map {it.id})
    }
    @Test fun occupationAndExpulsionPublishOnlyAfterOneDurableCommitAndRestartCannotDuplicateLoot() {
        var stored=stocks();var fail=true
        val store=object:PlanetStockStore {override fun load()=stored;override fun save(stocks:PlanetStocks){stored=stocks}}
        val loser=Navigator(1,10001,Power.ALLIANCE,2487,777,555,321,baseId=1000,mode=4,returnBase=1000)
        var savedPlayers=mapOf("offline" to loser)
        val navStore=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(players:Map<String,Navigator>){savedPlayers=players}
        }
        val production=PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        val nav=Navigation(galaxy,navStore,NavigationPolicy())
        val commit=WorldStateCommitter {players,inventory->if(fail)throw java.io.IOException("disk")
            else {savedPlayers=players;stored=checkNotNull(inventory)}}
        val events=mutableListOf<BaseOccupation>();val before=production.snapshot(1)!!
        assertFailsWith<java.io.IOException> {TacticalCombat.settle(session,galaxy,nav,production,commit,scale=1,onOccupation=events::add)}
        assertEquals(before,production.snapshot(1));assertEquals(loser,nav.snapshot(1)["offline"]);assertTrue(events.isEmpty())
        fail=false;TacticalCombat.settle(session,galaxy,nav,production,commit,scale=1,onOccupation=events::add)
        assertEquals(2,stored.bases.getValue(1000).power);assertEquals(0,savedPlayers.getValue("offline").baseId)
        assertEquals(1,events.size)
        val restored=PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy())
        TacticalCombat.settle(session,galaxy,Navigation(galaxy,navStore,NavigationPolicy()),restored,commit,scale=1,onOccupation=events::add)
        assertEquals(1,events.size);assertEquals(1000,stored.bases.getValue(1000).supplies)
    }
    @Test fun eliminatingTheLastDefenderCommitsCombatAndOccupationTogether() {
        val original=stocks();val defender=original.outfits.getValue(2)
        val troops=ActiveGroundUnits(defender.activeGround!!.values.map {
            if(it.id==4)it.copy(carrier=0,base=1000,strength=1) else it
        })
        var saved=original.copy(outfits=original.outfits+(2 to defender.copy(activeGround=troops)))
        val store=object:PlanetStockStore {override fun load()=saved;override fun save(stocks:PlanetStocks){saved=stocks}}
        val production=PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        val nav=Navigation(galaxy,null,NavigationPolicy());val captures=mutableListOf<BaseOccupation>()
        val battles=mutableListOf<GroundBattleResult>()
        val commit=WorldStateCommitter {_,inventory->saved=checkNotNull(inventory)}
        TacticalCombat.settle(session,galaxy,nav,production,commit,scale=1,onOccupation=captures::add)
        assertEquals(3,saved.bases.getValue(1000).power);assertTrue(captures.isEmpty())
        TacticalCombat.settle(session.copy(gameMillis=(now+5)*1000),galaxy,nav,production,commit,scale=1,
            onGroundBattle=battles::add,onOccupation=captures::add)
        assertEquals(2,saved.bases.getValue(1000).power);assertEquals(1,captures.size)
        assertTrue(4 in battles.single().destroyed);assertTrue(saved.groundBattles.isEmpty())
        assertEquals(1,saved.outfits.getValue(1).activeGround!!.values.single().id)
    }
    @Test fun losingCapitalPermanentlyRemovesStoredNationalAndLogisticsAppointments() {
        val capital=base.copy(grid=2015,name="ハイネセン")
        val world=Galaxy(listOf(GalaxySystem(3,2015,4,"Capital",3)),listOf(capital),Power.entries.associateWith {2015})
        val original=stocks().copy(nationalOffices=mapOf(NationalOffice.DEFENSE_HEAD to 10001),
            logisticsOfficers=mapOf(10001 to LogisticsOffice.ALLIANCE_THIRD))
        val captured=advance(original,world=world)
        assertTrue(captured.nationalOffices.isEmpty());assertTrue(captured.logisticsOfficers.isEmpty())
        val recaptured=captured.copy(bases=captured.bases+(1000 to captured.bases.getValue(1000).copy(owner=Allegiance.fromLegacy(3))))
        assertTrue(NationalOfficePolicy(world).cards(recaptured,10001,3).isEmpty())
        assertTrue(LogisticsOfficePolicy(world).cards(recaptured,10001,3).isEmpty())
    }

    @Test fun allianceCanCaptureAnUndefendedImperialPlanetWithoutConvertingTroops() {
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(4),base=1000)))
        val outfit=OutfitStock(1,3,OutfitKind.GROUND,1000,101,
            units=UnitInventory(troops=troops.quantities()),activeGround=troops)
        val original=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100,governorId=10000)),
            mapOf(1 to outfit),mapOf(101 to OutfitWarehouse(101,3,OutfitKind.PATROL,1000)),
            deploymentInitialized=true,nextShipId=2)
        val result=advance(original)
        assertEquals(3,result.bases.getValue(1000).power)
        assertEquals(0,result.bases.getValue(1000).governorId)
        assertEquals(troops,result.outfits.getValue(1).activeGround)
    }

}
