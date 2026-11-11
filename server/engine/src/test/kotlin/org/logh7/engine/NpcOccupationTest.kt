package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcOccupationTest {
    private val session=SessionSimulation(SessionRules.load()).exportState()
    private val now=session.gameMillis/1000
    private val grid=2488
    private val base=GalaxyBase(1000,grid,80,1,"Fortress",0,0f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,grid,4,"Battle",2)),listOf(base),
        Power.entries.associateWith {grid})
    private fun time(offset:Long)=session.copy(gameMillis=(now+offset)*1000)
    private fun stocks(distance:Float):PlanetStocks {
        val point=TacticalPoint(0f,distance)
        val motion=TacticalMotion(grid,now,TacticalPose(point,0f),0f,0f,listOf(point))
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion,now,now))))
        val attackers=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),carrier=1)))
        val defenders=ActiveGroundUnits(listOf(ActiveGroundUnit(6,UnitStack(4),base=base.id,strength=10)))
        val carrier=OutfitStock(1,2,OutfitKind.FLEET,base.id,10,0,UnitInventory(ships.quantities(),attackers.quantities()),
            activeShips=ships,activeGround=attackers,position=OutfitPosition(grid))
        val defense=OutfitStock(2,3,OutfitKind.FLEET,base.id,20,0,UnitInventory(troops=defenders.quantities()),
            activeShips=ActiveShipUnits(emptyList()),activeGround=defenders)
        return PlanetStocks(1,0,mapOf(base.id to PlanetStock(base.id,3,0,100,0,0)),
            outfits=mapOf(1 to carrier,2 to defense),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,base.id,1),
                20 to OutfitWarehouse(20,3,OutfitKind.FLEET,base.id,2)),deploymentInitialized=true,nextShipId=7,
            social=SocialState(goals=GoalState(national=mapOf(2 to NationalGoal(base.id,10000,now)))))
    }
    private fun ship(s:PlanetStocks)=s.outfits.getValue(1).activeShips!!.values.single()
    private fun troop(s:PlanetStocks)=s.outfits.getValue(1).activeGround!!.values.single()
    private fun advance(s:PlanetStocks,offset:Long,movements:MutableList<TacticalMovement> = mutableListOf())=
        NpcTactics.advance(time(offset),galaxy,mutableMapOf(),s,NpcTacticalPolicy(),1,mutableListOf(),movements)

    @Test fun carrierApproachesAnchorsAndCommitsTheSameInfantryToDefendedObjective() {
        val moves=mutableListOf<TacticalMovement>()
        var s=advance(stocks(12f),0,moves)
        assertTrue(moves.single().motion!!.velocity>0)
        assertNull(ship(s).posture?.order)
        s=advance(s,200)
        val anchor=assertNotNull(ship(s).posture?.order)
        assertEquals(now+210,anchor.readyAt)
        assertNull(troop(s).order)
        val poses=mutableListOf<TacticalPostureResult>()
        s=TacticalPostureOperations.advance(time(210),galaxy,mutableMapOf(),s,poses)
        assertEquals(0,poses.single().character)
        assertTrue(ship(s).posture!!.autonomousAnchor)
        s=advance(s,210)
        val landing=assertNotNull(troop(s).order)
        assertEquals(now+235,landing.readyAt)
        val events=mutableListOf<TacticalGroundResult>()
        assertEquals(s,TacticalGroundOperations.advance(time(234),galaxy,emptyMap(),s,events))
        s=TacticalGroundOperations.advance(time(235),galaxy,emptyMap(),s,events)
        assertFalse(events.single().cancelled)
        assertEquals(listOf(5),events.single().troops)
        assertEquals(base.id,troop(s).base)
        assertEquals(1,troop(s).origin)
        assertNull(BaseOccupationOperations.victor(s,base))
        val fights=mutableListOf<GroundBattleResult>()
        s=GroundBattleOperations.advance(time(235),galaxy,emptyMap(),s,1,fights)
        s=GroundBattleOperations.advance(time(240),galaxy,emptyMap(),s,1,fights)
        assertEquals(listOf(6),fights.single().destroyed)
        val occupations=mutableListOf<BaseOccupation>()
        s=BaseOccupationOperations.advance(time(240),galaxy,mutableMapOf(),s,10000,1,occupations,mutableListOf())
        assertEquals(Power.EMPIRE.country,occupations.single().country)
        assertEquals(Power.EMPIRE.country,s.bases.getValue(base.id).country)
        assertTrue(s.social.goals.national.isEmpty())
        assertFalse(s.strategic.people.containsKey(0))
        s=advance(s,241)
        s=TacticalPostureOperations.advance(time(241),galaxy,mutableMapOf(),s,poses)
        assertEquals(TacticalShipMode.SAILING,ship(s).posture!!.mode)
        assertEquals(5,troop(s).id)
    }

    @Test fun oneCarrierReturnsForInfantryWhileTheOtherFleetCoversTheGarrison() {
        val original=stocks(1f)
        val home=base.copy(id=1001,grid=2487)
        val friendlyPort=base.copy(id=1002)
        val world=Galaxy(
            listOf(GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,grid,4,"Front",3)),
            listOf(home,base,friendlyPort),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to grid))
        val ship=ship(original).copy(tactical=null)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,supplies=3000,
            units=UnitInventory(ships=mapOf(ship.stack to 1)),activeShips=ActiveShipUnits(listOf(ship)),activeGround=null)
        val screen=fleet.copy(id=3,warehouseId=30,activeShips=ActiveShipUnits(listOf(ship.copy(id=7))))
        val defense=original.outfits.getValue(2)
        val initial=original.copy(
            bases=original.bases+mapOf(home.id to PlanetStock(home.id,2,0,100_000,0,0,
                units=UnitInventory(troops=mapOf(UnitStack(0) to 2))),friendlyPort.id to PlanetStock(friendlyPort.id,2,0,0,0,0)),
            outfits=mapOf(fleet.id to fleet,screen.id to screen,defense.id to defense),
            warehouses=original.warehouses+mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id),
                screen.warehouseId to OutfitWarehouse(screen.warehouseId,2,OutfitKind.FLEET,home.id,screen.id)),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to base.id)),
            social=SocialState(),nextShipId=8)
        fun advance(offset:Long,state:PlanetStocks)=NpcStrategy.advance(time(offset),world,mutableMapOf(),state,mutableListOf())
        val withoutCover=initial.copy(outfits=initial.outfits-screen.id,warehouses=initial.warehouses-screen.warehouseId)
        assertNull(advance(0,withoutCover).outfits.getValue(fleet.id).strategy?.expeditionBase)
        val hostileShip=ActiveShipUnit(8,UnitStack(119))
        val withEnemy=initial.copy(outfits=initial.outfits+(defense.id to defense.copy(position=OutfitPosition(grid),
            activeShips=ActiveShipUnits(listOf(hostileShip)),units=UnitInventory(mapOf(hostileShip.stack to 1),defense.units.troops))),
            nextShipId=9)
        assertNull(advance(0,withEnemy).outfits.getValue(fleet.id).strategy?.expeditionBase)

        val reserved=advance(0,initial)
        assertEquals(base.id,reserved.outfits.getValue(fleet.id).strategy?.expeditionBase)
        assertEquals(home.grid,reserved.outfits.getValue(fleet.id).strategy?.destination)
        assertNull(reserved.outfits.getValue(screen.id).strategy?.expeditionBase)
        val docked=advance(3600,reserved)
        assertEquals(OutfitPosition(home.grid,home.id),docked.outfits.getValue(fleet.id).position)
        val ready=advance(25200,docked)
        assertTrue(NpcOccupation.hasCarrier(ready.outfits.getValue(fleet.id),base,ready))
        assertEquals(grid,ready.outfits.getValue(fleet.id).strategy?.destination)
        assertEquals(OutfitPosition(grid),ready.outfits.getValue(screen.id).position)
    }

    @Test fun assigningACommanderCancelsQueuedLandingAndPreservesTheCargo() {
        var s=advance(stocks(1f),0)
        s=TacticalPostureOperations.advance(time(10),galaxy,mutableMapOf(),s,mutableListOf())
        s=advance(s,10)
        assertNotNull(troop(s).order)
        s=s.copy(outfits=s.outfits+(1 to s.outfits.getValue(1).copy(commanderId=10001)))
        val events=mutableListOf<TacticalGroundResult>()
        s=TacticalGroundOperations.advance(time(35),galaxy,emptyMap(),s,events)
        assertTrue(events.single().cancelled)
        assertNull(troop(s).order)
        assertEquals(1,troop(s).carrier)
        assertEquals(0,troop(s).base)
        assertEquals(TacticalShipMode.ANCHORED,ship(s).posture!!.mode)
    }

    @Test fun vacantGovernmentsSelectOneExpeditionFromActualProductionWithoutSettingNationalGoals() {
        val odin=base.copy(id=1049,grid=2487,kind=0,name="オーディン")
        val heinessen=base.copy(id=1177,grid=2015,kind=0,name="ハイネセン")
        val depot=heinessen.copy(id=1178,name="Depot")
        val empireFront=base.copy(id=1051,grid=2016,kind=0,name="Empire front")
        val allianceFront=base.copy(id=1052,grid=2488,kind=0,name="Alliance")
        val secondTarget=empireFront.copy(id=1053,grid=2014,name="Second front")
        val world=Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2),GalaxySystem(4,2015,4,"バーラト",3),
            GalaxySystem(5,2016,4,"E",2),GalaxySystem(6,2488,4,"A",3),GalaxySystem(7,2014,4,"E2",2)),
            listOf(odin,heinessen,depot,empireFront,allianceFront,secondTarget),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
        val initial=InitialOutfitDeployment(world).initialize(PlanetStocks(1,0,world.conditions.mapValues {(_,b)->
            PlanetStock(b.baseId,b.power,0,if(b.baseId==depot.id)0 else 100_000,0,0)
        }))
        fun advance(stock:PlanetStocks,session:SessionState)=NpcStrategy.advance(session,world,mutableMapOf(),stock,mutableListOf(),
            infantryProduction={_,country->listOf(if(country==Power.EMPIRE.country)0 else 4)})
        val stock=advance(initial,time(0))
        val empire=stock.outfits.values.filter {it.power==2 && it.kind==OutfitKind.FLEET}.sortedBy {it.id}
        val alliance=stock.outfits.values.filter {it.power==3 && it.kind==OutfitKind.FLEET}.sortedBy {it.id}
        assertEquals(allianceFront,NpcOccupation.goal(empire.first(),stock,world,emptySet()))
        assertNull(NpcOccupation.goal(empire.last(),stock,world,emptySet()))
        assertEquals(empireFront,NpcOccupation.goal(alliance.first(),stock,world,emptySet()))
        assertNull(empire.first().strategy?.destination)
        assertEquals(initial.outfits.getValue(empire.first().id).supplies+initial.bases.getValue(odin.id).supplies,
            empire.first().supplies+stock.bases.getValue(odin.id).supplies)
        assertNull(NpcOccupation.goal(alliance.first(),stock,world,setOf(CountryId(2))))
        assertTrue(stock.social.goals.national.isEmpty())

        val home=stock.bases.getValue(heinessen.id)
        val produced=stock.copy(bases=stock.bases+(home.baseId to home.copy(units=UnitInventory(troops=mapOf(UnitStack(4) to 2)))))
        val loaded=advance(produced,time(21600))
        val carrier=loaded.outfits.getValue(alliance.first().id)
        assertTrue(NpcOccupation.hasCarrier(carrier,empireFront,loaded))
        assertEquals(empireFront.grid,carrier.strategy?.destination)
        assertTrue(loaded.bases.getValue(heinessen.id).units.troops.isEmpty())
        val governed=loaded.copy(strategic=loaded.strategic.copy(
            offices=listOf(OfficeHolding(132,heinessen.id,10000,CountryId(3)))))
        assertNull(NpcOccupation.goal(carrier,governed,world,emptySet()))
        val directed=governed.copy(social=SocialState(goals=GoalState(national=mapOf(3 to NationalGoal(odin.id,10000,now)))))
        assertEquals(odin,NpcOccupation.goal(carrier,directed,world,emptySet()))
        assertEquals(odin,NpcOccupation.goal(alliance.last(),directed,world,emptySet()))
        assertNull(NpcOccupation.goal(carrier.copy(commanderId=10001),directed,world,emptySet()))

        val drained=OutfitSupplies.withBalances(carrier,carrier.activeShips!!.values.associate {it.id to 0})
            .copy(strategy=NpcStrategicState(now+21600,expeditionBase=empireFront.id))
        val ships=drained.activeShips!!.values
        val total=ships.sumOf {it.boats*5/2}
        var remaining=total
        val uneven=OutfitSupplies.withBalances(drained,ships.associate {ship->
            val supplied=minOf(remaining,OutfitSupplies.unitCapacity(ship))
            remaining-=supplied
            ship.id to supplied
        })
        assertTrue(ships.any {OutfitSupplies.distribution(uneven).getValue(it.id)<it.boats})
        val dry=loaded.copy(outfits=loaded.outfits+(carrier.id to uneven),bases=loaded.bases+
            (home.baseId to loaded.bases.getValue(home.baseId).copy(supplies=0)))
        val balanced=advance(dry,time(21600)).outfits.getValue(carrier.id)
        assertEquals(total,balanced.supplies)
        assertTrue(ships.all {OutfitSupplies.distribution(balanced).getValue(it.id) in it.boats*2..it.boats*3})
        assertEquals(empireFront.grid,balanced.strategy?.destination)

        val thin=loaded.copy(outfits=loaded.outfits+(carrier.id to drained),bases=loaded.bases+
            (home.baseId to loaded.bases.getValue(home.baseId).copy(supplies=0))+
            (depot.id to loaded.bases.getValue(depot.id).copy(supplies=100)))
        val reserve=advance(thin,time(21600))
        val reserving=reserve.outfits.getValue(carrier.id)
        assertEquals(depot.id,reserving.positionIn(world).baseId)
        assertEquals(100,reserving.supplies)
        assertEquals(0,reserve.bases.getValue(depot.id).supplies)
        assertNull(reserving.strategy?.destination)
        val reservedByPlayer=NpcStrategy.advance(time(21600),world,mutableMapOf(),thin,mutableListOf(),
            warehouseBlocked={_,id->id==carrier.id})
        assertEquals(0,reservedByPlayer.outfits.getValue(carrier.id).supplies)
        assertEquals(100,reservedByPlayer.bases.getValue(depot.id).supplies)

        val short=loaded.copy(outfits=loaded.outfits+(carrier.id to drained),bases=loaded.bases+
            (home.baseId to loaded.bases.getValue(home.baseId).copy(supplies=0))+
            (depot.id to loaded.bases.getValue(depot.id).copy(supplies=100_000)))
        val rebased=advance(short,time(21600))
        val provisioned=rebased.outfits.getValue(carrier.id)
        assertEquals(OutfitPosition(heinessen.grid,depot.id),provisioned.positionIn(world))
        assertEquals(0,rebased.bases.getValue(home.baseId).supplies)
        assertEquals(100_000-provisioned.supplies,rebased.bases.getValue(depot.id).supplies)
        assertEquals(carrier.activeGround,provisioned.activeGround)
        assertEquals(empireFront.id,provisioned.strategy?.expeditionBase)
        val resumed=advance(rebased,time(43200)).outfits.getValue(carrier.id)
        assertEquals(empireFront.grid,resumed.strategy?.destination)

        val landed=ActiveGroundUnits(carrier.activeGround!!.values.map {
            it.copy(carrier=0,base=empireFront.id,origin=it.carrier)
        })
        val afterCapture=rebased.copy(
            outfits=rebased.outfits+(carrier.id to resumed.copy(position=OutfitPosition(empireFront.grid),
                activeGround=landed,strategy=NpcStrategicState(now+43200,expeditionBase=empireFront.id))),
            bases=rebased.bases+
                (empireFront.id to rebased.bases.getValue(empireFront.id).copy(owner=Allegiance.Country(Power.ALLIANCE.country)))+
                (home.baseId to rebased.bases.getValue(home.baseId).copy(supplies=100_000)))
        val returning=advance(afterCapture,time(43200))
        assertEquals(heinessen.grid,returning.outfits.getValue(carrier.id).strategy?.destination)
        val docked=advance(returning,time(46800))
        val dockedCarrier=docked.outfits.getValue(carrier.id)
        assertEquals(heinessen.id,dockedCarrier.positionIn(world).baseId)
        val atEmptyHome=OutfitSupplies.withBalances(dockedCarrier,dockedCarrier.activeShips!!.values.associate {it.id to 0})
        val next=advance(docked.copy(outfits=docked.outfits+(carrier.id to atEmptyHome),bases=docked.bases+
            (home.baseId to docked.bases.getValue(home.baseId).copy(supplies=0))+
            (depot.id to docked.bases.getValue(depot.id).copy(supplies=100_000,
                units=UnitInventory(troops=mapOf(UnitStack(4) to 1))))),time(68400))
        val atDepot=next.outfits.getValue(carrier.id)
        assertEquals(secondTarget.id,atDepot.strategy?.expeditionBase)
        assertEquals(depot.id,atDepot.positionIn(world).baseId)
        assertFalse(NpcOccupation.hasCarrier(atDepot,secondTarget,next))
        val departing=advance(next,time(90000))
        val rearmed=departing.outfits.getValue(carrier.id)
        assertTrue(NpcOccupation.hasCarrier(rearmed,secondTarget,departing))
        assertTrue(departing.bases.getValue(depot.id).units.troops.isEmpty())
        assertEquals(landed.values,rearmed.activeGround!!.values.filter {it.base==empireFront.id})
        assertEquals(secondTarget.id,rearmed.strategy?.expeditionBase)
    }

    @Test fun reservedExpeditionDocksAtItsForwardBerthAfterTheStockRunsOut() {
        val home=base.copy(id=1001,grid=2487)
        val forward=base.copy(id=1002,grid=2489)
        val objective=base.copy(grid=2491)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,forward.grid,4,"Forward",2),
            GalaxySystem(5,objective.grid,4,"Objective",3)
        ),listOf(home,forward,objective),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to objective.grid))
        val original=stocks(12f)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(2488),
            strategy=NpcStrategicState(now+21600,forward.grid,now+3600),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))))
        val fueled=OutfitSupplies.withBalances(fleet,fleet.activeShips!!.values.associate {it.id to it.boats})
        val waiting=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,100_000,0,0),
            forward.id to PlanetStock(forward.id,2,0,0,0,0),
            objective.id to original.bases.getValue(base.id)
        ),outfits=mapOf(fleet.id to fueled),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        val arrived=NpcStrategy.advance(time(3600),world,mutableMapOf(),waiting,mutableListOf())
        val docked=arrived.outfits.getValue(fleet.id)
        assertEquals(OutfitPosition(forward.grid,forward.id),docked.positionIn(world))
        assertEquals(0,docked.supplies)
        assertEquals(0,arrived.bases.getValue(forward.id).supplies)
        assertEquals(fueled.activeGround,docked.activeGround)
        assertTrue(docked.strategy!!.awaitingDockSupply)

        val deliveredSupplies=docked.activeShips!!.values.single().boats*2
        val delivered=arrived.copy(bases=arrived.bases+
            (forward.id to arrived.bases.getValue(forward.id).copy(supplies=deliveredSupplies)))
        val replenished=NpcStrategy.advance(time(25200),world,mutableMapOf(),delivered,mutableListOf())
        assertEquals(deliveredSupplies,replenished.outfits.getValue(fleet.id).supplies)
        assertEquals(0,replenished.bases.getValue(forward.id).supplies)

        val captured=waiting.copy(bases=waiting.bases+
            (forward.id to waiting.bases.getValue(forward.id).copy(owner=Allegiance.Country(CountryId(3)))))
        val redirected=NpcStrategy.advance(time(3600),world,mutableMapOf(),captured,mutableListOf())
        assertNotEquals(forward.id,redirected.outfits.getValue(fleet.id).positionIn(world).baseId)
        assertEquals(captured.bases,redirected.bases)
    }

    @Test fun expeditionCanTransitAHostileGarrisonButAvoidsDeployedWarships() {
        val home=base.copy(id=1001,grid=2487)
        val transit=base.copy(id=1002,grid=2489)
        val objective=base.copy(grid=2490)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,transit.grid,4,"Transit",3),
            GalaxySystem(5,objective.grid,4,"Objective",3),GalaxySystem(6,2488,4,"Approach",2)
        ),listOf(home,transit,objective),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to objective.grid),
            defaultCell=Galaxy.IMPASSABLE_CELL)
        val original=stocks(12f)
        val carrier=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(2488),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))))
        val ship=carrier.activeShips!!.values.single()
        val fueled=OutfitSupplies.withBalances(carrier,mapOf(ship.id to ship.boats*8))
        val soldiers=ActiveGroundUnits((10..15).map {ActiveGroundUnit(it,UnitStack(4),base=transit.id)})
        val defenders=ActiveShipUnits((20..24).map {ActiveShipUnit(it,UnitStack(93))})
        val garrison=original.outfits.getValue(2).copy(baseId=transit.id,position=OutfitPosition(transit.grid,transit.id),
            units=UnitInventory(defenders.quantities(),soldiers.quantities()),activeShips=defenders,activeGround=soldiers,
            commanderId=10001)
        val waiting=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,100_000,0,0),
            transit.id to PlanetStock(transit.id,3,0,0,0,0),
            objective.id to original.bases.getValue(base.id)
        ),outfits=mapOf(carrier.id to fueled,garrison.id to garrison),
            warehouses=original.warehouses+mapOf(
                carrier.warehouseId to original.warehouses.getValue(carrier.warehouseId).copy(baseId=home.id),
                garrison.warehouseId to original.warehouses.getValue(garrison.warehouseId).copy(baseId=transit.id)),
            nextShipId=25)
        fun advance(s:PlanetStocks,offset:Long)=NpcStrategy.advance(time(offset),world,mutableMapOf(),s,mutableListOf())
        val pending=advance(waiting,0)
        assertEquals(transit.grid,pending.outfits.getValue(carrier.id).strategy?.destination)
        val passing=advance(pending,3600)
        assertEquals(OutfitPosition(transit.grid),passing.outfits.getValue(carrier.id).positionIn(world))
        val onward=advance(passing,25200)
        assertEquals(objective.grid,onward.outfits.getValue(carrier.id).strategy?.destination)
        assertEquals(soldiers,onward.outfits.getValue(garrison.id).activeGround)

        val atSea=garrison.copy(position=OutfitPosition(transit.grid))
        val patrolShips=ActiveShipUnits(defenders.values.take(1))
        val lightPatrol=atSea.copy(activeShips=patrolShips,units=UnitInventory(patrolShips.quantities(),soldiers.quantities()))
        val withPatrol=advance(waiting.copy(outfits=waiting.outfits+(lightPatrol.id to lightPatrol)),0)
        assertEquals(transit.grid,withPatrol.outfits.getValue(carrier.id).strategy?.destination)
        val threatened=advance(waiting.copy(outfits=waiting.outfits+(atSea.id to atSea)),0)
        assertNotEquals(transit.grid,threatened.outfits.getValue(carrier.id).strategy?.destination)

        val fortified=atSea.copy(position=OutfitPosition(objective.grid,objective.id),
            activeShips=null,activeGround=ActiveGroundUnits(soldiers.values.map {it.copy(base=objective.id)}),
            units=UnitInventory(troops=soldiers.quantities()))
        val blockedAssault=advance(waiting.copy(outfits=waiting.outfits+(fortified.id to fortified)),0)
        assertNotEquals(transit.grid,blockedAssault.outfits.getValue(carrier.id).strategy?.destination)

        val committed=fueled.copy(strategy=NpcStrategicState(now,expeditionBase=objective.id))
        val autonomous=waiting.copy(outfits=waiting.outfits+(carrier.id to committed),social=SocialState(),
            strategic=waiting.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to objective.id)))
        val forces=GridForces.collect(world,autonomous.generation,emptyList(),autonomous)
        assertEquals(5,NpcStrategy.returnDistance(world,forces,committed,autonomous))
        assertEquals(1,NpcStrategy.returnDistance(world,forces,committed,autonomous,setOf(CountryId(3))))
    }

    @Test fun retainsAnExpeditionAtAnEmptyPortWithOngoingSupplyProduction() {
        val home=base.copy(id=1001,grid=2487)
        val alternate=base.copy(id=1002,grid=2488)
        val objective=base.copy(grid=2491)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,alternate.grid,4,"Alternate",3),
            GalaxySystem(5,objective.grid,4,"Objective",3)
        ),listOf(home,alternate,objective),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to objective.grid))
        val original=stocks(12f)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(home.grid,home.id),
            strategy=NpcStrategicState(now,expeditionBase=objective.id),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))))
        val ship=fleet.activeShips!!.values.single()
        val fueled=OutfitSupplies.withBalances(fleet,mapOf(ship.id to ship.boats*2))
        val waiting=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,0,0,0),
            alternate.id to PlanetStock(alternate.id,3,0,0,0,0),
            objective.id to original.bases.getValue(base.id)
        ),outfits=mapOf(fleet.id to fueled),social=SocialState(),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to objective.id)),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        val control=NpcStrategy.advance(time(0),world,mutableMapOf(),waiting,mutableListOf())
        assertEquals(alternate.id,control.outfits.getValue(fleet.id).strategy?.expeditionBase)
        val result=NpcStrategy.advance(time(0),world,mutableMapOf(),waiting,mutableListOf(),
            producesSupplies={it.baseId==home.id})
        val retained=result.outfits.getValue(fleet.id)
        assertEquals(objective.id,retained.strategy?.expeditionBase)
        assertNull(retained.strategy?.destination)
        assertEquals(fueled.supplies,retained.supplies)
        assertEquals(0,result.bases.getValue(home.id).supplies)
    }

    @Test fun returningExpeditionWaitsAtAProductiveBerthInsteadOfBacktrackingForRelief() {
        val home=base.copy(id=1001,grid=2487)
        val stage=base.copy(id=1002,grid=2491)
        val relief=base.copy(id=1003,grid=2492)
        val capital=base.copy(id=1004,grid=2493)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,stage.grid,4,"Stage",2),
            GalaxySystem(5,relief.grid,4,"Relief",2),GalaxySystem(6,capital.grid,4,"Capital",3)
        ),listOf(home,stage,relief,capital),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to capital.grid))
        val original=stocks(12f)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(stage.grid,stage.id),
            strategy=NpcStrategicState(now,expeditionBase=stage.id),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),base=stage.id,origin=1))))
        val waiting=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,0,0,0),
            stage.id to PlanetStock(stage.id,2,0,0,0,0),
            relief.id to PlanetStock(relief.id,2,0,100_000,0,0),
            capital.id to PlanetStock(capital.id,3,0,0,0,0)
        ),outfits=mapOf(fleet.id to fleet),social=SocialState(),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to capital.id)),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        fun advance(offset:Long,stock:PlanetStocks,productive:Boolean=true)=NpcStrategy.advance(
            time(offset),world,mutableMapOf(),stock,mutableListOf(),producesSupplies={productive && it.baseId==stage.id})

        assertEquals(relief.grid,advance(0,waiting,productive=false).outfits.getValue(fleet.id).strategy?.destination)
        val held=advance(0,waiting)
        val retained=held.outfits.getValue(fleet.id)
        assertEquals(stage.id,retained.positionIn(world).baseId)
        assertEquals(stage.id,retained.strategy?.expeditionBase)
        assertNull(retained.strategy?.destination)
        assertEquals(waiting.bases,held.bases)
        assertEquals(fleet.activeGround,retained.activeGround)

        val fuel=4*fleet.activeShips!!.values.single().boats
        val supplied=held.copy(bases=held.bases+(stage.id to held.bases.getValue(stage.id).copy(supplies=fuel)))
        val departing=advance(21600,supplied)
        val jump=assertNotNull(departing.outfits.getValue(fleet.id).strategy?.destination)
        assertEquals(90,jump%100)
        val underway=advance(25200,departing)
        assertEquals(jump,underway.outfits.getValue(fleet.id).positionIn(world).grid)
        assertEquals(fuel-fleet.activeShips!!.values.single().boats,underway.outfits.getValue(fleet.id).supplies)
        assertEquals(supplied.bases.getValue(relief.id),underway.bases.getValue(relief.id))
    }

    @Test fun returningExpeditionKeepsItsCargoWhenNoDepotIsWithinRange() {
        val home=base.copy(id=1001,grid=2487)
        val captured=base.copy(id=1002,grid=2497)
        val capital=base.copy(id=1003,grid=2499)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,captured.grid,4,"Captured",2),
            GalaxySystem(5,capital.grid,4,"Capital",3)
        ),listOf(home,captured,capital),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to capital.grid))
        val original=stocks(12f)
        val position=OutfitPosition(2490)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=position,
            strategy=NpcStrategicState(now,expeditionBase=captured.id),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),base=captured.id,origin=1))))
        val ship=fleet.activeShips!!.values.single()
        val short=OutfitSupplies.withBalances(fleet,mapOf(ship.id to ship.boats*2))
        val waiting=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,100_000,0,0),
            captured.id to PlanetStock(captured.id,2,0,0,0,0),
            capital.id to PlanetStock(capital.id,3,0,0,0,0)
        ),outfits=mapOf(fleet.id to short),social=SocialState(),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to capital.id)),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        val moves=mutableListOf<NpcStrategicMovement>()
        fun advance(offset:Long,stock:PlanetStocks)=NpcStrategy.advance(time(offset),world,mutableMapOf(),stock,moves)

        val held=advance(0,waiting)
        val retained=held.outfits.getValue(fleet.id)
        assertNull(retained.strategy?.destination)
        assertEquals(captured.id,retained.strategy?.expeditionBase)
        assertEquals(position,retained.positionIn(world))
        assertEquals(short.supplies,retained.supplies)
        assertEquals(waiting.bases,held.bases)
        assertTrue(moves.isEmpty())

        val funded=OutfitSupplies.withBalances(retained,mapOf(ship.id to ship.boats*3))
        val ready=held.copy(outfits=mapOf(fleet.id to funded))
        val departing=advance(21600,ready)
        val jump=assertNotNull(departing.outfits.getValue(fleet.id).strategy?.destination)
        assertEquals(89,jump%100)
        val underway=advance(25200,departing)
        assertEquals(jump,underway.outfits.getValue(fleet.id).positionIn(world).grid)
        assertEquals(short.supplies,underway.outfits.getValue(fleet.id).supplies)
        assertEquals(jump,moves.single().destination)
    }

    @Test fun aReturningExpeditionReservesTheNextLegWhileItsHomeSectorProducesSupplies() {
        val home=base.copy(id=1001,grid=2487)
        val depot=home.copy(id=1002)
        val captured=base.copy(id=1003,grid=2485)
        val objective=base.copy(id=1004,grid=2490)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,captured.grid,4,"Captured",2),
            GalaxySystem(5,objective.grid,4,"Objective",3)
        ),listOf(home,depot,captured,objective),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to objective.grid))
        val original=stocks(12f)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(home.grid,home.id),
            strategy=NpcStrategicState(now,expeditionBase=captured.id),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))))
        val waiting=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,0,0,0),
            depot.id to PlanetStock(depot.id,2,0,100,0,0),
            captured.id to PlanetStock(captured.id,2,0,0,0,0),
            objective.id to PlanetStock(objective.id,3,0,0,0,0)
        ),outfits=mapOf(fleet.id to fleet),social=SocialState(),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to objective.id)),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        fun advance(offset:Long,stock:PlanetStocks,productive:Boolean=true)=NpcStrategy.advance(
            time(offset),world,mutableMapOf(),stock,mutableListOf(),producesSupplies={productive && it.baseId==depot.id})

        assertEquals(captured.id,advance(0,waiting,productive=false).outfits.getValue(fleet.id).strategy?.expeditionBase)
        val reserved=advance(0,waiting)
        val parked=reserved.outfits.getValue(fleet.id)
        assertEquals(objective.id,parked.strategy?.expeditionBase)
        assertEquals(depot.id,parked.positionIn(world).baseId)
        assertEquals(100,parked.supplies)
        assertEquals(0,reserved.bases.getValue(depot.id).supplies)
        assertEquals(fleet.activeGround,parked.activeGround)

        val budget=fleet.activeShips!!.values.single().boats*6
        val produced=reserved.copy(bases=reserved.bases+
            (depot.id to reserved.bases.getValue(depot.id).copy(supplies=budget-parked.supplies)))
        val departing=advance(21600,produced)
        assertEquals(88,assertNotNull(departing.outfits.getValue(fleet.id).strategy?.destination)%100)
    }

    @Test fun expeditionFinishesTheLegWhenItsLastHopDoesNotReduceDistanceToTheObjective() {
        val home=base.copy(id=1001,grid=2487)
        val stage=base.copy(id=1002,grid=2190)
        val objective=base.copy(id=1003,grid=2492)
        val world=Galaxy(listOf(GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,stage.grid,4,"Stage",2),
            GalaxySystem(5,objective.grid,4,"Objective",3)),listOf(home,stage,objective),
            mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to objective.grid))
        val original=stocks(12f)
        val ship=ActiveShipUnit(1,UnitStack(32))
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(home.grid,home.id),
            strategy=NpcStrategicState(now,expeditionBase=objective.id),activeShips=ActiveShipUnits(listOf(ship)))
        val funded=OutfitSupplies.withBalances(fleet,mapOf(ship.id to ship.boats*3))
        val parked=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,0,0,0),
            stage.id to PlanetStock(stage.id,2,0,1000,0,0),
            objective.id to PlanetStock(objective.id,3,0,0,0,0)
        ),outfits=mapOf(fleet.id to funded),social=SocialState(),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to objective.id)),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        val moves=mutableListOf<NpcStrategicMovement>()
        fun advance(offset:Long,stock:PlanetStocks)=NpcStrategy.advance(time(offset),world,mutableMapOf(),stock,moves)

        var stock=parked
        for(offset in listOf(0L,3600L,25200L,28800L,50400L,54000L))stock=advance(offset,stock)
        val docked=stock.outfits.getValue(fleet.id)
        assertEquals(OutfitPosition(stage.grid,stage.id),docked.positionIn(world))
        assertEquals(objective.id,docked.strategy?.expeditionBase)
        assertEquals(fleet.activeGround,docked.activeGround)
        assertEquals(listOf(2388,2289,stage.grid),moves.map {it.destination})
        assertEquals(1000,docked.supplies)
    }

    @Test fun underwayExpeditionCanFinishAReachableLegToAnEmptyProductivePort() {
        val home=base.copy(id=1001,grid=2487)
        val stage=base.copy(id=1002,grid=2490)
        val objective=base.copy(id=1003,grid=2493)
        val world=Galaxy(listOf(
            GalaxySystem(3,home.grid,4,"Home",2),GalaxySystem(4,stage.grid,4,"Stage",2),
            GalaxySystem(5,objective.grid,4,"Objective",3)
        ),listOf(home,stage,objective),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to objective.grid))
        val original=stocks(12f)
        val fleet=original.outfits.getValue(1).copy(baseId=home.id,position=OutfitPosition(2488),
            strategy=NpcStrategicState(now,expeditionBase=objective.id),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))))
        val ship=fleet.activeShips!!.values.single()
        val funded=OutfitSupplies.withBalances(fleet,mapOf(ship.id to ship.boats*2))
        val underway=original.copy(bases=mapOf(
            home.id to PlanetStock(home.id,2,0,100_000,0,0),
            stage.id to PlanetStock(stage.id,2,0,0,0,0),
            objective.id to PlanetStock(objective.id,3,0,0,0,0)
        ),outfits=mapOf(fleet.id to funded),social=SocialState(),
            strategic=original.strategic.copy(capitals=mapOf(CountryId(2) to home.id,CountryId(3) to objective.id)),
            warehouses=mapOf(fleet.warehouseId to original.warehouses.getValue(fleet.warehouseId).copy(baseId=home.id)))
        val moves=mutableListOf<NpcStrategicMovement>()
        fun advance(offset:Long,stock:PlanetStocks,productive:Boolean=true)=NpcStrategy.advance(
            time(offset),world,mutableMapOf(),stock,moves,producesSupplies={productive && it.baseId==stage.id})

        assertEquals(home.grid,advance(0,underway,productive=false).outfits.getValue(fleet.id).strategy?.destination)
        val reserved=advance(0,underway)
        val jump=assertNotNull(reserved.outfits.getValue(fleet.id).strategy?.destination)
        assertEquals(89,jump%100)
        assertEquals(underway.bases,reserved.bases)
        val first=advance(3600,reserved)
        val next=advance(25200,first)
        assertEquals(stage.grid,next.outfits.getValue(fleet.id).strategy?.destination)
        val arrived=advance(28800,next)
        val docked=arrived.outfits.getValue(fleet.id)
        assertEquals(OutfitPosition(stage.grid,stage.id),docked.positionIn(world))
        assertEquals(0,docked.supplies)
        assertEquals(fleet.activeGround,docked.activeGround)
        assertEquals(listOf(jump,stage.grid),moves.map {it.destination})
    }
}
