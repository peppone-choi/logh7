package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.*

/** A third actual country (42) boots, acts, loses its capital and restarts without the legacy 2/3 fence.
 * Author: Peppone Choi. */
class WorldCountryRuntimeTest {
    private val rules=SessionRules.load()
    private val empire=ShipStyleId("empire")
    private val third=CountryId(42)
    private val normal=CountryCatalogLoader.load()
    // 42 and 43 both display as 3 (native alliance code) yet fly empire / alliance styles respectively.
    private val catalog=CountryCatalog(normal.calendars.values.toList(),normal.families.values.toList(),normal.countries.values.toList()+listOf(
        normal.countries.getValue(2).copy(id=42,key="new_empire",name="New Empire",nativePower=3,shipStyleOverride="empire"),
        normal.countries.getValue(3).copy(id=43,key="new_alliance",name="New Alliance",nativePower=3,shipStyleOverride="alliance")))
    private val galaxy=Galaxy(listOf(GalaxySystem(3,10,4,"Empire",2),GalaxySystem(4,20,4,"Alliance",3),GalaxySystem(5,30,4,"Third",0)),
        listOf(GalaxyBase(1049,10,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1177,20,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(2000,30,80,0,"Founding",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 10,Power.ALLIANCE to 20),countryStarts=mapOf(third to 30),
        initialOwners=mapOf(2000 to Allegiance.Country(third)))
    private val pilot=Navigator(1,10000,third,30,800,700,1000,baseId=2000,mode=4,shipStyle=empire)
    private fun stocks(capitalOwner:Allegiance=Allegiance.Country(third))=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,100,200,300),1177 to PlanetStock(1177,3,0,100,200,300),
        2000 to PlanetStock(2000,capitalOwner,0,100,200,300)),
        outfits=mapOf(1 to OutfitStock(1,third,OutfitKind.FLEET,2000,101,10000,shipStyle=empire)),
        warehouses=mapOf(101 to OutfitWarehouse(101,third,OutfitKind.FLEET,2000,1,shipStyle=empire)),
        strategic=StrategicState(capitals=mapOf(third to 2000)),deploymentInitialized=true)

    private class Durable(var state:DurableWorldState):SessionDurability {
        var loads=0;var commits=0;var closed=0;val actions=mutableListOf<SessionAction>()
        override fun load():DurableWorldState {loads++;return state}
        override fun commit(action:SessionAction,transition:SessionTransition) {commits++;actions+=action;state=transition.state}
        override fun close() {closed++}
    }
    private class World(var stocks:PlanetStocks,var players:Map<String,Navigator>) {
        var closed=0
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks) {stocks=value}
            override fun close() {closed++}}
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>) {players=value}
            override fun close() {closed++}}
        val committer=WorldStateCommitter {p,s->players=p;stocks=checkNotNull(s)}
    }
    /** Durable roster with an offline 42 participant. */
    private fun durable()=Durable(DurableWorldState(SessionSimulation(rules).also {it.join("pilot",third,false)}.exportState()))
    private fun <T> published(block:()->T):T {
        val revision=(CountryCatalogLoader.snapshot()?.revision ?: 0)+1
        try {
            CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision,catalog,emptyMap(),emptyMap()))
            return block()
        } finally {CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision+1,normal,emptyMap(),emptyMap()))}
    }
    private fun engine(scope:kotlinx.coroutines.CoroutineScope,world:World,session:Durable)=WorldEngine(scope,rules,ticking=false,
        durability=session,galaxy=galaxy,navigationStore=world.navigation,stockStore=world.inventory,
        worldStateCommitter=world.committer,npcPolicy=NpcTacticalPolicy(enabled=false))

    @Test fun worldTickInvalidatesSpecialGuardForAnEliminatedCountry()=published {runBlocking {
        val empireCountry=Power.EMPIRE.country
        val spot=galaxy.facilities.getValue(1049).flatMap {it.spots}.first {it.kind==0 && it.access==SpotAccess.GUARDED}.id
        val guard=Navigator(1,10000,Power.EMPIRE,10,800,700,1000,baseId=1049,mode=4,spotId=spot)
        val order=CivicOrder(guard.characterId,55,59,empireCountry,5,1049,0,spot,rules.startSeconds,
            rules.startSeconds+rules.speed,CivicPhase.GUARDING)
        val world=World(stocks().copy(civic=CivicState(mapOf(guard.characterId to order))),mapOf("guard" to guard))
        val eligible=CivicActions.settle(galaxy,world.stocks,order.readyAt,listOf(guard),catalog)
        assertEquals(CivicPhase.COMPLETED,eligible.civic.orders.getValue(guard.characterId).phase)
        val stored=durable().state
        val session=Durable(stored.copy(session=stored.session.copy(
            countries=setOf(empireCountry,Power.ALLIANCE.country,third),
            eliminated=mapOf(empireCountry to CountryDefeat(EndReason.SYSTEMS_REDUCED,rules.startSeconds)))))
        val engine=engine(this,world,session)
        try {
            engine.submit(WorldCommand.AdvanceTo(1000))
            val result=CompletableDeferred<CivicOrder>()
            engine.submit(WorldCommand.Quiesce({world.stocks.civic.orders.getValue(guard.characterId)},result))
            assertEquals(CivicPhase.INVALIDATED,withTimeout(5000) {result.await()}.phase)
        } finally {engine.drainAndClose()}
    }}

    @Test fun anActualThirdCountryBootsActsAndRestarts()=published {runBlocking {
        val world=World(stocks(),mapOf("pilot" to pilot));val session=durable()
        val first=engine(this,world,session)
        try {
            first.synchronizeTime()
            val snapshot=first.snapshot()
            assertEquals(third,snapshot.navigators.getValue("pilot").country)
            assertEquals(mapOf(third to 2000),snapshot.planetStocks?.strategic?.capitals)
            assertTrue(third in assertNotNull(snapshot.session).countries)
            val join=CompletableDeferred<Admission>()
            first.submit(WorldCommand.Join("pilot",third,false,join,"c",1))
            assertEquals(Admission.ACCEPTED,withTimeout(5000) {join.await()})
            fun request(country:CountryId)=StrategyActionRequest("pilot","c",10000,country,1,84,0,13)
            // Same display code 3, other actual country: refused without spending CP.
            val forged=CompletableDeferred<StrategyActionResult>()
            first.submit(WorldCommand.StrategyAction(request(CountryId(43)),forged))
            assertEquals(StrategyActionRejection.IDENTITY,withTimeout(5000) {forged.await()}.rejection)
            assertEquals(700,first.snapshot().navigators.getValue("pilot").mcp)
            val done=CompletableDeferred<StrategyActionResult>()
            first.submit(WorldCommand.StrategyAction(request(third),done))
            assertNull(withTimeout(5000) {done.await()}.rejection)
            assertEquals(540,first.snapshot().navigators.getValue("pilot").mcp,"160 MCP for the politician transfer")
            assertEquals(540,world.players.getValue("pilot").mcp,"committed")
        } finally {withTimeout(5000) {first.drainAndClose()}}
        // Restart from the committed stores keeps the 42 membership, capital and the spent CP.
        val second=engine(this,world,session)
        try {
            second.synchronizeTime()
            val restored=second.snapshot()
            assertEquals(third,restored.navigators.getValue("pilot").country)
            assertEquals(540,restored.navigators.getValue("pilot").mcp)
            assertEquals(mapOf(third to 2000),restored.planetStocks?.strategic?.capitals)
            assertTrue(third in assertNotNull(restored.session).countries)
        } finally {withTimeout(5000) {second.drainAndClose()}}
    }}

    @Test fun losingOnlyTheThirdCapitalEliminatesItWithoutEndingTheSessionAndSurvivesRestart()=published {runBlocking {
        val world=World(stocks(capitalOwner=Allegiance.Country(CountryId(3))),mapOf("pilot" to pilot));val session=durable()
        val first=engine(this,world,session)
        try {
            first.synchronizeTime()
            val s=assertNotNull(first.snapshot().session)
            assertEquals(setOf(third),s.eliminated.keys)
            assertEquals(CountryDefeat(EndReason.CAPITAL_CAPTURED,s.eliminated.getValue(third).gameSeconds,CountryId(3)),s.eliminated.getValue(third))
            assertNull(s.ended,"2 and 3 continue")
            assertTrue(setOf(CountryId(2),CountryId(3),third).all {it in s.countries})
            val join=CompletableDeferred<Admission>()
            first.submit(WorldCommand.Join("pilot",third,false,join,"c",1))
            assertEquals(Admission.COUNTRY_ELIMINATED,withTimeout(5000) {join.await()})
        } finally {withTimeout(5000) {first.drainAndClose()}}
        val defeat=session.state.session.eliminated.getValue(third)
        val territory=session.actions.count {it is SessionAction.CountryTerritory}
        val second=engine(this,world,session)
        try {
            second.synchronizeTime()
            val s=assertNotNull(second.snapshot().session)
            assertEquals(mapOf(third to defeat),s.eliminated,"the durable defeat is reopened unchanged")
            assertNull(s.ended)
            assertEquals(territory,session.actions.count {it is SessionAction.CountryTerritory},"no second elimination is derived")
        } finally {withTimeout(5000) {second.drainAndClose()}}
    }}

    @Test fun invalidDefinitionsStopBeforeAnyRecoveryWriteAndCloseEveryStore()=published {runBlocking {
        val cases=listOf(
            "unknown country" to World(stocks(),mapOf("pilot" to pilot.copy(country=CountryId(44)))),
            "capital of an unregistered country" to World(stocks().let {it.copy(strategic=it.strategic.copy(capitals=mapOf(third to 2000,CountryId(44) to 1049)))},mapOf("pilot" to pilot)),
            "unsupported style" to World(stocks(),mapOf("pilot" to pilot.copy(shipStyle=ShipStyleId("custom_style")))))
        for((name,world) in cases) {
            val session=durable()
            assertFailsWith<IllegalStateException>(name) {engine(this,world,session)}
            assertEquals(0,session.loads,name);assertEquals(0,session.commits,name)
            assertEquals(1,session.closed,name);assertEquals(2,world.closed,name)
        }
        // A restored roster naming an unregistered country is refused before connection recovery.
        val world=World(stocks(),mapOf("pilot" to pilot))
        val session=Durable(DurableWorldState(SessionSimulation(rules).also {it.join("ghost",CountryId(44),false,"c")}.exportState()))
        assertFailsWith<IllegalStateException> {engine(this,world,session)}
        assertEquals(0,session.commits);assertEquals(1,session.closed);assertEquals(2,world.closed)
    }}
}
