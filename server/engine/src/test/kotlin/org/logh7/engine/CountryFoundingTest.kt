package org.logh7.engine

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryFoundingTest {
    private val rules=SessionRules.load()
    private val style=ShipStyleId("alliance")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,10,4,"Empire",2),GalaxySystem(4,20,4,"Alliance",3),
        GalaxySystem(5,30,4,"Free",1)),listOf(GalaxyBase(2000,30,80,0,"Port",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 10,Power.ALLIANCE to 20),unaffiliatedStart=UnaffiliatedStart(30,style))

    private class Definitions:CountryDefinitionStore {
        val defaults=CountryCatalogLoader.load()
        private var state=CountryDefinitionSnapshot((CountryCatalogLoader.snapshot()?.revision ?: 0)+1,
            defaults,mapOf("assets" to listOf("empire","alliance")),emptyMap())
        override fun load()=state
        override fun save(expectedRevision:Long,edit:CountryDefinitionEdit):CountryDefinitionOutcome {
            assertEquals(state.revision,expectedRevision)
            val row=(edit as CountryDefinitionEdit.Country).value
            val c=state.catalog
            state=state.copy(revision=state.revision+1,catalog=CountryCatalog(c.calendars.values.toList(),
                c.families.values.toList(),c.countries.values.toList()+row))
            return CountryDefinitionOutcome.CHANGED
        }
    }

    private class Checkpoint(val rules:SessionRules,val style:ShipStyleId):WorldStateCommitter,SessionDurability {
        var saved=PlanetStocks(1,0,mapOf(2000 to PlanetStock(2000,Allegiance.Independent(10000),0,100,100,100)),
            deploymentInitialized=true)
        var pilots=mapOf("founder" to Navigator(1,10000,null,30,1000,1000,1000,baseId=2000,mode=4,shipStyle=style))
        var state=DurableWorldState(SessionSimulation(rules).also {it.join("founder",null,false);it.disconnect("founder")}.exportState())
        var failWorld=false
        var failJournal=false
        val stock=object:PlanetStockStore {
            override fun load()=saved
            override fun save(stocks:PlanetStocks) {saved=stocks}
        }
        val navigation=object:NavigationStore {
            override fun load()=pilots
            override fun save(players:Map<String,Navigator>) {pilots=players}
        }
        override fun load()=state
        override fun commit(action:SessionAction,transition:SessionTransition) {
            if(failJournal && action is SessionAction.Defect)throw IOException("journal unavailable")
            state=transition.state
        }
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?) {
            if(failWorld)throw IOException("world unavailable")
            pilots=navigation;saved=checkNotNull(inventory)
        }
        override fun close() {}
    }

    @Test fun failureBeforeWorldCommitRetriesTheSameCountry()=exercise(false)
    @Test fun failureAfterWorldCommitRepairsMembershipOnRestart()=exercise(true)

    @Test fun anExistingCapitalCannotBeUsedAgain() {
        val store=Checkpoint(rules,style)
        val pilot=store.pilots.getValue("founder")
        val capital=store.saved.copy(strategic=store.saved.strategic.copy(capitals=mapOf(CountryId(100) to pilot.baseId)))
        assertNotNull(CountryFounding.candidate(galaxy,pilot,store.saved))
        assertNull(CountryFounding.candidate(galaxy,pilot,capital))
    }

    private fun exercise(journal:Boolean)=runBlocking {
        val store=Checkpoint(rules,style)
        val definitions=Definitions()
        val registry=CountryFoundingRegistry(UUID.randomUUID(),definitions)
        fun engine()=WorldEngine(this,rules,ticking=false,durability=store,galaxy=galaxy,
            navigationStore=store.navigation,stockStore=store.stock,worldStateCommitter=store,founding=registry)
        suspend fun connect(engine:WorldEngine,country:CountryId?) {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("founder",country,false,joined,"socket",1))
            assertEquals(Admission.ACCEPTED,withTimeout(5000){joined.await()})
        }
        suspend fun found(engine:WorldEngine,country:CountryId?=null,name:String="자유 항성국"):StrategyActionResult {
            val result=CompletableDeferred<StrategyActionResult>()
            engine.submit(WorldCommand.StrategyAction(StrategyActionRequest("founder","socket",10000,country,1,
                CountryFounding.COMMAND,0,13,countryName=name),result))
            return withTimeout(5000){result.await()}
        }
        try {
            val first=engine()
            try {
                connect(first,null)
                store.failWorld=!journal;store.failJournal=journal
                assertEquals(StrategyActionRejection.STORAGE,found(first).rejection)
                assertNull(store.state.session.participants.getValue("founder").country)
                assertEquals(journal,store.saved.strategic.capitals.isNotEmpty())
                assertEquals(Allegiance.Independent(10000),first.snapshot().planetStocks?.bases?.get(2000)?.owner)
            } finally {withTimeout(5000){first.drainAndClose()}}

            val row=definitions.load().catalog.countries.values.single(CountryFounding::isFounded)
            val country=CountryId(row.id)
            store.failWorld=false;store.failJournal=false
            val second=engine()
            try {
                connect(second,if(journal)country else null)
                if(!journal)assertTrue(found(second,name="재시도").changed)
                assertEquals(country,store.state.session.participants.getValue("founder").country)
                assertEquals(country,store.pilots.getValue("founder").country)
                assertEquals(mapOf(country to 2000),store.saved.strategic.capitals)
                assertEquals(OfficeHolding(132,2000,10000,country),store.saved.strategic.offices.single())
                assertEquals(1000,store.pilots.getValue("founder").mcp)
                assertEquals(StrategyActionRejection.AUTHORITY,found(second,country).rejection)
                assertEquals(row,definitions.load().catalog.countries.getValue(country.value))
                assertEquals(1,definitions.load().catalog.countries.values.count(CountryFounding::isFounded))
                val joined=CompletableDeferred<Admission>()
                second.submit(WorldCommand.Join("settler",country,false,joined,"incoming",1))
                assertEquals(Admission.ACCEPTED,withTimeout(5000){joined.await()})
                val position=CompletableDeferred<NavigationResult>()
                second.submit(WorldCommand.Navigate(NavigationRequest("settler",10001,country,"incoming",1,shipStyle=style),position))
                val newcomer=assertNotNull(withTimeout(5000){position.await()}.player)
                assertEquals(country,newcomer.country)
                assertEquals(30,newcomer.grid)
            } finally {withTimeout(5000){second.drainAndClose()}}
        } finally {
            val revision=(CountryCatalogLoader.snapshot()?.revision ?: 0)+1
            CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision,definitions.defaults,emptyMap(),emptyMap()))
        }
    }
}
