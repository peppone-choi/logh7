package org.logh7.persistence

import org.logh7.engine.*

/** STOCK35 X, separate from S and the other extension grammars. Author: 최병호. */
internal object SocialStateCodec {
    private fun times(values:Map<Int,Long>)=values.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    fun encode(s:SocialState)=listOf(s.nextId,times(s.speakers),times(s.squares),s.speeches.toSortedMap().values.joinToString(";") {r->
        listOf(r.id,r.character,r.power,r.base,r.spot,r.at,r.politics,r.beforeInfluence,r.influence,r.beforeApproval,r.approval,r.nextAt)
            .joinToString(":")}.ifEmpty {"-"}).joinToString("|")
    private fun times(text:String):Map<Int,Long> {
        val out=linkedMapOf<Int,Long>();if(text!="-")for(row in text.split(',')) {
            val f=row.split(':');require(f.size==2 && out.put(f[0].toInt(),f[1].toLong())==null)
        };return out
    }
    fun decode(text:String):SocialState {
        require(text.length<=1_000_000);val f=text.split('|');require(f.size==4)
        val speeches=linkedMapOf<Int,SpeechReceipt>()
        if(f[3]!="-")for(row in f[3].split(';')) {
            val v=row.split(':');require(v.size==12)
            val r=SpeechReceipt(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toLong(),v[6].toInt(),
                v[7].toInt(),v[8].toInt(),v[9].toInt(),v[10].toInt(),v[11].toLong())
            require(speeches.put(r.character,r)==null)
        }
        return SocialState(f[0].toInt(),speeches,times(f[1]),times(f[2])).also {require(encode(it)==text) {"Noncanonical social state"}}
    }
}
