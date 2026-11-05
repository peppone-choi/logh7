package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class FlagshipStyleWireTest {
    @TempDir lateinit var dir:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun through(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(300) {val bytes=client.receive();if(ByteBuffer.wrap(bytes).getShort(4).toInt()==opcode)return bytes}
        error("Missing opcode $opcode")
    }
    @Test fun firstWireBootstrapAndReconnectKeepTheCountryAndTheOppositeStyleHull() {
        for(power in 2..3) {
            val directory=dir.resolve("country-$power")
            val style=ShipStyleId(if(power==2)"alliance" else "empire")
            val kind=if(power==2)89 else 0
            val character=CharacterMessages.Generate(4,10000,power,if(power==2)0 else 3,0,"Style","Pilot",30,1,1,1000001,
                List(8){50},0,0,0,20,0,kind,"StyleShip",1)
            assertTrue(CharacterCreation(directory.resolve("characters.properties")).carry("load-000",CharacterProfile(character,CountryId(power),style)))
            LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false).use {fixture->
                repeat(2) {
                    val joined=fixture.join("load-000",create=false,initialize=false)
                    assertTrue(joined.accepted)
                    joined.client.use {client->
                        client.send(SyntheticClient.opcode(0x0f00))
                        val unit=ByteBuffer.wrap(through(client,0x0325))
                        assertEquals(10000+1000000,unit.getInt(8));assertEquals(kind,unit.getShort(12).toInt())
                        through(client,0x0f01)
                        val player=fixture.engine.snapshot().navigators.getValue("load-000")
                        assertEquals(CountryId(power),player.country);assertEquals(style,player.shipStyle)
                        assertEquals(kind,player.hull?.kind);assertEquals(if(power==2)2487 else 2015,player.grid)
                    }
                    fixture.awaitState(0,0,1)
                }
            }
        }
    }
}
