package org.logh7.persistence

import org.logh7.engine.*

/** STOCK35 X, separate from S and the other extension grammars. Author: Peppone Choi. */
internal object SocialStateCodec {
    private fun times(values:Map<Int,Long>)=values.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    fun encode(s:SocialState)=(listOf(s.nextId,times(s.speakers),times(s.squares),s.speeches.toSortedMap().values.joinToString(";") {r->
        listOf(r.id,r.character,r.power,r.base,r.spot,r.at,r.politics,r.beforeInfluence,r.influence,r.beforeApproval,r.approval,r.nextAt)
            .joinToString(":")}.ifEmpty {"-"})+(if(s.academy==AcademyState())emptyList() else listOf(academy(s.academy)))).joinToString("|")
    /** Optional fifth field (academy, 2026-10-30): experience/lectures/attendance/wargames, '/' separated. */
    private fun academy(a:AcademyState)=listOf(
        a.experience.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value.joinToString(".")}"}.ifEmpty {"-"},
        a.lectures.toSortedMap().values.joinToString(",") {l->listOf(l.lecturer,l.base,l.spot,l.content,l.at).joinToString(":")}.ifEmpty {"-"},
        times(a.attendedUntil),
        a.wargames.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value.first}:${it.value.second}"}.ifEmpty {"-"}).joinToString("/")
    private fun rows(text:String)=if(text=="-")emptyList() else text.split(',').map {it.split(':')}
    private fun academy(text:String):AcademyState {
        val f=text.split('/');require(f.size==4)
        val experience=linkedMapOf<Int,List<Int>>();for(v in rows(f[0])) {require(v.size==2)
            require(experience.put(v[0].toInt(),v[1].split('.').map(String::toInt))==null)}
        val lectures=linkedMapOf<Int,Lecture>();for(v in rows(f[1])) {require(v.size==5)
            val at=v[4].toLong();require(lectures.put(v[0].toInt(),Lecture(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),at,at+AcademyActions.LECTURE_SECONDS))==null)}
        val wargames=linkedMapOf<Int,Pair<Long,Int>>();for(v in rows(f[3])) {require(v.size==3)
            require(wargames.put(v[0].toInt(),v[1].toLong() to v[2].toInt())==null)}
        return AcademyState(experience,lectures,times(f[2]),wargames)
    }
    private fun times(text:String):Map<Int,Long> {
        val out=linkedMapOf<Int,Long>();if(text!="-")for(row in text.split(',')) {
            val f=row.split(':');require(f.size==2 && out.put(f[0].toInt(),f[1].toLong())==null)
        };return out
    }
    fun decode(text:String):SocialState {
        require(text.length<=1_000_000);val f=text.split('|');require(f.size==4 || f.size==5)
        val speeches=linkedMapOf<Int,SpeechReceipt>()
        if(f[3]!="-")for(row in f[3].split(';')) {
            val v=row.split(':');require(v.size==12)
            val r=SpeechReceipt(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toLong(),v[6].toInt(),
                v[7].toInt(),v[8].toInt(),v[9].toInt(),v[10].toInt(),v[11].toLong())
            require(speeches.put(r.character,r)==null)
        }
        return SocialState(f[0].toInt(),speeches,times(f[1]),times(f[2]),if(f.size==5)academy(f[4]) else AcademyState())
            .also {require(encode(it)==text) {"Noncanonical social state"}}
    }
}
