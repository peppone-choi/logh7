package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class SessionEndingWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun code(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun through(client:SyntheticClient,opcode:Int):List<ByteArray> {
        val packets=mutableListOf<ByteArray>()
        repeat(300) {val packet=client.receive();packets+=packet;if(code(packet)==opcode)return packets}
        error("Missing result opcode $opcode")
    }
    private fun ready(fixture:LoadFixture,index:Int):SyntheticClient {
        val client=fixture.join(fixture.account(index),true,false).client
        client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
        return client
    }
    @Test fun twoAuthenticatedPeersReceiveExactlyOneEndingKeepClockAndCannotMutateAfterEndThenCloseOnNewGeneration()=runBlocking {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,powerByIndex={if(it==1)3 else 2}).use {fixture->
            val clients=(0..1).map {ready(fixture,it)}
            fixture.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            fixture.await("ended report") {fixture.engine.snapshot().result!=null}
            val before=fixture.engine.snapshot().navigators
            for(client in clients) {
                assertEquals(1,through(client,0x035a).count {code(it)==0x035a})
                // These encrypted requests were legal before ending. They must not reach mutation handlers now.
                client.send(SyntheticClient.opcode(0x0205),SyntheticClient.opcode(0x1008),SyntheticClient.opcode(0x0300))
                val replies=through(client,0x0301)
                assertFalse(replies.any {code(it)==0x035a})
                assertEquals(fixture.engine.snapshot().result!!.end.gameSeconds,ByteBuffer.wrap(replies.last()).getInt(6).toLong() and 0xffffffffL)
            }
            assertEquals(before,fixture.engine.snapshot().navigators)
            fixture.engine.submit(WorldCommand.Restart)
            fixture.await("next generation") {fixture.engine.snapshot().session!!.generation==2L}
            clients.forEach {it.expectClosed()}
        }
    }
    @Test fun endingWinsOverFailureOfAnOutstandingRefreshAndStillSendsOnlyOnce()=runBlocking {
        val hold=AtomicBoolean();val pending=AtomicReference<CompletableFuture<NavigationResult>?>()
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,navigationDecorator={delegate->
            object:GameNavigation by delegate {
                override fun apply(request:NavigationRequest):CompletableFuture<NavigationResult> {
                    if(!hold.get())return delegate.apply(request)
                    return CompletableFuture<NavigationResult>().also {pending.set(it)}
                }
            }
        }).use {fixture->
            val client=ready(fixture,0);hold.set(true)
            client.send(SyntheticClient.opcode(0x0300))
            fixture.await("held refresh") {pending.get()!=null}
            fixture.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
            fixture.await("ending while refresh waits") {fixture.engine.snapshot().result!=null}
            pending.get()!!.completeExceptionally(IllegalStateException("session ended before refresh completed"))
            assertEquals(1,through(client,0x035a).count {code(it)==0x035a})
            client.send(SyntheticClient.opcode(0x0300))
            assertFalse(through(client,0x0301).any {code(it)==0x035a})
        }
    }
}
