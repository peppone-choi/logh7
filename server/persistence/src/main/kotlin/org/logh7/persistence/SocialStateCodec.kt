package org.logh7.persistence

import org.logh7.engine.*

/** STOCK35 X, separate from S and the other extension grammars. Author: Peppone Choi. */
internal object SocialStateCodec {
    private fun times(values:Map<Int,Long>)=values.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    fun encode(s:SocialState):String {
        val base=listOf(s.nextId.toString(),times(s.speakers),times(s.squares),s.speeches.toSortedMap().values.joinToString(";") {r->
            listOf(r.id,r.character,r.power,r.base,r.spot,r.at,r.politics,r.beforeInfluence,r.influence,r.beforeApproval,r.approval,r.nextAt)
                .joinToString(":")}.ifEmpty {"-"})
        // Optional fields 5..9 are written up to the last one that differs from its default.
        val optional=listOf((s.academy!=AcademyState()) to {academy(s.academy)},(s.meeting!=MeetingState()) to {meeting(s.meeting)},
            (s.gatherings!=GatheringState()) to {gatherings(s.gatherings)},(s.proposals!=ProposalState()) to {proposals(s.proposals)},
            (s.justice!=JusticeState()) to {justice(s.justice)},(s.coup!=CoupState()) to {coup(s.coup)},(s.funds!=FundState()) to {funds(s.funds)},(s.goals!=GoalState()) to {goals(s.goals)},(s.trade!=TradeState()) to {trade(s.trade)},(s.pirates!=PirateState()) to {pirates(s.pirates)},(s.production!=ProductionState()) to {production(s.production)},(s.succession!=SuccessionState()) to {succession(s.succession)})
        val last=optional.indexOfLast {it.first}
        return (base+optional.take(last+1).map {it.second()}).joinToString("|")
    }
    /** Optional ninth field (U-25 逮捕·処断, 2026-11-01): permits "target:issuer:power:charge:until" /
     * rights "holder:target:issuer:until" / detentions "character:power:since:releaseAt:severe". */
    private fun justice(j:JusticeState):String {
        val parts=listOf(
            j.permits.toSortedMap().values.joinToString(",") {listOf(it.target,it.issuer,it.power,it.charge,it.until).joinToString(":")}.ifEmpty {"-"},
            j.rights.toSortedMap().values.joinToString(",") {listOf(it.holder,it.target,it.issuer,it.until).joinToString(":")}.ifEmpty {"-"},
            j.detentions.toSortedMap().values.joinToString(",") {listOf(it.character,it.power,it.since,it.releaseAt,if(it.severe)1 else 0).joinToString(":")}.ifEmpty {"-"},
            // Optional parts 4..7: watches, infiltrations, bombs, disabled bases (written up to the last non-empty one).
            j.watches.toSortedMap().values.joinToString(",") {listOf(it.watcher,it.target,it.since,it.checkAt).joinToString(":")}.ifEmpty {"-"},
            j.infiltrations.toSortedMap().values.joinToString(",") {listOf(it.character,it.base,it.spot,it.until).joinToString(":")}.ifEmpty {"-"},
            j.bombs.toSortedMap().values.joinToString(",") {listOf(it.character,it.base,it.spot,it.explodesAt).joinToString(":")}.ifEmpty {"-"},
            times(j.disabled),
            j.disguises.toSortedMap().values.joinToString(",") {listOf(it.character,it.alias,it.power,it.since,it.checkAt).joinToString(":")}.ifEmpty {"-"})
        val last=maxOf(2,parts.indexOfLast {it!="-"})
        return parts.take(last+1).joinToString("/")
    }
    private fun justice(text:String):JusticeState {
        val f=text.split('/');require(f.size in 3..8)
        val permits=linkedMapOf<Int,ArrestPermit>();for(v in rows(f[0])) {require(v.size==5)
            val x=ArrestPermit(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toLong());require(permits.put(x.target,x)==null)}
        val rights=linkedMapOf<Int,ArrestRight>();for(v in rows(f[1])) {require(v.size==4)
            val x=ArrestRight(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toLong());require(rights.put(x.holder,x)==null)}
        val detentions=linkedMapOf<Int,Detention>();for(v in rows(f[2])) {require(v.size==5 && v[4] in setOf("0","1"))
            val x=Detention(v[0].toInt(),v[1].toInt(),v[2].toLong(),v[3].toLong(),v[4]=="1");require(detentions.put(x.character,x)==null)}
        val watches=linkedMapOf<Int,Watch>();if(f.size==4)for(v in rows(f[3])) {require(v.size==4)
            val x=Watch(v[0].toInt(),v[1].toInt(),v[2].toLong(),v[3].toLong());require(watches.put(x.watcher,x)==null)}
        val infiltrations=linkedMapOf<Int,Infiltration>();if(f.size>=5)for(v in rows(f[4])) {require(v.size==4)
            val x=Infiltration(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toLong());require(infiltrations.put(x.character,x)==null)}
        val bombs=linkedMapOf<Int,Bomb>();if(f.size>=6)for(v in rows(f[5])) {require(v.size==4)
            val x=Bomb(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toLong());require(bombs.put(x.character,x)==null)}
        val disguises=linkedMapOf<Int,Disguise>();if(f.size==8)for(v in rows(f[7])) {require(v.size==5)
            val x=Disguise(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toLong(),v[4].toLong());require(disguises.put(x.character,x)==null)}
        return JusticeState(permits,rights,detentions,watches,infiltrations,bombs,if(f.size>=7)times(f[6]) else emptyMap(),disguises)
    }
    /** Optional eighth field (命令/提案, 2026-11-01): mails "id:sender:recipient:senderCard:card:command:order:created:expires:reply:pcp:mcp"
     * / refusal blocks "sender:recipient:command:until". */
    private fun proposals(p:ProposalState)=listOf(
        p.mails.sortedBy {it.id}.joinToString(",") {listOf(it.id,it.sender,it.recipient,it.senderCard,it.card,it.command,
            if(it.order)1 else 0,it.createdAt,it.expiresAt,it.reply,it.pcp,it.mcp).plus(if(it.target>0)listOf(it.target,it.targetCard) else emptyList())
            .plus(if(it.seat>0)listOf(it.seat) else emptyList())
            .joinToString(":")}.ifEmpty {"-"},
        p.blocked.entries.sortedWith(compareBy({it.key.first},{it.key.second},{it.key.third}))
            .joinToString(",") {"${it.key.first}:${it.key.second}:${it.key.third}:${it.value}"}.ifEmpty {"-"}).joinToString("/")
    private fun proposals(text:String):ProposalState {
        val f=text.split('/');require(f.size==2)
        val mails=rows(f[0]).map {v->require((v.size==12 || v.size in 14..15 && v[12].toInt()>0) && v[6] in setOf("0","1"))
            ProposalMail(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toInt(),v[6]=="1",
                v[7].toLong(),v[8].toLong(),v[9].toInt(),v[10].toInt(),v[11].toInt(),
                if(v.size>=14)v[12].toInt() else 0,if(v.size>=14)v[13].toInt() else 0,if(v.size==15)v[14].toInt().also {require(it>0)} else 0)}
        val blocked=linkedMapOf<Triple<Int,Int,Int>,Long>();for(v in rows(f[1])) {require(v.size==4)
            require(blocked.put(Triple(v[0].toInt(),v[1].toInt(),v[2].toInt()),v[3].toLong())==null)}
        return ProposalState(mails,blocked)
    }
    /** Optional seventh field (会談/談話, 2026-10-31): invites "host:guest:command:base:spot:expires" / host limits / pair limits. */
    private fun gatherings(g:GatheringState)=listOf(
        g.invites.toSortedMap().values.joinToString(",") {listOf(it.host,it.guest,it.command,it.base,it.spot,it.expiresAt).joinToString(":")}.ifEmpty {"-"},
        times(g.hostNextAt),
        g.pairNextAt.toSortedMap().entries.joinToString(",") {"${it.key ushr 32}:${it.key and 0xffffffffL}:${it.value}"}.ifEmpty {"-"}).joinToString("/")
    private fun gatherings(text:String):GatheringState {
        val f=text.split('/');require(f.size==3)
        val invites=linkedMapOf<Int,GatheringInvite>();for(v in rows(f[0])) {require(v.size==6)
            val i=GatheringInvite(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toLong());require(invites.put(i.host,i)==null)}
        val pairs=linkedMapOf<Long,Long>();for(v in rows(f[2])) {require(v.size==3);val a=v[0].toInt();val b=v[1].toInt();require(a<b)
            require(pairs.put(MeetingActions.pair(a,b),v[2].toLong())==null)}
        return GatheringState(invites,times(f[1]),pairs)
    }
    /** Optional sixth field (会見, 2026-10-30): friendship "low:high:value" / pair limits "low:high:at". */
    private fun meeting(m:MeetingState)=listOf(m.friendship.toSortedMap().entries.joinToString(",") {"${it.key ushr 32}:${it.key and 0xffffffffL}:${it.value}"}.ifEmpty {"-"},
        m.nextAt.toSortedMap().entries.joinToString(",") {"${it.key ushr 32}:${it.key and 0xffffffffL}:${it.value}"}.ifEmpty {"-"}).joinToString("/")
    private fun meeting(text:String):MeetingState {
        val f=text.split('/');require(f.size==2)
        fun key(v:List<String>):Long {require(v.size==3);val a=v[0].toInt();val b=v[1].toInt();require(a<b);return MeetingActions.pair(a,b)}
        val friendship=linkedMapOf<Long,Int>();for(v in rows(f[0]))require(friendship.put(key(v),v[2].toInt())==null)
        val nextAt=linkedMapOf<Long,Long>();for(v in rows(f[1]))require(nextAt.put(key(v),v[2].toLong())==null)
        return MeetingState(friendship,nextAt)
    }
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
    /** Optional tenth field (U-10 クーデター, 2026-11-01): plots "leader:power:since:revolt:consenting,..:members,..:bases,.."
     * joined by ';' (empty lists "-"), then '/' and outfit loyalty "outfit:value,..". */
    private fun ids(values:Collection<Int>)=values.joinToString(",").ifEmpty {"-"}
    private fun ids(text:String)=if(text=="-")emptyList() else text.split(',').map {it.toInt()}
    private fun coup(c:CoupState)=c.plots.toSortedMap().values.joinToString(";") {p->
        (listOf(p.leader,p.power,p.since,p.revolt,ids(p.consenting.sorted()),ids(p.members),ids(p.bases.sorted()))+
            (if(p.capitalSince>0)listOf(p.capitalSince) else emptyList())).joinToString(":")}
        .ifEmpty {"-"}+"/"+c.loyalty.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    private fun coup(text:String):CoupState {
        val (plots,loyalty)=text.split('/').also {require(it.size==2)}
        return CoupState(if(plots=="-")emptyMap() else plots.split(';').associate {row->
            val v=row.split(':');require(v.size in 7..8)
            v[0].toInt() to Plot(v[0].toInt(),v[1].toInt(),v[2].toLong(),ids(v[4]).toSet(),ids(v[5]),v[3].toLong(),ids(v[6]).toSet(),
                if(v.size==8)v[7].toLong().also {require(it>0)} else 0)},
            if(loyalty=="-")emptyMap() else loyalty.split(',').associate {e->e.split(':').let {(k,v)->k.toInt() to v.toInt()}})
    }
    /** Optional eleventh field (資金投入, 2026-11-01): confidence "power:for:against,.." then '/' and support "candidate:total,..". */
    private fun funds(f:FundState)=f.confidence.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value.first}:${it.value.second}"}
        .ifEmpty {"-"}+"/"+f.support.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    private fun funds(text:String):FundState {
        val (confidence,support)=text.split('/').also {require(it.size==2)}
        return FundState(if(confidence=="-")emptyMap() else confidence.split(',').associate {e->
            e.split(':').also {require(it.size==3)}.let {(k,a,b)->k.toInt() to (a.toLong() to b.toLong())}},
            if(support=="-")emptyMap() else support.split(',').associate {e->e.split(':').let {(k,v)->k.toInt() to v.toLong()}})
    }
    /** Optional twelfth field: national "country:base:setter:since,.." then '/' and focus "base:focus,.."; legacy 2/3 IDs are unchanged. */
    private fun goals(g:GoalState)=g.national.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value.base}:${it.value.setter}:${it.value.since}"}
        .ifEmpty {"-"}+"/"+g.focus.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    private fun goals(text:String):GoalState {
        val (national,focus)=text.split('/').also {require(it.size==2)}
        return GoalState(if(national=="-")emptyMap() else national.split(',').associate {e->
            e.split(':').also {require(it.size==4)}.let {v->v[0].toInt() to NationalGoal(v[1].toInt(),v[2].toInt(),v[3].toLong())}},
            if(focus=="-")emptyMap() else focus.split(',').associate {e->e.split(':').let {(k,v)->k.toInt() to v.toInt()}})
    }
    /** Optional thirteenth field (trade, 2026-11-02): routes "character:from:to:until:paidThrough,.." / tariffs / convoys /
     * pacts / passages, each "key:value,.." ("-" when empty). */
    private fun pairs(m:Map<Int,Long>)=m.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value}"}.ifEmpty {"-"}
    private fun pairs(text:String)=if(text=="-")emptyMap() else text.split(',').associate {e->e.split(':').let {(k,v)->k.toInt() to v.toLong()}}
    private fun trade(t:TradeState)=listOf(t.routes.toSortedMap().values.joinToString(",") {listOf(it.character,it.from,it.to,it.until,it.paidThrough)
        .joinToString(":")}.ifEmpty {"-"},pairs(t.tariffs.mapValues {it.value.toLong()}),pairs(t.convoys),pairs(t.pacts),pairs(t.passages))
        .plus(if(t.violations.isEmpty())emptyList() else listOf(pairs(t.violations),pairs(t.subsidies.mapValues {it.value.toLong()}))).joinToString("/")
    private fun trade(text:String):TradeState {
        val v=text.split('/');require(v.size==5 || v.size==7 && v[5]!="-")
        return TradeState(if(v[0]=="-")emptyMap() else v[0].split(',').associate {e->e.split(':').also {require(it.size==5)}.let {x->
            x[0].toInt() to TradeRoute(x[0].toInt(),x[1].toInt(),x[2].toInt(),x[3].toLong(),x[4].toLong())}},
            pairs(v[1]).mapValues {it.value.toInt()},pairs(v[2]),pairs(v[3]),pairs(v[4]),
            if(v.size==7)pairs(v[5]) else emptyMap(),if(v.size==7)pairs(v[6]).mapValues {it.value.toInt()} else emptyMap())
    }
    /** Optional fourteenth field (pirates, 2026-11-02): "nextId/nextSpawnAt/fleet;fleet" with fleet
     * "id:grid:since:unit=boats,..". */
    private fun pirates(p:PirateState)=listOf(p.nextId.toString(),p.nextSpawnAt.toString(),p.fleets.toSortedMap().values.joinToString(";") {f->
        listOf(f.id,f.grid,f.since).joinToString(":")+":"+f.units.joinToString(",") {"${it.id}=${it.boats}"}+(if(f.firedAt>0)":${f.firedAt}" else "")}.ifEmpty {"-"})
        .plus(if(p.harmedThrough>0)listOf(p.harmedThrough.toString()) else emptyList()).joinToString("/")
    private fun pirates(text:String):PirateState {
        val v=text.split('/');require(v.size in 3..4)
        return PirateState(if(v[2]=="-")emptyMap() else v[2].split(';').associate {row->val x=row.split(':');require(x.size in 4..5)
            x[0].toInt() to PirateFleet(x[0].toInt(),x[1].toInt(),x[2].toLong(),x[3].split(',').map {u->u.split('=').let {(id,b)->PirateUnit(id.toInt(),b.toInt())}},if(x.size==5)x[4].toLong().also {require(it>0)} else 0)},
            v[0].toInt(),v[1].toLong(),if(v.size==4)v[3].toLong().also {require(it>0)} else 0)
    }
    /** Optional fifteenth field (U-08, 2026-11-02): built "base:opensAt,.." / paused "base,.." / orders "base:kind:readyAt,..". */
    private fun production(p:ProductionState)=listOf(pairs(p.built),p.paused.sorted().joinToString(",").ifEmpty {"-"},
        p.orders.toSortedMap().entries.joinToString(",") {"${it.key}:${it.value.kind}:${it.value.readyAt}"}.ifEmpty {"-"})
        .plus(if(p.destroyed.isEmpty())emptyList() else listOf(p.destroyed.sorted().joinToString(","))).joinToString("/")
    private fun production(text:String):ProductionState {
        val v=text.split('/');require(v.size in 3..4)
        return ProductionState(pairs(v[0]),if(v[1]=="-")emptySet() else v[1].split(',').map {it.toInt()}.toSet(),
            if(v[2]=="-")emptyMap() else v[2].split(',').associate {e->e.split(':').also {require(it.size==3)}.let {x->x[0].toInt() to ShipOrder(x[1].toInt(),x[2].toLong())}},
            if(v.size==4)v[3].split(',').map {it.toInt()}.toSet() else emptySet())
    }
    /** Optional sixteenth field (2026-11-02): succession "heir:vacant:emperor:rival:at". */
    private fun succession(s:SuccessionState)=listOf(s.heir,if(s.vacant)1 else 0,s.emperor,s.rival,s.at).joinToString(":")
    private fun succession(text:String):SuccessionState {
        val v=text.split(':');require(v.size==5 && v[1] in setOf("0","1"))
        return SuccessionState(v[0].toInt(),v[1]=="1",v[2].toInt(),v[3].toInt(),v[4].toLong())
    }
    fun decode(text:String):SocialState {
        require(text.length<=1_000_000);val f=text.split('|');require(f.size in 4..16)
        val speeches=linkedMapOf<Int,SpeechReceipt>()
        if(f[3]!="-")for(row in f[3].split(';')) {
            val v=row.split(':');require(v.size==12)
            val r=SpeechReceipt(v[0].toInt(),v[1].toInt(),v[2].toInt(),v[3].toInt(),v[4].toInt(),v[5].toLong(),v[6].toInt(),
                v[7].toInt(),v[8].toInt(),v[9].toInt(),v[10].toInt(),v[11].toLong())
            require(speeches.put(r.character,r)==null)
        }
        return SocialState(f[0].toInt(),speeches,times(f[1]),times(f[2]),if(f.size>=5)academy(f[4]) else AcademyState(),
            if(f.size>=6)meeting(f[5]) else MeetingState(),if(f.size>=7)gatherings(f[6]) else GatheringState(),
            if(f.size>=8)proposals(f[7]) else ProposalState(),if(f.size>=9)justice(f[8]) else JusticeState(),
            if(f.size>=10)coup(f[9]) else CoupState(),if(f.size>=11)funds(f[10]) else FundState(),
            if(f.size>=12)goals(f[11]) else GoalState(),if(f.size>=13)trade(f[12]) else TradeState(),
            if(f.size>=14)pirates(f[13]) else PirateState(),if(f.size>=15)production(f[14]) else ProductionState(),if(f.size==16)succession(f[15]) else SuccessionState())
            .also {require(encode(it)==text) {"Noncanonical social state"}}
    }
}
