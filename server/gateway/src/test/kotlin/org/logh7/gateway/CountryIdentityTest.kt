package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.OutfitPartyMessages
import kotlin.test.*

class CountryIdentityTest {
    @TempDir lateinit var directory:Path
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),old.families.values.toList(),
        old.countries.values.toList()+CountryDefinition(42,"new_empire_country","새 제국","new_empire",3,0,"alliance"))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),emptyList(),Power.entries.associateWith {2487})
    private fun fields(message:ByteArray):List<String> {
        val b=ByteBuffer.wrap(message);assertEquals(0,b.int);assertEquals(0x032f,b.short.toInt() and 65535)
        assertEquals(10000,b.int);assertEquals(65535,b.int);assertEquals(11,b.get().toInt());assertEquals(2,b.get().toInt())
        b.get();assertEquals(0,b.int);assertEquals(0,b.int);assertEquals(10,b.get().toInt())
        return (0 until 10).map {b.int;b.get();b.get();val n=b.get().toInt() and 255
            if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")}
    }
    @Test fun actualMembershipSelectsItsCalendarIndependentlyOfTheDisplayCodeOrShipStyle() {
        val world=WorldBootstrap(galaxy,catalog)
        val query=OutfitPartyMessages.Query(10000,65535,11)
        val identity=fields(world.countryIdentity(query,42,3))
        assertEquals(listOf("NI1","42","새 제국","","신제국력","-798"),identity.take(6))
        assertEquals(listOf("3","1"),identity.takeLast(2))
        assertEquals("제국력",fields(world.countryIdentity(query,2,2))[4])
        assertEquals("우주력",fields(world.countryIdentity(query,3,3))[4])
        val unaffiliated=fields(world.countryIdentity(query,null,2))
        assertEquals(listOf("0","무소속"),unaffiliated.subList(1,3));assertEquals("0",unaffiliated.last())
        val removed=fields(world.countryIdentity(query,99,3))
        assertEquals(listOf("99","미등록 국가"),removed.subList(1,3));assertEquals("우주력",removed[4]);assertEquals("0",removed.last())
    }
    @Test fun encryptedIdentityQueryIsAvailableOnlyForTheAuthenticatedOwnCharacter() {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false).use {f->
            val client=f.join(f.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            var ready=false
            for(ignored in 0 until 100)if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01) {ready=true;break}
            assertTrue(ready)
            fun query(id:Int)=ByteBuffer.allocate(11).putShort(0x032e).putInt(id).putInt(65535).put(11).array()
            client.send(query(10000));assertEquals("2",fields(client.expect(0x032f))[1])
            client.send(query(10001));client.expectClosed()
        }
    }
}
