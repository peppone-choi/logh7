package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalOfflineSettingsTest {
    private val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"one")}.exportState()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Home",2)),
        listOf(GalaxyBase(1000,2488,80,1,"Home",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2488})
    @Test fun settingsAreOwnerOnlyDurableAndNeverPublishedAfterStorageFailure() {
        var players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,baseId=1000,mode=4))
        var stocks=PlanetStocks(1,session.gameMillis/1000,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)))
        val navStore=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val stockStore=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        val n=Navigation(galaxy,navStore,NavigationPolicy())
        val production=PlanetProduction(galaxy,stockStore,SessionRules.load().startSeconds,PlanetProductionPolicy())
        production.settle(1,session.gameMillis/1000)
        val request=NavigationRequest("pilot",10000,Power.EMPIRE,"one",1,offlineDirection=21)
        var fail=false
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");players=p;stocks=checkNotNull(s)}
        fun accept(r:NavigationRequest=request)=TacticalOfflineSettings.accept(r,session,n,production,production.snapshot(1)!!,commit)
        assertEquals(NavigationRejection.IDENTITY,accept(request.copy(characterId=10001)).rejection)
        assertTrue(stocks.tacticalCommands.aiSwitches.isEmpty())
        val result=accept();assertNotNull(result.player)
        assertEquals(listOf(1,0,1,0,1),stocks.tacticalCommands.ai(10000))
        assertEquals(100,result.player!!.pcp);assertEquals(100,result.player!!.mcp)
        val saved=stocks;val savedPlayers=n.snapshot(1)
        fail=true
        assertFailsWith<java.io.IOException>{accept(request.copy(offlineDirection=0))}
        assertEquals(saved,production.snapshot(1));assertEquals(savedPlayers,n.snapshot(1))
        assertEquals(saved,stocks)
        assertFails {request.copy(tacticalAttack=TacticalAttack(listOf(1010000),2))}
    }
}
