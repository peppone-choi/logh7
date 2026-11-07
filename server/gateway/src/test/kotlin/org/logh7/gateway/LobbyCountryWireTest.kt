package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CountryImageMessages
import org.logh7.protocol.OutfitPartyMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class LobbyCountryWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))

    private fun lobby(f:LoadFixture,index:Int):SyntheticClient {
        val token=SyntheticClient(f.loginPort,LoadMetrics()).use {login->
            login.send(SyntheticClient.login(f.account(index),f.accounts.getValue(f.account(index))))
            ByteBuffer.wrap(login.receive(false)).getInt(14).toLong() and 0xffffffffL
        }
        return SyntheticClient(f.sessionPort,LoadMetrics()).also {
            it.send(SyntheticClient.token(token),SyntheticClient.opcode(0x2000));it.expect(0x2001)
        }
    }
    private fun query(session:Int,nonce:Int,offset:Int,mode:Int=7)=
        ByteBuffer.allocate(11).putShort(0x032e).putInt(session).putInt(nonce*512+offset).put(mode.toByte()).array()
    /** Text fields of one 032f mode-7 frame, after checking it echoes the query. */
    private fun fields(message:ByteArray,session:Int,base:Int):Pair<Int,List<String>> {
        val b=ByteBuffer.wrap(message);assertEquals(0,b.int);assertEquals(0x032f,b.short.toInt() and 65535)
        assertEquals(session,b.int);assertEquals(base,b.int);assertEquals(7,b.get().toInt());assertEquals(2,b.get().toInt());b.get()
        val kind=b.int;b.int;val count=b.get().toInt()
        return kind to (0 until count).map {b.int;b.get();b.get();val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")}
    }
    private fun catalogue(client:SyntheticClient,session:Int,nonce:Int,offset:Int):List<Pair<Int,List<String>>> {
        client.send(query(session,nonce,offset))
        val header=fields(client.expect(0x032f),session,nonce*512+offset)
        val rows=header.second[3].toInt()
        return listOf(header)+(0 until rows).map {fields(client.expect(0x032f),session,nonce*512+offset)}
    }

    private fun assertEmblem(client: SyntheticClient, session: Int, country: Int, slot: Int, revision: Long = 0) {
        val base = (9 shl 16) or session
        val request = CountryImageMessages.LobbyRequest(OutfitPartyMessages.Query(country, base, 18))
        client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(country).putInt(base).put(18).array())
        for (frame in CountryImageMessages.lobbyReply(request, revision, slot, null)) {
            assertContentEquals(frame, client.expect(0x032f))
        }
    }

    @Test fun lobbyAndPreGameConnectionsReadOnlyTheirAllowedCatalogue() {
        LoadFixture(directory,3,LoadMetrics(),galaxy,ticking=false).use {f->
            f.join(f.account(0),true,false).client.close()
            lobby(f,1).use {client->
                val frames=catalogue(client,1,3,0)
                assertEquals(3,frames.size,"header and the two bundled countries")
                assertEquals(0,frames[0].first);assertEquals(listOf("NC1","2","0","2"),frames[0].second.take(4))
                assertEquals("0",frames[0].second[9],"no stock snapshot yet in this unticked fixture, so no regular bases")
                assertEquals(listOf("2","은하제국","","inherit","1"),frames[1].second.take(5),"the registered empire character counts")
                assertEquals("3",frames[1].second[9],"create and lottery")
                assertEquals(listOf("3","자유행성동맹"),frames[2].second.take(2))
                assertEquals("0",frames[2].second[4])
                assertEquals("NC2",catalogue(client,1,127,350).single().second[0])
                val header = frames.first().second
                assertEmblem(client,1,2,2,(header[4]+header[5]).toLong())
                assertEmblem(client,7,2,0)
                // Another session id reveals nothing; the lobby keeps working afterwards.
                val unknown=catalogue(client,7,4,0)
                assertEquals(1,unknown.size);assertEquals(listOf("NC1","0","0","0"),unknown[0].second.take(4))
                client.send(SyntheticClient.opcode(0x2005)+byteArrayOf(0));client.expect(0x2006)
            }
            // A gameplay-only query is still rejected on the lobby connection.
            lobby(f,1).use {client->client.send(query(1,0,0,mode=4));client.expectClosed()}
            lobby(f,1).use {client->client.send(query(1,0,5,mode=7));client.expectClosed()}
            // A game connection that has not created or joined reads only its own world.
            f.game(f.account(2),create=false).use {game->
                val frames = catalogue(game,1,9,0)
                assertEquals(3,frames.size)
                assertEquals(1,catalogue(game,2,9,0).size,"another session id is answered empty")
                val header = frames.first().second
                assertEmblem(game,1,3,3,(header[4]+header[5]).toLong())
                assertEmblem(game,2,3,0)
            }
        }
    }
}
