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
        events:MutableList<BaseOccupation> = mutableListOf(),state:SessionState=session,world:Galaxy=galaxy,cap:Int=Int.MAX_VALUE)=
        BaseOccupationOperations.advance(state,world,players,s,cap,1,events)

    @Test fun unopposedLandingCapturesOnceDestroysStationedShipsAndClearsAppointments() {
        val original=stocks();val events=mutableListOf<BaseOccupation>();val captured=advance(original,events=events)
        val owner=captured.bases.getValue(1000)
        assertEquals(2,owner.power);assertEquals(1000,owner.supplies)
        assertEquals(0,owner.governorId);assertEquals(0,owner.garrisonCommanderId)
        assertTrue(owner.units.ships.isEmpty());assertTrue(owner.spareShips.values.isEmpty())
        assertEquals(mapOf("T4" to 3),owner.units.work)
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
        val recaptured=captured.copy(bases=captured.bases+(1000 to captured.bases.getValue(1000).copy(power=3)))
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
