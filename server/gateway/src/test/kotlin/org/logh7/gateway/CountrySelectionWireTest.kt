package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.gateway.load.*
import org.logh7.engine.Allegiance
import org.logh7.engine.CountryId
import org.logh7.engine.Galaxy
import org.logh7.engine.GalaxyBase
import org.logh7.engine.GalaxySystem
import org.logh7.engine.OriginalCandidateEdit
import org.logh7.engine.Power
import org.logh7.engine.PublishedOriginalCandidates
import org.logh7.engine.ShipStyleId
import org.logh7.engine.UnaffiliatedStart
import org.logh7.engine.WorldStateCommitter
import org.logh7.persistence.FileNavigationStore
import org.logh7.persistence.FileWorldStateStore
import org.logh7.protocol.*
import kotlin.test.*

class CountrySelectionWireTest {
    @TempDir lateinit var directory:Path
    private fun choice(client:SyntheticClient,country:Int,mode:Int=8,nonce:Int=1,session:Int=1):List<String> {
        val base=nonce*65536+session
        client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(country).putInt(base).put(mode.toByte()).array())
        return choiceReply(client,country,mode,base)
    }
    private fun choiceReply(client:SyntheticClient,country:Int,mode:Int,base:Int):List<String> {
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
    private fun through(client:SyntheticClient,opcode:Int):ByteBuffer {
        repeat(100) {
            val message=ByteBuffer.wrap(client.receive())
            if(message.getShort(4).toInt() and 65535==opcode)return message
        }
        error("Missing opcode ${opcode.toString(16)}")
    }
    @Test fun acknowledgementIsBoundToSessionCountryPurposeAndFullNonce() {
        LoadFixture(directory,2,LoadMetrics(),ticking=false).use {f->
            f.game(f.account(0),false).use {client->
                assertEquals(listOf("NS1","0","0",""),choice(client,2,session=2).take(4))
                assertEquals("0",choice(client,42,nonce=2)[1],"not playable yet; never trap a newly registered character")
                assertEquals("0",choice(client,0,nonce=3)[1],"no unaffiliated start in this world")
                assertEquals(listOf("NS1","1","2","empire"),choice(client,2,nonce=32767).take(4))
                val data=create(client,2,"SelEmpire")
                assertEquals(2,data.power);assertEquals(13,data.rank)
                assertEquals("0",choice(client,3,nonce=3)[1],"registered accounts cannot replace their choice")
            }
            assertEquals(org.logh7.engine.CountryId(2),CharacterCreation(directory.resolve("characters.properties")).profiles().getValue(f.account(0)).country)
        }
    }
    @Test fun unaffiliatedCreationPersistsNoCountryAndCanWarpWithoutBorrowingItsShipStylesAffiliation() {
        val world=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2488,4,"Alliance",3),
            GalaxySystem(5,2489,4,"Neutral")),listOf(GalaxyBase(1049,2488,80,0,"Port",0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488),
            unaffiliatedStart=UnaffiliatedStart(2489,ShipStyleId("alliance")))
        val saved=directory.resolve("navigation")
        fun fixture()=LoadFixture(directory,2,LoadMetrics(),world,ticking=false,navigationStore=FileNavigationStore(saved))
        val account="load-000"
        fixture().use { f ->
            f.game(account,false).use {client->
                client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(1).putInt(512).put(7).array())
                assertEquals(1,client.expect(0x032f)[16].toInt() and 1)
                repeat(2) {client.expect(0x032f)}
                assertEquals(listOf("NS1","1","3","alliance"),choice(client,0).take(4))
                val data=create(client,3,"FreePilot")
                assertEquals(3,data.power);assertEquals(13,data.rank)
            }
            val profile=CharacterCreation(directory.resolve("characters.properties")).profiles().getValue(account)
            assertNull(profile.country);assertEquals(ShipStyleId("alliance"),profile.shipStyle)
            f.join(account,false,false).client.use {client->
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                val start=f.engine.snapshot().navigators.getValue(account)
                assertNull(start.country);assertEquals(2489,start.grid)
                fun warp(grid:Int):ByteBuffer {
                    val message=NavigationMessages.accepted(NavigationMessages.Warp(start.characterId,195,grid,0,5),0,0,0)
                    client.send(message.copyOfRange(4,message.size))
                    return through(client,0x0b07)
                }
                warp(2488) // Alliance-style hull grants no friendly entry into an Alliance base system.
                val refused=f.engine.snapshot().navigators.getValue(account)
                assertEquals(2489,refused.grid);assertEquals(1000,refused.mcp);assertEquals(1000,refused.cruise)
                assertEquals(2490,warp(2490).getInt(14))
                val after=f.engine.snapshot().navigators.getValue(account)
                assertEquals(2490,after.grid);assertEquals(960,after.mcp);assertEquals(900,after.cruise)
                assertEquals(0,after.baseId);assertNull(after.dockTarget)
            }
        }
        fixture().use { f ->
            f.join(account,false,false).client.use {client->
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                val restored=f.engine.snapshot().navigators.getValue(account)
                assertNull(restored.country);assertEquals(ShipStyleId("alliance"),restored.shipStyle)
                assertEquals(2490,restored.grid);assertEquals(960,restored.mcp);assertEquals(900,restored.cruise)
            }
        }
    }

    @Test fun independentSettlementCommitsOwnerAndLandingTogetherAndSurvivesRestart() = checkIndependentSettlement(false)

    @Test fun independentSettlementStorageFailureKeepsThePlanetAndShipUnchanged() = checkIndependentSettlement(true)

    private fun checkIndependentSettlement(fail:Boolean) {
        val neutralBase=2000
        val fezzan=2001
        val world=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2488,4,"Alliance",3),
            GalaxySystem(5,2489,4,"Neutral",1)),listOf(
            GalaxyBase(neutralBase,2489,80,0,"Free Port",0,20f,86400,0,0f,2f),
            GalaxyBase(fezzan,2489,80,0,"フェザーン",0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488),
            unaffiliatedStart=UnaffiliatedStart(2489,ShipStyleId("alliance")))
        val state=directory.resolve("world")
        val account="load-000"
        fun fixture(store:FileWorldStateStore)=LoadFixture(directory,2,LoadMetrics(),world,ticking=false,
            stockStore=store.stockStore,navigationStore=store.navigationStore,worldStateCommitter=WorldStateCommitter {nav,stocks->
                if(fail && stocks?.bases?.get(neutralBase)?.owner is Allegiance.Independent)
                    throw java.io.IOException("settlement save failure")
                store.saveWorld(nav,stocks)
            })
        var id=0
        FileWorldStateStore(state).use {store->fixture(store).use { f->
            f.game(account,false).use { client->
                assertEquals("1",choice(client,0)[1])
                id=create(client,3,"FreeSettler").id.toInt()
            }
            f.join(account,false,false).client.use {client->
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                fun send(text:String) {
                    val packet=ChatMessages.grid(ChatMessages.Grid(id,0,text),0)
                    client.send(packet.copyOfRange(4,packet.size))
                }
                fun reply():String {
                    repeat(10) {
                        val packet=through(client,0x0f1d).array()
                        val message=ChatMessages.spot(packet.copyOfRange(4,packet.size)).text
                        if(message.startsWith("[거점 확보]"))return message
                    }
                    error("Missing settlement reply")
                }
                send("/claim $fezzan")
                assertTrue(reply().contains("없습니다"))
                val before=f.engine.snapshot()
                send("/claim")
                if(fail) {
                    assertTrue(reply().contains("저장할 수 없어"))
                    assertEquals(before.navigators.getValue(account),f.engine.snapshot().navigators.getValue(account))
                    assertEquals(Allegiance.Neutral,f.engine.snapshot().planetStocks?.bases?.get(neutralBase)?.owner)
                } else {
                    through(client,0x0b0b)
                    assertTrue(reply().contains("Free Port 확보"))
                    val after=f.engine.snapshot()
                    assertEquals(Allegiance.Independent(id),after.planetStocks?.bases?.get(neutralBase)?.owner)
                    assertEquals(neutralBase,after.navigators.getValue(account).baseId)
                    assertEquals(before.navigators.getValue(account).mcp,after.navigators.getValue(account).mcp)
                    val facility=neutralBase*100+1
                    client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(facility).putInt(16).put(6).array())
                    val guide=through(client,0x032f)
                    assertEquals(facility,guide.getInt(6))
                    assertEquals(6,guide.get(14).toInt())
                    assertEquals(3,guide.get(15).toInt())
                    client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(1).putInt(0).put(2).array())
                    through(client,0x032f)
                    assertEquals(0,through(client,0x0331).get(6).toInt())
                    send("/거점확보 $neutralBase")
                    assertTrue(reply().contains("전략 화면"))
                    assertEquals(after.planetStocks?.bases?.get(neutralBase),f.engine.snapshot().planetStocks?.bases?.get(neutralBase))
                }
            }
        }}
        FileWorldStateStore(state).use {store->
            assertEquals(if(fail)Allegiance.Neutral else Allegiance.Independent(id),store.stockStore.load()?.bases?.get(neutralBase)?.owner)
            val saved=store.navigationStore.load().getValue(account)
            assertNull(saved.country)
            assertEquals(if(fail)0 else neutralBase,saved.baseId)
            if(!fail)fixture(store).use { f->
                f.join(account,false,false).client.use {client->
                    client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                    assertEquals(neutralBase,f.engine.snapshot().navigators.getValue(account).baseId)
                }
            }
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

    @Test fun editedLotteryCatalogueReplacesThePrefetchedListBeforeTheCountryAcknowledgement() {
        val defaults=CharacterCreation.originalDefinitions()
        val published=PublishedOriginalCandidates(defaults)
        val original=defaults.first().profile
        published.publish(listOf(OriginalCandidateEdit(1003,1,original.copy(surname="별빛함장"),CountryId(2))))
        LoadFixture(directory,2,LoadMetrics(),ticking=false,originalCandidates=published).use {f->
            f.game(f.account(0),false).use {client->
                fun readCatalogue():List<Int> {
                    client.expect(0x1200)
                    val body=ByteBuffer.wrap(client.expect(0x120f));body.position(6)
                    val ids=List(body.get().toInt() and 255) {
                        body.position(body.position()+8)
                        val id=body.int
                        val name=body.get().toInt() and 255
                        body.position(body.position()+name*2+15)
                        id
                    }
                    client.expect(0x1201)
                    return ids
                }
                client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(0).putShort(0x13).putShort(0x4a).array())
                assertEquals(listOf(1001,1002),readCatalogue())
                for((country,ids) in listOf(2 to listOf(1001,1003),3 to listOf(1002))) {
                    val base=country*65536+1
                    client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(country).putInt(base).put(9).array())
                    assertEquals(ids,readCatalogue())
                    assertEquals("1",choiceReply(client,country,9,base)[1])
                }
            }
        }
    }
}
