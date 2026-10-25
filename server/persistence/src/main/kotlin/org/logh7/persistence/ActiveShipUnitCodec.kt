package org.logh7.persistence

import org.logh7.engine.ActiveShipUnit
import org.logh7.engine.ActiveShipUnits
import org.logh7.engine.UnitStack

/** Null denotes a legacy aggregate awaiting commissioning, zero an empty active roster. */
internal object ActiveShipUnitCodec {
    fun encode(units: ActiveShipUnits?): String = when {
        units == null -> "-"
        units.values.isEmpty() -> "0"
        else -> units.values.joinToString(",") { "${it.id}.${it.stack.kind}.${it.stack.level}" }
    }
    fun decode(value: String): ActiveShipUnits? = when (value) {
        "-" -> null
        "0" -> ActiveShipUnits(emptyList())
        else -> ActiveShipUnits(value.split(',').map { token ->
            val fields = token.split('.')
            require(fields.size == 3) { "Invalid active ship record" }
            ActiveShipUnit(fields[0].toInt(), UnitStack(fields[1].toInt(), fields[2].toInt()))
        })
    }
}
