package org.logh7.gateway

import java.nio.ByteBuffer
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.LobbyCountryMessages
import org.logh7.protocol.OutfitPartyMessages
import kotlin.test.*

class LobbyCountryCatalogueTest {
    private fun character(id:Long,power:Int=2)=CharacterMessages.Generate(4,id,power,0,0,"Pilot$id","Test",30,1,1,1000001,
        List(8){50},0,0,0,9,2,90,"Ship$id",1)
    private fun request(session:Int=1,nonce:Int=5,offset:Int=0)=
        LobbyCountryMessages.request(OutfitPartyMessages.Query(session,nonce*512+offset,7))

    /** Decodes one session 032f mode-7 frame (field50, opcode, body) into kind, number and the ten text fields. */
    data class Frame(val outfit:Int,val base:Int,val kind:Int,val number:Int,val fields:List<String>)
    private fun decode(frame:ByteArray):Frame {
        val b=ByteBuffer.wrap(frame)
        assertEquals(0,b.int)
        assertEquals(0x032f,b.short.toInt() and 65535)
        val outfit=b.int;val base=b.int;assertEquals(7,b.get().toInt());assertEquals(2,b.get().toInt());b.get()
        val kind=b.int;val number=b.int;val count=b.get().toInt()
        assertEquals(10,count)
        val fields=(0 until count).map {index->
            assertEquals(index+1,b.int);assertEquals(index,b.get().toInt());assertEquals(0,b.get().toInt())
            val length=b.get().toInt() and 255
            if(length==0)"" else String(CharArray(length) {b.char}).removeSuffix("\u0000")
        }
        assertEquals(0,b.get().toInt(),"no ships");assertEquals(0,b.get().toInt(),"no troops")
        assertEquals(0,b.int,"no supplies")
        return Frame(outfit,base,kind,number,fields)
    }

    private val catalog:CountryCatalog by lazy {
        val bundled=CountryCatalogLoader.load()
        val empire=bundled.countries.getValue(2)
        // 350 definitions: the two native countries plus added ones, one with a 24-character name.
        val added=(4..351).map {id->empire.copy(id=id,key="added_$id",name=if(id==42)"가".repeat(24) else "추가국$id",nativePower=0,nativeCamp=1)}
        CountryCatalog(bundled.calendars.values.toList(),bundled.families.values.toList(),
            listOf(empire,bundled.countries.getValue(3))+added)
    }

    private fun source(snapshot:WorldSnapshot?,profiles:Map<String,CharacterProfile>,generation:Long=3)=LobbyCountryCatalogue.Source(
        generation,snapshot,profiles,catalog,1234567890123456789,
        listOf(CountryAppearance(2,"x","empire",CountryColors(primary=0x12ab34),7),CountryAppearance(42,"y","empire",CountryColors(),2)))

    @Test fun pagesListEveryDefinitionWithActualCountryCountsAndOnlyNativeCountriesSelectable() {
        val profiles=mapOf(
            "a" to CharacterProfile(character(10001),CountryId(2),ShipStyleId("empire")),
            "b" to CharacterProfile(character(10002,3),CountryId(3),ShipStyleId("alliance")),
            // Registered in country 2, but this generation's navigator says it defected to 3: 3 counts it.
            "c" to CharacterProfile(character(10003),CountryId(2),ShipStyleId("empire")),
            "d" to CharacterProfile(character(10004),CountryId(42),ShipStyleId("empire")),
            "e" to CharacterProfile(character(10005),null,ShipStyleId("empire")))
        val navigators=mapOf("c" to Navigator(3,10003,CountryId(3),2015,0,0,0),
            // A navigator of another generation never changes the registered country.
            "a" to Navigator(2,10001,CountryId(3),2487,0,0,0))
        val bases=mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,2,1,0,0,0),
            1002 to PlanetStock(1002,Allegiance.Country(CountryId(42)),0,0,0,0),1003 to PlanetStock(1003,3,0,0,0,0))
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(3,86_400,0,0,emptyMap(),1,0,null),navigators=navigators,
            planetStocks=PlanetStocks(3,0,bases))
        val first=LobbyCountryCatalogue.reply(request(),source(snapshot,profiles)).map(::decode)
        assertEquals(7,first.size,"header plus six rows")
        assertTrue(first.all {it.outfit==1 && it.base==5*512},"every frame echoes the query identity and nonce")
        val header=first[0]
        assertEquals(0 to 0,header.kind to header.number)
        assertEquals(listOf("NC1","350","0","6","123456789012","3456789","86400","3","","3"),header.fields,
            "the last field is the session-wide regular bases of defined countries (2, 3 and 42), camp 1 excluded")
        val rows=first.drop(1)
        assertEquals((0..5).toList(),rows.map {it.number});assertTrue(rows.all {it.kind==1})
        assertEquals(listOf("2","3","4","5","6","7"),rows.map {it.fields[0]},"stable country id order")
        assertEquals(listOf("2","은하제국","","12AB34","1","1","2","0","","3"),rows[0].fields,"one empire member left after the defection; camp 1 excluded")
        assertEquals(listOf("3","자유행성동맹","","inherit","2","1","3","0","","3"),rows[1].fields,"the defector counts for its new country")
        assertEquals(listOf("0","0","0","1","","0"),rows[2].fields.drop(4),"an added country is listed but not selectable")
        // Country 42 is on the page starting at offset 40 (ids 2,3,4.. so index 40 is id 42).
        val page=LobbyCountryCatalogue.reply(request(offset=40),source(snapshot,profiles)).map(::decode).drop(1)
        val added=page.single {it.fields[0]=="42"}
        assertEquals(listOf("42","가".repeat(12),"가".repeat(12),"inherit","1","1","0","1","","0"),added.fields)
        // The end of the list and an empty page.
        val last=LobbyCountryCatalogue.reply(request(offset=347),source(snapshot,profiles)).map(::decode)
        assertEquals(listOf("350","347","3"),last[0].fields.subList(1,4));assertEquals(4,last.size)
        val beyond=LobbyCountryCatalogue.reply(request(offset=350),source(snapshot,profiles)).map(::decode)
        assertEquals(1,beyond.size);assertEquals(listOf("350","350","0"),beyond[0].fields.subList(1,4))
    }

    @Test fun aWorldOfAnotherGenerationContributesNoCountsAndAnUnknownSessionRevealsNothing() {
        val stale=WorldSnapshot(0,0,SessionSnapshot(2,500,0,0,emptyMap(),1,0,null),
            planetStocks=PlanetStocks(2,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0))))
        val rows=LobbyCountryCatalogue.reply(request(),source(stale,emptyMap())).map(::decode)
        assertEquals("0",rows[0].fields[6],"no game time from an older generation")
        assertEquals("0",rows[1].fields[5],"no bases from an older generation")
        val empty=LobbyCountryCatalogue.empty(request(session=9,nonce=3,offset=12)).map(::decode)
        assertEquals(1,empty.size)
        assertEquals(listOf("NC1","0","12","0","0","","0","0","","0"),empty[0].fields)
    }

    @Test fun invalidQueriesAreRefused() {
        for(base in listOf(0,12,128*512,5*512+351))
            assertFailsWith<IllegalArgumentException> {LobbyCountryMessages.request(OutfitPartyMessages.Query(1,base,7))}
        assertFailsWith<IllegalArgumentException> {LobbyCountryMessages.request(OutfitPartyMessages.Query(0,5*512,7))}
        assertFailsWith<IllegalArgumentException> {LobbyCountryMessages.request(OutfitPartyMessages.Query(1,5*512,6))}
    }
    @Test fun addedCountriesNeedTheirOwnStartAndEliminatedCountriesCannotBeSelected() {
        val old=CountryCatalogLoader.load()
        val supported=CountryCatalog(old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+
            CountryDefinition(42,"third","제삼국","new_empire",3,0,"empire"))
        assertNull(LobbyCountryCatalogue.selection(42,supported))
        val starts=setOf(2,3,42).map(::CountryId).toSet()
        val chosen=checkNotNull(LobbyCountryCatalogue.selection(42,supported,starts))
        assertEquals(CountryId(42),chosen.country);assertEquals(3,chosen.nativePower);assertEquals(ShipStyleId("empire"),chosen.shipStyle)
        fun flags(eliminated:Boolean):String {
            val session=SessionSnapshot(3,500,0,0,emptyMap(),1,0,null,starts,
                if(eliminated)mapOf(CountryId(42) to CountryDefeat(EndReason.CAPITAL_CAPTURED,400,CountryId(3))) else emptyMap())
            val source=LobbyCountryCatalogue.Source(3,WorldSnapshot(0,0,session),emptyMap(),supported,1,emptyList(),starts)
            return LobbyCountryCatalogue.reply(request(),source).map(::decode).single {it.kind==1 && it.fields[0]=="42"}.fields[9]
        }
        assertEquals("3",flags(false));assertEquals("0",flags(true))
    }

    @Test fun finishedWorldResultsRouteBySessionAndAuthenticatedAccountWithoutIssuingATicket() {
        val directory=GameSessionDirectory();val accounts=mapOf("fixture" to "fixture")
        val asked=mutableListOf<Pair<Int,String>>()
        fun entry(id:Int)=GameSessionDirectory.Entry(id,"World$id","127.0.0.1",48000+id,setOf("127.0.0.1"),{1},{false},
            {error("result lookup must not ask for a playable session")},{null},endings={request,account->
                asked+=id to account
                org.logh7.protocol.CountryEndingMessages.empty(request).map {org.logh7.protocol.LoginMessages.sessionMessage(0x032f,it)}
            })
        directory.register(entry(1),accounts).use {directory.register(entry(2),accounts).use {
            fun ending(id:Int)=org.logh7.protocol.CountryEndingMessages.Request(OutfitPartyMessages.Query(id,512,14))
            assertNotNull(directory.endings(ending(2),"fixture"));assertEquals(listOf(2 to "fixture"),asked)
            assertNull(directory.target(2,"127.0.0.1"))
            assertNull(directory.endings(ending(9),"fixture"));assertEquals(1,asked.size)
        }}
    }

    @Test fun theDirectoryAsksOnlyTheSelectedAvailableWorld() {
        val directory=GameSessionDirectory();val accounts=mapOf("fixture" to "fixture")
        var asked=mutableListOf<Int>();var open=true
        fun entry(id:Int,available:()->Boolean)=GameSessionDirectory.Entry(id,"World$id","127.0.0.1",48000+id,setOf("127.0.0.1"),{1},available,
            {org.logh7.protocol.SessionEndingMessages.Session(id,1,"World$id","",0,
                listOf(2,3).map {org.logh7.protocol.SessionEndingMessages.PowerResult(it,0,0,0,null)},null)},{null},
            countries={request->asked+=id;LobbyCountryCatalogue.empty(request)})
        directory.register(entry(1){true},accounts).use {directory.register(entry(2){open},accounts).use {
            assertNotNull(directory.countries(request(session=2)));assertEquals(listOf(2),asked)
            open=false
            assertNull(directory.countries(request(session=2)),"an unavailable world is not asked");assertEquals(listOf(2),asked)
            assertNull(directory.countries(request(session=9)),"an unknown world")
        }}
    }
}
