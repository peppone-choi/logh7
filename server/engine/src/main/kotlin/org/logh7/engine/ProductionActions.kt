package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-08 manual production (user decision 2026-11-02): 施設建設 31, 施設休止 32, 施設再稼動 33, 施設破棄 34, 艦艇建造 35
 * (MCP 80 each) and 募兵 36 (MCP 160), costs from the original command help text (constmsg group 0, evidence:client).
 * The governor, capital administrator or fief lord acts on the planet where it stands. Facilities mean the shipyard
 * (造兵工廠, facility kind 10) that automatic ship production needs; prices and times are evidence:guess.
 * Author: Peppone Choi.
 */
data class ShipOrder(val kind:Int,val readyAt:Long) {
    init {require(kind in 0..199 && readyAt in 0..0xffffffffL)}
}
data class ProductionState(
    /** base -> game second its built shipyard opens. */
    val built:Map<Int,Long> = emptyMap(),
    /** Bases whose shipyard is paused. */
    val paused:Set<Int> = emptySet(),
    /** base -> the one manual ship order in progress. */
    val orders:Map<Int,ShipOrder> = emptyMap(),
    /** Bases whose original shipyard was destroyed (every seeded base starts with one). */
    val destroyed:Set<Int> = emptySet()) {
    init {require(built.size<=350 && built.all {(k,v)->k in 1..65535 && v in 0..0xffffffffL} && paused.all {it in 1..65535} &&
        orders.size<=350 && orders.keys.all {it in 1..65535})}
    fun detached()=copy(built=java.util.Collections.unmodifiableMap(LinkedHashMap(built)),
        paused=java.util.Collections.unmodifiableSet(LinkedHashSet(paused)),orders=java.util.Collections.unmodifiableMap(LinkedHashMap(orders)),
        destroyed=java.util.Collections.unmodifiableSet(LinkedHashSet(destroyed)))
}

object ProductionActions {
    private val countries by lazy {CountryCatalogLoader.load()}
    const val BUILD=31
    const val PAUSE=32
    const val RESUME=33
    const val DESTROY=34
    const val SHIP=35
    const val RECRUIT=36
    val commands=setOf(BUILD,PAUSE,RESUME,DESTROY,SHIP,RECRUIT)
    const val SHIPYARD=10
    const val BUILD_PRICE=100_000_000L
    const val BUILD_DAYS=30L

    /** Whether the base's shipyard produces at [seconds]: an original or opened built one, not paused. */
    fun shipyard(g:Galaxy,s:PlanetStocks,base:Int,seconds:Long):Boolean {
        val p=s.social.production
        if(base in p.paused)return false
        return original(g,s,base) || (p.built[base] ?: Long.MAX_VALUE)<=seconds
    }
    private fun original(g:Galaxy,s:PlanetStocks,base:Int)=base !in s.social.production.destroyed && g.facilities[base].orEmpty().any {it.kind==SHIPYARD}
    fun hasShipyard(g:Galaxy,s:PlanetStocks,base:Int)=original(g,s,base) || base in s.social.production.built
    /** Korean unit names for the order list (localization term list, user decision D-4); the numeral suffix is kept. */
    private val korean=mapOf("戦艦" to "전함","高速戦艦" to "고속전함","巡航艦" to "순항함","駆逐艦" to "구축함",
        "戦闘艇母艦" to "전투정모함","雷撃艇母艦" to "뇌격정모함","工作艦" to "공작함","輸送艦" to "수송함","兵員輸送艦" to "병력수송함",
        "揚陸艦" to "상륙함","打撃巡航艦" to "타격순항함","商船" to "상선","軽装陸戦兵" to "경장육전병","装甲擲弾兵" to "장갑척탄병",
        "装甲兵" to "장갑병","艦隊乗組員" to "함대 승조원","近衛兵" to "근위병","擲弾兵教導" to "척탄병 교도대","薔薇の騎士" to "로젠리터")
    fun koreanName(name:String):String {
        val stem=name.replace(Regex("[ⅠⅡⅢⅣⅤⅥⅦⅧ0-9 ]+$"),"")
        return (korean[stem]?.plus(name.substring(stem.length)) ?: name).take(12)
    }
    /** Ship kinds (35) or troop kinds (36) the base's side may order. */
    fun kinds(power:Int,ship:Boolean)=ProductionUnits.definitions.filter {it.power==power && it.ship==ship}.distinctBy {it.kind}.sortedBy {it.kind}
    fun kinds(country:CountryId,ship:Boolean,catalog:CountryCatalog=countries)=kinds(catalog.shipStyle(country).nativePower,ship)

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,
        policy:UnitProductionPolicy=UnitProductionPolicy(),catalog:CountryCatalog=countries):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.power.country || member.country!=i.power.country)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val own=if(p.power==Power.EMPIRE)2 else 3
        val held=OrganizationalOfficePolicy(g).held(s,i.characterId).map {it.card}.toSet()
        if(i.card !in GoalActions.governanceCards || i.card !in held && !(i.card==66 && s.strategic.fiefs.containsValue(i.characterId)))
            return reject(SocialRejection.CARD)
        val base=p.baseId
        if(base==0 || base !in FundActions.governed(s,i.characterId,own))return reject(SocialRejection.LOCATION)
        val stock=s.bases.getValue(base);val prod=s.social.production
        val unit=if(i.command==SHIP || i.command==RECRUIT)kinds(i.power.country,i.command==SHIP,catalog).singleOrNull {it.kind==i.value} else null
        when(i.command) {
            BUILD -> {
                if(hasShipyard(g,s,base))return reject(SocialRejection.TARGET)
                if(stock.treasury<BUILD_PRICE)return reject(SocialRejection.MAXIMUM)
            }
            PAUSE -> if(!hasShipyard(g,s,base) || base in prod.paused)return reject(SocialRejection.TARGET)
            RESUME -> if(base !in prod.paused)return reject(SocialRejection.TARGET)
            DESTROY -> if(!hasShipyard(g,s,base))return reject(SocialRejection.TARGET)
            SHIP -> {
                if(unit==null || !shipyard(g,s,base,now))return reject(SocialRejection.TARGET)
                if(base in prod.orders)return reject(SocialRejection.BUSY)
                if(stock.mineral<unit.hours.toLong()*policy.mineralPerShipHour || stock.supplies<unit.hours.toLong()*policy.suppliesPerShipHour)
                    return reject(SocialRejection.MAXIMUM)
            }
            RECRUIT -> {
                val e=stock.economy
                if(unit==null || e==null)return reject(SocialRejection.TARGET)
                if(e.adults<policy.peoplePerUnit || stock.food<policy.foodPerTroop || stock.supplies<policy.suppliesPerTroop)
                    return reject(SocialRejection.MAXIMUM)
                val held=stock.units.troops[UnitStack(unit.kind)] ?: 0
                if(held>=65535 || held==0 && stock.units.troops.size>=24)return reject(SocialRejection.MAXIMUM)
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command==RECRUIT)160L else 80L,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        fun with(next:ProductionState,st:PlanetStocks=s)=st.copy(social=st.social.copy(production=next))
        val next=when(i.command) {
            BUILD -> with(prod.copy(built=prod.built+(base to now+BUILD_DAYS*Justice.DAY)),
                s.copy(bases=s.bases+(base to stock.copy(treasury=stock.treasury-BUILD_PRICE))))
            PAUSE -> with(prod.copy(paused=prod.paused+base))
            RESUME -> with(prod.copy(paused=prod.paused-base))
            DESTROY -> with(prod.copy(built=prod.built-base,paused=prod.paused-base,orders=prod.orders-base,
                destroyed=if(base in prod.built)prod.destroyed else prod.destroyed+base))
            SHIP -> {
                val u=checkNotNull(unit)
                with(prod.copy(orders=prod.orders+(base to ShipOrder(u.kind,now+u.hours*3600L))),
                    s.copy(bases=s.bases+(base to stock.copy(mineral=stock.mineral-u.hours*policy.mineralPerShipHour,
                        supplies=stock.supplies-u.hours*policy.suppliesPerShipHour))))
            }
            else -> {
                val u=checkNotNull(unit);val e=checkNotNull(stock.economy);val key=UnitStack(u.kind)
                val troops=stock.units.troops+(key to (stock.units.troops[key] ?: 0)+1)
                s.copy(bases=s.bases+(base to stock.copy(food=stock.food-policy.foodPerTroop,supplies=stock.supplies-policy.suppliesPerTroop,
                    economy=e.copy(population=e.population-policy.peoplePerUnit,adults=e.adults-policy.peoplePerUnit),
                    units=UnitInventory(stock.units.ships,troops,stock.units.work))))
            }
        }
        return SocialResult(payer,next,changed=true,outcome=1,found=base)
    }

    /** Completes due manual ship orders into the base's stock. */
    fun settle(s:PlanetStocks,now:Long):PlanetStocks {
        val prod=s.social.production
        val due=prod.orders.filterValues {it.readyAt<=now}
        if(due.isEmpty())return s
        var bases=s.bases
        val done=mutableSetOf<Int>()
        for((base,order) in due)bases[base]?.let {stock->val key=UnitStack(order.kind)
            val held=stock.units.ships[key] ?: 0
            // A full stack (or a full kind list) keeps the order waiting, as automatic production stops at the cap.
            if(held>=65535 || held==0 && stock.units.ships.size>=99)return@let
            bases=bases+(base to stock.copy(units=UnitInventory(stock.units.ships+(key to held+1),stock.units.troops,stock.units.work)))
            done+=base}
        if(done.isEmpty())return s
        return s.copy(bases=bases,social=s.social.copy(production=prod.copy(orders=prod.orders-done)))
    }
}
