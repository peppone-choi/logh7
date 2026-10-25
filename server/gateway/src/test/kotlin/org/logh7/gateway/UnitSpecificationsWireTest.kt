package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class UnitSpecificationsWireTest {
    @TempDir lateinit var directory: Path
    @Test fun fullCataloguesReachAnAuthenticatedClientWithEveryProducedKind() = runBlocking<Unit> {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),emptyList(),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false).use { fixture ->
            val client=fixture.join(fixture.account(0),true,false).client
            val ships=ByteBuffer.wrap(client.request(SyntheticClient.opcode(0x030a),0x030b,"ships"))
            ships.position(6); val count=ships.get().toInt() and 255
            assertEquals(112,count)
            val sizes=buildMap {
                repeat(count) {
                    val start=ships.position(); val kind=ships.short.toInt() and 65535
                    val definition=UnitSpecifications.ships.single { it.kind==kind }
                    ships.get(); assertEquals(definition.category,ships.get().toInt())
                    ships.short; assertEquals(definition.model,ships.short.toInt())
                    val length=ships.get().toInt() and 255; ships.position(ships.position()+length*2)
                    put(kind,ships.short.toInt() and 65535)
                    ships.position(start+106+length*2)
                }
            }
            assertFalse(ships.hasRemaining())
            assertEquals(1,sizes[0]); assertEquals(300,sizes[32]); assertEquals(300,sizes[119])
            val crews=ByteBuffer.wrap(client.request(SyntheticClient.opcode(0x030c),0x030d,"crews"))
            crews.position(6); assertEquals(11,crews.get().toInt())
            val kinds=buildSet {
                repeat(11) { val at=crews.position(); add(crews.short.toInt() and 65535); crews.position(at+22) }
            }
            assertEquals((1000..1010).toSet(),kinds); assertFalse(crews.hasRemaining())
        }
    }
}
