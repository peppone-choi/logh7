package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.Properties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class CharacterCountryTest {
    @Test fun mailDirectoryUsesActualMembershipInsteadOfNativeDisplayAndKeepsUnattachedPlayersUnattached() {
        val characters=CharacterCreation(dir.resolve("mail-characters.properties"))
        assertTrue(characters.carry("pilot",CharacterProfile(character().copy(shipKind=2),CountryId(42),ShipStyleId("empire"))))
        val player=Navigator(7,10000,CountryId(43),2487,1000,1000,1000,shipStyle=ShipStyleId("empire"))
        assertEquals(CountryId(42),communicationIdentity(characters,10000)!!.country)
        assertEquals(CountryId(43),communicationIdentity(characters,10000,world(player))!!.country)
        assertNull(communicationIdentity(characters,10000,world(player.copy(country=null)))!!.country)
        assertEquals(CountryId(42),communicationIdentity(characters,10000,world(player).copy(navigators=mapOf("other" to player)))!!.country)
        assertNull(communicationIdentity(characters,10001,world(player)))
        assertEquals(CountryId(42),characters.profiles().getValue("pilot").country)
    }
    @TempDir lateinit var dir:Path
    private fun character(id:Long=10000)=CharacterMessages.Generate(4,id,2,0,0,"Pilot$id","Test",30,1,1,1000001,
        List(8){50},0,0,0,9,2,90,"Ship$id",1)
    private fun world(player:Navigator)=WorldSnapshot(0,0,SessionSnapshot(player.generation,0,0,0,emptyMap(),1,0,null),
        navigators=mapOf("pilot" to player))

    @Test fun countriesAndUnattachedCharactersKeepTheirStyleAndWireDataAcrossEverySaveAndReload() {
        val file=dir.resolve("characters.properties")
        val store=CharacterCreation(file)
        val expected=listOf(CountryId(1),CountryId(4),CountryId(42),null).mapIndexed {index,country->
            "pilot$index" to CharacterProfile(character(10000L+index),country,ShipStyleId("alliance"))
        }.toMap()
        for((account,profile) in expected)assertTrue(store.carry(account,profile))
        val restored=CharacterCreation(file)
        assertEquals(expected,restored.profiles())
        expected.forEach {(account,profile)->assertEquals(profile.character,restored.character(account))}
        val values=Properties().also {Files.newBufferedReader(file).use(it::load)}
        assertTrue(values.values.all {it.toString().startsWith("v2:generated:")})
        // Wire display says empire; the alliance cruiser must not turn into an imperial ship on restart.
        assertEquals(90,restored.character("pilot2")!!.shipKind)
        assertNull(restored.profiles().getValue("pilot3").country)
    }

    @Test fun legacyRecordsStayUntouchedUntilAnAtomicWriteAndOriginalsKeepTheirStatus() {
        val file=dir.resolve("old.properties")
        val old=character().copy(shipKind=0,shipType=3)
        val hex=HexFormat.of().formatHex(CharacterMessages.generateReply(old).drop(4).toByteArray())
        Files.writeString(file,"pilot=$hex\noriginal=original:$hex\n")
        val before=Files.readAllBytes(file)
        val store=CharacterCreation(file)
        assertContentEquals(before,Files.readAllBytes(file))
        assertEquals(3,store.character("pilot")!!.shipKind)
        assertFalse(store.character("original")!!.generated)
        assertEquals(CountryId(2),store.profiles().getValue("original").country)
        assertTrue(store.carry("new",CharacterProfile(character(10001),CountryId(42),ShipStyleId("alliance"))))
        assertEquals(store.profiles(),CharacterCreation(file).profiles())
        assertEquals(0,CharacterCreation(file).character("original")!!.shipKind)
    }

    @Test fun currentWorldMembershipWinsButOtherAccountsCharactersAndGenerationsNeverLeak() {
        val stored=mapOf("pilot" to CharacterProfile.legacy(character().copy(shipKind=2)))
        val nav=Navigator(1,10000,CountryId(42),2487,1000,1000,1000,shipStyle=ShipStyleId("alliance"))
        val current=world(nav)
        val resolved=WorldCharacters.profiles(stored,current).getValue("pilot")
        assertEquals(CountryId(42),resolved.country);assertEquals(ShipStyleId("alliance"),resolved.shipStyle)
        assertEquals(CountryId(42),WorldCharacters.identities(stored,current).single().country)
        assertEquals(stored,WorldCharacters.profiles(stored,current.copy(navigators=mapOf("other" to nav))))
        assertEquals(stored,WorldCharacters.profiles(stored,current.copy(navigators=mapOf("pilot" to nav.copy(characterId=10001)))))
        assertEquals(stored,WorldCharacters.profiles(stored,current.copy(navigators=mapOf("pilot" to nav.copy(generation=2)))))
        assertNull(WorldCharacters.identities(stored,world(nav.copy(country=null))).single().country)
        assertEquals(CountryId(2),stored.getValue("pilot").country,"resolving a world never mutates its creation file")
    }

    @Test fun aFailedWriteDoesNotPartiallyChangeMembershipOrRetireItsCharacter() {
        val file=dir.resolve("atomic.properties")
        val store=CharacterCreation(file)
        val profile=CharacterProfile(character(),CountryId(42),ShipStyleId("alliance"))
        assertTrue(store.carry("pilot",profile))
        Files.move(file,dir.resolve("before.properties"));Files.createDirectory(file)
        assertFails {store.defect("pilot",10000,3)}
        assertEquals(profile,store.profiles().getValue("pilot"))
        assertFails {store.retire("pilot",10000)}
        assertEquals(profile,store.profiles().getValue("pilot"))
        assertEquals(profile,CharacterCreation(dir.resolve("before.properties")).profiles().getValue("pilot"))
    }

    @Test fun actualCountryDefectionKeepsTheCommittedStyleAndSavesAtomically() {
        val seed=CountryCatalogLoader.load()
        val catalog=CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),seed.countries.values.toList()+
            seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="empire"))
        val file=dir.resolve("defected.properties")
        val store=CharacterCreation(file)
        assertTrue(store.carry("pilot",CharacterProfile(character(),CountryId(2),ShipStyleId("empire"))))
        assertTrue(store.defect("pilot",10000,CountryId(42),ShipStyleId("alliance"),catalog))
        val moved=CharacterCreation(file).profiles().getValue("pilot")
        assertEquals(CountryId(42),moved.country);assertEquals(ShipStyleId("alliance"),moved.shipStyle)
        assertEquals(2,moved.character.power);assertEquals(character().face,moved.character.face)
        assertFalse(store.defect("pilot",10000,CountryId(42),catalog=catalog))
        Files.move(file,dir.resolve("defected-before.properties"));Files.createDirectory(file)
        assertFails {store.defect("pilot",10000,CountryId(3),catalog=catalog)}
        assertEquals(moved,store.profiles().getValue("pilot"))
    }
    @Test fun invalidVersionedMembershipIsRejectedInsteadOfFallingBackToItsDisplayCountry() {
        val valid=CharacterProfile(character(),CountryId(42),ShipStyleId("alliance")).encoded()
        for(value in listOf(valid.replace(":42:",":0:"),valid.replace(":42:",":042:"),valid.replace(":42:",":+42:"),
            valid.replace(":alliance:",":bad style:"),
            valid.replace(":generated:",":unknown:"),"$valid:extra",valid.replace("v2:","v3:"))) {
            assertFails {CharacterProfile.decoded(value)}
        }
    }
}
