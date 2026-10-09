package org.logh7.engine.strategy

import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Properties

enum class CommandGroup(val sourceName: String, val key: String) {
    OPERATION("作戦コマンド", "operation"), PERSONAL("個人コマンド", "personal"),
    COMMAND("指揮コマンド", "command"), LOGISTICS("兵站コマンド", "logistics"),
    PERSONNEL("人事コマンド", "personnel"), POLITICS("政治コマンド", "politics"),
    INTELLIGENCE("諜報コマンド", "intelligence"),
}
enum class ManualStatus { DOCUMENTED, UNIMPLEMENTED, UNKNOWN, PATCH }
sealed interface TableValue {
    data class Fixed(val value: Long) : TableValue
    data class Range(val minimum: Long, val maximum: Long) : TableValue
    data class DistanceProportional(val reference: Long?) : TableValue
    data object Unspecified : TableValue
}
data class CommandDefinition(
    val id: String, val group: CommandGroup, val sourceName: String,
    val rawCp: String, val rawWait: String, val rawDuration: String,
    val cp: TableValue, val wait: TableValue, val duration: TableValue,
    val page: Int, val sourceRecord: Int, val manualStatus: ManualStatus, val rawStatus: String,
)

class StrategyCatalog(definitions: List<CommandDefinition>) {
    val commands: List<CommandDefinition> = Collections.unmodifiableList(ArrayList(definitions))
    val byId: Map<String, CommandDefinition> = Collections.unmodifiableMap(commands.associateBy { it.id })
    init { require(commands.isNotEmpty() && byId.size == commands.size) { "Duplicate/empty command registry" } }
}
data class StrategyRules(val maximumCards: Int, val substitutionMultiplier: Long) {
    init { require(maximumCards == 16 && substitutionMultiplier == 2L) { "Only confirmed W27 rules are supported" } }
}
data class StrategyDefinitions(val catalog: StrategyCatalog, val rules: StrategyRules)

object StrategyCatalogLoader {
    fun load(): StrategyDefinitions {
        fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) { "Missing $path" }
        return resource("/strategy/strategy-commands.csv").use { csv ->
            resource("/rules/strategy.properties").use { rules -> read(csv, rules) }
        }
    }
    fun read(csv: InputStream, metadata: InputStream): StrategyDefinitions {
        fun text(input: InputStream): String = java.io.InputStreamReader(input,
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)).readText().removePrefix("\uFEFF")
        val properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                require(!containsKey(key)) { "Duplicate strategy rule $key" }
                return super.put(key, value)
            }
        }.also { it.load(text(metadata).reader()) }
        fun rule(key: String) = requireNotNull(properties.getProperty(key)) { "Missing strategy rule $key" }
        val rules = StrategyRules(rule("card.maximum").toInt(), rule("cp.substitution_multiplier").toLong())
        val rows = csvRows(text(csv))
        require(rows.firstOrNull() == listOf("group", "command", "cp", "wait", "duration", "page"))
        val expectedKeys = mutableSetOf("card.maximum", "cp.substitution_multiplier")
        val definitions = rows.drop(1).mapIndexed { index, row ->
            require(row.size == 6) { "strategy-commands.csv record ${index + 2}: wrong columns" }
            val group = requireNotNull(CommandGroup.entries.find { it.sourceName == row[0] }) { "Unknown command group" }
            require(row[1].isNotBlank())
            val id = "${group.key}/${row[1]}" // Stable semantic ID, not a guessed client opcode.
            val statusKey = "status.$id"; expectedKeys += statusKey
            val status = rule(statusKey).split('|', limit = 2)
            require(status.size == 2 && status[1].isNotBlank())
            fun number(raw: String): TableValue {
                if (raw.isEmpty()) return TableValue.Unspecified
                fun integer(s: String): Long { require(s.matches(Regex("0|[1-9][0-9]*"))); return s.toLong() }
                val parts = raw.split('～')
                return when (parts.size) {
                    1 -> TableValue.Fixed(integer(parts[0]))
                    2 -> TableValue.Range(integer(parts[0]), integer(parts[1])).also { require(it.minimum <= it.maximum) }
                    else -> error("Invalid table value $raw")
                }
            }
            fun value(column: String, raw: String): TableValue {
                val parsed = number(raw); val distanceKey = "distance.$id.$column"
                if (!properties.containsKey(distanceKey)) return parsed
                expectedKeys += distanceKey; require(rule(distanceKey) == "true")
                require(parsed is TableValue.Fixed || parsed == TableValue.Unspecified)
                return TableValue.DistanceProportional((parsed as? TableValue.Fixed)?.value)
            }
            val page = row[5].toInt(); require(page in 68..74)
            CommandDefinition(id, group, row[1], row[2], row[3], row[4], value("cp", row[2]),
                value("wait", row[3]), value("duration", row[4]), page, index + 2,
                ManualStatus.valueOf(status[0]), status[1])
        }
        require(properties.stringPropertyNames() == expectedKeys) { "Unknown/unreferenced strategy rules" }
        return StrategyDefinitions(StrategyCatalog(definitions), rules)
    }

    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var closed = false; var at = 0
        fun cell() { row += field.toString(); field.clear(); closed = false }
        fun record() { cell(); rows += row; row = mutableListOf() }
        while (at < text.length) {
            val c = text[at++]
            if (quoted) {
                if (c != '"') field.append(c)
                else if (at < text.length && text[at] == '"') { field.append('"'); at++ }
                else { quoted = false; closed = true }
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed); quoted = true }
                ',' -> cell()
                '\n' -> record()
                '\r' -> { require(at < text.length && text[at++] == '\n'); record() }
                else -> { require(!closed) { "Text after closing CSV quote" }; field.append(c) }
            }
        }
        require(!quoted) { "Unclosed CSV quote" }
        if (closed || field.isNotEmpty() || row.isNotEmpty()) record()
        return rows
    }
}
