package org.logh7.engine.worldseed

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import kotlin.test.*

class OrganizationSeedTest {
    private val postHeader = "faction,page,department,post,seats,min_rank,max_rank,appointed_by"
    private val cardHeader = "faction,page,post,initial_holder,raw_cells,department,unit"
    private val post = "empire,56,Department,Leader,1,-,-,"
    private val card = "empire,60,Leader,Person,Source cells,Department,"
    private fun stream(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
    private fun read(postBody: String = post, cardBody: String = card) =
        OrganizationSeedLoader.read(stream("$postHeader\n$postBody\n"), stream("$cardHeader\n$cardBody\n"))

    @Test fun packagedManualSnapshotPreservesCountsProvenanceAndOriginalMarkers() {
        val seed = OrganizationSeedLoader.load()
        assertEquals(121, seed.posts.size)
        assertEquals(58, seed.posts.count { it.faction == SeedFaction.EMPIRE })
        assertEquals(63, seed.posts.count { it.faction == SeedFaction.ALLIANCE })
        assertEquals(75, seed.initialCards.size)
        assertEquals(36, seed.initialCards.count { it.faction == SeedFaction.EMPIRE })
        assertEquals(39, seed.initialCards.count { it.faction == SeedFaction.ALLIANCE })
        val secondFleetCards = seed.initialCards.filter { it.faction == SeedFaction.EMPIRE && it.unit == "第２艦隊" }
        assertTrue(secondFleetCards.any { it.post == "司令官副官" })
        assertTrue(secondFleetCards.map { it.post }.distinct().size > 1)
        val emperor = seed.posts.first()
        assertEquals("皇帝", emperor.post)
        assertEquals(1, emperor.seats)
        assertEquals("-", emperor.minRank)
        assertEquals("-", emperor.maxRank)
        assertEquals("", emperor.appointedBy)
        assertEquals(ManualSource("org-posts.csv", 56, 2), emperor.source)
        assertEquals("フリードリヒⅣ世", seed.initialCards.first().initialHolder)
        assertEquals("", seed.initialCards.first().unit)
        assertEquals(ManualSource("initial-card-holders.csv", 60, 2), seed.initialCards.first().source)
        val last = seed.posts.last()
        assertEquals("諜報官", last.post)
        assertEquals(50, last.seats)
        assertEquals("少尉", last.minRank)
        assertEquals("大佐", last.maxRank)
        assertEquals(ManualSource("org-posts.csv", 64, 122), last.source)
        assertTrue(seed.posts.any { it.minRank == "政治家/准将" })
        assertEquals("第５６巡察隊", seed.initialCards.last().unit)
        assertEquals(ManualSource("initial-card-holders.csv", 67, 76), seed.initialCards.last().source)
    }

    // Raw comparisons from t_48a9acb4/recount.py, not appointment or capacity rules.
    @Test fun rawCardPostMismatchesPreserveAllEightSourceRecords() {
        val seed = OrganizationSeedLoader.load()
        val keys = seed.posts.map { Triple(it.faction, it.department, it.post) }.toSet()
        val unmatched = seed.initialCards.filter { Triple(it.faction, it.department, it.post) !in keys }
        assertEquals(listOf(
            InitialCardHolder(SeedFaction.EMPIRE, "艦隊", "第２艦隊", "司令官副官", "キルヒアイス少佐",
                "艦隊 | 第２艦隊 | キルヒアイス少佐（司令官副官）", ManualSource("initial-card-holders.csv", 60, 22)),
            InitialCardHolder(SeedFaction.EMPIRE, "イゼルローン要塞", "", "要塞司令官", "シュトックハウゼン上級大将",
                "イゼルローン要塞 | 要塞司令官 | シュトックハウゼン上級大将", ManualSource("initial-card-holders.csv", 61, 37)),
            InitialCardHolder(SeedFaction.ALLIANCE, "艦隊", "第２艦隊", "副司令官", "アーメド少将",
                "艦隊 | 第２艦隊 | アーメド少将（副司令官）", ManualSource("initial-card-holders.csv", 66, 53)),
            InitialCardHolder(SeedFaction.ALLIANCE, "艦隊", "第４艦隊", "副司令官", "タナンチャイ少将",
                "艦隊 | 第４艦隊 | タナンチャイ少将（副司令官）", ManualSource("initial-card-holders.csv", 66, 56)),
            InitialCardHolder(SeedFaction.ALLIANCE, "艦隊", "第５艦隊", "副司令官", "モンシャルマン少将",
                "艦隊 | 第５艦隊 | モンシャルマン少将（副司令官）", ManualSource("initial-card-holders.csv", 66, 58)),
            InitialCardHolder(SeedFaction.ALLIANCE, "艦隊", "第９艦隊", "副司令官", "モートン少将",
                "艦隊 | 第９艦隊 | モートン少将（副司令官）", ManualSource("initial-card-holders.csv", 66, 63)),
            InitialCardHolder(SeedFaction.ALLIANCE, "艦隊", "第１０艦隊", "副司令官", "チェン少将",
                "艦隊 | 第１０艦隊 | チェン少将（副司令官）", ManualSource("initial-card-holders.csv", 66, 65)),
            InitialCardHolder(SeedFaction.ALLIANCE, "艦隊", "第１２艦隊", "副司令官", "コナリー少将",
                "艦隊 | 第１２艦隊 | コナリー少将（副司令官）", ManualSource("initial-card-holders.csv", 66, 68)),
        ), unmatched)
    }

    @Test fun rawAppointerNamesPreserveFiveUnresolvedRecordsWithoutSplitting() {
        val seed = OrganizationSeedLoader.load()
        val names = seed.posts.map { it.faction to it.post }.toSet()
        val appointed = seed.posts.filter { it.appointedBy.isNotEmpty() }
        assertEquals(108, appointed.size)
        val unresolved = appointed.filter { (it.faction to it.appointedBy) !in names }
        assertEquals(listOf(
            OrganizationPost(SeedFaction.ALLIANCE, "同盟軍士官学校", "士官学校長", 1, "中将", "元帥",
                "国防委員会・教育部長", ManualSource("org-posts.csv", 63, 101)),
            OrganizationPost(SeedFaction.ALLIANCE, "要塞", "要塞司令官", 1, "中将", "元帥",
                "国防委員会・施設部長", ManualSource("org-posts.csv", 64, 115)),
            OrganizationPost(SeedFaction.ALLIANCE, "首都惑星を除く各惑星", "惑星守備隊指揮官", 1, "少佐", "元帥",
                "国防委員会・防衛部長", ManualSource("org-posts.csv", 64, 119)),
            OrganizationPost(SeedFaction.ALLIANCE, "首都惑星", "首都防衛指揮官", 1, "大佐", "元帥",
                "国防委員会・防衛部長", ManualSource("org-posts.csv", 64, 121)),
            OrganizationPost(SeedFaction.ALLIANCE, "戦略作戦局", "諜報官", 50, "少尉", "大佐",
                "国防委員会・情報部長", ManualSource("org-posts.csv", 64, 122)),
        ), unresolved)
    }

    @Test fun rawCardCountsAbovePrintedSeatsPreserveFourGroupsWithoutCapacityVerdict() {
        val seed = OrganizationSeedLoader.load()
        val posts = seed.posts.associateBy { Triple(it.faction, it.department, it.post) }
        val groups = seed.initialCards.groupBy { Triple(it.faction, it.department, it.post) }
            .filter { (key, cards) -> posts[key]?.let { cards.size > it.seats } == true }
        assertEquals(mapOf(
            OrganizationPost(SeedFaction.EMPIRE, "艦隊", "艦隊司令官", 1, "中将", "元帥",
                "軍務尚書", ManualSource("org-posts.csv", 57, 40)) to 9,
            OrganizationPost(SeedFaction.EMPIRE, "巡察隊", "巡察隊司令", 1, "准将", "元帥",
                "軍務省次官", ManualSource("org-posts.csv", 58, 48)) to 7,
            OrganizationPost(SeedFaction.ALLIANCE, "艦隊", "艦隊司令官", 1, "少将", "元帥",
                "宇宙艦隊司令長官", ManualSource("org-posts.csv", 63, 103)) to 12,
            OrganizationPost(SeedFaction.ALLIANCE, "巡察隊", "巡察隊司令", 1, "准将", "元帥",
                "宇宙艦隊司令長官", ManualSource("org-posts.csv", 64, 111)) to 8,
        ), groups.map { (key, cards) -> posts.getValue(key) to cards.size }.toMap())

        // Pin every contributing card's raw cells and provenance, not just the four totals.
        val cards = seed.initialCards.filter { Triple(it.faction, it.department, it.post) in groups }
        cards.forEach {
            assertEquals("${it.department} | ${it.post} | ${it.unit} | ${it.initialHolder}", it.rawCells)
        }
        assertEquals(listOf(
            ManualSource("initial-card-holders.csv", 60, 20) to "艦隊 | 艦隊司令官 | 第１艦隊 | G.ミュッケンベルガー元帥",
            ManualSource("initial-card-holders.csv", 60, 21) to "艦隊 | 艦隊司令官 | 第２艦隊 | ローエングラム中将",
            ManualSource("initial-card-holders.csv", 60, 23) to "艦隊 | 艦隊司令官 | 第３艦隊 | メルカッツ大将",
            ManualSource("initial-card-holders.csv", 60, 24) to "艦隊 | 艦隊司令官 | 第４艦隊 | シュターデン中将",
            ManualSource("initial-card-holders.csv", 60, 25) to "艦隊 | 艦隊司令官 | 第５艦隊 | フォーゲル中将",
            ManualSource("initial-card-holders.csv", 60, 26) to "艦隊 | 艦隊司令官 | 第６艦隊 | ヒルデスハイム中将",
            ManualSource("initial-card-holders.csv", 61, 27) to "艦隊 | 艦隊司令官 | 第８艦隊 | ランズベルク中将",
            ManualSource("initial-card-holders.csv", 61, 28) to "艦隊 | 艦隊司令官 | 第１０艦隊 | フレーゲル中将",
            ManualSource("initial-card-holders.csv", 61, 29) to "艦隊 | 艦隊司令官 | 第１２艦隊 | ゼークト大将",
            ManualSource("initial-card-holders.csv", 61, 30) to "巡察隊 | 巡察隊司令 | 第４巡察隊 | グリューネマン少将",
            ManualSource("initial-card-holders.csv", 61, 31) to "巡察隊 | 巡察隊司令 | 第５巡察隊 | ディッタースドルフ少将",
            ManualSource("initial-card-holders.csv", 61, 32) to "巡察隊 | 巡察隊司令 | 第２９巡察隊 | ホフマイスター少将",
            ManualSource("initial-card-holders.csv", 61, 33) to "巡察隊 | 巡察隊司令 | 第４４巡察隊 | グルーゼンシュテルン少将",
            ManualSource("initial-card-holders.csv", 61, 34) to "巡察隊 | 巡察隊司令 | 第５３巡察隊 | ミッターマイヤー少将",
            ManualSource("initial-card-holders.csv", 61, 35) to "巡察隊 | 巡察隊司令 | 第５４巡察隊 | ロイエンタール少将",
            ManualSource("initial-card-holders.csv", 61, 36) to "巡察隊 | 巡察隊司令 | 第５５巡察隊 | レンネンカンプ少将",
            ManualSource("initial-card-holders.csv", 66, 51) to "艦隊 | 艦隊司令官 | 第１艦隊 | クブルスリー中将",
            ManualSource("initial-card-holders.csv", 66, 52) to "艦隊 | 艦隊司令官 | 第２艦隊 | パエッタ中将",
            ManualSource("initial-card-holders.csv", 66, 54) to "艦隊 | 艦隊司令官 | 第３艦隊 | ルフェーブル中将",
            ManualSource("initial-card-holders.csv", 66, 55) to "艦隊 | 艦隊司令官 | 第４艦隊 | パストーレ中将",
            ManualSource("initial-card-holders.csv", 66, 57) to "艦隊 | 艦隊司令官 | 第５艦隊 | ビュコック中将",
            ManualSource("initial-card-holders.csv", 66, 59) to "艦隊 | 艦隊司令官 | 第６艦隊 | ムーア中将",
            ManualSource("initial-card-holders.csv", 66, 60) to "艦隊 | 艦隊司令官 | 第７艦隊 | ホーウッド中将",
            ManualSource("initial-card-holders.csv", 66, 61) to "艦隊 | 艦隊司令官 | 第８艦隊 | アップルトン中将",
            ManualSource("initial-card-holders.csv", 66, 62) to "艦隊 | 艦隊司令官 | 第９艦隊 | アル・サレム中将",
            ManualSource("initial-card-holders.csv", 66, 64) to "艦隊 | 艦隊司令官 | 第１０艦隊 | ウランフ中将",
            ManualSource("initial-card-holders.csv", 66, 66) to "艦隊 | 艦隊司令官 | 第１１艦隊 | ホーランド中将",
            ManualSource("initial-card-holders.csv", 66, 67) to "艦隊 | 艦隊司令官 | 第１２艦隊 | ボロディン中将",
            ManualSource("initial-card-holders.csv", 66, 69) to "巡察隊 | 巡察隊司令 | 第１１巡察隊 | カールセン少将",
            ManualSource("initial-card-holders.csv", 66, 70) to "巡察隊 | 巡察隊司令 | 第１６巡察隊 | マリネッティ准将",
            ManualSource("initial-card-holders.csv", 67, 71) to "巡察隊 | 巡察隊司令 | 第２１巡察隊 | アラルコン少将",
            ManualSource("initial-card-holders.csv", 67, 72) to "巡察隊 | 巡察隊司令 | 第２６巡察隊 | ザーニアル准将",
            ManualSource("initial-card-holders.csv", 67, 73) to "巡察隊 | 巡察隊司令 | 第３１巡察隊 | ルグランジュ少将",
            ManualSource("initial-card-holders.csv", 67, 74) to "巡察隊 | 巡察隊司令 | 第３６巡察隊 | デッシュ准将",
            ManualSource("initial-card-holders.csv", 67, 75) to "巡察隊 | 巡察隊司令 | 第４６巡察隊 | マスカーニ准将",
            ManualSource("initial-card-holders.csv", 67, 76) to "巡察隊 | 巡察隊司令 | 第５６巡察隊 | グエン准将",
        ), cards.map { it.source to it.rawCells })
    }

    @Test fun quotedCommaEscapesAndEmbeddedNewlinesSurviveBomAndCrLf() {
        val posts = "\uFEFF$postHeader\r\nempire,56,\"D, one\",\"A \"\"leader\"\"\",10,-,-,\"Line1\nLine2\"\r\n"
        val seed = OrganizationSeedLoader.read(stream(posts), stream("$cardHeader\n$card"))
        assertEquals("D, one", seed.posts.single().department)
        assertEquals("A \"leader\"", seed.posts.single().post)
        assertEquals("Line1\nLine2", seed.posts.single().appointedBy)
    }

    @Test fun contextualKeysKeepSamePostInDifferentDepartmentsAndUnits() {
        val seed = read("$post\n${post.replace("Department", "Other")}", "$card\n${card.dropLast(1)},Fleet2")
        assertEquals(2, seed.posts.size)
        assertEquals(listOf("", "Fleet2"), seed.initialCards.map { it.unit })
    }

    @Test fun duplicateContextualDefinitionsFailWithoutPublishingPartialSeed() {
        assertFailsWith<IllegalArgumentException> { read("$post\n$post") }
        assertFailsWith<IllegalArgumentException> { read(cardBody = "$card\n$card") }
    }

    @Test fun unknownFactionOrUnverifiedPageFails() {
        for (invalid in listOf(post.replace("empire", "neutral"), post.replace(",56,", ",60,"),
            post.replace("empire,56", "alliance,56"))) {
            assertFailsWith<IllegalArgumentException> { read(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { read(cardBody = card.replace(",60,", ",56,")) }
    }

    @Test fun invalidCapacityIncludesOverflowAndDoesNotBecomeZero() {
        for (capacity in listOf("", "0", "-", "-1", "1.5", "01", " 1", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { read(post.replace(",1,", ",$capacity,")) }
        }
    }

    @Test fun missingRequiredFieldsAreRejectedWhileOptionalBlanksArePreserved() {
        assertFailsWith<IllegalArgumentException> { read(post.replace("Department", "")) }
        assertFailsWith<IllegalArgumentException> { read(post.replace(",Leader,", ",,")) }
        assertFailsWith<IllegalArgumentException> { read(post.replace(",1,-,-,", ",1,,-,")) }
        assertFailsWith<IllegalArgumentException> { read(cardBody = card.replace("Person", "")) }
        assertEquals("", read().posts.single().appointedBy)
        assertEquals("", read().initialCards.single().unit)
    }

    @Test fun headerAndColumnChangesOrEmptyTablesFail() {
        for (posts in listOf("$postHeader\n", "$postHeader\n$post,extra", "$postHeader\n${post.dropLast(1)}",
            "${postHeader.replace("seats", "capacity")}\n$post", "$postHeader\n$post\n\n")) {
            assertFailsWith<IllegalArgumentException> {
                OrganizationSeedLoader.read(stream(posts), stream("$cardHeader\n$card"))
            }
        }
    }

    @Test fun malformedQuotesAndBareCarriageReturnFail() {
        for (bad in listOf("a,b\"c", "a,\"b", "a,\"b\"c", "a,\"b\" \n", "a,b\rc", "a,b\r")) {
            assertFailsWith<IllegalArgumentException> { StrictSeedCsv.parse(bad, "synthetic.csv") }
        }
    }

    @Test fun invalidUtf8FailsInsteadOfReplacingCharacters() {
        val malformed = ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<CharacterCodingException> {
            OrganizationSeedLoader.read(malformed, stream("$cardHeader\n$card"))
        }
    }

    @Test fun snapshotsCannotBeMutatedThroughJvmListCasts() {
        val seed = read()
        assertFailsWith<UnsupportedOperationException> { (seed.posts as MutableList<OrganizationPost>).clear() }
        assertFailsWith<UnsupportedOperationException> { (seed.initialCards as MutableList<InitialCardHolder>).clear() }
    }
}
