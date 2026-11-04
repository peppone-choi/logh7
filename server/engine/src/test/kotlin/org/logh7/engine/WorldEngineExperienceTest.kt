package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class WorldEngineExperienceTest {
    private class SessionStore(rules:SessionRules):SessionDurability {
        private var state=DurableWorldState(SessionSimulation(rules).exportState())
        override fun load()=state
        override fun commit(action:SessionAction,transition:SessionTransition) {state=transition.state}
        override fun close() {}
    }
    private suspend fun checkNewcomer(scope:CoroutineScope,durable:Boolean,rejectedJoins:Boolean) {
        val rules=SessionRules.load()
        val profile=StrategicPerson(rank=20,merit=123,money=45,abilities=List(8){50})
        var players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,985,960,900,pcpUsed=15,mcpUsed=40))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),deploymentInitialized=true)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val engine=WorldEngine(scope,rules,ticking=false,durability=if(durable)SessionStore(rules)else null,
            galaxy=galaxy,navigationStore=object:NavigationStore {
                override fun load()=players
                override fun save(next:Map<String,Navigator>) {players=next}
            },stockStore=object:PlanetStockStore {
                override fun load()=stocks
                override fun save(next:PlanetStocks) {stocks=next}
            },worldStateCommitter=WorldStateCommitter {next,inventory->players=next;stocks=checkNotNull(inventory)},
            npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            suspend fun join(connection:String,generation:Long,value:StrategicPerson):Admission {
                val result=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,result,connection,generation,value))
                return result.await()
            }
            assertEquals(Admission.ACCEPTED,join("live",1,profile))
            if(rejectedJoins) {
                val other=profile.copy(rank=0,money=999,abilities=List(8){100})
                assertEquals(Admission.ALREADY_ONLINE,join("duplicate",1,other))
                assertEquals(Admission.STALE_SESSION,join("old",2,other))
            }
            assertTrue(stocks.strategic.people.isEmpty())
            engine.submit(WorldCommand.AdvanceTo(1000))
            engine.synchronizeTime()
            assertEquals(profile,stocks.strategic.people[10000],"join profile, durable=$durable")
            assertEquals(listOf(1,0,0,0,1,1,1,1),stocks.social.academy.experience[10000])
            assertEquals(5,players.getValue("pilot").pcpUsed)
            assertEquals(0,players.getValue("pilot").mcpUsed)
            val settled=stocks.social.academy.experience
            engine.submit(WorldCommand.AdvanceTo(2000))
            engine.synchronizeTime()
            assertEquals(settled,stocks.social.academy.experience)
        } finally {engine.drainAndClose()}
    }
    @Test fun admittedNewcomerEarnsSpentCpExperienceWithoutAnExistingStrategicRecord()=runBlocking {
        for(durable in listOf(false,true))checkNewcomer(this,durable,false)
    }
    @Test fun duplicateOrStaleJoinCannotReplaceTheAcceptedCharactersExperienceProfile()=runBlocking {
        for(durable in listOf(false,true))checkNewcomer(this,durable,true)
    }
}
