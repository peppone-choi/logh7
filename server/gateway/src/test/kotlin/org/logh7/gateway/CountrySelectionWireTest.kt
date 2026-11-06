package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import kotlin.test.*

class CountrySelectionWireTest {
    @TempDir lateinit var directory:Path
    private fun choice(client:SyntheticClient,country:Int,mode:Int=8,nonce:Int=1,session:Int=1):List<String> {
        val base=nonce*65536+session
        client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(country).putInt(base).put(mode.toByte()).array())
        val b=ByteBuffer.wrap(client.expect(0x032f))
        assertEquals(0,b.int);assertEquals(0x032f,b.short.toInt() and 65535)
        assertEquals(country,b.int);assertEquals(base,b.int);assertEquals(mode,b.get().toInt());assertEquals(2,b.get().toInt())
        b.get();assertEquals(0,b.int);assertEquals(0,b.int);assertEquals(10,b.get().toInt())
        return (0 until 10).map {b.int;b.get();b.get();val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")}
    }
    private fun initial(power:Int,surname:String)=CharacterMessages.Generate(0,0,power,if(power==3)3 else 0,0,
        surname,"Pilot",30,1,1,1,List(8){0},0,0,0,0,0,0,"",0)
    private fun step(client:SyntheticClient,data:CharacterMessages.Generate):CharacterMessages.Generate {
        client.send(SyntheticClient.creationBody(data));return SyntheticClient.generated(client.expect(0x1008))
    }
    private fun create(client:SyntheticClient,power:Int,surname:String):CharacterMessages.Generate {
        var data=initial(power,surname)
        for(stage in 0..4)data=step(client,data.copy(category=stage,shipName=if(stage>=3)"S$surname" else ""))
        return data
    }
    @Test fun acknowledgementIsBoundToSessionCountryPurposeAndFullNonce() {
        LoadFixture(directory,2,LoadMetrics(),ticking=false).use {f->
            f.game(f.account(0),false).use {client->
                assertEquals(listOf("NS1","0","0",""),choice(client,2,session=2).take(4))
                assertEquals("0",choice(client,42,nonce=2)[1],"not playable yet; never trap a newly registered character")
                assertEquals(listOf("NS1","1","2","empire"),choice(client,2,nonce=32767).take(4))
                val data=create(client,2,"SelEmpire")
                assertEquals(2,data.power);assertEquals(13,data.rank)
                assertEquals("0",choice(client,3,nonce=3)[1],"registered accounts cannot replace their choice")
            }
            assertEquals(org.logh7.engine.CountryId(2),CharacterCreation(directory.resolve("characters.properties")).profiles().getValue(f.account(0)).country)
        }
    }
    @Test fun wrongPowerIsRefusedAndDisconnectReleasesTheUnfinishedWizard() {
        LoadFixture(directory,2,LoadMetrics(),ticking=false).use {f->
            f.game(f.account(0),false).use {client->
                assertEquals("1",choice(client,2)[1])
                client.send(SyntheticClient.creationBody(initial(3,"WrongPower")));client.expectClosed()
            }
            f.game(f.account(0),false).use {client->
                assertEquals("1",choice(client,3)[1]);step(client,initial(3,"CancelName"))
                assertEquals("1",choice(client,0,10,2)[1])
                assertEquals("1",choice(client,2,8,3)[1]);step(client,initial(2,"CancelName"))
            }
            f.game(f.account(0),false).use {client->
                assertEquals("1",choice(client,3)[1]);assertEquals(3,create(client,3,"CancelName").power)
            }
        }
    }
    @Test fun lotteryChoiceCannotBeUsedAsAGeneratedCharacterReservation() {
        LoadFixture(directory,2,LoadMetrics(),ticking=false).use {f->
            f.game(f.account(0),false).use {client->
                assertEquals(listOf("NS1","1","3","alliance"),choice(client,3,9).take(4))
                client.send(SyntheticClient.creationBody(initial(3,"WrongPurpose")));client.expectClosed()
            }
        }
    }
    @Test fun lotteryCatalogueAndReconnectedApplicationKeepTheChosenCountry() {
        LoadFixture(directory,2,LoadMetrics(),ticking=false).use {f->
            val application=ByteBuffer.allocate(7).putShort(0x1006).put(1).putInt(1002).array()
            f.game(f.account(0),false).use {client->
                assertEquals("1",choice(client,3,9)[1])
                client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(0).putShort(0x13).putShort(0x4a).array())
                client.expect(0x1200)
                val catalogue=ByteBuffer.wrap(client.expect(0x120f));catalogue.position(6)
                assertEquals(1,catalogue.get().toInt());assertEquals(3,catalogue.get().toInt());catalogue.get();catalogue.short;catalogue.int
                assertEquals(1002,catalogue.int);client.expect(0x1201)
                for(id in listOf(1001,1002)) {
                    client.send(ByteBuffer.allocate(6).putShort(0x1004).putInt(id).array())
                    assertEquals(if(id==1002)0 else 1,client.expect(0x1005)[6].toInt())
                }
                client.send(application);client.expect(0x1006)
            }
            f.game(f.account(0),false).use {client->
                assertEquals("0",choice(client,2,9)[1],"an accepted application cannot change country")
                assertEquals("1",choice(client,3,9,2)[1]);client.send(application);client.expect(0x1006)
                assertEquals("1",choice(client,0,10,3)[1],"cancel never withdraws an accepted lottery application")
            }
            val saved=CharacterCreation(directory.resolve("characters.properties"))
            assertEquals(listOf(1002L),saved.entries(f.account(0)))
            assertEquals(org.logh7.engine.CountryId(3),saved.applicationSelection(f.account(0))?.country)
        }
    }
}
