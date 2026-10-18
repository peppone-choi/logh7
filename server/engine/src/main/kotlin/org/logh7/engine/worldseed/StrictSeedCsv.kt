package org.logh7.engine.worldseed

/** CSV quoting, doubled quotes and CRLF/LF; blank records and quote recovery are rejected. */
internal object StrictSeedCsv {
    fun parse(text: String, source: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var started = false
        var index = 0
        fun fail(): Nothing = throw IllegalArgumentException("$source: record ${rows.size + 1}: malformed CSV")
        fun endField() {
            row.add(field.toString()); field.setLength(0); closedQuote = false; started = false
        }
        fun endRow() {
            endField()
            if (row.size == 1 && row[0].isEmpty()) fail()
            rows.add(row); row = mutableListOf()
        }
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closedQuote = true }
                } else field.append(char)
                continue
            }
            when (char) {
                '"' -> { if (started || closedQuote) fail(); quoted = true; started = true }
                ',' -> endField()
                '\n' -> endRow()
                '\r' -> { if (index >= text.length || text[index++] != '\n') fail(); endRow() }
                else -> { if (closedQuote) fail(); started = true; field.append(char) }
            }
        }
        if (quoted) fail()
        if (started || closedQuote || row.isNotEmpty()) endRow()
        return rows
    }
}
