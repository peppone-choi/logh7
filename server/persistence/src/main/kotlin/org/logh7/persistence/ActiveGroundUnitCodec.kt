package org.logh7.persistence

import org.logh7.engine.ActiveGroundUnit
import org.logh7.engine.ActiveGroundUnits
import org.logh7.engine.UnitStack

/** Durable identities; carrier/base zero keeps unresolved legacy allocation explicit. */
internal object ActiveGroundUnitCodec {
    fun encode(units: ActiveGroundUnits?): String = when {
        units == null -> "-"
        units.values.isEmpty() -> "0"
        else -> units.values.joinToString(",") { "${it.id}~${it.stack.kind}~${it.stack.level}~${it.carrier}~${it.base}" }
    }
    fun decode(text: String): ActiveGroundUnits? {
        if (text == "-") return null
        if (text == "0") return ActiveGroundUnits(emptyList())
        val units = ActiveGroundUnits(text.split(',').map { row ->
            val fields = row.split('~')
            require(fields.size == 5) { "Invalid ground unit row" }
            val n = fields.map(String::toInt)
            ActiveGroundUnit(n[0], UnitStack(n[1], n[2]), n[3], n[4])
        })
        require(encode(units) == text) { "Noncanonical ground unit row" }
        return units
    }
}
