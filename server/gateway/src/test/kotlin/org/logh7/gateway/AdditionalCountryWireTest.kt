package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import kotlin.test.*

class AdditionalCountryWireTest {
    @TempDir lateinit var directory:Path
    private fun fields(frame:ByteArray):List<String> {
        val b=ByteBuffer.wrap(frame);b.position(6);b.int;b.int;b.get();b.get();b.get();b.int;b.int
        return List(b.get().toInt() and 255) {b.int;b.get();b.get();val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")}
    }
    @Test fun additionalCountriesCreateEnterAndReconnectWithoutBecomingTheirSharedDisplayCountry()=runBlocking {
        val normal=CountryCatalogLoader.current();val revision=(CountryCatalogLoader.snapshot()?.revision ?: 0)+1
        val added=listOf(CountryDefinition(42,"third","제삼국","new_empire",3,0,"empire"),
            CountryDefinition(43,"fourth","제사국","alliance",3,0,"alliance"))
        val catalog=CountryCatalog(normal.calendars.values.toList(),normal.families.values.toList(),normal.countries.values.toList()+added)
        val ids=listOf(2,3,42,43);val grids=listOf(2487,2015,2488,2489);val bases=listOf(1049,1177,1050,1051)
        val galaxy=Galaxy(ids.indices.map {GalaxySystem(3+it,grids[it],4,"Home$it",if(it==0)2 else 3)},
            ids.indices.map {GalaxyBase(bases[it],grids[it],80,0,"Base$it",0,20f,86400,0,0f,2f)},
            mapOf(Power.EMPIRE to grids[0],Power.ALLIANCE to grids[1]),
            countryStarts=ids.indices.associate {CountryId(ids[it]) to grids[it]},
            initialOwners=ids.indices.associate {bases[it] to Allegiance.Country(CountryId(ids[it]))})
        CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision,catalog,emptyMap(),emptyMap()))
        try {
            LoadFixture(directory,3,LoadMetrics(),galaxy,ticking=false).use {fixture->
                for((index,country) in listOf(42,43).withIndex()) {
                    val account=fixture.account(index)
                    fixture.game(account,false).use {client->
                        val query=ByteBuffer.allocate(11).putShort(0x032e).putInt(country).putInt(65536+1).put(8).array()
                        client.send(query);assertEquals(listOf("NS1","1","3",if(country==42)"empire" else "alliance"),fields(client.expect(0x032f)).take(4))
                        var current=CharacterMessages.Generate(0,0,3,3,0,"Country$country","Pilot",18,1,1,1100001,List(8){0},0,0,0,0,0,0,"",0)
                        for(category in 0..4) {
                            client.send(SyntheticClient.creationBody(current.copy(category=category,shipName=if(category>=3)"Ship$country" else "")))
                            current=SyntheticClient.generated(client.expect(0x1008))
                        }
                        client.send(SyntheticClient.opcode(0x0205));client.expect(0x0206)
                        client.send(SyntheticClient.opcode(0x0f00));var ready=false
                        repeat(300) {if(!ready && ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01)ready=true}
                        assertTrue(ready)
                        client.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(current.id.toInt()).putInt(1).put(11).array())
                        assertEquals(country.toString(),fields(client.expect(0x032f))[1])
                        val player=fixture.engine.snapshot().navigators.getValue(account)
                        assertEquals(CountryId(country),player.country);assertEquals(grids[ids.indexOf(country)],player.grid)
                        assertEquals(if(country==42)"empire" else "alliance",player.shipStyle.value)
                    }
                    fixture.await("country disconnected") {fixture.engine.snapshot().session!!.online==0}
                    fixture.game(account,false).use {client->
                        client.send(SyntheticClient.opcode(0x0205));client.expect(0x0206)
                        assertEquals(CountryId(country),fixture.engine.snapshot().navigators.getValue(account).country)
                    }
                    fixture.await("reconnect disconnected") {fixture.engine.snapshot().session!!.online==0}
                }
                val saved=CharacterCreation(directory.resolve("characters.properties")).profiles()
                assertEquals(listOf(CountryId(42),CountryId(43)),listOf(0,1).map {saved.getValue(fixture.account(it)).country})
                val peers=(0..1).map {index->fixture.game(fixture.account(index),false).also {client->
                    client.send(SyntheticClient.opcode(0x0205));client.expect(0x0206)
                    client.send(SyntheticClient.opcode(0x0f00))
                    while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
                }}
                val rules=SessionRules.load()
                fixture.engine.submit(WorldCommand.AdvanceTo((rules.endSeconds-rules.startSeconds)*1000/rules.speed))
                fixture.await("four-country result") {fixture.engine.snapshot().result!=null}
                val ended=fixture.engine.snapshot()
                assertEquals(ids.map(::CountryId).toSet(),ended.result!!.countries.keys)
                peers.forEachIndexed {index,client->
                    for(offset in listOf(0,3)) {
                        val request=CountryEndingMessages.Request(OutfitPartyMessages.Query(1,127*512+offset,14))
                        CountryEndingReport.reply(request,ended,saved,catalog,emptyList(),42+index,galaxy).forEach {
                            assertContentEquals(it,client.expect(0x032f),"every actual-country page precedes 035a")
                        }
                    }
                    client.expect(0x035a);client.close()
                }
            }
        } finally {CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision+1,normal,emptyMap(),emptyMap()))}
    }
}
