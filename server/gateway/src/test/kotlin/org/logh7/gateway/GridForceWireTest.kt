package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.NavigationMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class GridForceWireTest {
    @TempDir lateinit var directory: Path
    private fun through(client: SyntheticClient, opcode: Int) {
        repeat(300) { if (ByteBuffer.wrap(client.receive()).getShort(4).toInt() == opcode) return }
        error("Missing transaction end")
    }
    private fun report(client: SyntheticClient, grid: Int) {
        val input=ByteBuffer.wrap(client.expect(0x0b0d));input.position(10)
        assertEquals(0,input.int);assertEquals(10000,input.int);assertEquals(1,input.get().toInt())
        assertEquals(grid,input.short.toInt());assertEquals(1,input.get().toInt())
        assertEquals(2,input.get().toInt());assertEquals(0,input.get().toInt());assertEquals(1,input.short.toInt())
        assertFalse(input.hasRemaining())
    }
    @Test fun encryptedSnapshotFollowsAuthoritativeWarpAndDoesNotCreateAnOldGridUnit()=runBlocking<Unit> {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire"),GalaxySystem(4,2015,4,"Alliance")),
            emptyList(),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
            fixture.clock(client);report(client,2487)
            val warp=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,2488,0,5),0,0,0)
            client.send(warp.copyOfRange(4,warp.size));through(client,0x0b0a)
            fixture.clock(client);report(client,2488)
        }
    }
    @Test fun deniedWarpCompletesNativeCommandAtOriginAndAllowsAnotherCommand()=runBlocking<Unit> {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire"),GalaxySystem(4,2015,4,"Alliance")),
            emptyList(),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
            val before = fixture.engine.snapshot().navigators.values.single()
            val denied=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,2487,0,5),0,0,0)
            client.send(denied.copyOfRange(4,denied.size))
            var ack: ByteBuffer?=null
            for (attempt in 0 until 300) {
                val message=ByteBuffer.wrap(client.receive())
                if(message.getShort(4).toInt()==0x0b01) { ack=message;break }
            }
            assertEquals(1,checkNotNull(ack).getInt(0))
            client.expect(0x0356);client.expect(0x0325);client.expect(0x0317)
            val completion=ByteBuffer.wrap(client.expect(0x0b07))
            assertEquals(2487,completion.getInt(14));assertEquals(1000,completion.getInt(29))
            val notice = client.expect(0x0f1d)
            assertEquals(ChatMessages.Spot(10000, "[워프] 다른 칸 선택"),
                ChatMessages.spot(notice.copyOfRange(4, notice.size)))
            assertEquals(before, fixture.engine.snapshot().navigators.values.single())
            val allowed=NavigationMessages.accepted(NavigationMessages.Warp(10000,59,2488,0,5),0,0,0)
            client.send(allowed.copyOfRange(4,allowed.size));through(client,0x0b0a)
            fixture.clock(client);report(client,2488)
            val after = fixture.engine.snapshot().navigators.values.single()
            assertEquals(960, after.mcp); assertEquals(900, after.cruise)
        }
    }

    @Test fun encryptedSearchChargesServerBalanceAndReturnsTheMapReport() = runBlocking<Unit> {
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
            emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        LoadFixture(directory, 1, LoadMetrics(), galaxy, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            client.send(SyntheticClient.opcode(0x0f00)); through(client, 0x0f01)
            val search = ByteBuffer.allocate(22).putShort(0x0b03).putInt(-1).putInt(-1)
                .putInt(10000).putInt(-1).putInt(-1).array()
            val reply = ByteBuffer.wrap(client.request(search, 0x0b03, "strategicSearch"))
            assertEquals(0, reply.getInt(10))
            assertEquals(10000, reply.getInt(14))
            assertEquals(1000, reply.getInt(18))
            assertEquals(760, reply.getInt(22))
            client.expect(0x0356)
            report(client, 2487)
            assertEquals(760, fixture.engine.snapshot().navigators.values.single().mcp)

            repeat(5) {
                client.request(search, 0x0b03, "strategicSearch")
                client.expect(0x0356)
                report(client, 2487)
            }
            val exhausted = fixture.engine.snapshot().navigators.values.single()
            assertEquals(40, exhausted.pcp)
            assertEquals(40, exhausted.mcp)
            // The next frame is the unchanged balance, not a successful search acknowledgement.
            client.request(search, 0x0356, "deniedStrategicSearch")
            fixture.clock(client)
            assertEquals(exhausted, fixture.engine.snapshot().navigators.values.single())
        }
    }
}
