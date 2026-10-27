package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
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
}
