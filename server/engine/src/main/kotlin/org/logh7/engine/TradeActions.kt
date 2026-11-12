package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * Trade, tariffs and Fezzan diplomacy (design-trade.md, approved 2026-11-02). The original announced these as postponed
 * (ns-update 2004-10); 関税率変更 17 and 外交 20 keep the manual's MCP 320 (p.73), 航路貿易 39 and 船団貿易 40 have
 * names only. Every rule and value here is evidence:guess. Author: Peppone Choi.
 */
data class TradeRoute(val character:Int,val from:Int,val to:Int,val until:Long,val paidThrough:Long,
    /** Null only for an old five-column route; bind to a legacy country before its next payment. */
    val country:CountryId?=null) {
    init {require(character>0 && from in 1..65535 && to in 1..65535 && from!=to && paidThrough in 0..until && until<=0xffffffffL)}
}
data class TradeState(val routes:Map<Int,TradeRoute> = emptyMap(),
    /** Actual country ID -> tariff percent (default [TradeActions.DEFAULT_TARIFF]). */
    val tariffs:Map<Int,Int> = emptyMap(),
    /** transport outfit -> next convoy allowed at. */
    val convoys:Map<Int,Long> = emptyMap(),
    /** Actual country ID -> commerce pact until. */
    val pacts:Map<Int,Long> = emptyMap(),
    /** power -> Fezzan corridor passage until: its ships may enter Fezzan without violating neutrality. */
    val passages:Map<Int,Long> = emptyMap(),
    /** U-07: power -> game second it violated Fezzan's neutrality (CD manual p.39). */
    val violations:Map<Int,Long> = emptyMap(),
    /** actual country that violated -> installments already shared by its surviving foreign countries (up to [TradeActions.SUBSIDIES]). */
    val subsidies:Map<Int,Int> = emptyMap()) {
    init {require(routes.size<=4096 && routes.all {(k,v)->k==v.character} && tariffs.size<=4096 && tariffs.all {(k,v)->k>0 && v in 0..TradeActions.MAX_TARIFF} &&
        convoys.size<=1024 && convoys.all {(k,v)->k>0 && v in 0..0xffffffffL} &&
        listOf(pacts,passages,violations).all {m->m.size<=4096 && m.all {(k,v)->k>0 && v in 0..0xffffffffL}} &&
        subsidies.all {(k,v)->k in violations && v in 1..TradeActions.SUBSIDIES})}
    /** The enemy of a violator reads its secrets for [TradeActions.LEAK_DAYS] days (CD p.39 "information leak"). */
    fun leaking(power:Int,now:Long)=violations[power]?.let {now<it+TradeActions.LEAK_DAYS*Justice.DAY}==true
    fun tariff(power:Int)=tariffs[power] ?: TradeActions.DEFAULT_TARIFF
    val countryFormat get()=routes.values.any {it.country!=null} || tariffs.keys.any {it !in 2..3}
    val diplomacyFormat get()=listOf(pacts,passages,violations).any {m->m.keys.any {it !in 2..3}}
    fun detached()=copy(routes=java.util.Collections.unmodifiableMap(LinkedHashMap(routes)),tariffs=java.util.Collections.unmodifiableMap(LinkedHashMap(tariffs)),
        convoys=java.util.Collections.unmodifiableMap(LinkedHashMap(convoys)),pacts=java.util.Collections.unmodifiableMap(LinkedHashMap(pacts)),
        passages=java.util.Collections.unmodifiableMap(LinkedHashMap(passages)),violations=java.util.Collections.unmodifiableMap(LinkedHashMap(violations)),
        subsidies=java.util.Collections.unmodifiableMap(LinkedHashMap(subsidies)))
}

object TradeActions {
    data class Quote(val amount:Int,val tariff:Int=0,val routeRemainingSeconds:Int=0)
    const val QUOTE_PHASE=1
    const val TARIFF=17
    const val DIPLOMACY=20
    const val ROUTE=39
    const val CONVOY=40
    val commands=setOf(TARIFF,DIPLOMACY,ROUTE,CONVOY)
    const val DEFAULT_TARIFF=10
    const val MAX_TARIFF=50
    const val ROUTE_DAYS=7L
    const val ROUTE_CELLS=10
    const val PACT_DAYS=30L
    const val INTEL_PRICE=100_000_000L
    /** CD manual p.39: the violator's enemy receives 30 billion marks every 30 days, 12 times; secrets leak 90 days. */
    const val SUBSIDY=30_000_000_000L
    const val SUBSIDIES=12
    const val SUBSIDY_DAYS=30L
    const val LEAK_DAYS=90L
    /** 財務尚書 8 / 財政委員長 136. */
    val tariffCards=setOf(8,136)
    /** 帝国宰相 5, 議長 132 and the Fezzan commissioners 14 / 144. */
    val diplomacyCards=setOf(5,132,14,144)
    /** Transport fleet commanders. */
    val convoyCards=setOf(44,180)
    const val COMMERCE=0
    const val PASSAGE=1
    const val INTELLIGENCE=2
    fun quotes(command:Int,value:Int)=command==CONVOY || command==ROUTE || command==DIPLOMACY && value==INTELLIGENCE

    const val RAID_CELLS=1
    const val FEZZAN_NAME="フェザーン"
    /** Neutral Fezzan's base, or null once it was occupied (U-07). */
    fun fezzan(g:Galaxy,s:PlanetStocks)=g.bases.firstOrNull {it.name==FEZZAN_NAME && s.bases[it.id]?.owner==Allegiance.Neutral}?.id
    /** Daily profit of a route: the smaller side's adults / 1000. */
    fun routeProfit(s:PlanetStocks,r:TradeRoute,galaxy:Galaxy?=null):Long {
        val base=minOf(s.bases[r.from]?.economy?.adults ?: 0,s.bases[r.to]?.economy?.adults ?: 0)/1000L
        // U-32a phase 2: a route whose end lies within [RAID_CELLS] of a pirate-held grid earns half. Pirates never stand
        // on an owned base's grid, but their movement seeks the grids next to route ends (PirateActions.move).
        val grids=s.social.pirates.grids()
        val raided=galaxy!=null && grids.isNotEmpty() && galaxy.bases.any {b->(b.id==r.from || b.id==r.to) &&
            grids.any {EspionageActions.cells(it,b.grid)<=RAID_CELLS}}
        return if(raided)base/2 else base
    }
    fun roll(actor:Int,kind:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+kind*17L+97).nextInt(100)
    fun routeTargets(g:Galaxy,s:PlanetStocks,p:Navigator,catalog:CountryCatalog=CountryCatalogLoader.current()):List<Int> {
        val own=p.country?.takeIf {it.value in catalog.countries} ?: return emptyList()
        if(p.generation!=s.generation || s.bases[p.baseId]?.let {it.country==own && it.camp==0}!=true)return emptyList()
        return g.bases.filter {b->b.id!=p.baseId &&
        s.bases[b.id]?.let {it.country==own && it.camp==0}==true &&
        g.bases.singleOrNull {it.id==p.baseId}?.let {here->EspionageActions.cells(here.grid,b.grid)<=ROUTE_CELLS}==true}.map {it.id}
    }
    /** Only current countries with an owned regular capital can receive trade or Fezzan funds. */
    fun foreignTargets(g:Galaxy,s:PlanetStocks,own:CountryId,catalog:CountryCatalog=CountryCatalogLoader.current(),
        eliminated:Set<CountryId> = emptySet()):List<CountryId> {
        if(own.value !in catalog.countries)return emptyList()
        val offices=OrganizationalOfficePolicy(g,catalog)
        return catalog.countries.keys.sorted().map(::CountryId).filter {it!=own && it !in eliminated && offices.capital(s,it)!=null}
    }

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,
        catalog:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        if(i.country.value !in catalog.countries)return reject(SocialRejection.CARD)
        if(i.country in session.eliminated)return reject(SocialRejection.SESSION)
        if(i.command !in commands)return reject(SocialRejection.COMMAND)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val own=i.country.value
        val held=OrganizationalOfficePolicy(g,catalog).held(s,i.characterId).filter {it.country==i.country}.map {it.card}.toSet()
        val cards=when(i.command) {TARIFF->tariffCards;DIPLOMACY->diplomacyCards;CONVOY->convoyCards;else->setOf(0)}
        if(i.card !in cards || i.card!=0 && i.card !in held)return reject(SocialRejection.CARD)
        val foreign=i.command==CONVOY || i.command==DIPLOMACY && i.value==INTELLIGENCE
        val counterparty=if(foreign)foreignTargets(g,s,i.country,catalog,session.eliminated.keys).let {targets->
            if(i.target==0)targets.singleOrNull() else targets.singleOrNull {it.value==i.target}}
            ?: return reject(SocialRejection.TARGET) else null
        val t=s.social.trade
        // Validate before charging.
        var outfit:OutfitStock?=null
        var convoyDuty=0L
        var convoyNet=0L
        var route:TradeRoute?=null
        var routeIncome=0
        var intelligencePrice:Long?=null
        when(i.command) {
            ROUTE -> {
                if(p.baseId==0 || p.mode !in 1..4)return reject(SocialRejection.LOCATION)
                if(s.bases[p.baseId]?.let {it.country==i.country && it.camp==0}!=true)return reject(SocialRejection.LOCATION)
                if(i.target !in routeTargets(g,s,p,catalog))return reject(SocialRejection.TARGET)
                val remaining=t.routes[i.characterId]?.until?.minus(now) ?: 0
                if(remaining>0) {
                    return if(r.phase==QUOTE_PHASE)SocialResult(p,s,SocialRejection.BUSY,
                        tradeQuote=Quote(0,routeRemainingSeconds=minOf(remaining,ROUTE_DAYS*Justice.DAY).toInt()))
                    else reject(SocialRejection.BUSY)
                }
                route=TradeRoute(i.characterId,p.baseId,i.target,now+ROUTE_DAYS*Justice.DAY,now,i.country)
                routeIncome=routeProfit(s,route,g).toInt()
                if(r.phase==QUOTE_PHASE)return SocialResult(p,s,tradeQuote=Quote(routeIncome))
                // A quote of zero is valid; the wire encodes the amount plus one.
                if(i.argument>0 && i.argument-1!=routeIncome)return reject(SocialRejection.QUOTE_CHANGED)
            }
            CONVOY -> {
                if(p.dockTarget!=null || p.warpTarget!=null)return reject(SocialRejection.BUSY)
                if(s.bases[p.baseId]?.let {it.country==i.country && it.camp==0}!=true)return reject(SocialRejection.LOCATION)
                outfit=s.outfits.values.singleOrNull {it.kind==OutfitKind.TRANSPORT && it.country==i.country && it.commanderId==i.characterId &&
                    it.positionIn(g).baseId==p.baseId && p.baseId!=0} ?: return reject(SocialRejection.LOCATION)
                if(fezzan(g,s)==null)return reject(SocialRejection.TARGET)
                // U-07: Fezzan's merchant fleets no longer trade with a side that violated its neutrality.
                if(own in t.violations)return reject(SocialRejection.STALE)
                if(i.amount !in 1..65535 || i.amount>outfit.supplies)return reject(SocialRejection.MAXIMUM)
                if((t.convoys[outfit.id] ?: 0)>now)return reject(SocialRejection.COOLDOWN)
                val pact=(t.pacts[own] ?: 0)>now
                val gross=i.amount*3/2*(if(pact)6 else 5)/5
                val tariff=t.tariff(checkNotNull(counterparty).value)
                convoyDuty=gross*tariff/100
                convoyNet=gross-convoyDuty
                if(r.phase==QUOTE_PHASE)return SocialResult(p,s,tradeQuote=Quote(convoyNet.toInt(),tariff))
                if(i.argument>0 && i.argument.toLong()!=convoyNet)return reject(SocialRejection.QUOTE_CHANGED)
            }
            TARIFF -> if(i.value !in 0..MAX_TARIFF)return reject(SocialRejection.TARGET)
            DIPLOMACY -> {
                if(i.value !in COMMERCE..INTELLIGENCE)return reject(SocialRejection.TARGET)
                // U-07: an occupied Fezzan is no longer a neutral party to negotiate with.
                if(fezzan(g,s)==null)return reject(SocialRejection.TARGET)
                // U-07: Fezzan withdraws its investment from a violator; no new commerce pact.
                if(i.value==COMMERCE && own in t.violations)return reject(SocialRejection.STALE)
                if(i.value==INTELLIGENCE) {
                    intelligencePrice=if(t.leaking(checkNotNull(counterparty).value,now))0L else INTEL_PRICE
                    if(r.phase==QUOTE_PHASE)return SocialResult(p,s,tradeQuote=Quote(intelligencePrice.toInt()))
                    if(i.argument>0 && i.argument-1L!=intelligencePrice)return reject(SocialRejection.QUOTE_CHANGED)
                    if((s.nationalTreasuries[i.country] ?: 0)<intelligencePrice)return reject(SocialRejection.MAXIMUM)
                }
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command==ROUTE)160L else 320L,
            if(i.command==ROUTE)CpPool.PCP else CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        fun trade(next:TradeState,st:PlanetStocks=s)=st.copy(social=st.social.copy(trade=next))
        when(i.command) {
            ROUTE -> return SocialResult(payer,trade(t.copy(routes=t.routes+(i.characterId to checkNotNull(route)))),
                changed=true,outcome=1,found=routeIncome)
            CONVOY -> {
                val o=checkNotNull(outfit)
                // The other side's tariff takes its cut into the other side's treasury (trade through Fezzan).
                val other=checkNotNull(counterparty)
                val treasuries=s.nationalTreasuries+(i.country to Math.addExact(s.nationalTreasuries[i.country] ?: 0,convoyNet))+
                    (other to Math.addExact(s.nationalTreasuries[other] ?: 0,convoyDuty))
                val next=s.copy(outfits=s.outfits+(o.id to o.copy(supplies=(o.supplies-i.amount).toInt())),nationalTreasuries=treasuries)
                return SocialResult(payer,trade(t.copy(convoys=t.convoys+(o.id to now+Justice.DAY)),next),changed=true,outcome=1,
                    found=convoyNet.toInt())
            }
            TARIFF -> return SocialResult(payer,trade(t.copy(tariffs=t.tariffs+(own to i.value))),changed=true,outcome=1,found=i.value)
            DIPLOMACY -> {
                val politics=s.strategic.people[i.characterId]?.abilities?.getOrNull(1)
                    ?: i.identities.singleOrNull {it.id==i.characterId}?.abilities?.getOrNull(1) ?: 50
                if(intelligencePrice!=0L && roll(i.characterId,i.value,now)>=(50+(politics-50)/2).coerceIn(20,80))
                    return SocialResult(payer,s,changed=true,outcome=3)
                val until=now+PACT_DAYS*Justice.DAY
                return when(i.value) {
                    COMMERCE -> SocialResult(payer,trade(t.copy(pacts=t.pacts+(own to until))),changed=true,outcome=1)
                    PASSAGE -> SocialResult(payer,trade(t.copy(passages=t.passages+(own to until))),changed=true,outcome=1)
                    else -> {
                        val capital=checkNotNull(OrganizationalOfficePolicy(g,catalog).capital(s,checkNotNull(counterparty))).let {s.bases.getValue(it)}
                        // A leaking enemy's secrets come free (U-07).
                        val price=checkNotNull(intelligencePrice)
                        val next=if(price==0L)s else s.copy(nationalTreasuries=s.nationalTreasuries+
                            (i.country to ((s.nationalTreasuries[i.country] ?: 0)-price)))
                        // The treasury travels in units of 10,000 marks: whole-capital treasuries overflow 32 bits.
                        SocialResult(payer,next,changed=true,outcome=1,found=minOf(capital.treasury/10_000,0x7fffffffL).toInt(),
                            report=(capital.economy?.approval ?: 0)*256+(capital.economy?.peace ?: 0),tradeReportCountry=counterparty)
                    }
                }
            }
        }
        return reject(SocialRejection.COMMAND)
    }

    /** U-07 (CD manual p.39): a side whose ship units (not a lone flagship) first stand in Fezzan's grid without a
     * corridor passage violates its neutrality; surviving foreign countries share [SUBSIDY] every [SUBSIDY_DAYS] days,
     * [SUBSIDIES] times in all, starting at once. */
    fun neutrality(g:Galaxy,s:PlanetStocks,now:Long,catalog:CountryCatalog=CountryCatalogLoader.current(),
        eliminated:Set<CountryId> = emptySet()):PlanetStocks {
        val fezzan=fezzan(g,s)?.let {id->g.bases.single {it.id==id}} ?: return s
        var t=s.social.trade;var treasuries=s.nationalTreasuries
        for(power in catalog.countries.keys.sorted()) {
            val origin=CountryId(power)
            if(origin !in eliminated && power !in t.violations && (t.passages[power] ?: 0)<=now && s.outfits.values.any {o->o.country==origin &&
                o.positionIn(g).grid==fezzan.grid && o.activeShips?.values.orEmpty().any {it.boats>0}})
                // U-07 (CD manual p.39): Fezzan-flagged merchant ships vanish and its investment falls; here the
                // violator loses its commerce pact and convoy trade with Fezzan (evidence:guess mapping).
                t=t.copy(violations=t.violations+(power to now),pacts=t.pacts-power)
            val since=t.violations[power] ?: continue
            if(now<since)continue
            val due=minOf(SUBSIDIES.toLong(),(now-since)/(SUBSIDY_DAYS*Justice.DAY)+1).toInt()
            val paid=t.subsidies[power] ?: 0
            if(due>paid) {
                val recipients=foreignTargets(g,s,origin,catalog,eliminated)
                // Each installment totals exactly SUBSIDY; stable ID order receives the integer remainder.
                for((index,country) in recipients.withIndex()) {
                    val share=SUBSIDY/recipients.size+(if(index.toLong()<SUBSIDY%recipients.size)1 else 0)
                    treasuries=treasuries+(country to Math.addExact(treasuries[country] ?: 0,share*(due-paid)))
                }
                t=t.copy(subsidies=t.subsidies+(power to due))
            }
        }
        return if(t==s.social.trade)s else s.copy(nationalTreasuries=treasuries,social=s.social.copy(trade=t))
    }
    /** Pays each route for its whole elapsed days; a route ends when it expires or either base leaves the owner's regular camp. */
    fun settle(g:Galaxy,start:PlanetStocks,now:Long,catalog:CountryCatalog=CountryCatalogLoader.current(),
        eliminated:Set<CountryId> = emptySet()):PlanetStocks {
        val s=neutrality(g,start,now,catalog,eliminated)
        val t=s.social.trade
        if(t.routes.isEmpty() && t.convoys.isEmpty() && t.pacts.isEmpty() && t.passages.isEmpty())return s
        var bases=s.bases;val routes=LinkedHashMap<Int,TradeRoute>()
        for(r in t.routes.values.sortedBy {it.character}) {
            // Old records have no origin; their first settlement can only bind a current legacy country, never an added one.
            val country=r.country ?: bases[r.from]?.country?.takeIf {it.value in 2..3}
            val alive=country!=null && country.value in catalog.countries && country !in eliminated &&
                listOf(r.from,r.to).all {bases[it]?.let {b->b.country==country && b.camp==0}==true}
            val end=minOf(now,r.until)
            val days=if(alive && end>r.paidThrough)(end-r.paidThrough)/Justice.DAY else 0
            if(days>0) {
                val profit=routeProfit(s,r,g)*days
                for(b in listOf(r.from,r.to))bases=bases+(b to bases.getValue(b).let {it.copy(treasury=Math.addExact(it.treasury,profit))})
            }
            val paid=r.paidThrough+days*Justice.DAY
            if(alive && now<r.until && paid<r.until)routes[r.character]=r.copy(paidThrough=paid,country=country)
        }
        val next=t.copy(routes=routes,convoys=t.convoys.filterValues {it>now},pacts=t.pacts.filterValues {it>now},passages=t.passages.filterValues {it>now})
        return if(next==t && bases==s.bases)s else s.copy(bases=bases,social=s.social.copy(trade=next))
    }
}
