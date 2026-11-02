package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.ChatMessages
import java.nio.file.Path
import kotlin.test.*

/** Actual codecs and character file reopening; native IME/UI is verified separately. Author: Peppone Choi. */
class KoreanLocalizationTest {
    @TempDir lateinit var directory: Path
    private fun character(packet: ByteArray) = CharacterMessages.generate(packet.copyOfRange(4,packet.size))

    @Test fun koreanSurnameGivenNameAndShipNameSurviveRegistrationAndFileReopening() {
        val file = directory.resolve("korean-characters.properties")
        val service = CharacterCreation(file)
        val surname = "가나다라마바사아자차카타" // Native maximum: 12 UTF-16 units, plus NUL on wire.
        val given = "한글ABC日本"
        val request = CharacterMessages.Generate(0,0,2,0,0,surname,given,0,0,0,0,
            List(8){0},0,0,0,0,0,0,"",0)
        val reserved = character(service.accept("korean",request))
        val prepared = character(service.accept("korean",reserved.copy(category=1,age=18,birthMonth=1,birthDay=1,face=1_000_001)))
        val allocated = character(service.accept("korean",prepared.copy(category=2)))
        val named = character(service.accept("korean",allocated.copy(category=3,shipName="한글기함１２ABC")))
        val finished = character(service.accept("korean",named.copy(category=4)))
        assertEquals(surname,finished.surname)
        assertEquals(given,finished.givenName)
        assertEquals(named.shipName,finished.shipName)
        assertEquals(finished,CharacterCreation(file).character("korean"))
        assertFailsWith<IllegalArgumentException> { service.accept("other",request) }
        assertFailsWith<IllegalArgumentException> {
            CharacterMessages.generateReply(finished.copy(surname=surname+"파"))
        }
    }

    @Test fun lastHangulAtChatLengthBoundaryAndEveryGridCastRoundTripWithoutLoss() {
        val text="가".repeat(64)+"힣"
        for(cast in 0..2) {
            val message=ChatMessages.Grid(10000,cast,text)
            val encoded=ChatMessages.grid(message,42)
            assertEquals(message,ChatMessages.grid(encoded.copyOfRange(4,encoded.size)))
        }
        val spot=ChatMessages.Spot(10000,"한글 ABC 日本 · 혼합")
        val encoded=ChatMessages.spot(spot,43)
        assertEquals(spot,ChatMessages.spot(encoded.copyOfRange(4,encoded.size)))
        for(invalid in listOf(text+"가","한글\u0000","한글😀")) {
            assertFailsWith<IllegalArgumentException> { ChatMessages.grid(ChatMessages.Grid(10000,0,invalid),44) }
        }
    }

    @Test fun sourceKeysStayJapaneseWhileDisplayedCataloguesAreKorean() {
        val source="オーディン"
        assertEquals("오딘",KoreanDisplayNames.base(source))
        assertEquals("オーディン",source)
        assertEquals("하이네센",KoreanDisplayNames.base("ハイネセン"))
        assertEquals("반플리트4",KoreanDisplayNames.base("ヴァンフリート4"))
        assertEquals("함대 승조원",KoreanDisplayNames.catalogue("艦隊乗組員"))
        assertEquals("고속전함Ⅷ",KoreanDisplayNames.catalogue("高速戦艦Ⅷ"))
        assertEquals("내 기함",KoreanDisplayNames.catalogue("내 기함"))
        assertFailsWith<IllegalArgumentException> { KoreanDisplayNames.base("가".repeat(13)) }
    }

    @Test fun everyServerOwnedShipAndProductionUnitHasAKoreanDisplayName() {
        val japanese=Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]")
        val names=org.logh7.engine.UnitSpecifications.ships.map {it.name}+
            org.logh7.engine.ProductionUnits.definitions.map {it.name}
        assertTrue(names.isNotEmpty())
        val shown=names.associateWith(KoreanDisplayNames::catalogue)
        assertEquals(emptyMap(),shown.filterValues(japanese::containsMatchIn),"Untranslated server catalogue")
        assertEquals(emptyMap(),shown.filterValues {it.length !in 1..12},"Native name field exceeded")
    }

    @Test fun builtInOriginalCandidatesAndSavedOwnersDisplayKoreanWithoutRewritingPlayerNames() {
        val clock=java.time.Clock.fixed(java.time.Instant.ofEpochMilli(1000),java.time.ZoneOffset.UTC)
        val file=directory.resolve("original-korean.properties")
        val service=CharacterCreation(file,clock,1)
        assertEquals(listOf("키르히아이스","양"),service.candidates("new").map {it.character.surname})
        assertEquals(listOf("지크프리트","웬리"),service.candidates("new").map {it.character.givenName})
        val wire=org.logh7.protocol.OriginalCharacterMessages.catalogue(service.candidates("new"))
        val nameBytes="키르히아이스".toByteArray(Charsets.UTF_16BE).toList()
        assertTrue(wire.asList().windowed(nameBytes.size).any {it==nameBytes})
        service.apply("original",listOf(1002))
        val later=java.time.Clock.offset(clock,java.time.Duration.ofMillis(2))
        val restored=CharacterCreation(file,later,1)
        val owner=restored.character("original")!!
        assertEquals("양",owner.surname);assertEquals("웬리",owner.givenName)
        val bytes=java.nio.file.Files.readAllBytes(file)
        assertEquals(owner,CharacterCreation(file,later,1).characterById(1002))
        assertEquals(listOf(owner),restored.registeredCharacters())
        assertContentEquals(bytes,java.nio.file.Files.readAllBytes(file))
        for(reserved in listOf("양","ヤン","키르히아이스","ｷﾙﾋｱｲｽ")) {
            val request=CharacterMessages.Generate(0,0,2,0,0,reserved,"Pilot",0,0,0,0,List(8){0},0,0,0,0,0,0,"",0)
            assertFailsWith<IllegalArgumentException> {restored.accept("new",request)}
        }
        val original=org.logh7.protocol.OriginalCharacterMessages.localCandidates.last().character
        val player=original.copy(id=10000)
        assertSame(player,KoreanDisplayNames.original(player))
    }
}
