package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Manual participation and designed simultaneous damage, using real physical rosters. Author: Peppone Choi. */
class GroundBattleOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).exportState()
    private val now=session.gameMillis/1000
    private val base=GalaxyBase(1000,2487,80,0,"Battle",0,0f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(base),Power.entries.associateWith {2487})
    private fun stocks(empire:Int=1,alliance:Int=1,empireKind:Int=0):PlanetStocks {
        val units=listOf(2 to Pair(empire,empireKind),3 to Pair(alliance,4))
        var id=1
        val outfits=units.mapIndexed {index,(power,pair)->
            val roster=ActiveGroundUnits(List(pair.first){ActiveGroundUnit(id++,UnitStack(pair.second),base=1000)})
            index+1 to OutfitStock(index+1,power,OutfitKind.GROUND,1000,101+index,
                units=UnitInventory(troops=roster.quantities()),activeGround=roster)
        }.toMap()
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,200,300)),outfits,
            outfits.mapValues {(_,o)->OutfitWarehouse(o.warehouseId,o.power,OutfitKind.PATROL,1000)}.values.associateBy {it.id},
            deploymentInitialized=true,nextShipId=id)
    }
    private fun advance(s:PlanetStocks,after:Long,events:MutableList<GroundBattleResult> = mutableListOf())=
        GroundBattleOperations.advance(session.copy(gameMillis=(now+after)*1000),galaxy,emptyMap(),s,1,events)

    @Test fun simultaneousLossesPreserveIdentityUntilDeathAndCannotReplayAtSameTime() {
        val original=stocks();val started=advance(original,0)
        assertEquals(now,started.groundBattles[1000]);assertEquals(original.outfits,started.outfits)
        val events=mutableListOf<GroundBattleResult>();val first=advance(started,5,events)
        assertEquals(listOf(800,800),first.outfits.values.flatMap {it.activeGround!!.values}.map {it.strength})
        assertEquals(800,TacticalBaseDefense.strength(first,1000));assertTrue(events.single().destroyed.isEmpty())
        assertSame(first,advance(first,5));assertEquals(3,first.nextShipId)
        val finished=advance(first,500)
        assertTrue(finished.outfits.values.all {it.activeGround!!.values.isEmpty() && it.units.troops.isEmpty()})
        assertTrue(finished.groundBattles.isEmpty());assertEquals(original.warehouses,finished.warehouses)
    }
    @Test fun elapsedSettlementMatchesFrequentTicksAndStrongerSurvivorKeepsRealId() {
        val initial=advance(stocks(empireKind=2),0)
        val fast=advance(initial,500)
        var frequent=initial
        for(i in 1..100)frequent=advance(frequent,i*5L)
        assertEquals(fast,frequent)
        assertEquals(1,fast.outfits.getValue(1).activeGround!!.values.single().id)
        assertTrue(fast.outfits.getValue(2).activeGround!!.values.isEmpty())
    }
    @Test fun thirtyParticipantsReservePromotionAndBoardedOrUnknownUnitsCannotAttack() {
        val original=stocks(31,1);val initial=advance(original,0)
        assertEquals((1..30).toList(),GroundBattleOperations.participants(initial,base,2).map {it.id})
        val first=advance(initial,5)
        assertEquals(1000,first.outfits.getValue(1).activeGround!!.values.single {it.id==31}.strength)
        assertTrue(first.outfits.getValue(2).activeGround!!.values.isEmpty())
        val o=original.outfits.getValue(1)
        val unknown=original.copy(outfits=original.outfits+(1 to o.copy(activeGround=ActiveGroundUnits(o.activeGround!!.values.map {it.copy(base=0)}))))
        assertSame(unknown,advance(unknown,0))
        val fortress=base.copy(kind=1)
        assertTrue(GroundBattleOperations.participants(stocks(empireKind=2),fortress,2).isEmpty())
    }
    @Test fun reserveCannotHideDamageInTheFirstThirtyDefenseBarAndInvalidStateIsRejected() {
        val original=stocks(31,1);val o=original.outfits.getValue(1)
        val wounded=original.copy(outfits=original.outfits+(1 to o.copy(activeGround=ActiveGroundUnits(o.activeGround!!.values.map {
            if(it.id==1)it.copy(strength=1) else it
        }))))
        assertEquals(29001,TacticalBaseDefense.strength(wounded,1000))
        assertFails {ActiveGroundUnit(99,UnitStack(0),strength=0)}
        assertFails {original.copy(groundBattles=mapOf(1001 to now))}
    }
    @Test fun worldCommitFailureCannotPublishCasualtiesClockOrNotificationsAndRetryIsOnce() {
        var saved=stocks();var fail=false
        val store=object:PlanetStockStore {override fun load()=saved;override fun save(stocks:PlanetStocks){saved=stocks}}
        val nav=Navigation(galaxy,object:NavigationStore {
            override fun load()=emptyMap<String,Navigator>();override fun save(players:Map<String,Navigator>) {}
        },NavigationPolicy())
        val production=PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        val commit=WorldStateCommitter {_,inventory->if(fail)throw java.io.IOException("disk");saved=checkNotNull(inventory)}
        val events=mutableListOf<GroundBattleResult>()
        TacticalCombat.settle(session,galaxy,nav,production,commit,scale=1,onGroundBattle=events::add)
        val before=production.snapshot(1)!!;assertEquals(now,before.groundBattles[1000]);fail=true
        val later=session.copy(gameMillis=(now+5)*1000)
        assertFailsWith<java.io.IOException> {TacticalCombat.settle(later,galaxy,nav,production,commit,scale=1,onGroundBattle=events::add)}
        assertEquals(before,production.snapshot(1));assertEquals(before,saved);assertTrue(events.isEmpty())
        fail=false;TacticalCombat.settle(later,galaxy,nav,production,commit,scale=1,onGroundBattle=events::add)
        assertEquals(1,events.size);assertEquals(800,saved.outfits.getValue(1).activeGround!!.values.single().strength)
        TacticalCombat.settle(later,galaxy,nav,production,commit,scale=1,onGroundBattle=events::add);assertEquals(1,events.size)
    }

    @Test fun killedFrontlineUnitPromotesReserveWithoutHealingAndSessionEndFreezesCombat() {
        val original=stocks(31,30);val o=original.outfits.getValue(1)
        val wounded=original.copy(outfits=original.outfits+(1 to o.copy(activeGround=ActiveGroundUnits(o.activeGround!!.values.map {
            if(it.id==1)it.copy(strength=1) else it
        }))))
        val started=advance(wounded,0);val fought=advance(started,5)
        assertEquals((2..31).toList(),GroundBattleOperations.participants(fought,base,2).map {it.id})
        assertEquals(1000,fought.outfits.getValue(1).activeGround!!.values.single {it.id==31}.strength)
        assertTrue(fought.outfits.getValue(1).activeGround!!.values.filter {it.id!=31}.all {it.strength==800})
        val ended=session.copy(gameMillis=(now+500)*1000,ended=SessionEnd(EndReason.TIME_LIMIT,Power.entries.toSet(),now))
        assertSame(fought,GroundBattleOperations.advance(ended,galaxy,emptyMap(),fought,1,mutableListOf()))
        assertFails {ActiveShips.reorganize(fought,1,UnitInventory(troops=mapOf(UnitStack(0) to 1)))}
    }

}
