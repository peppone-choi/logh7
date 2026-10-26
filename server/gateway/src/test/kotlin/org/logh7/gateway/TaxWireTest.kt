package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.Files
import java.util.Properties
import java.util.HexFormat
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class TaxWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State(governor: Int, galaxy: Galaxy, capital:Boolean=false) : WorldStateCommitter {
        var players = mapOf("load-000" to Navigator(1,10000,if(capital)Power.ALLIANCE else Power.EMPIRE,
            if(capital)2015 else 2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks = PlanetStocks(1,0,galaxy.conditions.mapValues { (_,c) -> PlanetStock(c.baseId,c.power,c.camp,
            100,200,300,PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace),
            governorId=if(capital)0 else governor,capitalAdministratorId=if(capital)governor else 0) },deploymentInitialized=true)
        override fun saveWorld(navigation: Map<String, Navigator>, inventory: PlanetStocks?) {
            players = navigation.toMap(); stocks = checkNotNull(inventory)
        }
        val navigationStore = object : NavigationStore {
            override fun load() = players
            override fun save(players: Map<String, Navigator>) { this@State.players = players.toMap() }
        }
        val stockStore = object : PlanetStockStore {
            override fun load() = stocks
            override fun save(stocks: PlanetStocks) { this@State.stocks = stocks }
        }
    }
    private fun query(card: Int = 54) = ByteBuffer.allocate(27).putShort(0x0a00).putInt(-1).putInt(10000).put(0)
        .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putInt(1049).put(35).putShort(card.toShort()).put(0).array()
    private fun next(client: SyntheticClient, opcode: Int): ByteArray {
        repeat(100) { val value = client.receive(); if ((ByteBuffer.wrap(value).getShort(4).toInt() and 65535) == opcode) return value }
        error("Expected response $opcode")
    }
    @Test fun encryptedCommandUsesAuthoritativeBalanceAndUpdatesConditionsWithoutDoubleCharging() = exercise(10000)
    @Test fun forgedGovernorCardIsRejectedWhenTheServerHasNoAppointment() = exercise(0)
    @Test fun encryptedCapitalAdministratorChangesTaxAndRetainsItsOffice() = exercise(10000,true)
    @Test fun forgedCapitalAdministratorCardCannotGrantAuthority() = exercise(0,true)
    private fun exercise(governor: Int,capital:Boolean=false) {
        val galaxy=if(capital) Galaxy(listOf(GalaxySystem(3,2015,4,"Home",3)),
            galaxy.bases.map { it.copy(grid=2015,name="ハイネセン") },
            mapOf(Power.EMPIRE to 2015,Power.ALLIANCE to 2015)) else galaxy
        val card=if(capital)192 else 54
        if(capital) {
            val person=CharacterMessages.Generate(4,10000,3,0,0,"Capital","Admin",30,1,1,1,List(8){50},0,0,0,1,0,0,"Ship",0)
            val bytes=CharacterMessages.generateReply(person)
            val properties=Properties().also { it.setProperty("load-000",HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size))) }
            Files.newBufferedWriter(directory.resolve("characters.properties")).use { properties.store(it,null) }
        }
        val state = State(governor,galaxy,capital)
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=state.stockStore,
            navigationStore=state.navigationStore,worldStateCommitter=state).use { fixture ->
            val client = fixture.join(fixture.account(0),!capital,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            fun verifyCards(message: ByteArray) {
                val size = if (governor == 0) 14 else 20
                val tail = ByteBuffer.wrap(message,message.size-size,size)
                assertEquals(if (governor == 0) 2 else 3,tail.get().toInt())
                tail.position(tail.position()+12)
                if (governor != 0) { assertEquals(card,tail.short.toInt()); assertEquals(1049,tail.int) }
                assertEquals(0,tail.get().toInt()); assertFalse(tail.hasRemaining())
            }
            verifyCards(next(client,0x0323)); next(client,0x0f01)
            client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())
            val roster = next(client,0x034f)
            assertEquals(card,ByteBuffer.wrap(roster).getShort(6).toInt())
            assertEquals(if (governor == 0) 0 else 1,roster[8].toInt())
            if (governor == 0) assertEquals(9,roster.size)
            else {
                assertEquals(10000,ByteBuffer.wrap(roster).getInt(9))
                // Last office is a stored governor card; the final byte is together count.
                assertEquals(card,ByteBuffer.wrap(roster).getShort(roster.size-7).toInt())
                assertEquals(1049,ByteBuffer.wrap(roster).getInt(roster.size-5))
                assertEquals(0,roster.last().toInt())
            }
            repeat(2) {
                client.send(query(card))
                val response = ByteBuffer.wrap(next(client,0x0a00))
                assertEquals(if (governor == 0) 1 else 0,response.int)
                assertEquals(1000,response.getInt(15))
                assertEquals(if (governor == 0) 1000 else 840,response.getInt(19))
                verifyCards(next(client,0x0356))
                val conditions = next(client,0x031f)
                assertEquals(if (governor == 0) 20 else 35,ByteBuffer.wrap(conditions).getShort(55).toInt())
                assertEquals(if (governor == 0) null else 35,state.stocks.bases.getValue(1049).taxRate)
            }
            assertEquals(1000,state.players.getValue("load-000").pcp)
            assertEquals(if (governor == 0) 1000 else 840,state.players.getValue("load-000").mcp)
        }
    }
}
