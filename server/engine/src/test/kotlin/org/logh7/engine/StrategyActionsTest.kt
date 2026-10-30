package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 명령별 실제 상태 변경·권한·비용·지연 효과. 작성: 최병호. 실행은 리드 담당. */
class StrategyActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private fun session()=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private fun player()=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4)
    private val stack=UnitStack(UnitSpecifications.crews.first {it.power==2 && GroundUnits.isGround(it.kind)}.kind)
    private fun stocks():PlanetStocks {
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(1,stack,base=1049)))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,
            economy=PlanetEconomy(1000,600,70,80))),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.GROUND,1049,300,10000,
                UnitInventory(troops=troops.quantities()),activeGround=troops)),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.PATROL,1049)),nextShipId=2)
    }
    private fun request(command:Int)=StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,command,
        if(command==84)0 else 59,10,if(command in setOf(84,85))0 else 200)
    private fun act(command:Int,s:PlanetStocks=stocks(),p:Navigator=player())=
        StrategyActions.evaluate(galaxy,request(command),session(),p,s)

    @Test fun groundTrainingRetainsIdentitiesAndPromotesTheActualRosterAndInventoryTogether() {
        val s=stocks();val result=act(48,s)
        assertNull(result.rejection);assertTrue(result.changed);assertEquals(920,result.player?.mcp)
        val trained=assertNotNull(result.stocks).outfits.getValue(200)
        assertEquals(1,trained.activeGround!!.values.single().id)
        assertEquals(1,trained.activeGround.values.single().stack.level)
        assertEquals(trained.activeGround.quantities(),GroundUnits.quantities(trained.units))
        assertEquals(s.warehouses,result.stocks.warehouses)
        var current=s
        repeat(3){current=assertNotNull(act(48,current).stocks)}
        val maximum=act(48,current)
        assertEquals(StrategyActionRejection.MAXIMUM,maximum.rejection);assertEquals(player(),maximum.player)
    }
    @Test fun learnedTacticsAffectAutomaticGroundCombatAndSurviveAnOfflineCommander() {
        val result=act(50);val stored=assertNotNull(result.stocks)
        assertNull(result.rejection);assertEquals(1,stored.strategic.people.getValue(10000).groundTactics)
        assertEquals(110,StrategicGroundTactics.factor(stored,1))
        assertEquals(100,StrategicGroundTactics.factor(stored,999))
    }
    @Test fun suppressionPaysOnceAndSettlesOnlyAfterTwentyFourGameHours() {
        val s=stocks();val result=act(53,s);val stored=assertNotNull(result.stocks)
        val ready=session().gameMillis/1000+86400
        assertEquals(ready,result.readyAt);assertEquals(840,result.player?.mcp)
        assertEquals(s.bases,stored.bases)
        assertEquals(stored,StrategyActions.settle(stored,ready-1))
        val complete=StrategyActions.settle(stored,ready)
        assertEquals(85,complete.bases.getValue(1049).economy?.peace)
        assertEquals(68,complete.bases.getValue(1049).economy?.approval)
        assertTrue(complete.strategic.suppressions.isEmpty())
        assertEquals(complete,StrategyActions.settle(complete,ready+86400))
        assertEquals(StrategyActionRejection.BUSY,act(53,stored).rejection)
        val lost=stored.copy(outfits=stored.outfits.mapValues {(_,o)->o.copy(commanderId=0)})
        assertEquals(s.bases,StrategyActions.settle(lost,ready).bases)
    }
    @Test fun retirementClearsOnlyOwnOfficesAndPreventsEarlyVolunteering() {
        val s=stocks().copy(nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to 10000,
            NationalOffice.IMPERIAL_SUPREME_COMMANDER to 10001))
        val retired=act(84,s);val stored=assertNotNull(retired.stocks)
        assertNull(retired.rejection);assertEquals(1,stored.strategic.people.getValue(10000).rank)
        assertEquals(0,stored.outfits.getValue(200).commanderId)
        assertEquals(mapOf(NationalOffice.IMPERIAL_SUPREME_COMMANDER to 10001),stored.nationalOffices)
        assertEquals(StrategyActionRejection.RANK,act(85,stored).rejection)
        val ready=session().copy(gameMillis=stored.strategic.people.getValue(10000).retiredUntil*1000)
        val joined=StrategyActions.evaluate(galaxy,request(85),ready,player(),stored)
        assertNull(joined.rejection);assertEquals(10,joined.stocks?.strategic?.people?.get(10000)?.rank)
        assertEquals(Flagships.forType(2,0).kind,joined.player?.hull?.kind)
    }
    @Test fun wrongConnectionCardFactionRankAuthorityAndCpCannotChangeTheWorld() {
        val s=stocks();val p=player();val q=request(48)
        for(r in listOf(q.copy(connection="old"),q.copy(card=195),q.copy(power=Power.ALLIANCE),
            q.copy(rank=1),q.copy(outfit=201))) {
            val result=StrategyActions.evaluate(galaxy,r,session(),p,s)
            assertNotNull(result.rejection);assertFalse(result.changed);assertEquals(s,result.stocks);assertEquals(p,result.player)
        }
        val wrongOwner=s.copy(outfits=s.outfits.mapValues {(_,o)->o.copy(commanderId=10001)})
        assertEquals(StrategyActionRejection.AUTHORITY,act(48,wrongOwner).rejection)
        assertEquals(StrategyActionRejection.CP,act(48,s,p.copy(pcp=159,mcp=79)).rejection)
        assertEquals(0,act(48,s,p.copy(pcp=160,mcp=79)).player?.pcp)
        assertEquals(79,act(48,s,p.copy(pcp=160,mcp=79)).player?.mcp)
    }
}
