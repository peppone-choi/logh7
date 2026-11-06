package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import org.logh7.engine.*
import kotlin.test.*

/** 계승 상태(사회 상태 16번째 필드) 왕복. 작성: Peppone Choi. */
class SuccessionCodecTest {
    @TempDir lateinit var directory:Path
    @Test fun multipleCountriesPreserveTheLegacyRecordAndRejectDuplicateOrImplicitCountryOverrides() {
        val legacy=SuccessionState(20,true,22,21,99)
        val state=legacy.with(CountryId(42),ThroneState(30,true,32,31,100)).with(CountryId(43),ThroneState(emperor=40,at=101))
        val social=SocialState(succession=state)
        val text=SocialStateCodec.encode(social)
        assertEquals(social,SocialStateCodec.decode(text));assertTrue(text.endsWith("20:1:22:21:99~42:30:1:32:31:100;43:0:0:40:0:101"))
        assertTrue(SocialStateCodec.encode(SocialState(succession=legacy)).endsWith("20:1:22:21:99"))
        for(bad in listOf("2:30:1:32:31:100","0:30:1:32:31:100","42:0:0:0:0:0","42:30:1:32:31:100;42:30:1:32:31:100"))
            assertFails {SocialStateCodec.decode(text.substringBefore('~')+"~"+bad)}
        val mutable=linkedMapOf(CountryId(42) to ThroneState(heir=30))
        val detached=SocialState(succession=SuccessionState(countries=mutable)).detached()
        mutable.clear();assertEquals(30,detached.succession.of(CountryId(42)).heir)
        assertFails {(detached.succession.countries as MutableMap).clear()}
    }
    @Test fun countrySuccessionChoosesStock44AndReopensWithoutChangingEitherCountry() {
        val file=directory.resolve("stocks.tsv")
        val legacy=PlanetStocks(7,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),
            social=SocialState(succession=SuccessionState(heir=20)))
        FilePlanetStockStore(file).use {it.save(legacy)}
        assertFalse(Files.readString(file).startsWith("LOGH7-STOCK-44"))
        val updated=legacy.copy(social=legacy.social.copy(succession=legacy.social.succession.with(CountryId(42),ThroneState(heir=30))))
        FilePlanetStockStore(file).use {it.save(updated)}
        val bytes=Files.readString(file);assertTrue(bytes.startsWith("LOGH7-STOCK-44"))
        FilePlanetStockStore(file).use {assertEquals(updated,it.load())}
        Files.writeString(file,bytes.replaceFirst("LOGH7-STOCK-44","LOGH7-STOCK-43"))
        FilePlanetStockStore(file).use {assertFails {it.load()}}
    }
    @Test fun successionRoundTripsAsTheSixteenthField() {
        val s=SocialState(succession=SuccessionState(heir=20,vacant=true,emperor=22,rival=21,at=99))
        val text=SocialStateCodec.encode(s)
        assertEquals(16,text.split('|').size);assertEquals(s,SocialStateCodec.decode(text))
        assertEquals(SocialState(),SocialStateCodec.decode(SocialStateCodec.encode(SocialState())))
    }
}
