package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SharedFameWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val accounts=(0..4).associate {"load-%03d".format(it) to "fixture"}
    private val profile=CharacterMessages.Generate(4,10000,2,0,0,"Captain","Pilot",30,1,1,1000001,
        List(8){50},0,0,0,2,3,0,"Ship",1)
    private fun lobby(f:LoadFixture):SyntheticClient {
        val token=SyntheticClient(f.loginPort,LoadMetrics()).use {login->
            login.send(SyntheticClient.login(f.account(0),"fixture"))
            ByteBuffer.wrap(login.receive(false)).getInt(14).toLong() and 0xffffffffL
        }
        return SyntheticClient(f.sessionPort,LoadMetrics()).also {it.send(SyntheticClient.token(token),SyntheticClient.opcode(0x2000));it.expect(0x2001)}
    }
    private fun endingEvaluation(bytes:ByteArray,f:LoadFixture,name:String):SessionEndingMessages.Evaluation {
        val info=SessionEndingReport.session(f.engine.snapshot(),listOf(profile)).copy(name=name)
        val offset=bytes.size-SessionEndingMessages.information(info).size-17
        val b=ByteBuffer.wrap(bytes).position(offset)
        fun u32()=b.int.toLong() and 0xffffffffL
        return SessionEndingMessages.Evaluation(u32(),u32(),u32(),u32(),b.get().toInt() and 255)
    }
    @Test fun restoredEndedWorldSettlesMissingAwardBeforeAdmissionAndDoesNotGrantAgainOnAnotherBoot()=runBlocking {
        val root=Files.createDirectory(directory.resolve("restored"))
        assertTrue(CharacterCreation(root.resolve("characters.properties")).carry("load-000",profile))
        val rules=SessionRules.load().copy(capacity=2)
        val journal=directory.resolve("session")
        fun durability()=org.logh7.persistence.FileSessionStore(journal,rules,
            directorySync=org.logh7.persistence.DirectorySync.FILE_CONTENTS_ONLY)
        // The world result survives, but this deliberately volatile bank models a missing durable award.
        fun stocks()=org.logh7.persistence.FilePlanetStockStore(directory.resolve("planet-stocks"))
        fun navigation()=org.logh7.persistence.FileNavigationStore(directory.resolve("navigation"))
        fun hydrate(engine:WorldEngine)=runBlocking {
            engine.submit(WorldCommand.AdvanceTo(rules.restartDelayRealMillis+1))
            val loaded=kotlinx.coroutines.CompletableDeferred<PlanetStocks>()
            engine.submit(WorldCommand.Stock(1,loaded));loaded.await();Unit
        }
        LoadFixture(root,2,LoadMetrics(),galaxy,ticking=false,stockStore=stocks(),navigationStore=navigation(),
            accountFame=FameLedger(null),sessionDurability=durability(),awaitIdentities=true,prepare=::hydrate).use {f->
            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            f.await("durable ended world") {f.engine.snapshot().result!=null}
        }
        FameLedger.openShared(directory.resolve("common.fame"),false).use {bank->
            repeat(2) {boot->
                LoadFixture(root,2,LoadMetrics(),galaxy,ticking=false,stockStore=stocks(),navigationStore=navigation(),
                    accountFame=bank.forWorld("restored"),sessionDurability=durability(),awaitIdentities=true,
                    prepare={engine->if(boot==0)runBlocking {engine.submit(WorldCommand.AdvanceTo(rules.restartDelayRealMillis+1))} else hydrate(engine)}).use {f->
                    assertNotNull(f.engine.snapshot().result)
                    assertEquals(10,bank.fame().getValue("load-000"))
                    assertEquals(0,f.engine.snapshot().session!!.online)
                }
            }
        }
    }
    @Test fun gameEndingAndLobbyStatementsKeepWorldHistoryWhileAccountFameAccumulates()=runBlocking {
        for(name in listOf("alpha","beta")) {
            val path=Files.createDirectory(directory.resolve(name)).resolve("characters.properties")
            assertTrue(CharacterCreation(path).carry("load-000",profile))
        }
        FameLedger.openShared(directory.resolve("common.fame"),false).use {bank->
            val registry=GameSessionDirectory()
            val alpha=bank.forWorld("alpha");val beta=bank.forWorld("beta")
            alpha.importLegacy(null);beta.importLegacy(null)
            LoadFixture(directory.resolve("alpha"),2,LoadMetrics(),galaxy,ticking=false,sessionDirectory=registry,
                sessionId=1,sessionName="Alpha",accountSource=accounts,accountFame=alpha).use {a->
                LoadFixture(directory.resolve("beta"),2,LoadMetrics(),galaxy,ticking=false,sessionDirectory=registry,
                    sessionId=2,sessionName="Beta",accountSource=accounts,accountFame=beta).use {b->
                    val peers=listOf(a,b).map {f->f.join(f.account(0),false,false).client.also {client->
                        client.send(SyntheticClient.opcode(0x0f00))
                        while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
                    }}
                    try {
                        for((index,f) in listOf(a,b).withIndex()) {
                            f.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
                            f.await("settled ending") {f.engine.snapshot().result!=null}
                            val request=CountryEndingMessages.Request(OutfitPartyMessages.Query(index+1,127*512,14))
                            val profiles=CharacterCreation(f.directory.resolve("characters.properties")).profiles()
                            CountryEndingReport.reply(request,f.engine.snapshot(),profiles,CountryCatalogLoader.current(),emptyList(),2,galaxy).forEach {
                                assertContentEquals(it,peers[index].expect(0x032f),"country results precede the personal fame statement")
                            }
                            val statement=endingEvaluation(peers[index].expect(0x035a),f,if(index==0)"Alpha" else "Beta")
                            assertEquals((index*10).toLong(),statement.previousFame)
                            assertEquals(((index+1)*10).toLong(),statement.finalFame)
                            assertEquals(((index+1)*10),bank.fame().getValue("load-000"))
                        }
                        val expected=listOf(a,b).mapIndexed {index,f->
                            val view=if(index==0)alpha else beta
                            val character=CharacterStores(directory.resolve(if(index==0)"alpha/characters.properties" else "beta/characters.properties"),view)
                                .get(1).character("load-000")!!
                            val info=SessionEndingReport.session(f.engine.snapshot(),listOf(character))
                                .copy(id=index+1,name=if(index==0)"Alpha" else "Beta")
                            LobbyMessages.ChargedSession(character,info,SessionEndingMessages.Evaluation(0,1000,(index*10).toLong(),((index+1)*10).toLong(),0))
                        }
                        lobby(a).use {l->
                            l.send(SyntheticClient.opcode(0x2003))
                            assertContentEquals(LobbyMessages.characters(expected),l.expect(0x2004))
                        }
                    } finally {peers.forEach {it.close()}}
                }
            }
        }
    }
}
