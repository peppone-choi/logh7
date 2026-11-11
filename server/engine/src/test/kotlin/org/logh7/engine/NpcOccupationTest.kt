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
        val world=Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2),GalaxySystem(4,2015,4,"バーラト",3),
            GalaxySystem(5,2016,4,"E",2),GalaxySystem(6,2488,4,"A",3)),
            listOf(odin,heinessen,depot,empireFront,allianceFront),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
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
    }
}
