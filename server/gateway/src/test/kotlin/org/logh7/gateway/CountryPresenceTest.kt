package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class CountryPresenceTest {
    @Test fun publicSuccessionNoticesCarryTheCurrentCountryNameAndStayInTheirSessionGeneration() {
        val hub=GamePresence();val messages=List(3){mutableListOf<List<ByteArray>>()}
        val character=CharacterMessages.Generate(4,10000,2,0,0,"Pilot","One",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",1)
        for(i in 0..2)hub.update(GamePresence.Member("c$i",character.copy(id=10000L+i),
            Navigator(if(i==2)2 else 1,10000+i,Power.EMPIRE,2487,1000,1000,1000).copy(country=CountryId(42+i)),messages[i]::add))
        messages.forEach {it.clear()}
        var name="새 국가"
        val notice=SuccessionNotice(1,10000,SuccessionNotice.Kind.ACCESSION,9,CountryId(42))
        hub.succession(notice,{character},{name})
        fun line(i:Int):String {val b=messages[i].single().single();return ChatMessages.spot(b.copyOfRange(4,b.size)).text}
        assertTrue(line(0).contains("[새 국가]"));assertEquals(line(0),line(1));assertTrue(messages[2].isEmpty())
        messages.forEach {it.clear()};name="변경 국가";hub.succession(notice,{character},{name})
        assertTrue(line(0).contains("[변경 국가]"))
    }
    @Test fun sharedDisplayNeverGrantsAnotherCountrysPresenceOrPrivateChannels() {
        val hub=GamePresence()
        val received=List(5) {mutableListOf<List<ByteArray>>()}
        val character=CharacterMessages.Generate(4,10000,2,0,0,"Pilot","One",30,1,1,0,
            List(8){50},0,0,0,20,0,0,"Ship",1)
        val countries=listOf(42,42,43,null,null)
        val members=countries.mapIndexed {i,country ->
            val p=Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true).copy(country=country?.let(::CountryId))
            GamePresence.Member("c$i",character.copy(id=10000L+i),p,received[i]::add,
                listOf(BootstrapMessages.OfficeCard(25,1049)),outfit=11)
        }
        members.forEach {hub.update(it)}
        assertEquals(listOf("c1"),hub.update(members[0]).peers.map {it.connection})
        assertTrue(hub.update(members[3]).peers.isEmpty(),"independent characters have separate identities")
        fun recipients(action:()->Unit):Set<Int> {
            received.forEach {it.clear()};action()
            return received.indices.filter {received[it].isNotEmpty()}.toSet()
        }
        assertEquals(setOf(1),recipients {hub.gridChat("c0",ChatMessages.Grid(10000,1,"Country"),1)})
        assertEquals(setOf(1),recipients {hub.gridChat("c0",ChatMessages.Grid(10000,2,"Outfit"),1)})
        assertEquals(setOf(1),recipients {hub.spotChat("c0",ChatMessages.Spot(10000,"/GOV Secret"),1)})
        assertTrue(recipients {hub.gridChat("c3",ChatMessages.Grid(10003,1,"No country"),1)}.isEmpty())
        assertTrue(recipients {hub.spotChat("c3",ChatMessages.Spot(10003,"/GOV No country"),1)}.isEmpty())
        assertEquals(setOf(0,1,2,4),recipients {hub.gridChat("c3",ChatMessages.Grid(10003,0,"Public"),1)})
    }
}
