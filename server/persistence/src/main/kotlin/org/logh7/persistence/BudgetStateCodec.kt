package org.logh7.persistence

import org.logh7.engine.*

/** STOCK34 B: versioned independently from S/C/M/F/V. Author: Peppone Choi. */
internal object BudgetStateCodec {
    fun encode(s:BudgetState)=listOf(s.nextId,s.remittanceRates.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"},
        s.quotes.toSortedMap().values.joinToString(";") {q->listOf(q.id,q.character,q.command,q.card,q.power,q.base,q.rate,q.beforeRate,
            q.national,q.local,q.amount,q.createdAt,q.expiresAt,q.phase.ordinal).joinToString(":")}.ifEmpty {"-"}).joinToString("|")
    fun decode(text:String):BudgetState {
        require(text.length<=1_000_000);val f=text.split('|');require(f.size==3)
        val rates=linkedMapOf<Int,Int>();if(f[1]!="-")for(row in f[1].split(',')) {
            val v=row.split(':');require(v.size==2 && rates.put(v[0].toInt(),v[1].toInt())==null)
        }
        val quotes=linkedMapOf<Int,BudgetQuote>();if(f[2]!="-")for(row in f[2].split(';')) {
            val v=row.split(':');require(v.size==14)
            val q=BudgetQuote(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toInt(),v[6].toInt(),v[7].toInt(),
                v[8].toLong(),v[9].toLong(),v[10].toLong(),v[11].toLong(),v[12].toLong(),requireNotNull(BudgetPhase.entries.getOrNull(v[13].toInt())))
            require(quotes.put(q.character,q)==null)
        }
        return BudgetState(rates,f[0].toInt(),quotes).also {require(encode(it)==text) {"Noncanonical budget state"}}
    }
}
