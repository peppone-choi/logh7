package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class CommandedOutfitWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private fun stocks() = PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,2000,3000)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,number=1)),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1)),deploymentInitialized=true)
    @Test fun encryptedRosterResolvesAssignedCommanderFromRegisteredCharacter() = runBlocking<Unit> {
        val store = object : PlanetStockStore {
            var value = stocks()
            override fun load() = value
            override fun save(stocks: PlanetStocks) { value=stocks }
        }
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=store).use { fixture ->
            val client = fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            var receivedCards = false
            for (ignored in 0..300) {
                val packet=client.receive(); val opcode=ByteBuffer.wrap(packet).getShort(4).toInt()
                if (opcode == 0x0323) {
                    val tail=ByteBuffer.wrap(packet,packet.size-20,20)
                    assertEquals(3,tail.get().toInt()); assertEquals(0,tail.short.toInt()); assertEquals(0,tail.int)
                    assertEquals(59,tail.short.toInt()); assertEquals(1010000,tail.int)
                    assertEquals(39,tail.short.toInt()); assertEquals(1,tail.int); assertEquals(0,tail.get().toInt())
                    receivedCards=true
                }
                if (opcode == 0x0f01) break
            }
            assertTrue(receivedCards)
            val query = ByteBuffer.allocate(11).putShort(0x032e).putInt(1).putInt(1049).put(0).array()
            val expected = assertNotNull(CharacterCreation(directory.resolve("characters.properties")).characterById(10000))
            val first = client.request(query,0x032f,"commandedParty")
            val input = ByteBuffer.wrap(first); input.position(6)
            assertEquals(1,input.int); assertEquals(1049,input.int); assertEquals(0,input.get().toInt())
            assertEquals(2,input.get().toInt()); assertEquals(0,input.get().toInt())
            assertEquals(0,input.int); assertEquals(1,input.int); assertEquals(1,input.get().toInt())
            assertEquals(10000,input.int); assertEquals(0,input.get().toInt());
            assertEquals(expected.rank,input.get().toInt())
            val name = String(CharArray(input.get().toInt() and 255) { input.char })
            assertEquals(expected.surname+'\u0000',name)
            assertEquals(0,input.get().toInt()); assertEquals(0,input.get().toInt()) // Empty ship/troop lists, not empty membership.
            assertContentEquals(first,client.request(query,0x032f,"commandedPartyAgain"))
            assertEquals(10000,store.value.outfits.getValue(1).commanderId)
        }
    }
    @Test fun missingWrongIdOrEnemyCharacterCannotBeSubstitutedForStoredCommander() {
        val world = WorldBootstrap(galaxy); val stocks=stocks()
        val query=OutfitPartyMessages.Query(1,1049,0)
        val commander=CharacterMessages.Generate(4,10000,2,0,0,"지휘관","",30,1,1,1,List(8) { 0 },0,0,0,0,0,0,"Ship",0)
        assertFails { world.outfitParty(query,2,stocks) }
        assertFails { world.outfitParty(query,2,stocks) { commander.copy(id=20000) } }
        assertFails { world.outfitParty(query,2,stocks) { commander.copy(power=3) } }
        assertFails { world.outfitParty(query,3,stocks) { commander } }
        assertEquals(0x032f,ByteBuffer.wrap(world.outfitParty(query,2,stocks) { commander }).getShort(4).toInt())
    }
}
