package org.logh7.persistence

import org.logh7.engine.*
import java.util.Base64

/** STOCK33 V section; old S/C/M/F grammars are unchanged. Author: Peppone Choi. */
internal object CivicStateCodec {
    fun encode(state:CivicState)=state.orders.toSortedMap().values.joinToString(";") {o->
        listOf(o.character,o.command,o.card,o.power,o.rank,o.base,o.outfit,o.spot,o.acceptedAt,o.readyAt,o.phase.ordinal,
            o.units.sorted().joinToString(",").ifEmpty {"-"},o.effect,o.token,
            Base64.getUrlEncoder().withoutPadding().encodeToString(o.connection.toByteArray(Charsets.UTF_8)).ifEmpty {"-"}).joinToString(":")
    }.ifEmpty {"-"}
    fun decode(text:String):CivicState {
        require(text.length<=1_000_000)
        val orders=linkedMapOf<Int,CivicOrder>()
        if(text!="-")for(row in text.split(';')) {
            val f=row.split(':');require(f.size==15)
            val connection=if(f[14]=="-")"" else String(Base64.getUrlDecoder().decode(f[14]),Charsets.UTF_8)
            val o=CivicOrder(f[0].toInt(),f[1].toInt(),f[2].toInt(),f[3].toInt(),f[4].toInt(),f[5].toInt(),f[6].toInt(),
                f[7].toInt(),f[8].toLong(),f[9].toLong(),requireNotNull(CivicPhase.entries.getOrNull(f[10].toInt())),
                if(f[11]=="-")emptySet() else f[11].split(',').map(String::toInt).toSet(),f[12].toInt(),f[13].toInt(),connection)
            require(orders.put(o.character,o)==null)
        }
        return CivicState(orders).also {require(encode(it)==text) {"Noncanonical civic state"}}
    }
}
