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
            if(it.attack!=null) ".${it.tactical?.let(NpcTacticalCodec::encode) ?: "-"}.${it.supplies ?: "-"}.${it.damaged}.${it.support?.let(TacticalSupportCodec::encode) ?: "-"}.${it.motionCommander}.${checkNotNull(it.attack).let {a->listOf(a.commander,a.order.mode,a.order.acceptedAt,a.order.startsAt,a.order.nextAt).joinToString("~")}}"
            else if(it.motionCommander!=0) ".${it.tactical?.let(NpcTacticalCodec::encode) ?: "-"}.${it.supplies ?: "-"}.${it.damaged}.${it.support?.let(TacticalSupportCodec::encode) ?: "-"}.${it.motionCommander}"
            else if(it.support!=null || it.damaged>0) ".${it.tactical?.let(NpcTacticalCodec::encode) ?: "-"}.${it.supplies ?: "-"}.${it.damaged}.${it.support?.let(TacticalSupportCodec::encode) ?: "-"}"
            else if(it.supplies!=null) ".${it.tactical?.let(NpcTacticalCodec::encode) ?: "-"}.${it.supplies}"
            else (it.tactical?.let {s->"."+NpcTacticalCodec.encode(s)} ?: "") }
    }
    fun decode(value: String, legacyFull: Boolean = true, tactics:Boolean=false, cargo:Boolean=false, support:Boolean=false, commanded:Boolean=false, attacks:Boolean=false): ActiveShipUnits? = when (value) {
        "-" -> null
        "0" -> ActiveShipUnits(emptyList())
        else -> ActiveShipUnits(value.split(',').map { token ->
            val fields = token.split('.')
            require(fields.size == 4 || legacyFull && fields.size == 3 || tactics && fields.size==5 || cargo && fields.size==6 || support && fields.size==8 || commanded && fields.size==9 || attacks && fields.size==10) { "Invalid active ship record" }
            val stack=UnitStack(fields[1].toInt(),fields[2].toInt())
            if(fields.size==3) ActiveShipUnit(fields[0].toInt(),stack)
            else ActiveShipUnit(fields[0].toInt(),stack,fields[3].toInt(),
                if(fields.size>=5 && fields[4]!="-")NpcTacticalCodec.decode(fields[4]) else null,
                if(fields.size>=6 && fields[5]!="-")fields[5].toInt() else null,
                if(fields.size>=8)fields[6].toInt() else 0,
                if(fields.size>=8 && fields[7]!="-")TacticalSupportCodec.decode(fields[7]) else null,
                if(fields.size>=9)fields[8].toInt().also {require(it>=0 && (fields.size!=9 || it>0))} else 0,
                if(fields.size==10)fields[9].split('~').also {require(it.size==5)}.let {
                    org.logh7.engine.TacticalUnitAttack(it[0].toInt(),org.logh7.engine.TacticalAttackOrder(
                        it[1].toInt(),it[2].toLong(),it[3].toLong(),it[4].toLong()))} else null).also {
                require(fields.size!=5 || fields[4]!="-") { "Empty NPC field in legacy record" }
                require(fields.size!=6 || fields[5]!="-") { "Empty cargo field in cargo record" }
            }
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
