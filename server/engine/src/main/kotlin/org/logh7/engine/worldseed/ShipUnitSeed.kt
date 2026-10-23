package org.logh7.engine.worldseed

import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

data class ShipUnitDefinition(
    val faction: SeedFaction,
    val sourceRow: Int,
    val unit: String,
    val stats: Map<String, String>,
    val verificationStatus: String,
    val source: ManualSource,
)

/** Source-preserving definitions only; names and statistical meanings are not inferred. */
class ShipUnitSeed internal constructor(units: List<ShipUnitDefinition>) {
    val units: List<ShipUnitDefinition> = Collections.unmodifiableList(ArrayList(units))
}

object ShipUnitSeedLoader {
    private const val FILE = "ship-units.csv"
    private val header = listOf(
        "faction", "page", "unit", "建造工期:建造工期", "必要乗組員ユニット数:必要乗組員ユニット数",
        "出力:出力", "索敵範囲（万km）:索敵範囲（万km）", "最高速度（km/1G秒）:最高速度（km/1G秒）",
        "装甲:前", "装甲:側", "装甲:後", "シールド（旗艦のみ）:防護値", "シールド（旗艦のみ）:容量",
        "ビーム兵装破壊力:ビーム兵装破壊力", "ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資",
        "ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資", "対空兵装破壊力:対空兵装破壊力",
        "戦闘艇/雷撃艇搭載数:戦闘艇/雷撃艇搭載数", "物資搭載量:物資搭載量", "修理消費物資(１隻):修理消費物資(１隻)",
        "source_row", "verification_status",
    )
    private val positive = Regex("[1-9][0-9]*")
    private val statistic = Regex("-|0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+|(?:0|[1-9][0-9]*)/(?:0|[1-9][0-9]*)")

    /** The caller owns the stream; every record is validated before publishing a snapshot. */
    fun read(stream: InputStream): ShipUnitSeed {
        val seen = mutableSetOf<Triple<SeedFaction, Int, Int>>()
        val units = rows(stream).mapIndexed { index, row ->
            val record = index + 2
            check(row.size == header.size, record, "unexpected column count")
            val faction = when (row[0]) {
                "empire" -> SeedFaction.EMPIRE
                "alliance" -> SeedFaction.ALLIANCE
                else -> throw IllegalArgumentException("$FILE: record $record: unknown faction")
            }
            val page = number(row[1], record, "page")
            // tools/manual_tables.py SHIP_PAGES and docs/manual/08 identify empire 79..89 / alliance 90..99.
            val pages = if (faction == SeedFaction.EMPIRE) 79..89 else 90..99
            check(page in pages, record, "page outside verified faction table")
            check(row[2].isNotBlank(), record, "missing unit")
            val stats = LinkedHashMap<String, String>()
            for (column in 3..19) {
                check(statistic.matches(row[column]), record, "invalid ${header[column]}")
                stats[header[column]] = row[column]
            }
            val sourceRow = number(row[20], record, "source_row")
            check(row[21] == "reviewed" || row[21] == "name_clipped", record, "unknown verification_status")
            check(seen.add(Triple(faction, page, sourceRow)), record, "duplicate source row")
            ShipUnitDefinition(faction, sourceRow, row[2], Collections.unmodifiableMap(LinkedHashMap(stats)),
                row[21], ManualSource(FILE, page, record))
        }
        return ShipUnitSeed(units)
    }

    fun load(): ShipUnitSeed = requireNotNull(
        ShipUnitSeedLoader::class.java.getResourceAsStream("/worldseed/$FILE")
    ) { "Missing worldseed/$FILE" }.use { read(it) }

    // shortcut: Organization/InitialDeployment/CrewUnitSeedLoader.rows와 중복, PR50~52 병합 후 공용 helper로 합칠 수 있음
    private fun rows(stream: InputStream): List<List<String>> {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { java.io.InputStreamReader(stream, decoder).readText().removePrefix("\uFEFF") }
            catch (error: CharacterCodingException) {
                throw IllegalArgumentException("$FILE: record 1: invalid UTF-8", error)
            }
        val rows = StrictSeedCsv.parse(text, FILE)
        check(rows.firstOrNull() == header, 1, "unexpected header")
        check(rows.size > 1, 1, "empty ship unit seed")
        return rows.drop(1)
    }

    private fun number(value: String, record: Int, column: String): Int {
        check(positive.matches(value), record, "invalid $column")
        return requireNotNull(value.toIntOrNull()) { "$FILE: record $record: invalid $column" }
    }

    private fun check(valid: Boolean, record: Int, reason: String) {
        require(valid) { "$FILE: record $record: $reason" }
    }
}
