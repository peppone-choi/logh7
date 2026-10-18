package org.logh7.engine.worldseed

import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** p.76–78: 행성별 자동 생산 품목 원문. 수량·주기·보유량이 아니다. */
data class AutomaticProduction(
    val faction: SeedFaction,
    val table: Int,
    val starSystem: String,
    val planet: String,
    val shipUnits: List<String>,
    val crewUnits: List<String>,
    val groundUnits: List<String>,
    val source: ManualSource,
)

class AutomaticProductionSeed internal constructor(entries: List<AutomaticProduction>) {
    val entries: List<AutomaticProduction> = Collections.unmodifiableList(ArrayList(entries.map { entry ->
        entry.copy(
            shipUnits = Collections.unmodifiableList(ArrayList(entry.shipUnits)),
            crewUnits = Collections.unmodifiableList(ArrayList(entry.crewUnits)),
            groundUnits = Collections.unmodifiableList(ArrayList(entry.groundUnits)),
        )
    }))
}

object AutomaticProductionSeedLoader {
    private const val FILE = "initial-deployment.csv"
    private const val COLUMNS = "星系名|惑星名|艦艇ユニット種別|乗組員ユニット種別|陸戦兵ユニット種別"
    private val header = listOf("page", "table", "columns", "values", "faction", "kind")
    private val integer = Regex("0|[1-9][0-9]*")

    /** The caller owns the stream; no snapshot is published until every record is validated. */
    fun read(stream: InputStream): AutomaticProductionSeed {
        val entries = mutableListOf<AutomaticProduction>()
        rows(stream).forEachIndexed { index, row ->
            val record = index + 2
            check(row[5] == "deployment" || row[5] == "automatic_production", record, "unknown kind")
            if (row[5] == "deployment") return@forEachIndexed
            val faction = when (row[4]) {
                "empire" -> SeedFaction.EMPIRE
                "alliance" -> SeedFaction.ALLIANCE
                else -> throw IllegalArgumentException("$FILE: record $record: unknown faction")
            }
            val page = number(row[0], record)
            val table = number(row[1], record)
            check(page in 76..78, record, "page outside verified automatic production table")
            check(table == if (faction == SeedFaction.EMPIRE) 0 else 1, record, "table faction mismatch")
            check(row[2] == COLUMNS, record, "unexpected columns")
            val cells = row[3].split('|')
            check(cells.size == 5, record, "unexpected value cell count")
            check(cells[0].isNotBlank() && cells[1].isNotBlank(), record, "missing place")
            val ships = items(cells[2], record)
            val crew = items(cells[3], record)
            val ground = items(cells[4], record)
            check(ships.isNotEmpty() || crew.isNotEmpty() || ground.isNotEmpty(), record, "empty production row")
            entries.add(AutomaticProduction(faction, table, cells[0], cells[1], ships, crew, ground,
                ManualSource(FILE, page, record)))
        }
        check(entries.isNotEmpty(), 1, "empty automatic production seed")
        return AutomaticProductionSeed(entries)
    }

    fun load(): AutomaticProductionSeed = requireNotNull(
        AutomaticProductionSeedLoader::class.java.getResourceAsStream("/worldseed/$FILE")
    ) { "Missing worldseed/$FILE" }.use { read(it) }

    // shortcut: InitialDeploymentSeedLoader.rows와 중복, PR50~53 병합 후 공용 helper로 합칠 수 있음
    private fun rows(stream: InputStream): List<List<String>> {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { java.io.InputStreamReader(stream, decoder).readText().removePrefix("\uFEFF") }
            catch (error: CharacterCodingException) {
                throw IllegalArgumentException("$FILE: record 1: invalid UTF-8", error)
            }
        val rows = StrictSeedCsv.parse(text, FILE)
        check(rows.firstOrNull() == header, 1, "unexpected header")
        check(rows.size > 1, 1, "empty seed")
        return rows.drop(1).also { data -> data.forEachIndexed { index, row ->
            check(row.size == header.size, index + 2, "unexpected column count")
        } }
    }

    private fun items(value: String, record: Int): List<String> {
        if (value.isEmpty()) return emptyList()
        return value.split(" / ").also { tokens ->
            check(tokens.all { it.isNotEmpty() && it == it.trim() && '/' !in it }, record, "invalid item list")
        }
    }

    private fun number(value: String, record: Int): Int {
        check(integer.matches(value), record, "invalid nonnegative integer")
        return requireNotNull(value.toIntOrNull()) { "$FILE: record $record: integer overflow" }
    }

    private fun check(valid: Boolean, record: Int, reason: String) {
        require(valid) { "$FILE: record $record: $reason" }
    }
}
