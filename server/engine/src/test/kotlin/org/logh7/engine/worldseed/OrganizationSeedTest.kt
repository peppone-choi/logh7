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
