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
    private val countries get()=CountryCatalogLoader.current()
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
    const val QUOTE_PHASE=1
    enum class Yard { MISSING, BUILDING, PAUSED, ACTIVE }
    data class Progress(val yard:Yard,val buildingUntil:Long,val order:ShipOrder?,val stock:Int,val automaticPausedUntil:Long)
    data class Quote(val base:Int,val mcp:Int,val hours:Int=0,val treasury:Long=0,val mineral:Long=0,
        val supplies:Long=0,val food:Int=0,val adults:Int=0,val progress:Progress?=null)

    /** Whether the base's shipyard produces at [seconds]: an original or opened built one, not paused. */
    fun shipyard(g:Galaxy,s:PlanetStocks,base:Int,seconds:Long):Boolean {
        val p=s.social.production
        if(base in p.paused)return false
        return shipyardPresent(g,s,base,seconds)
    }
    internal fun shipyardPresent(g: Galaxy, s: PlanetStocks, base: Int, seconds: Long?): Boolean =
        original(g,s,base) || s.social.production.built[base]?.let { seconds != null && it <= seconds } == true

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
        var quote:Quote?=null
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason,productionQuote=quote.takeIf {r.phase==QUOTE_PHASE})
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        if(i.country.value !in catalog.countries)return reject(SocialRejection.CARD)
        if(i.country in session.eliminated)return reject(SocialRejection.SESSION)
        if(i.command !in commands)return reject(SocialRejection.COMMAND)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val office=OrganizationalOfficePolicy(g,catalog)
        val held=GoalActions.governanceCards.filter {office.holders(s,it,i.country).any {holder->holder.character==i.characterId}}.toSet()
        if(i.card !in GoalActions.governanceCards || i.card !in held && !(i.card==66 && s.strategic.fiefs.containsValue(i.characterId)))
            return reject(SocialRejection.CARD)
        val base=p.baseId
        if(base==0 || base !in FundActions.governed(g,s,i.characterId,i.country,catalog,card=i.card))return reject(SocialRejection.LOCATION)
        if(i.base!=0 && i.base!=base)return reject(SocialRejection.QUOTE_CHANGED)
        val stock=s.bases.getValue(base);val prod=s.social.production
        val unit=if(i.command==SHIP || i.command==RECRUIT)
            kinds(i.country,i.command==SHIP,catalog).singleOrNull {it.kind==i.value} ?: return reject(SocialRejection.TARGET) else null
        val cost=when(i.command) {
            BUILD -> Quote(base,80,hours=(BUILD_DAYS*24).toInt(),treasury=BUILD_PRICE)
            SHIP -> checkNotNull(unit).let {Quote(base,80,hours=it.hours,
                mineral=it.hours.toLong()*policy.mineralPerShipHour,supplies=it.hours.toLong()*policy.suppliesPerShipHour)}
            RECRUIT -> Quote(base,160,supplies=policy.suppliesPerTroop.toLong(),food=policy.foodPerTroop,adults=policy.peoplePerUnit)
            else -> Quote(base,80)
        }
        if(r.phase==QUOTE_PHASE) {
            val buildingUntil=prod.built[base]?.takeIf {it>now} ?: 0
            val yard=when {
                buildingUntil>0 -> Yard.BUILDING
                base in prod.paused -> Yard.PAUSED
                hasShipyard(g,s,base) -> Yard.ACTIVE
                else -> Yard.MISSING
            }
            val count=if(i.command==SHIP)stock.units.ships.entries.sumOf {(stack,quantity)->if(stack.kind==i.value)quantity else 0} else 0
            quote=cost.copy(progress=Progress(yard,buildingUntil,prod.orders[base],count,
                EspionageActions.productionPausedUntil(s,base,now)))
        }
        when(i.command) {
            BUILD -> {
                if(hasShipyard(g,s,base))return reject(SocialRejection.TARGET)
                if(stock.treasury<cost.treasury)return reject(SocialRejection.MAXIMUM)
            }
            PAUSE -> if(!hasShipyard(g,s,base) || base in prod.paused)return reject(SocialRejection.TARGET)
            RESUME -> if(base !in prod.paused)return reject(SocialRejection.TARGET)
            DESTROY -> if(!hasShipyard(g,s,base))return reject(SocialRejection.TARGET)
            SHIP -> {
                if(!shipyard(g,s,base,now))return reject(SocialRejection.TARGET)
                if(base in prod.orders)return reject(SocialRejection.BUSY)
                if(stock.mineral<cost.mineral || stock.supplies<cost.supplies)
                    return reject(SocialRejection.MAXIMUM)
            }
            RECRUIT -> {
                val e=stock.economy
                if(e==null)return reject(SocialRejection.TARGET)
                if(e.adults<cost.adults || stock.food<cost.food || stock.supplies<cost.supplies)
                    return reject(SocialRejection.MAXIMUM)
                val held=stock.units.troops[UnitStack(checkNotNull(unit).kind)] ?: 0
                if(held>=65535 || held==0 && stock.units.troops.size>=24)return reject(SocialRejection.MAXIMUM)
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost.mcp.toLong(),CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        if(r.phase==QUOTE_PHASE)return SocialResult(p,s,productionQuote=quote)
        val payer=p.paidWith(paid.balance)
        fun with(next:ProductionState,st:PlanetStocks=s)=st.copy(social=st.social.copy(production=next))
        val next=when(i.command) {
            BUILD -> with(prod.copy(built=prod.built+(base to now+cost.hours*3600L)),
                s.copy(bases=s.bases+(base to stock.copy(treasury=stock.treasury-cost.treasury))))
            PAUSE -> with(prod.copy(paused=prod.paused+base))
            RESUME -> with(prod.copy(paused=prod.paused-base))
            DESTROY -> with(prod.copy(built=prod.built-base,paused=prod.paused-base,orders=prod.orders-base,
                destroyed=if(base in prod.built)prod.destroyed else prod.destroyed+base))
            SHIP -> {
                val u=checkNotNull(unit)
                with(prod.copy(orders=prod.orders+(base to ShipOrder(u.kind,now+cost.hours*3600L))),
                    s.copy(bases=s.bases+(base to stock.copy(mineral=stock.mineral-cost.mineral.toInt(),supplies=stock.supplies-cost.supplies.toInt()))))
            }
            else -> {
                val u=checkNotNull(unit);val e=checkNotNull(stock.economy);val key=UnitStack(u.kind)
                val troops=stock.units.troops+(key to (stock.units.troops[key] ?: 0)+1)
                s.copy(bases=s.bases+(base to stock.copy(food=stock.food-cost.food,supplies=stock.supplies-cost.supplies.toInt(),
                    economy=e.copy(population=e.population-cost.adults,adults=e.adults-cost.adults),
                    units=UnitInventory(stock.units.ships,troops,stock.units.work))))
            }
        }
        return SocialResult(payer,next,changed=true,outcome=1,found=base)
    }

    /** Completes due manual ship orders into the base's stock. */
    fun settle(s:PlanetStocks,now:Long):PlanetStocks {
        val prod=s.social.production
        val due=prod.orders.filter { (base, order) -> base !in prod.paused && order.readyAt <= now }
        if(due.isEmpty())return s
        var bases=s.bases
        val done=mutableSetOf<Int>()
        for((base,order) in due) {
            // Temporary loss of regular control holds the paid order, just as a paused yard does.
            val stock=bases[base]?.takeIf {it.country!=null && it.camp==0} ?: continue
            val key=UnitStack(order.kind)
            val held=stock.units.ships[key] ?: 0
            // A full stack (or a full kind list) keeps the order waiting, as automatic production stops at the cap.
            if(warehouseShipProductionRoom(order.kind,stock.units.ships,stock.spareShips)==0 ||
                held==0 && stock.units.ships.size>=99)continue
            bases=bases+(base to stock.copy(units=UnitInventory(stock.units.ships+(key to held+1),stock.units.troops,stock.units.work)))
            done+=base
        }
        if(done.isEmpty())return s
        return s.copy(bases=bases,social=s.social.copy(production=prod.copy(orders=prod.orders-done)))
    }
}
