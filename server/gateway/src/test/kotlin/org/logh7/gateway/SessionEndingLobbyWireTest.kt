package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SessionEndingLobbyWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun lobby(f:LoadFixture,index:Int):SyntheticClient {
        val token=SyntheticClient(f.loginPort,LoadMetrics()).use {login->
            login.send(SyntheticClient.login(f.account(index),f.accounts.getValue(f.account(index))))
            val response=login.receive(false)
            assertEquals(0x7001,ByteBuffer.wrap(response).short.toInt() and 65535)
            ByteBuffer.wrap(response).getInt(14).toLong() and 0xffffffffL
        }
        return SyntheticClient(f.sessionPort,LoadMetrics()).also {
            it.send(SyntheticClient.token(token),SyntheticClient.opcode(0x2000));it.expect(0x2001)
        }
    }
    @Test fun singleSessionSelectionReportsCurrentTerritoryCharactersAndElapsedTime()=runBlocking {
        LoadFixture(directory,3,LoadMetrics(),galaxy,ticking=false,powerByIndex={if(it==1)3 else 2}).use {f->
            for(index in 0..1)f.join(f.account(index),true,false).client.close()
            f.engine.submit(WorldCommand.AdvanceTo(3_600_000))
            f.await("one game day") {f.engine.snapshot().session!!.gameSeconds==86_400L}
            lobby(f,3).use {client->
                fun expectSummary(empireCharacters:Long,seconds:Long) {
                    val information=org.logh7.protocol.SessionEndingMessages.Session(1,1,"LOGH7 Local","",seconds,
                        listOf(org.logh7.protocol.SessionEndingMessages.PowerResult(2,0,empireCharacters,1,null),
                            org.logh7.protocol.SessionEndingMessages.PowerResult(3,0,1,1,null)),null)
                    for(filter in 0..2) {
                        client.send(SyntheticClient.opcode(0x2005)+byteArrayOf(filter.toByte()))
                        assertContentEquals(org.logh7.protocol.LobbyMessages.sessions(listOf(information)),client.expect(0x2006))
                    }
                }
                expectSummary(1,86_400)
                f.join(f.account(2),true,false).client.close()
                f.engine.submit(WorldCommand.AdvanceTo(7_200_000))
                f.await("second game day") {f.engine.snapshot().session!!.gameSeconds==172_800L}
                expectSummary(2,172_800)
            }
        }
    }
    private fun charged(packet:ByteArray):Pair<Int,Int> {
        val b=ByteBuffer.wrap(packet).position(6)
        fun string(){val n=b.get().toInt() and 255;b.position(b.position()+n*2)}
        assertEquals(1,b.get().toInt());assertEquals(1,b.short.toInt());assertEquals(3,b.get().toInt())
        string();string();b.int
        repeat(2) {
            val power=b.get().toInt();assertTrue(power in 2..3)
            repeat(3){b.int};assertEquals(1,b.get().toInt());string();b.short
            repeat(5){b.get()};repeat(2){b.short};repeat(3){b.int}
        }
        assertEquals(1,b.get().toInt());repeat(2){b.short};repeat(3){b.int}
        assertEquals(listOf(0,2,1),List(3){b.get().toInt()})
        return b.int to b.get().toInt()
    }
    @Test fun endedAccountsInspectOnlyTheirChargedCharacterWithoutJoiningOrMutatingAndNewGenerationClearsIt()=runBlocking {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,powerByIndex={if(it==1)3 else 2}).use {f->
            val peers=(0..1).map {index->f.join(f.account(index),true,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00))
                while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
            }}
            val ids=(0..1).map {f.engine.snapshot().navigators.getValue(f.account(it)).characterId}
            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            f.await("ended") {f.engine.snapshot().result!=null}
            val checkpoint=f.engine.snapshot()
            val profiles=CharacterCreation(directory.resolve("characters.properties")).profiles()
            val endings=peers.mapIndexed {index,client->
                val request=org.logh7.protocol.CountryEndingMessages.Request(org.logh7.protocol.OutfitPartyMessages.Query(1,127*512,14))
                val expected=CountryEndingReport.reply(request,checkpoint,profiles,CountryCatalogLoader.current(),emptyList(),if(index==0)2 else 3,galaxy)
                expected.forEach {assertContentEquals(it,client.expect(0x032f),"complete actual-country results precede the native ending")}
                client.expect(0x035a).also {client.close()}
            }
            f.await("no ended players online") {f.engine.snapshot().session!!.online==0 && f.access.trackedConnections()==0}
            val before=f.engine.snapshot()
            val registered=CharacterStores(directory.resolve("characters.properties")).get(1).registeredCharacters()
            val sessionBytes=org.logh7.protocol.SessionEndingMessages.information(SessionEndingReport.session(before,registered)).size
            for(index in 0..1)lobby(f,index).use {client->
                client.send(SyntheticClient.opcode(0x2003));val reply=client.expect(0x2004)
                assertEquals(ids[index] to if(index==0)2 else 3,charged(reply))
                assertEquals(1,reply[reply.size-18].toInt())
                val ending=endings[index];val evaluationAt=ending.size-sessionBytes-17
                assertContentEquals(ending.copyOfRange(evaluationAt,evaluationAt+17),reply.takeLast(17).toByteArray(),
                    "game ending and read-only lobby query use the same settled statement")
                client.send(ByteBuffer.allocate(4).putShort(0x2009).putShort(1).array());client.expect(0x200b)
                assertEquals(0,f.access.trackedConnections());assertEquals(before.navigators,f.engine.snapshot().navigators)
                assertEquals(before.planetStocks,f.engine.snapshot().planetStocks)
            }
            fun empty(packet:ByteArray,status:Int,informationSize:Int) {
                assertEquals(1,packet[6].toInt());assertEquals(status,packet[9].toInt())
                assertEquals(7+informationSize+3,packet.size,"session information only; no other account's character or evaluation")
                assertContentEquals(byteArrayOf(0,0,0),packet.takeLast(3).toByteArray())
            }
            lobby(f,2).use {client->client.send(SyntheticClient.opcode(0x2003));empty(client.expect(0x2004),3,sessionBytes)}
            lobby(f,0).use {client->
                f.engine.submit(WorldCommand.Restart);f.await("next generation") {f.engine.snapshot().session!!.generation==2L}
                client.send(SyntheticClient.opcode(0x2003))
                val information=SessionEndingReport.information(f.engine.snapshot(),emptyList(),generation=2)
                empty(client.expect(0x2004),1,org.logh7.protocol.SessionEndingMessages.information(information).size)
                client.send(SyntheticClient.opcode(0x2005)+byteArrayOf(0))
                val sessions=client.expect(0x2006);assertEquals(1,sessions[7].toInt());assertEquals(1,sessions[10].toInt())
            }
        }
    }
    @Test fun endedLobbyKeepsAFallenPlayersRegistrationAndSettlementInBothLobbyModes() = runBlocking {
        for (shared in listOf(false, true)) {
            val root = directory.resolve(if (shared) "shared" else "single")
            java.nio.file.Files.createDirectories(root)
            val file = root.resolve("characters.properties")
            val casualty = org.logh7.protocol.CharacterMessages.Generate(4,10000,2,0,0,"Fallen","Pilot",30,
                1,1,1,List(8){50},0,0,0,7,0,0,"Ship",1)
            val characters = CharacterCreation(file)
            assertTrue(characters.carry("load-000", casualty))
            assertTrue(characters.carry("load-001", casualty.copy(id=10001,surname="Other",shipName="OtherShip")))
            val state = object : PlanetStockStore {
                var saved = PlanetStocks(1,0, mapOf(1000 to PlanetStock(1000,2,0,0,0,0),
                    1001 to PlanetStock(1001,3,0,0,0,0)), strategic = StrategicState(people =
                    (10000..10001).associateWith { StrategicPerson(rank=7,evaluation=1200,deceasedAt=1) }))
                override fun load() = saved
                override fun save(stocks: PlanetStocks) { saved = stocks }
            }
            val registry = if (shared) GameSessionDirectory() else null
            LoadFixture(root,2,LoadMetrics(),galaxy,ticking=false,stockStore=state,sessionDirectory=registry).use { f ->
                f.engine.submit(WorldCommand.Probe)
                f.await("loaded casualties") { f.engine.snapshot().planetStocks != null }
                lobby(f,1).use { client ->
                    client.send(SyntheticClient.opcode(0x2003)); client.expect(0x2004)
                }
                assertNull(CharacterCreation(file).character(f.account(1)))
                f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4, Power.ALLIANCE to 4), setOf(Power.ALLIANCE)))
                f.await("settled casualties") { f.engine.snapshot().result != null &&
                    FameLedger(file.resolveSibling("${file.fileName}.fame")).fame().containsKey(f.account(0)) }
                val before = java.nio.file.Files.readAllBytes(file)
                lobby(f,0).use { client ->
                    repeat(2) {
                        client.send(SyntheticClient.opcode(0x2003))
                        val reply = client.expect(0x2004)
                        assertEquals(10000 to 2, charged(reply))
                        val expected = org.logh7.protocol.SessionEndingMessages.evaluation(
                            org.logh7.protocol.SessionEndingMessages.Evaluation(1200,1936,0,19,0))
                        assertContentEquals(expected, reply.takeLast(expected.size).toByteArray())
                    }
                }
                assertContentEquals(before, java.nio.file.Files.readAllBytes(file))
            }
        }
    }

    @Test fun fameStorageFailureKeepsEndedSessionAndRejectsRollover() = runBlocking {
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { f ->
            f.join(f.account(0), true, false).client.close()
            f.engine.submit(WorldCommand.AdvanceTo(0))
            f.await("world ready") { f.engine.health() == EngineHealth.RUNNING }
            // An unreadable ledger target makes result settlement fail on the real file path.
            java.nio.file.Files.createDirectory(directory.resolve("characters.properties.fame"))
            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4, Power.ALLIANCE to 4), setOf(Power.ALLIANCE)))
            f.await("fame storage failure") {
                f.engine.health() in setOf(EngineHealth.STORAGE_FAILED, EngineHealth.STOPPED)
            }
            assertEquals(EngineHealth.STORAGE_FAILED, f.engine.health())

            val restarted = kotlinx.coroutines.CompletableDeferred<Boolean>()
            f.engine.submit(WorldCommand.RequestRestart(restarted))
            assertFalse(kotlinx.coroutines.withTimeout(5000) { restarted.await() })
            f.engine.submit(WorldCommand.AdvanceTo(SessionRules.load().restartDelayRealMillis))
            f.engine.submit(WorldCommand.Probe)
            f.await("tick and probe after failed settlement") { f.engine.snapshot().acceptedCommands == 1L }
            assertEquals(1L, f.engine.snapshot().session!!.generation)
            assertTrue(f.engine.snapshot().result != null)
        }
    }

    @Test fun automaticNextGenerationRecreatesBothAccountsAndRestoresInitialWorldWithoutOldLocationsOrCosts()=runBlocking {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,powerByIndex={if(it==1)3 else 2}).use {f->
            val old=(0..1).map {f.join(f.account(it),true,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00))
                while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
            }}
            val warp=org.logh7.protocol.NavigationMessages.accepted(
                org.logh7.protocol.NavigationMessages.Warp(10000,59,2488,0,5),0,0,0)
            old[0].send(warp.copyOfRange(4,warp.size))
            while(ByteBuffer.wrap(old[0].receive()).getShort(4).toInt() and 65535!=0x0b0a) { }
            val previous=f.engine.snapshot()
            assertEquals(2488,previous.navigators.getValue(f.account(0)).grid)
            assertEquals(900,previous.navigators.getValue(f.account(0)).cruise)
            assertEquals(1L,previous.session!!.generation)
            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            f.await("ended before next game") {f.engine.snapshot().result!=null}
            f.engine.submit(WorldCommand.AdvanceTo(SessionRules.load().restartDelayRealMillis))
            f.await("fresh generation") {f.engine.snapshot().session!!.generation==2L}
            old.forEach {it.expectClosed()}
            f.await("old game bindings removed") {f.access.trackedConnections()==0}
            assertNull(f.engine.snapshot().result)
            assertTrue(f.engine.snapshot().navigators.isEmpty())
            for(index in 0..1) {
                val client=f.join(f.account(index),true,false).client
                client.send(SyntheticClient.opcode(0x0f00))
                while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
                val player=f.engine.snapshot().navigators.getValue(f.account(index))
                assertEquals(2L,player.generation)
                assertEquals(if(index==0)Power.EMPIRE else Power.ALLIANCE,player.power)
                assertNull(player.motion)
                assertEquals(if(index==0)2487 else 2015,player.grid)
                assertEquals(0L,player.moves)
                assertEquals(1000,player.cruise)
                assertEquals(1000,player.mcp)
                client.close()
            }
            val fresh=f.engine.snapshot()
            assertEquals(2L,fresh.planetStocks!!.generation)
            assertEquals(previous.planetStocks!!.bases,fresh.planetStocks!!.bases)
            assertEquals(previous.planetStocks!!.outfits,fresh.planetStocks!!.outfits)
            assertEquals(previous.planetStocks!!.warehouses,fresh.planetStocks!!.warehouses)
            assertNull(fresh.session!!.ended)
        }
    }

}
