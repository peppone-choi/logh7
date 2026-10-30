package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class WorldCharactersTest {
    private val character=CharacterMessages.Generate(0,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,10,0,0,"Ship",0)
    @Test fun observerAndReconnectViewsUseStrategicRankAndTotalMeritAlongsideSavedAi() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Home",2)),
            listOf(GalaxyBase(1000,2488,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2488})
        val first=Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,cpPaused=true)
        val second=first.copy(characterId=10001)
        val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)),
            strategic=StrategicState(people=mapOf(10001 to StrategicPerson(rank=5,merit=120,money=900))),
            tacticalCommands=TacticalCommandState(aiSwitches=mapOf(10001 to 21),ranks=mapOf(10001 to 20),
                merits=mapOf(10001 to 30L),creditedMerits=mapOf(10001 to 20L)))
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,100,0,0,emptyMap(),2,1,null),
            navigators=mapOf("first" to first,"second" to second),planetStocks=stocks)
        fun person(id:Int)=if(id==10000)character else character.copy(id=10001,rank=20)
        val projected=WorldCharacters.view(person(10001),snapshot)
        assertEquals(5,projected.rank);assertEquals(130L,projected.merit);assertEquals(900L,projected.money)
        assertEquals(listOf(0,1,0,1,0,1),projected.ai)
        val tactical=TacticalBootstrap(snapshot,galaxy,first,::person)
        assertFalse(tactical.corps.single {it.character==10000}.chief)
        assertTrue(tactical.corps.single {it.character==10001}.chief)
        // Viewing a legacy ledger never mutates or double-credits the stored personnel.
        assertEquals(projected,WorldCharacters.view(projected,snapshot))
        assertEquals(120L,stocks.strategic.people.getValue(10001).merit)
    }
}
