package org.logh7.persistence

import org.logh7.engine.ActiveShipUnit
import org.logh7.engine.ActiveShipUnits
import org.logh7.engine.UnitStack

/** Null denotes a legacy aggregate awaiting commissioning, zero an empty active roster. */
internal object ActiveShipUnitCodec {
    fun encode(units: ActiveShipUnits?): String = when {
        units == null -> "-"
        units.values.isEmpty() -> "0"
        else -> units.values.joinToString(",") { "${it.id}.${it.stack.kind}.${it.stack.level}.${it.boats}"+
            (it.tactical?.let {s->"."+NpcTacticalCodec.encode(s)} ?: "") }
    }
    fun decode(value: String, legacyFull: Boolean = true, tactics:Boolean=false): ActiveShipUnits? = when (value) {
        "-" -> null
        "0" -> ActiveShipUnits(emptyList())
        else -> ActiveShipUnits(value.split(',').map { token ->
            val fields = token.split('.')
            require(fields.size == 4 || legacyFull && fields.size == 3 || tactics && fields.size==5) { "Invalid active ship record" }
            val stack=UnitStack(fields[1].toInt(),fields[2].toInt())
            if(fields.size==3) ActiveShipUnit(fields[0].toInt(),stack)
            else ActiveShipUnit(fields[0].toInt(),stack,fields[3].toInt(),if(fields.size==5)NpcTacticalCodec.decode(fields[4]) else null)
        })
    }
}

internal object SpareShipBoatCodec {
    fun encode(value: org.logh7.engine.SpareShipBoats): String = value.values.entries
        .sortedWith(compareBy({it.key.kind},{it.key.level}))
        .joinToString(",") { "${it.key.kind}.${it.key.level}.${it.value}" }.ifEmpty { "-" }
    fun decode(text: String): org.logh7.engine.SpareShipBoats {
        require(text.length<=4000)
        val values=linkedMapOf<UnitStack,Int>()
        if(text!="-") text.split(',').forEach { token ->
            val f=token.split('.');require(f.size==3)
            require(values.put(UnitStack(f[0].toInt(),f[1].toInt()),f[2].toInt())==null)
        }
        return org.logh7.engine.SpareShipBoats(values).also {require(encode(it)==text)}
    }
}
