package org.logh7.engine.worldseed

import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

data class CrewUnitDefinition(
    val faction: SeedFaction,
    val unit: String,
    val training: Int,
    val groundAttack: Int?,
    val groundDefense: Int?,
    val source: ManualSource,
)

/** Manual definitions only; training and production rules are not inferred. */
class CrewUnitSeed internal constructor(units: List<CrewUnitDefinition>) {
    val units: List<CrewUnitDefinition> = Collections.unmodifiableList(ArrayList(units))
}

object CrewUnitSeedLoader {
    private const val FILE = "crew-units.csv"
    // tools/manual_tables.py CREW_PAGE and docs/manual/08 sections 3–4 identify page 100.
    private const val PAGE = 100
    private val header = listOf("faction", "unit", "training", "ground_attack", "ground_defense")
    private val positive = Regex("[1-9][0-9]*")
    private val nonnegative = Regex("0|[1-9][0-9]*")

    /** The caller owns the stream; every record is validated before publishing a snapshot. */
    fun read(stream: InputStream): CrewUnitSeed {
        val seen = mutableSetOf<Pair<SeedFaction, String>>()
        val units = rows(stream).mapIndexed { index, row ->
            val record = index + 2
            val faction = when (row[0]) {
                "empire" -> SeedFaction.EMPIRE
                "alliance" -> SeedFaction.ALLIANCE
                else -> throw IllegalArgumentException("$FILE: record $record: unknown faction")
            }
            check(row[1].isNotBlank(), record, "missing unit")
            val training = number(row[2], positive, record, "training")
            val attack = ground(row[3], record, "ground_attack")
            val defense = ground(row[4], record, "ground_defense")
            check(seen.add(faction to row[1]), record, "duplicate contextual crew unit")
            CrewUnitDefinition(faction, row[1], training, attack, defense, ManualSource(FILE, PAGE, record))
        }
        return CrewUnitSeed(units)
    }

    fun load(): CrewUnitSeed = requireNotNull(
        CrewUnitSeedLoader::class.java.getResourceAsStream("/worldseed/$FILE")
    ) { "Missing worldseed/$FILE" }.use { read(it) }

    // shortcut: OrganizationSeedLoader/InitialDeploymentSeedLoader.rows와 중복, PR50·51 병합 후 공용 helper로 합칠 수 있음
    private fun rows(stream: InputStream): List<List<String>> {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { java.io.InputStreamReader(stream, decoder).readText().removePrefix("\uFEFF") }
            catch (error: CharacterCodingException) {
                throw IllegalArgumentException("$FILE: record 1: invalid UTF-8", error)
            }
        val rows = StrictSeedCsv.parse(text, FILE)
        check(rows.firstOrNull() == header, 1, "unexpected header")
        check(rows.size > 1, 1, "empty crew unit seed")
        return rows.drop(1).also { data -> data.forEachIndexed { index, row ->
            check(row.size == header.size, index + 2, "unexpected column count")
        } }
    }

    private fun ground(value: String, record: Int, column: String): Int? =
        if (value == "-") null else number(value, nonnegative, record, column)

    private fun number(value: String, pattern: Regex, record: Int, column: String): Int {
        check(pattern.matches(value), record, "invalid $column")
        return requireNotNull(value.toIntOrNull()) { "$FILE: record $record: invalid $column" }
    }

    private fun check(valid: Boolean, record: Int, reason: String) {
        require(valid) { "$FILE: record $record: $reason" }
    }
}
