package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class CharacterDetailCountryTest {
    @TempDir lateinit var directory:Path
    private val person=CharacterMessages.Generate(4,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,10,0,0,"Ship",0)
    private val style=ShipStyleId.fromLegacy(Power.EMPIRE)
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),old.families.values.toList(),
        old.countries.values.toList()+listOf(CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private fun profile(country:Int?,id:Long=10000,power:Int=2)=CharacterProfile(person.copy(id=id,power=power),country?.let(::CountryId),style)

    @Test fun privateDetailRequiresTheSameRegisteredActualCountryRatherThanTheDisplayByte() {
        assertEquals(CountryId(42),WorldCharacters.friendlyCountry(profile(42),profile(42,10001,3),catalog))
        assertNull(WorldCharacters.friendlyCountry(profile(42),profile(2,10001),catalog))
        assertNull(WorldCharacters.friendlyCountry(profile(43),profile(2,10001),catalog))
        assertNull(WorldCharacters.friendlyCountry(profile(42),profile(43,10001),catalog))
        assertEquals(CountryId(2),WorldCharacters.friendlyCountry(profile(2),profile(2,10001),catalog))
        assertEquals(CountryId(3),WorldCharacters.friendlyCountry(profile(3),profile(3,10001),catalog))
        for(missing in listOf(null,99))assertNull(WorldCharacters.friendlyCountry(profile(missing),profile(missing,10001),catalog))
        assertNull(WorldCharacters.friendlyCountry(null,profile(2),catalog))
        assertNull(WorldCharacters.friendlyCountry(profile(2),null,catalog))
        assertNull(WorldCharacters.friendlyCountry(profile(42),profile(42,10001),CountryCatalogLoader.load()))
    }

    @Test fun aPersonCanReadTheirOwnPersonalRecordWithoutGrantingOtherUnaffiliatedPeopleAccess() {
        for(country in listOf(null,99,42,2,3)) {
            val own=profile(country)
            assertTrue(WorldCharacters.mayReadPrivateDetail(own,own,catalog))
        }
        assertFalse(WorldCharacters.mayReadPrivateDetail(profile(null),profile(null,10001),catalog))
        assertFalse(WorldCharacters.mayReadPrivateDetail(profile(99),profile(99,10001),catalog))
        assertFalse(WorldCharacters.mayReadPrivateDetail(profile(42),profile(2,10001),catalog))
        assertFalse(WorldCharacters.mayReadPrivateDetail(null,profile(2),catalog))
        assertFalse(WorldCharacters.mayReadPrivateDetail(profile(2),null,catalog))
    }

    @Test fun currentWorldMembershipOverridesTheSavedProfileButOldGenerationsAndOtherIdentitiesDoNot() {
        val stored=mapOf("actor" to profile(42),"target" to profile(42,10001))
        val player=Navigator(1,10001,CountryId(2),2487,1000,1000,1000)
        fun current(navigator:Navigator)=WorldCharacters.profiles(stored,WorldSnapshot(0,0,
            SessionSnapshot(1,100,0,0,emptyMap(),2,1,null),navigators=mapOf("target" to navigator)))
        val changed=current(player)
        assertNull(WorldCharacters.friendlyCountry(changed["actor"],changed["target"],catalog))
        for(stale in listOf(player.copy(generation=2),player.copy(characterId=99999))) {
            val ignored=current(stale)
            assertEquals(CountryId(42),WorldCharacters.friendlyCountry(ignored["actor"],ignored["target"],catalog))
        }
    }

    @Test fun encryptedStrategicDetailRejectsAnOfflinePersonWhoseCurrentCountryDiffersFromTheirSavedDisplay() {
        val characters=CharacterCreation(directory.resolve("characters.properties"))
        assertTrue(characters.carry("load-000",profile(2)))
        assertTrue(characters.carry("load-001",profile(2,10001)))
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),emptyList(),Power.entries.associateWith {2487})
        var savedPlayers=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000),
            "load-001" to Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,1000))
        val store=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(players:Map<String,Navigator>) {savedPlayers=players}
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=store).use {fixture->
            val client=fixture.join(fixture.account(0),false,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            var ready=false
            for(ignored in 0 until 100)if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01) {ready=true;break}
            assertTrue(ready)
            val current=WorldCharacters.profiles(characters.profiles(),fixture.engine.snapshot())
            assertEquals(CountryId(3),current.getValue("load-001").country)
            val own=client.request(ByteBuffer.allocate(6).putShort(0x0322).putInt(10000).array(),0x0323,"ownPrivateDetail")
            assertEquals(10000,ByteBuffer.wrap(own).getInt(6))
            client.send(ByteBuffer.allocate(6).putShort(0x0322).putInt(10001).array())
            client.expectClosed()
        }
    }
}
