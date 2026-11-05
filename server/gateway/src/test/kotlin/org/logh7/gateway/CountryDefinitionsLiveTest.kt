package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class CountryDefinitionsLiveTest {
    @Test fun existingWizardAndWorldUsePublishedDefinitionsButAnInProgressHullKeepsItsStyle() {
        val normal=CountryCatalogLoader.load()
        val changed=CountryCatalog(normal.calendars.values.map {if(it.key=="imperial")it.copy(name="시험력",yearOffset=-310) else it},
            normal.families.values.toList(),normal.countries.values.map {if(it.id==2)it.copy(name="시험 제국",shipStyleOverride="alliance") else it})
        val service=CharacterCreation()
        val world=WorldBootstrap(Galaxy(listOf(GalaxySystem(3,2487,0,"Fixture")),emptyList(),Power.entries.associateWith {2487}))
        val firstRevision=(CountryCatalogLoader.snapshot()?.revision ?: 0)+1
        fun publish(catalog:CountryCatalog,revision:Long) {CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision,catalog,emptyMap(),emptyMap()))}
        val request=CharacterMessages.Generate(0,0,2,0,0,"Alpha","Pilot",0,0,0,0,List(8){0},0,0,0,0,0,0,"",0)
        fun decode(bytes:ByteArray)=CharacterMessages.generate(bytes.copyOfRange(4,bytes.size))
        val reserved=decode(service.accept("old",request))
        val old=decode(service.accept("old",reserved.copy(category=1,age=18,birthMonth=1,birthDay=1,face=1000001)))
        assertEquals(0,old.shipKind)
        try {
            publish(changed,firstRevision)
            val query=OutfitPartyMessages.Query(2,17,5)
            assertContentEquals(LoginMessages.sessionMessage(0x032f,CountryMetadataMessages.reply(query,2,"시험 제국","시험력",-310,firstRevision)),world.countryMetadata(query,2))
            val next=decode(service.accept("new",request.copy(surname="New")))
            val fresh=decode(service.accept("new",next.copy(category=1,age=18,birthMonth=1,birthDay=1,face=1000001)))
            assertEquals(89,fresh.shipKind)
            val abilities=decode(service.accept("old",old.copy(category=2)))
            val named=decode(service.accept("old",abilities.copy(category=3,shipName="Original")))
            decode(service.accept("old",named.copy(category=4)))
            assertEquals(ShipStyleId("empire"),service.profiles().getValue("old").shipStyle)
            assertEquals(0,service.profiles().getValue("old").character.shipKind)
        } finally {publish(normal,firstRevision+1)}
    }
}
