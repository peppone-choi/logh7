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
            val administration = EngineGameAdministration(fixture.engine,this,galaxy)
            val stationed = Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4)
            assertEquals(1,administration.stationedOutfit(stationed))
            for (outside in listOf(stationed.copy(generation=2),stationed.copy(characterId=10001),
                stationed.copy(power=Power.ALLIANCE),stationed.copy(baseId=1000),stationed.copy(mode=2),
                stationed.copy(baseId=0,mode=5))) assertEquals(0,administration.stationedOutfit(outside))
            client.send(SyntheticClient.opcode(0x0f00))
            var receivedCards = false
            var receivedDefinitions = false
            for (ignored in 0..300) {
                val packet=client.receive(); val opcode=ByteBuffer.wrap(packet).getShort(4).toInt()
                if (opcode == 0x0323) {
                    val tail=ByteBuffer.wrap(packet,packet.size-20,20)
                    assertEquals(3,tail.get().toInt()); assertEquals(0,tail.short.toInt()); assertEquals(0,tail.int)
                    assertEquals(59,tail.short.toInt()); assertEquals(1010000,tail.int)
                    assertEquals(39,tail.short.toInt()); assertEquals(1,tail.int); assertEquals(0,tail.get().toInt())
                    receivedCards=true
                }
                if (opcode == 0x032b) {
                    assertEquals(1,packet[6].toInt()); assertEquals(1,ByteBuffer.wrap(packet).getInt(7))
                    receivedDefinitions=true
                }
                if (opcode == 0x0f01) { assertTrue(receivedDefinitions); break }
            }
            assertTrue(receivedCards)
            assertTrue(receivedDefinitions)
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
        val editor=query.copy(mode=1)
        assertFails { world.outfitParty(editor,2,stocks) { commander } }
        assertFails { world.outfitParty(editor,2,stocks,10001) { commander } }
        assertFails { world.outfitParty(editor.copy(base=0),2,stocks,10000) { commander } }
        val reply=world.outfitParty(editor,2,stocks,10000) { commander }
        assertEquals(1,reply[14].toInt())
        val allocated=stocks.copy(logisticsOfficers=mapOf(10001 to LogisticsOffice.EMPIRE_FIRST))
        assertEquals(1,world.outfitParty(editor.copy(base=0),2,allocated,10001) { commander }[14].toInt())
        assertEquals(1,world.outfitParty(editor,2,allocated,10001) { commander }[14].toInt())
        assertFails { world.outfitParty(editor.copy(base=1050),2,allocated,10001) { commander } }
        val wrongOffice=stocks.copy(logisticsOfficers=mapOf(10001 to LogisticsOffice.EMPIRE_SECOND))
        assertFails { world.outfitParty(editor.copy(base=0),2,wrongOffice,10001) { commander } }
        val enemyBase=allocated.copy(bases=allocated.bases.mapValues { (_,base)->base.copy(power=3) })
        assertFails { world.outfitParty(editor.copy(base=0),2,enemyBase,10001) { commander } }
        // A commander away from the flagship dock: the native editor asks for outfit 0.
        val away=world.outfitParty(OutfitPartyMessages.query(ByteBuffer.allocate(11).putShort(0x032e).putInt(0).putInt(1032).put(1).array()),2,stocks,10000)
        assertEquals(0x032f,ByteBuffer.wrap(away).getShort(4).toInt());assertEquals(0,ByteBuffer.wrap(away).getInt(6))

    }
}
