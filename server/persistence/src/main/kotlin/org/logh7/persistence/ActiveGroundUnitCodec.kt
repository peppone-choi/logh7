package org.logh7.persistence

import org.logh7.engine.ActiveGroundUnit
import org.logh7.engine.ActiveGroundUnits
import org.logh7.engine.UnitStack

/** Durable identities; carrier/base zero keeps unresolved legacy allocation explicit. */
internal object ActiveGroundUnitCodec {
    fun encode(units: ActiveGroundUnits?): String = when {
        units == null -> "-"
        units.values.isEmpty() -> "0"
        else -> units.values.joinToString(",") {unit ->
            val prefix="${unit.id}~${unit.stack.kind}~${unit.stack.level}~${unit.carrier}~${unit.base}"
            if(unit.origin==0 && unit.order==null && unit.strength==1000)prefix else prefix+"~${unit.origin}~"+
                (unit.order?.let {o->listOf(o.action.ordinal,o.character,o.grid,o.carrier,o.base,o.acceptedAt,o.readyAt).joinToString(":")} ?: "-")+
                (if(unit.strength==1000)"" else "~${unit.strength}")
        }
    }
    fun decode(text: String, deployments:Boolean=false,combat:Boolean=false): ActiveGroundUnits? {
        if (text == "-") return null
        if (text == "0") return ActiveGroundUnits(emptyList())
        val units = ActiveGroundUnits(text.split(',').map { row ->
            val fields = row.split('~')
            require(fields.size == 5 || deployments && fields.size==7 || combat && fields.size==8) { "Invalid ground unit row" }
            val n = fields.take(5).map(String::toInt)
            val order=if(fields.size>=7 && fields[6]!="-")fields[6].split(':').also {require(it.size==7)}.let {
                org.logh7.engine.TacticalGroundOrder(org.logh7.engine.TacticalGroundAction.entries[it[0].toInt()],
                    it[1].toInt(),it[2].toInt(),it[3].toInt(),it[4].toInt(),it[5].toLong(),it[6].toLong())
            } else null
            ActiveGroundUnit(n[0], UnitStack(n[1], n[2]), n[3], n[4],if(fields.size>=7)fields[5].toInt() else 0,order,if(fields.size==8)fields[7].toInt() else 1000)
        })
        require(encode(units) == text) { "Noncanonical ground unit row" }
        return units
    }
}
