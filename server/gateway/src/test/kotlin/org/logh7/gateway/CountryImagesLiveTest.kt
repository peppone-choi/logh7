package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class CountryImagesLiveTest {
    @Test fun generatedImageProfilesKeepTheirNativeFallbackWhileCountryStyleAndImagePublicationRemainIndependent() {
        val normal=CountryCatalogLoader.load()
        val third=normal.countries.getValue(3).copy(id=42,key="third",name="신은하제국",family="new_empire",assets="uploaded_country_42_fixture",shipStyleOverride="empire")
        val catalog=CountryCatalog(normal.calendars.values.toList(),normal.families.values.toList(),normal.countries.values.toList()+third)
        val published=PublishedCountryImages()
        val world=WorldBootstrap(Galaxy(listOf(GalaxySystem(3,2487,0,"Fixture")),emptyList(),Power.entries.associateWith {2487}),
            configuredCountryCatalog=catalog,countryImages=published::current)
        val query=OutfitPartyMessages.Query(42,99,16)
        published.publish(CountryImageSnapshot(1,mapOf(42 to CountryImageSelection(2,ByteArray(4096){-1}))))
        assertContentEquals(LoginMessages.sessionMessage(0x032f,CountryMetadataMessages.reply(query,3,"신은하제국","신제국력",-798,0,2)),world.countryMetadata(query,42))
        assertEquals(ShipStyleId("empire"),catalog.shipStyle(CountryId(42)));assertEquals(3,catalog.countries.getValue(42).nativePower)
        assertEquals(47,world.countryImageFrames(query.copy(mode=17)).size)
        val lobby = CountryImageMessages.LobbyRequest(query.copy(mode = 18, base = (99 shl 16) or 1))
        val expected = CountryImageMessages.lobbyReply(lobby, 1, 2, ByteArray(4096) { -1 })
        world.lobbyCountryEmblem(lobby).also { frames ->
            assertEquals(expected.size, frames.size)
            expected.zip(frames).forEach { (expectedFrame, actualFrame) -> assertContentEquals(expectedFrame, actualFrame) }
        }
        published.publish(CountryImageSnapshot(2,mapOf(42 to CountryImageSelection(2))))
        assertContentEquals(CountryImageMessages.replies(query.copy(mode=17),2,null,null).first(),world.countryImageFrames(query.copy(mode=17)).first())
        assertEquals(2,world.countryImageFrames(query.copy(mode=17)).size)
        assertEquals(2,world.countryImageFrames(query.copy(outfit=999,mode=17)).size)
        assertEquals(2,world.lobbyCountryEmblem(lobby).size)
    }
}
