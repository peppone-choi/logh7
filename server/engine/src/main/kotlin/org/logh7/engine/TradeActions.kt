package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * Trade, tariffs and Fezzan diplomacy (design-trade.md, approved 2026-11-02). The original announced these as postponed
 * (ns-update 2004-10); 関税率変更 17 and 外交 20 keep the manual's MCP 320 (p.73), 航路貿易 39 and 船団貿易 40 have
 * names only. Every rule and value here is evidence:guess. Author: Peppone Choi.
 */
data class TradeRoute(val character:Int,val from:Int,val to:Int,val until:Long,val paidThrough:Long) {
    init {require(character>0 && from in 1..65535 && to in 1..65535 && from!=to && paidThrough in 0..until && until<=0xffffffffL)}
}
data class TradeState(val routes:Map<Int,TradeRoute> = emptyMap(),
    /** power -> tariff percent (default [TradeActions.DEFAULT_TARIFF]). */
    val tariffs:Map<Int,Int> = emptyMap(),
    /** transport outfit -> next convoy allowed at. */
    val convoys:Map<Int,Long> = emptyMap(),
    /** power -> commerce pact until. */
    val pacts:Map<Int,Long> = emptyMap(),
    /** power -> Fezzan corridor passage until (enforced once Fezzan neutrality, U-07, exists). */
    val passages:Map<Int,Long> = emptyMap()) {
    init {require(routes.size<=4096 && routes.all {(k,v)->k==v.character} && tariffs.all {(k,v)->k in 2..3 && v in 0..TradeActions.MAX_TARIFF} &&
        convoys.size<=1024 && convoys.all {(k,v)->k>0 && v in 0..0xffffffffL} &&
        listOf(pacts,passages).all {m->m.all {(k,v)->k in 2..3 && v in 0..0xffffffffL}})}
    fun tariff(power:Int)=tariffs[power] ?: TradeActions.DEFAULT_TARIFF
    fun detached()=copy(routes=java.util.Collections.unmodifiableMap(LinkedHashMap(routes)),tariffs=java.util.Collections.unmodifiableMap(LinkedHashMap(tariffs)),
        convoys=java.util.Collections.unmodifiableMap(LinkedHashMap(convoys)),pacts=java.util.Collections.unmodifiableMap(LinkedHashMap(pacts)),
        passages=java.util.Collections.unmodifiableMap(LinkedHashMap(passages)))
}

object TradeActions {
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
    /** 財務尚書 8 / 財政委員長 136. */
    val tariffCards=setOf(8,136)
    /** 帝国宰相 5, 議長 132 and the Fezzan commissioners 14 / 144. */
    val diplomacyCards=setOf(5,132,14,144)
    /** Transport fleet commanders. */
    val convoyCards=setOf(44,180)
    const val COMMERCE=0
    const val PASSAGE=1
    const val INTELLIGENCE=2

    fun fezzan(g:Galaxy,s:PlanetStocks)=g.bases.firstOrNull {it.name=="フェザーン" && s.bases[it.id]?.power==1}?.id
    /** Daily profit of a route: the smaller side's adults / 1000. */
    fun routeProfit(s:PlanetStocks,r:TradeRoute)=minOf(s.bases[r.from]?.economy?.adults ?: 0,s.bases[r.to]?.economy?.adults ?: 0)/1000L
    fun roll(actor:Int,kind:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+kind*17L+97).nextInt(100)
    fun routeTargets(g:Galaxy,s:PlanetStocks,p:Navigator,own:Int)=g.bases.filter {b->b.id!=p.baseId &&
        s.bases[b.id]?.let {it.power==own && it.camp==0}==true &&
        g.bases.singleOrNull {it.id==p.baseId}?.let {here->EspionageActions.cells(here.grid,b.grid)<=ROUTE_CELLS}==true}.map {it.id}

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val own=if(p.power==Power.EMPIRE)2 else 3
        val held=OrganizationalOfficePolicy(g).held(s,i.characterId).map {it.card}.toSet()
        val cards=when(i.command) {TARIFF->tariffCards;DIPLOMACY->diplomacyCards;CONVOY->convoyCards;else->setOf(0)}
        if(i.card !in cards || i.card!=0 && i.card !in held)return reject(SocialRejection.CARD)
        val t=s.social.trade
        // Validate before charging.
        var outfit:OutfitStock?=null
        when(i.command) {
            ROUTE -> {
                if(p.baseId==0 || p.mode !in 1..4)return reject(SocialRejection.LOCATION)
                if(s.bases[p.baseId]?.let {it.power==own && it.camp==0}!=true)return reject(SocialRejection.LOCATION)
                if(i.target !in routeTargets(g,s,p,own))return reject(SocialRejection.TARGET)
                if(t.routes[i.characterId]?.let {it.until>now}==true)return reject(SocialRejection.BUSY)
            }
            CONVOY -> {
                outfit=s.outfits.values.singleOrNull {it.kind==OutfitKind.TRANSPORT && it.power==own && it.commanderId==i.characterId &&
                    it.positionIn(g).baseId==p.baseId && p.baseId!=0} ?: return reject(SocialRejection.LOCATION)
                if(fezzan(g,s)==null)return reject(SocialRejection.TARGET)
                if(i.amount<=0 || i.amount>outfit.supplies)return reject(SocialRejection.MAXIMUM)
                if((t.convoys[outfit.id] ?: 0)>now)return reject(SocialRejection.COOLDOWN)
            }
            TARIFF -> if(i.value !in 0..MAX_TARIFF)return reject(SocialRejection.TARGET)
            DIPLOMACY -> {
                if(i.value !in COMMERCE..INTELLIGENCE)return reject(SocialRejection.TARGET)
                if(i.value==INTELLIGENCE && (s.nationalTreasuries[own] ?: 0)<INTEL_PRICE)return reject(SocialRejection.MAXIMUM)
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command==ROUTE)160L else 320L,
            if(i.command==ROUTE)CpPool.PCP else CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        fun trade(next:TradeState,st:PlanetStocks=s)=st.copy(social=st.social.copy(trade=next))
        when(i.command) {
            ROUTE -> {
                val route=TradeRoute(i.characterId,p.baseId,i.target,now+ROUTE_DAYS*Justice.DAY,now)
                return SocialResult(payer,trade(t.copy(routes=t.routes+(i.characterId to route))),changed=true,outcome=1,
                    found=minOf(routeProfit(s,route),0x7fffffffL).toInt())
            }
            CONVOY -> {
                val o=checkNotNull(outfit)
                val pact=(t.pacts[own] ?: 0)>now
                val gross=i.amount*3/2*(if(pact)6 else 5)/5
                // The other side's tariff takes its cut into the other side's treasury (trade through Fezzan).
                val other=5-own;val duty=gross*t.tariff(other)/100;val net=gross-duty
                val treasuries=s.nationalTreasuries+(own to Math.addExact(s.nationalTreasuries[own] ?: 0,net))+
                    (other to Math.addExact(s.nationalTreasuries[other] ?: 0,duty))
                val next=s.copy(outfits=s.outfits+(o.id to o.copy(supplies=(o.supplies-i.amount).toInt())),nationalTreasuries=treasuries)
                return SocialResult(payer,trade(t.copy(convoys=t.convoys+(o.id to now+Justice.DAY)),next),changed=true,outcome=1,
                    found=minOf(net,0x7fffffffL).toInt())
            }
            TARIFF -> return SocialResult(payer,trade(t.copy(tariffs=t.tariffs+(own to i.value))),changed=true,outcome=1,found=i.value)
            DIPLOMACY -> {
                val politics=s.strategic.people[i.characterId]?.abilities?.getOrNull(1)
                    ?: i.identities.singleOrNull {it.id==i.characterId}?.abilities?.getOrNull(1) ?: 50
                if(roll(i.characterId,i.value,now)>=(50+(politics-50)/2).coerceIn(20,80))
                    return SocialResult(payer,s,changed=true,outcome=3)
                val until=now+PACT_DAYS*Justice.DAY
                return when(i.value) {
                    COMMERCE -> SocialResult(payer,trade(t.copy(pacts=t.pacts+(own to until))),changed=true,outcome=1)
                    PASSAGE -> SocialResult(payer,trade(t.copy(passages=t.passages+(own to until))),changed=true,outcome=1)
                    else -> {
                        val capital=g.bases.singleOrNull {Capital.of(it)?.power==5-own}?.let {s.bases[it.id]}
                        val next=s.copy(nationalTreasuries=s.nationalTreasuries+(own to (s.nationalTreasuries[own] ?: 0)-INTEL_PRICE))
                        SocialResult(payer,next,changed=true,outcome=1,found=minOf(capital?.treasury ?: 0,0x7fffffffL).toInt(),
                            report=(capital?.economy?.approval ?: 0)*256+(capital?.economy?.peace ?: 0))
                    }
                }
            }
        }
        return reject(SocialRejection.COMMAND)
    }

    /** Pays each route for its whole elapsed days; a route ends when it expires or either base leaves the owner's regular camp. */
    fun settle(s:PlanetStocks,now:Long):PlanetStocks {
        val t=s.social.trade
        if(t.routes.isEmpty())return s
        var bases=s.bases;val routes=LinkedHashMap<Int,TradeRoute>()
        for(r in t.routes.values.sortedBy {it.character}) {
            val power=bases[r.from]?.power
            val alive=power in 2..3 && listOf(r.from,r.to).all {bases[it]?.let {b->b.power==power && b.camp==0}==true}
            val end=minOf(now,r.until)
            val days=if(alive)(end-r.paidThrough)/Justice.DAY else 0
            if(days>0) {
                val profit=routeProfit(s,r)*days
                for(b in listOf(r.from,r.to))bases=bases+(b to bases.getValue(b).let {it.copy(treasury=Math.addExact(it.treasury,profit))})
            }
            val paid=r.paidThrough+days*Justice.DAY
            if(alive && paid<r.until)routes[r.character]=r.copy(paidThrough=paid)
        }
        val next=t.copy(routes=routes,convoys=t.convoys.filterValues {it>now},pacts=t.pacts.filterValues {it>now},passages=t.passages.filterValues {it>now})
        return if(next==t && bases==s.bases)s else s.copy(bases=bases,social=s.social.copy(trade=next))
    }
}
