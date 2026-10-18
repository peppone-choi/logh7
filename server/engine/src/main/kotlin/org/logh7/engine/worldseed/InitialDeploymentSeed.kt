package org.logh7.engine.worldseed

import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

enum class DeploymentUnitKind { FLEET, PATROL, GROUND }

data class InitialDeployment(
    val faction: SeedFaction,
    val unitKind: DeploymentUnitKind,
    val table: Int,
    val unitLabel: String,
    val starSystem: String,
    val location: String,
    val source: ManualSource,
)

/** Original manual definitions only; ranges and place names are not interpreted. */
class InitialDeploymentSeed internal constructor(entries: List<InitialDeployment>) {
    val entries: List<InitialDeployment> = Collections.unmodifiableList(ArrayList(entries))
}

object InitialDeploymentSeedLoader {
    private const val FILE = "initial-deployment.csv"
    private val header = listOf("page", "table", "columns", "values", "faction", "kind")
    private val integer = Regex("0|[1-9][0-9]*")
    private val factions = listOf(SeedFaction.EMPIRE, SeedFaction.ALLIANCE, SeedFaction.EMPIRE,
        SeedFaction.ALLIANCE, SeedFaction.ALLIANCE, SeedFaction.EMPIRE)
    private val kinds = listOf(DeploymentUnitKind.FLEET, DeploymentUnitKind.FLEET,
        DeploymentUnitKind.PATROL, DeploymentUnitKind.PATROL, DeploymentUnitKind.GROUND, DeploymentUnitKind.GROUND)
    private val columns = listOf("艦隊番号|星系|惑星/要塞", "艦隊番号|星系|惑星/要塞",
        "巡察隊番号|ユニット配置星系|ユニット配置惑星", "巡察隊番号|ユニット配置星系|ユニット配置惑星",
        "地上部隊番号|ユニット配置星系|ユニット配置惑星", "地上部隊番号|ユニット配置星系|ユニット配置惑星")

    /** The caller owns the stream; no snapshot is published until every record is validated. */
    fun read(stream: InputStream): InitialDeploymentSeed {
        val entries = mutableListOf<InitialDeployment>()
        val seen = mutableSetOf<Triple<SeedFaction, DeploymentUnitKind, String>>()
        rows(stream).forEachIndexed { index, row ->
            val record = index + 2
            check(row[5] == "deployment" || row[5] == "automatic_production", record, "unknown kind")
            val faction = when (row[4]) {
                "empire" -> SeedFaction.EMPIRE
                "alliance" -> SeedFaction.ALLIANCE
                else -> throw IllegalArgumentException("$FILE: record $record: unknown faction")
            }
            val page = number(row[0], record)
            val table = number(row[1], record)
            if (row[5] == "automatic_production") {
                check(page in 76..78, record, "page outside verified automatic production table")
            } else {
                check(page == 75, record, "page outside verified deployment table")
                check(table in 0..5, record, "unknown deployment table")
                check(faction == factions[table], record, "table faction mismatch")
                check(row[2] == columns[table], record, "unexpected columns")
                val cells = row[3].split('|')
                check(cells.size == 3, record, "unexpected value cell count")
                check(cells[0].isNotBlank(), record, "missing unit label")
                check(cells[1].isBlank() == cells[2].isBlank(), record, "incomplete location")
                check(seen.add(Triple(faction, kinds[table], cells[0])), record, "duplicate contextual deployment")
                entries.add(InitialDeployment(faction, kinds[table], table, cells[0], cells[1], cells[2],
                    ManualSource(FILE, page, record)))
            }
        }
        check(entries.isNotEmpty(), 1, "empty deployment seed")
        return InitialDeploymentSeed(entries)
    }

    fun load(): InitialDeploymentSeed = requireNotNull(
        InitialDeploymentSeedLoader::class.java.getResourceAsStream("/worldseed/$FILE")
    ) { "Missing worldseed/$FILE" }.use { read(it) }

    // shortcut: OrganizationSeedLoader.rows와 중복, PR50 병합 후 공용 helper로 합칠 수 있음
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

    private fun number(value: String, record: Int): Int {
        check(integer.matches(value), record, "invalid nonnegative integer")
        return requireNotNull(value.toIntOrNull()) { "$FILE: record $record: integer overflow" }
    }

    private fun check(valid: Boolean, record: Int, reason: String) {
        require(valid) { "$FILE: record $record: $reason" }
    }
}
