package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * Imperial succession and the succession war (user decision 2026-11-02: designated heir then peerage, immediate
 * accession, the runner-up may refuse and rise). The CD manual p.38 only names the politician ladder; this order, the
 * 後継指名 233 / 継承拒否 234 commands, their costs and the [CONTEST_DAYS] window are evidence:guess.
 * Order: the designated heir if alive, then peerage (公爵 first, none last), influence, merit and the lowest ID, among
 * living, free empire characters outside a revolt. A refusal turns the runner-up into the 盟主 of a revolt (CoupActions):
 * holding the capital for CoupActions.CAPITAL_DAYS then crowns the claimant.
 * Author: Peppone Choi.
 */
data class ThroneState(
    /** 後継指名: the emperor's designated heir; 0 none. */
    val heir:Int=0,
    /** The throne was vacated by death or removal; the next settlement crowns the first in line. */
    val vacant:Boolean=false,
    /** The last accession: emperor, runner-up who may refuse, and the game second it happened. */
    val emperor:Int=0,val rival:Int=0,val at:Long=0) {
    init {require(heir>=0 && emperor>=0 && rival>=0 && at in 0..0xffffffffL && (rival==0 || emperor>0))}
}

/** The five legacy fields belong only to country 2. Other countries have exactly one keyed record. */
data class SuccessionState(val heir:Int=0,val vacant:Boolean=false,val emperor:Int=0,val rival:Int=0,val at:Long=0,
    val countries:Map<CountryId,ThroneState> = emptyMap()) {
    init {ThroneState(heir,vacant,emperor,rival,at);require(countries.size<=4096 && countries.all {(id,s)->id!=CountryId(2) && s!=ThroneState()})}
    fun of(country:CountryId)=if(country==CountryId(2))ThroneState(heir,vacant,emperor,rival,at) else countries[country] ?: ThroneState()
    fun with(country:CountryId,state:ThroneState)=if(country==CountryId(2))copy(heir=state.heir,vacant=state.vacant,emperor=state.emperor,rival=state.rival,at=state.at)
        else copy(countries=if(state==ThroneState())countries-country else countries+(country to state))
    fun all()=countries+(CountryId(2) to of(CountryId(2)))
    fun anyVacant()=vacant || countries.values.any {it.vacant}
    fun detached()=copy(countries=java.util.Collections.unmodifiableMap(LinkedHashMap(countries)))
}

/** A head-of-state change the whole session hears, detected between two published stocks (evidence:guess). */
data class SuccessionNotice(val generation:Long,val character:Int,val kind:Kind,val seconds:Long=0,
    val country:CountryId=CountryId(if(kind in setOf(Kind.CHAIRMAN_ELECTED,Kind.CHAIRMAN_DISMISSED))3 else 2)) {
    enum class Kind {ACCESSION,CONTEST,CHAIRMAN_ELECTED,CHAIRMAN_DISMISSED}
}

object SuccessionActions {
    /** Accession, the runner-up's refusal, and the alliance chairman's election or no-confidence dismissal. */
    fun notices(old:PlanetStocks,new:PlanetStocks,seconds:Long=0,catalog:CountryCatalog=CountryCatalogLoader.current()):List<SuccessionNotice> {
        if(old.generation!=new.generation)return emptyList()
        val out=mutableListOf<SuccessionNotice>()
        for(id in catalog.countries.keys.sorted()) {
            val country=CountryId(id)
            fun add(character:Int,kind:SuccessionNotice.Kind) {out+=SuccessionNotice(new.generation,character,kind,seconds,country)}
            when(OrganizationOffices.system(country,catalog)) {
                2 -> {
                    val was=old.social.succession.of(country);val now=new.social.succession.of(country)
                    if(now.emperor>0 && (now.at!=was.at || now.emperor!=was.emperor))add(now.emperor,SuccessionNotice.Kind.ACCESSION)
                    if(was.rival>0 && now.rival==0 && CoupActions.rebel(new,was.rival,country) && !CoupActions.rebel(old,was.rival,country))
                        add(was.rival,SuccessionNotice.Kind.CONTEST)
                }
                3 -> {
                    fun chairmen(s:PlanetStocks)=s.strategic.offices.filter {it.country==country && it.card==PoliticianLadder.CHAIRMAN_CARD}.map {it.character}.toSet()
                    val former=chairmen(old)
                    val current=chairmen(new)
                    val incoming=current-former
                    val coupSuccess=incoming.any {leader->old.social.coup.plots[leader]?.let {
                        it.country==country && it.revolt>0 && leader !in new.social.coup.plots
                    }==true}
                    val (yes,no)=old.social.funds.confidence[id] ?: (0L to 0L)
                    if(!coupSuccess && no>yes && id !in new.social.funds.confidence)
                        (former-current).forEach {add(it,SuccessionNotice.Kind.CHAIRMAN_DISMISSED)}
                    incoming.forEach {add(it,SuccessionNotice.Kind.CHAIRMAN_ELECTED)}
                }
            }
        }
        return out
    }
    const val DESIGNATE=233
    const val CONTEST=234
    val commands=setOf(DESIGNATE,CONTEST)
    const val DESIGNATE_COST=160L
    const val CONTEST_COST=640L
    const val CONTEST_DAYS=7L
    private const val EMPEROR=1

    fun alive(s:PlanetStocks,id:Int)=(s.strategic.people[id]?.deceasedAt ?: 0)==0L
    private fun eligible(s:PlanetStocks,identity:StrategicIdentity,country:CountryId)=identity.country==country &&
        alive(s,identity.id) && !s.social.justice.detained(identity.id) && !CoupActions.rebel(s,identity.id,country)

    /** Line of succession (see the class comment). */
    fun order(s:PlanetStocks,identities:List<StrategicIdentity>,country:CountryId=CountryId(2),catalog:CountryCatalog=CountryCatalogLoader.current()):List<Int> {
        if(OrganizationOffices.system(country,catalog)!=2)return emptyList()
        val people=s.strategic.people;val heir=s.social.succession.of(country).heir
        return identities.filter {eligible(s,it,country)}
            .sortedWith(compareBy<StrategicIdentity> {if(it.id==heir)0 else 1}
                .thenBy {people[it.id]?.peerage?.ordinal ?: Peerage.entries.size}
                .thenByDescending {people[it.id]?.influence ?: 0}
                .thenByDescending {people[it.id]?.merit ?: 0L}.thenBy {it.id}).map {it.id}
    }
    private fun throne(g:Galaxy,s:PlanetStocks,country:CountryId,catalog:CountryCatalog):Int? {
        val policy=OrganizationalOfficePolicy(g,catalog)
        val role=policy.role(EMPEROR,country) ?: return null
        return policy.capital(s,country)?.takeIf {policy.active(s,role,it)}
    }
    fun throneVacant(g:Galaxy,s:PlanetStocks,country:CountryId=CountryId(2),catalog:CountryCatalog=CountryCatalogLoader.current())=
        throne(g,s,country,catalog)!=null && OrganizationalOfficePolicy(g,catalog).holders(s,EMPEROR,country).isEmpty() && !s.social.coup.revolting(country)
    /** Crowns the first in line on a vacant throne and records the runner-up who may refuse. */
    fun accede(g:Galaxy,s:PlanetStocks,identities:List<StrategicIdentity>,now:Long,catalog:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        var next=s
        for(id in catalog.countries.keys.sorted()) {
            val country=CountryId(id)
            if(!throneVacant(g,next,country,catalog))continue
            val state=next.social.succession.of(country)
            // The emperor who abdicated or was removed does not take the throne straight back.
            val previous=state.emperor.takeIf {state.vacant}
            val line=order(next,identities,country,catalog).filter {it!=previous}
            val emperor=line.firstOrNull() ?: continue
            val crowned=OrganizationalOfficePolicy(g,catalog).set(next,OfficeHolding(EMPEROR,checkNotNull(throne(g,next,country,catalog)),emperor,country))
            next=crowned.copy(social=crowned.social.copy(succession=crowned.social.succession.with(country,ThroneState(
                emperor=emperor,rival=line.getOrElse(1) {0},at=maxOf(now,1L)))))
        }
        return next
    }
    /** 皇帝 rank (0) follows the throne (card 1, or its rebel alias 68): the holder takes it and anyone else who
     * held it returns to 政治家 (1). Without it a crowned politician has no authority of the emperor card. The
     * throne ends a politician's kept military ladder (militaryRank). */
    fun ranked(s:PlanetStocks,identities:List<StrategicIdentity> = emptyList(),catalog:CountryCatalog=CountryCatalogLoader.current(),g:Galaxy?=null):PlanetStocks {
        val members=identities.associate {it.id to it.country}
        val emperors=s.strategic.offices.filter {(it.card==EMPEROR || it.card==68) && OrganizationOffices.system(it.country,catalog)==2 &&
            (it.character !in members || members[it.character]==it.country) &&
            s.bases[it.seat]?.let {base->base.country==it.country && base.camp==if(it.card==68)1 else 0}==true &&
            (g==null || OrganizationalOfficePolicy(g,catalog).holders(s,it.card,it.country).contains(it))}.map {it.character}.toSet()
        var people=s.strategic.people
        for(id in emperors)if(people[id]?.rank!=0)people=people+(id to (people[id] ?: StrategicPerson()).copy(rank=0,militaryRank=0))
        for((id,p) in s.strategic.people)if(p.rank==0 && id !in emperors)people=people+(id to p.copy(rank=1))
        return if(people==s.strategic.people)s else s.copy(strategic=s.strategic.copy(people=people))
    }
    /** StrategyActions.clearOffices marks a vacated throne here. */
    fun vacated(s:PlanetStocks,character:Int,country:CountryId?=null):PlanetStocks {
        var state=s.social.succession
        for(h in s.strategic.offices.filter {it.card==EMPEROR && it.character==character && (country==null || it.country==country)})
            state=state.with(h.country,state.of(h.country).copy(vacant=true,emperor=character))
        return if(state==s.social.succession)s else s.copy(social=s.social.copy(succession=state))
    }

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,catalog:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection || i.country in session.eliminated)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        val own=i.country
        if(OrganizationOffices.system(own,catalog)!=2)return reject(SocialRejection.CARD)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val policy=OrganizationalOfficePolicy(g,catalog);val state=s.social.succession.of(own)
        when(i.command) {
            DESIGNATE -> {
                if(i.card!=EMPEROR || policy.holders(s,EMPEROR,own).none {it.character==i.characterId})return reject(SocialRejection.CARD)
                val target=i.identities.singleOrNull {it.id==i.target}
                if(target==null || target.id==i.characterId || !eligible(s,target,own))
                    return reject(SocialRejection.TARGET)
            }
            CONTEST -> {
                if(i.card!=0)return reject(SocialRejection.CARD)
                if(state.rival!=i.characterId || state.at>maxOf(now,1L) || now-state.at>CONTEST_DAYS*Justice.DAY ||
                    policy.holders(s,EMPEROR,own).none {it.character==state.emperor})return reject(SocialRejection.STALE)
                if(s.social.coup.plotOf(i.characterId)!=null || s.social.coup.revolting(own))return reject(SocialRejection.BUSY)
            }
            else -> return reject(SocialRejection.COMMAND)
        }
        val cost=if(i.command==DESIGNATE)DESIGNATE_COST else CONTEST_COST
        val pool=if(i.command==DESIGNATE)CpPool.MCP else CpPool.PCP
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,pool)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        if(i.command==DESIGNATE)
            return SocialResult(payer,s.copy(social=s.social.copy(succession=s.social.succession.with(own,state.copy(heir=i.target)))),changed=true,outcome=1,found=i.target)
        // 継承拒否: the claimant's own base and fiefs rise with it.
        val stations=(listOf(p.baseId)+s.strategic.fiefs.filterValues {it==i.characterId}.keys)
            .filter {it>0 && s.bases[it]?.let {b->b.country==own && b.camp==0}==true}.toSet()
        val next=CoupActions.contest(g,s.copy(social=s.social.copy(succession=s.social.succession.with(own,state.copy(rival=0)))),i.characterId,stations,now,own,catalog)
        return SocialResult(payer,next,changed=true,outcome=1,found=stations.size)
    }
}
