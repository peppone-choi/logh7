package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class MultipleSessionsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val accounts=(0..4).associate {"load-%03d".format(it) to "fixture"}
    private fun lobby(f:LoadFixture,index:Int=0):SyntheticClient {
        val token=SyntheticClient(f.loginPort,LoadMetrics()).use {login->
            login.send(SyntheticClient.login(f.account(index),"fixture"))
            ByteBuffer.wrap(login.receive(false)).getInt(14).toLong() and 0xffffffffL
        }
        return SyntheticClient(f.sessionPort,LoadMetrics()).also {it.send(SyntheticClient.token(token),SyntheticClient.opcode(0x2000));it.expect(0x2001)}
    }
    private fun selected(lobby:SyntheticClient,id:Int):ByteArray {
        lobby.send(ByteBuffer.allocate(4).putShort(0x2009).putShort(id.toShort()).array())
        return lobby.expect(0x200a)
    }
    @Test fun `shared lobby routes to independent characters worlds and CP and rejects wrong destination and old generation tickets`()=runBlocking {
        val registry=GameSessionDirectory()
        LoadFixture(directory.resolve("a"),2,LoadMetrics(),galaxy,ticking=false,sessionDirectory=registry,
            sessionId=1,sessionName="Alpha",accountSource=accounts).use {a->
            LoadFixture(directory.resolve("b"),2,LoadMetrics(),galaxy,ticking=false,powerByIndex={3},sessionDirectory=registry,
                sessionId=2,sessionName="Beta",accountSource=accounts).use {b->
                fun emptyWorld(expected:Int) {
                    lobby(a,3).use {client->
                        client.send(SyntheticClient.opcode(0x2003));val packet=client.expect(0x2004)
                        assertEquals(1,packet[6].toInt());assertEquals(expected,ByteBuffer.wrap(packet).getShort(7).toInt())
                        assertContentEquals(byteArrayOf(0,0,0),packet.takeLast(3).toByteArray())
                        assertEquals(7+org.logh7.protocol.SessionEndingMessages.information(registry.sessions().first()).size+3,packet.size)
                    }
                }
                emptyWorld(1)
                for(fixture in listOf(a,b))fixture.join(fixture.account(0),true,false).client.use {client->
                    client.send(SyntheticClient.opcode(0x0f00))
                    while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
                }
                a.await("A disconnected") {a.engine.snapshot().session!!.online==0}
                b.await("B disconnected") {b.engine.snapshot().session!!.online==0}
                val ca=CharacterStores(directory.resolve("a/characters.properties")).get(1).character(a.account(0))!!
                val cb=CharacterStores(directory.resolve("b/characters.properties")).get(1).character(b.account(0))!!
                assertEquals(ca.id,cb.id);assertEquals(2,ca.power);assertEquals(3,cb.power)
                assertEquals(Power.EMPIRE,a.engine.snapshot().navigators.getValue(a.account(0)).power)
                assertEquals(Power.ALLIANCE,b.engine.snapshot().navigators.getValue(b.account(0)).power)
                val beforeB=b.engine.snapshot()
                lobby(a).use {l->
                    l.send(SyntheticClient.opcode(0x2005)+byteArrayOf(0));assertEquals(2,l.expect(0x2006)[7].toInt())
                    l.send(SyntheticClient.opcode(0x2003));assertEquals(2,l.expect(0x2004)[6].toInt())
                    val redirect=selected(l,2)
                    assertEquals(b.sessionPort,ByteBuffer.wrap(redirect).getShort(10).toInt() and 65535)
                    val token=ByteBuffer.wrap(redirect).getInt(12).toLong() and 0xffffffffL
                    SyntheticClient(a.sessionPort,LoadMetrics()).use {wrong->wrong.send(SyntheticClient.token(token));wrong.expectClosed()}
                    SyntheticClient(b.sessionPort,LoadMetrics()).use {replay->replay.send(SyntheticClient.token(token));replay.expectClosed()}
                    val next=ByteBuffer.wrap(selected(l,2)).getInt(12).toLong() and 0xffffffffL
                    SyntheticClient(b.sessionPort,LoadMetrics()).use {right->
                        right.send(SyntheticClient.token(next),SyntheticClient.opcode(0x0200));right.expect(0x0201)
                    }
                    val stale=ByteBuffer.wrap(selected(l,1)).getInt(12).toLong() and 0xffffffffL
                    a.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
                    a.await("A ended") {a.engine.snapshot().result!=null}
                    assertEquals(listOf(2),registry.sessions().map {it.id})
                    emptyWorld(2)
                    a.engine.submit(WorldCommand.Restart)
                    a.await("A restarted") {a.engine.snapshot().session!!.generation==2L}
                    SyntheticClient(a.sessionPort,LoadMetrics()).use {old->old.send(SyntheticClient.token(stale));old.expectClosed()}
                }
                assertEquals(beforeB.navigators,b.engine.snapshot().navigators)
                assertEquals(beforeB.planetStocks,b.engine.snapshot().planetStocks)
            }
            assertEquals(listOf(1),registry.sessions().map {it.id})
        }
        assertTrue(registry.sessions().isEmpty())
    }
}
