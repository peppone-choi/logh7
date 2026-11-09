package org.logh7.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Country-scoped repair authority and durable policing reservations. Author: Peppone Choi. */
class CountryArmyActionsTest {
    private val a=CountryId(42);private val b=CountryId(43)
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),old.families.values.toList(),
        old.countries.values.toList()+listOf(old.countries.getValue(3).copy(id=42,key="a",name="A",nativePower=2),
            old.countries.getValue(2).copy(id=43,key="b",name="B")))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {it.join("actor",a,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val player=Navigator(1,10000,a,2487,1000,1000,1000,baseId=1000,mode=4,
        hull=FlagshipHull(89,now,hp=50),shipStyle=ShipStyleId("alliance"),supplies=100)
    private val offline=player.copy(characterId=10001,hull=FlagshipHull(90,now,hp=60),supplies=77)
    private val foreign=player.copy(characterId=10002,country=b,hull=FlagshipHull(89,now,hp=40))
    private val players=mapOf("actor" to player,"offline" to offline,"foreign" to foreign)
    private fun stocks():PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(119),boats=293,damaged=5,supplies=99)))
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(4),base=1000)))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,Allegiance.Country(a),0,100,200,300,PlanetEconomy(1000,600,70,80))),
            outfits=mapOf(200 to OutfitStock(200,a,OutfitKind.FLEET,1000,300,10000,UnitInventory(ships=ships.quantities()),
                supplies=99,activeShips=ships,staff=FleetStaff(officers=listOf(10001,10002)),shipStyle=ShipStyleId("alliance")),
                201 to OutfitStock(201,a,OutfitKind.GROUND,1000,301,10000,UnitInventory(troops=troops.quantities()),
                    activeGround=troops,shipStyle=ShipStyleId("alliance"))),
            warehouses=mapOf(300 to OutfitWarehouse(300,a,OutfitKind.FLEET,1000,200,shipStyle=ShipStyleId("alliance")),
                301 to OutfitWarehouse(301,a,OutfitKind.PATROL,1000,shipStyle=ShipStyleId("alliance"))),nextShipId=3)
    }
    private fun request(command:Int=61,card:Int=175,outfit:Int=200)=StrategyActionRequest("actor","c",10000,a,1,command,card,7,
        outfit=outfit,navigators=players)
    private fun act(r:StrategyActionRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player,state:SessionState=session,
        countries:CountryCatalog=catalog)=StrategyActions.evaluate(galaxy,r,state,p,s,countries)
    private fun suppress(s:PlanetStocks=stocks())=act(request(53,59,201),s)
    private fun settle(s:PlanetStocks,at:Long,nav:Collection<Navigator> = players.values,eliminated:Set<CountryId> = emptySet())=
        StrategyActions.settle(s,at,galaxy,nav,catalog,eliminated)

    @Test fun repairUsesTheActualCountryOfficeSystemAndPreservesForeignHullsCargoAndIdentity() {
        val before=stocks();val result=act();assertNull(result.rejection)
        assertEquals(840,result.player?.mcp);assertEquals(1000,result.player?.pcp);assertEquals(100,result.player?.hull?.hp)
        assertEquals(100,result.player?.supplies);assertEquals(a,result.player?.country)
        assertEquals(setOf("offline"),result.navigators.keys);assertEquals(100,result.navigators.getValue("offline").hull?.hp)
        assertEquals(77,result.navigators.getValue("offline").supplies)
        val repaired=result.stocks!!.outfits.getValue(200);val unit=repaired.activeShips!!.values.single()
        assertEquals(293,unit.boats);assertEquals(0,unit.damaged);assertEquals(0,unit.supplies);assertEquals(0,repaired.supplies)
        assertEquals(ShipStyleId("alliance"),repaired.shipStyle);assertEquals(before.warehouses,result.stocks!!.warehouses)
        assertEquals(StrategyActionRejection.CARD,act(request(card=39)).rejection,"same display does not select the office system")
        val poor=player.copy(pcp=319,mcp=159);val failed=act(p=poor)
        assertEquals(StrategyActionRejection.CP,failed.rejection);assertEquals(poor,failed.player);assertEquals(before,failed.stocks)
    }

    @Test fun repairRejectsBorrowedCountryOwnershipUnregisteredMembershipAndElimination() {
        val s=stocks();val borrowed=s.copy(outfits=s.outfits+(200 to s.outfits.getValue(200).copy(country=b)),
            warehouses=s.warehouses+(300 to s.warehouses.getValue(300).copy(country=b)))
        assertEquals(StrategyActionRejection.AUTHORITY,act(s=borrowed).rejection)
        val lost=s.copy(bases=s.bases+(1000 to s.bases.getValue(1000).copy(owner=Allegiance.Country(b))))
        assertEquals(StrategyActionRejection.LOCATION,act(s=lost).rejection)
        assertEquals(StrategyActionRejection.IDENTITY,act(countries=CountryCatalogLoader.load()).rejection)
        assertEquals(StrategyActionRejection.SESSION,act(state=session.copy(eliminated=mapOf(a to CountryDefeat(EndReason.CAPITAL_CAPTURED,now,b)))).rejection)
    }

    @Test fun suppressionStoresItsActualCountryPaysOnceAndSettlesExactlyOnce() {
        val before=stocks();val result=suppress();assertNull(result.rejection)
        val pending=result.stocks!!;val due=now+86400
        assertEquals(a,pending.strategic.suppressions.single().country);assertEquals(due,result.readyAt)
        assertEquals(840,result.player?.mcp);assertEquals(before.bases,pending.bases)
        assertEquals(pending,settle(pending,due-1))
        val done=settle(pending,due);assertEquals(85,done.bases.getValue(1000).economy?.peace)
        assertEquals(68,done.bases.getValue(1000).economy?.approval);assertTrue(done.strategic.suppressions.isEmpty())
        assertEquals(done,settle(done,due+86400));assertEquals(StrategyActionRejection.BUSY,suppress(pending).rejection)
        val failed=act(request(53,59,201),p=player.copy(pcp=319,mcp=159))
        assertEquals(StrategyActionRejection.CP,failed.rejection);assertEquals(before,failed.stocks)
    }

    @Test fun suppressionCannotFollowARenamedCountryArmyBaseOrDefectedCommander() {
        val pending=suppress().stocks!!;val due=now+86400;val base=pending.bases.getValue(1000)
        val changed=listOf(pending.copy(bases=pending.bases+(1000 to base.copy(owner=Allegiance.Country(b)))),
            pending.copy(bases=pending.bases+(1000 to base.copy(camp=1))),
            pending.copy(outfits=pending.outfits+(201 to pending.outfits.getValue(201).copy(commanderId=0))),
            pending.copy(outfits=pending.outfits+(201 to pending.outfits.getValue(201).copy(country=b)),
                warehouses=pending.warehouses+(301 to pending.warehouses.getValue(301).copy(country=b))))
        for(s in changed) {val done=settle(s,due);assertEquals(s.bases,done.bases);assertTrue(done.strategic.suppressions.isEmpty())}
        val defected=players.values.map {if(it.characterId==10000)it.copy(country=b) else it}
        assertEquals(pending.bases,settle(pending,due,defected).bases)
        assertEquals(pending.bases,settle(pending,due,eliminated=setOf(a)).bases)
        assertEquals(pending.bases,StrategyActions.settle(pending,due,galaxy,players.values).bases,"removed definitions cannot finish the job")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun restoredSuppressionSettlesAtItsDeadlineBetweenPeriodicTicks() = runTest {
        val legacy=CountryId(2)
        val source=stocks()
        var saved=source.copy(
            bases=source.bases.mapValues {(_,base)->base.copy(owner=Allegiance.Country(legacy))},
            outfits=source.outfits.mapValues {(_,outfit)->outfit.copy(country=legacy)},
            warehouses=source.warehouses.mapValues {(_,warehouse)->warehouse.copy(country=legacy)},
            strategic=StrategicState(suppressions=listOf(SuppressionOrder(10000,201,1000,legacy,15))))
        val engine=WorldEngine(this,SessionRules.load(),galaxy=galaxy,monotonicMillis={testScheduler.currentTime},
            navigationStore=object:NavigationStore {
                override fun load()=mapOf("actor" to player.copy(country=legacy))
                override fun save(players:Map<String,Navigator>) {}
            },
            stockStore=object:PlanetStockStore {
                override fun load()=saved
                override fun save(stocks:PlanetStocks){saved=stocks}
            })
        try {
            engine.submit(WorldCommand.Probe);runCurrent()
            advanceTimeBy(624);runCurrent()
            assertEquals(80,saved.bases.getValue(1000).economy?.peace)
            assertEquals(1,saved.strategic.suppressions.size)
            advanceTimeBy(1);runCurrent()
            assertEquals(15L,engine.snapshot().session!!.gameSeconds)
            assertEquals(85,saved.bases.getValue(1000).economy?.peace)
            assertEquals(68,saved.bases.getValue(1000).economy?.approval)
            assertTrue(saved.strategic.suppressions.isEmpty())
            advanceTimeBy(375);runCurrent()
            assertEquals(85,saved.bases.getValue(1000).economy?.peace)
        } finally {engine.drainAndClose()}
    }

    @Test fun productionSettlementReceivesTheSessionDefeatEvenWhenTheArmyStillOwnsTheBase() {
        val legacy=CountryId(2);val ready=now+1
        val source=stocks().let {s->s.copy(bases=s.bases.mapValues {(_,v)->v.copy(owner=Allegiance.Country(legacy))},
            outfits=s.outfits.mapValues {(_,v)->v.copy(country=legacy)},
            warehouses=s.warehouses.mapValues {(_,v)->v.copy(country=legacy)},
            strategic=StrategicState(suppressions=listOf(SuppressionOrder(10000,201,1000,legacy,ready))))}
        val state=SessionSimulation(SessionRules.load()).also {it.join("actor",legacy,false,"c")}.exportState()
            .copy(gameMillis=ready*1000)
        fun settle(state:SessionState)=PlanetProduction(galaxy,null,now,PlanetProductionPolicy(),restored=source,
            civicPlayers={listOf(player.copy(country=legacy))}).settle(state)
        assertEquals(85,settle(state).bases.getValue(1000).economy?.peace)
        val defeated=settle(state.copy(eliminated=mapOf(legacy to CountryDefeat(EndReason.CAPITAL_CAPTURED,now,CountryId(3)))))
        assertEquals(80,defeated.bases.getValue(1000).economy?.peace)
        assertTrue(defeated.strategic.suppressions.isEmpty())
    }
}
