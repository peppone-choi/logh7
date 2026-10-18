package org.logh7.engine.worldseed

import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Manual page numbers are PDF pages, one-based; record includes the CSV header. */
data class ManualSource(val file: String, val page: Int, val record: Int)
enum class SeedFaction { EMPIRE, ALLIANCE }
data class OrganizationPost(
    val faction: SeedFaction, val department: String, val post: String,
    val seats: Int, val minRank: String, val maxRank: String,
    val appointedBy: String, val source: ManualSource,
)
data class InitialCardHolder(
    val faction: SeedFaction, val department: String, val unit: String,
    val post: String, val initialHolder: String, val rawCells: String, val source: ManualSource,
)

/** Static definitions only: no appointments, rank ordering or live card ownership is inferred. */
class OrganizationSeed internal constructor(posts: List<OrganizationPost>, cards: List<InitialCardHolder>) {
    val posts: List<OrganizationPost> = Collections.unmodifiableList(ArrayList(posts))
    val initialCards: List<InitialCardHolder> = Collections.unmodifiableList(ArrayList(cards))
}

object OrganizationSeedLoader {
    private const val POSTS = "org-posts.csv"
    private const val CARDS = "initial-card-holders.csv"
    private val postHeader = listOf("faction", "page", "department", "post", "seats", "min_rank", "max_rank", "appointed_by")
    private val cardHeader = listOf("faction", "page", "post", "initial_holder", "raw_cells", "department", "unit")

    /** Streams belong to the caller. Missing files and invalid data fail before a snapshot is returned. */
    fun read(posts: InputStream, cards: InputStream): OrganizationSeed {
        val postRows = rows(posts, POSTS, postHeader)
        val cardRows = rows(cards, CARDS, cardHeader)
        val parsedPosts = postRows.mapIndexed { index, row ->
            val record = index + 2
            OrganizationPost(faction(row[0], POSTS, record), required(row[2], POSTS, record),
                required(row[3], POSTS, record), positive(row[4], POSTS, record),
                required(row[5], POSTS, record), required(row[6], POSTS, record), row[7],
                ManualSource(POSTS, page(row[1], POSTS, record, row[0], false), record))
        }
        val parsedCards = cardRows.mapIndexed { index, row ->
            val record = index + 2
            InitialCardHolder(faction(row[0], CARDS, record), required(row[5], CARDS, record), row[6],
                required(row[2], CARDS, record), required(row[3], CARDS, record), required(row[4], CARDS, record),
                ManualSource(CARDS, page(row[1], CARDS, record, row[0], true), record))
        }
        require(parsedPosts.map { Triple(it.faction, it.department, it.post) }.distinct().size == parsedPosts.size) {
            "$POSTS: duplicate contextual post"
        }
        require(parsedCards.map { listOf(it.faction, it.department, it.unit, it.post) }.distinct().size == parsedCards.size) {
            "$CARDS: duplicate contextual card"
        }
        return OrganizationSeed(parsedPosts, parsedCards)
    }

    fun load(): OrganizationSeed {
        fun resource(name: String) = requireNotNull(OrganizationSeedLoader::class.java.getResourceAsStream("/worldseed/$name")) {
            "Missing worldseed/$name"
        }
        return resource(POSTS).use { posts -> resource(CARDS).use { cards -> read(posts, cards) } }
    }

    private fun rows(stream: InputStream, file: String, header: List<String>): List<List<String>> {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = java.io.InputStreamReader(stream, decoder).readText().removePrefix("\uFEFF")
        val rows = StrictSeedCsv.parse(text, file)
        require(rows.firstOrNull() == header) { "$file: unexpected header" }
        require(rows.size > 1) { "$file: empty seed" }
        rows.drop(1).forEachIndexed { index, row ->
            require(row.size == header.size) { "$file: record ${index + 2}: unexpected column count" }
        }
        return rows.drop(1)
    }
    private fun faction(value: String, file: String, record: Int) = when (value) {
        "empire" -> SeedFaction.EMPIRE
        "alliance" -> SeedFaction.ALLIANCE
        else -> throw IllegalArgumentException("$file: record $record: unknown faction")
    }
    private fun required(value: String, file: String, record: Int): String {
        require(value.isNotBlank()) { "$file: record $record: missing required value" }
        return value // Original blanks and '-' in optional/source fields are never replaced by zero.
    }
    private fun positive(value: String, file: String, record: Int): Int {
        require(value.matches(Regex("[1-9][0-9]*"))) { "$file: record $record: invalid positive integer" }
        return requireNotNull(value.toIntOrNull()) { "$file: record $record: integer overflow" }
    }
    private fun page(value: String, file: String, record: Int, faction: String, cards: Boolean): Int {
        val page = positive(value, file, record)
        val allowed = if (cards) { if (faction == "empire") 60..61 else 66..67 }
            else { if (faction == "empire") 56..58 else 62..64 }
        require(page in allowed) { "$file: record $record: page outside verified faction table" }
        return page
    }
}
