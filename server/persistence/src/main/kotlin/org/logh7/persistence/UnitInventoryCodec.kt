package org.logh7.persistence

import org.logh7.engine.UnitInventory
import org.logh7.engine.UnitStack

/** Compact bounded text; a partial/corrupt inventory is never replaced with an empty one. */
internal object UnitInventoryCodec {
    fun encode(value: UnitInventory): String {
        fun stacks(prefix: String, rows: Map<UnitStack, Int>) = rows.entries.sortedWith(compareBy({ it.key.kind }, { it.key.level }))
            .joinToString(",") { "$prefix${it.key.kind}.${it.key.level}.${it.value}" }
        return listOf(stacks("S", value.ships), stacks("T", value.troops),
            value.work.toSortedMap().entries.joinToString(",") { "${it.key}.${it.value}" }).joinToString(";")
    }
    fun decode(text: String): UnitInventory {
        require(text.length <= 10000)
        val groups = text.split(';'); require(groups.size == 3)
        fun stacks(prefix: Char, group: String): Map<UnitStack, Int> {
            val rows = linkedMapOf<UnitStack, Int>()
            if (group.isEmpty()) return rows
            group.split(',').forEach { token ->
                val parts = token.split('.'); require(parts.size == 3 && parts[0].first() == prefix)
                val key = UnitStack(parts[0].drop(1).toInt(), parts[1].toInt())
                require(rows.put(key, parts[2].toInt()) == null)
            }
            return rows
        }
        val work = linkedMapOf<String, Int>()
        if (groups[2].isNotEmpty()) groups[2].split(',').forEach { token ->
            val parts = token.split('.'); require(parts.size == 2)
            require(work.put(parts[0], parts[1].toInt()) == null)
        }
        return UnitInventory(stacks('S', groups[0]), stacks('T', groups[1]), work).also {
            require(encode(it) == text) { "Noncanonical inventory encoding" }
        }
    }
}
