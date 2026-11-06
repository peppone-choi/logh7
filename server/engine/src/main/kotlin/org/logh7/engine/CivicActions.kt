package org.logh7.engine

import org.logh7.engine.strategy.*

enum class CivicPhase { WAITING, GUARDING, COMPLETED, CANCELLED, INVALIDATED, EMPTY }
data class CivicOrder(val character:Int,val command:Int,val card:Int,val country:CountryId,val rank:Int,
    val base:Int,val outfit:Int,val spot:Int,val acceptedAt:Long,val readyAt:Long,
    val phase:CivicPhase,val units:Set<Int> = emptySet(),val effect:Int=0,val token:Int=0,val connection:String="") {
    init {require(character>0 && command in CivicActions.supported && card in 0..260 && rank in 2..20 &&
        base in 1..65535 && outfit>=0 && spot>=0 && acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL &&
        units.size<=300 && units.all {it in 1..999999} && effect in 0..65535 && token in 0..65535 &&
        connection.length<=128 && connection.none {it=='\u0000'} &&
        (if(command==55)outfit==0 && spot>0 && units.isEmpty() else outfit>0 && spot==0 && units.isNotEmpty()) &&
        (phase!=CivicPhase.GUARDING || command==55) && (phase!=CivicPhase.WAITING || command!=55))}
    val active get()=phase in setOf(CivicPhase.WAITING,CivicPhase.GUARDING)
}
data class CivicState(val orders:Map<Int,CivicOrder> = emptyMap()) {
    init {require(orders.size<=256 && orders.all {(id,o)->id==o.character} && active.size<=128 &&
        active.filter {it.outfit>0}.map {it.outfit}.distinct().size==active.count {it.outfit>0} &&
        active.filter {it.spot>0}.map {it.spot}.distinct().size==active.count {it.spot>0})}
    val active get()=orders.values.filter {it.active}
    fun detached()=copy(orders=java.util.Collections.unmodifiableMap(orders.mapValues {(_,o)->
        o.copy(units=java.util.Collections.unmodifiableSet(LinkedHashSet(o.units)))}))
}
data class CivicChange(val generation:Long,val stocks:PlanetStocks,val bases:Set<Int>)

/** W68/69 fixes CP160 and 24-hour wait/duration; MCP, coefficients, commander/card
 * authority, continuous local eligibility, cancellation without refunds, captured-seed
 * identity and a 1000-supply transfer are explicit replacement policy. Author: Peppone Choi. */
object CivicActions {
    val supported=setOf(52,54,55,56)
    val commandingCards=setOf(39,44,47,50,175,180,183,186)
    const val DAY=86400L
    fun army(command:Int)=command in setOf(52,54,56)
    /** 特別警備 guards the faction lobby only; academy rooms are also GUARDED (own-faction entry) but not guard posts. */
    private fun lobby(spot:PlanetSpot)=spot.kind==0 && spot.access==SpotAccess.GUARDED
    private fun location(g:Galaxy,p:Navigator,base:Int)=p.baseId==base && p.mode in 1..4 &&
        g.bases.singleOrNull {it.id==base}?.grid==p.grid && !p.cpPaused && !p.navigationPending
    private fun card(g:Galaxy,stocks:PlanetStocks,p:Navigator,country:CountryId,rank:Int,card:Int,outfit:Int,countries:CountryCatalog):Boolean {
        if(card==TacticalAuthority.captainCard(p,countries))return rank in 2..20
        val role=OrganizationOffices.of(card,country,countries) ?: return false
        return card in commandingCards && rank in role.ranks &&
            OrganizationalOfficePolicy(g,countries).holders(stocks,card,country).any {it.character==p.characterId && it.seat==outfit}
    }
    /** Seed nationality is historical ownership, not the current owner's display code. */
    private fun captured(g:Galaxy,base:Int,country:CountryId)=g.initialOwners[base]?.country?.let {it!=country}==true
    private fun eligible(g:Galaxy,s:PlanetStocks,o:CivicOrder,p:Navigator?,countries:CountryCatalog,eliminated:Set<CountryId>):Boolean {
        if(o.country.value !in countries.countries || o.country in eliminated)return false
        val b=s.bases[o.base] ?: return false
        if(!b.belongsTo(o.country) || b.camp!=0 || p==null || p.generation!=s.generation || p.country!=o.country ||
            !location(g,p,o.base))return false
        val rank=s.strategic.people[o.character]?.rank ?: o.rank
        if(!card(g,s,p,o.country,rank,o.card,o.outfit,countries))return false
        if(o.command==55)return p.spotId==o.spot && p.spotOwner==0 &&
            g.facilitiesAt(o.base,s,countries).any {f->f.spots.any {it.id==o.spot && lobby(it)}}
        val outfit=s.outfits[o.outfit] ?: return false
        if(outfit.country!=o.country || outfit.commanderId!=o.character || outfit.positionIn(g)!=OutfitPosition(p.grid,o.base))return false
        val ground=outfit.activeGround?.values.orEmpty().associateBy {it.id}
        if(o.units.any {id->ground[id]?.let {it.base==o.base && it.carrier==0 && it.order==null && it.strength>0}!=true})return false
        if(o.command==54 && g.bases.single {it.id==o.base}.kind!=0)return false
        if(o.command==56 && !captured(g,o.base,o.country))return false
        return true
    }
    private fun remember(s:CivicState,o:CivicOrder):CivicState {
        val entries=s.orders.toMutableMap();entries[o.character]=o
        while(entries.size>256)entries.remove(entries.values.filter {!it.active}.minWith(compareBy({it.readyAt},{it.character})).character)
        return CivicState(entries)
    }
    fun evaluate(g:Galaxy,r:StrategyActionRequest,session:SessionState,p:Navigator,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        if(r.command !in supported || r.value !in 0..2 || r.base!=0 || r.argument!=0 || r.amount!=0L ||
            r.requestToken !in 0..65535 || r.connection.length>128 || '\u0000' in r.connection)
            return reject(StrategyActionRejection.COMMAND)
        val old=stocks.civic.orders[r.characterId]?.takeIf {it.command==r.command}
        if(r.value==2)return StrategyActionResult(p,stocks,civic=old)
        if(r.value==1) {
            if(old?.active!=true)return StrategyActionResult(p,stocks,civic=old)
            val cancelled=old.copy(phase=CivicPhase.CANCELLED)
            return StrategyActionResult(p,stocks.copy(civic=remember(stocks.civic,cancelled)),changed=true,civic=cancelled)
        }
        if(old!=null && r.requestToken>0 && old.token==r.requestToken && old.connection==r.connection)
            return StrategyActionResult(p,stocks,civic=old)
        val country=p.country?.takeIf {it.value in countries.countries} ?: return reject(StrategyActionRejection.AUTHORITY)
        val rank=stocks.strategic.people[p.characterId]?.rank ?: r.rank
        if(rank !in 2..20)return reject(StrategyActionRejection.RANK)
        if(r.command==55 && (r.outfit!=0 || r.card!=TacticalAuthority.captainCard(p,countries)) ||
            !card(g,stocks,p,country,rank,r.card,r.outfit,countries))return reject(StrategyActionRejection.CARD)
        if(stocks.civic.active.any {it.character==r.characterId || r.outfit>0 && it.outfit==r.outfit} ||
            stocks.strategic.suppressions.any {it.character==r.characterId || r.outfit>0 && it.outfit==r.outfit})return reject(StrategyActionRejection.BUSY)
        if(stocks.civic.active.size>=128)return reject(StrategyActionRejection.MAXIMUM)
        if(p.cpPaused || p.navigationPending)return reject(StrategyActionRejection.BUSY)
        val base=stocks.bases[p.baseId] ?: return reject(StrategyActionRejection.LOCATION)
        if(!base.belongsTo(country) || base.camp!=0 || !location(g,p,base.baseId))return reject(StrategyActionRejection.LOCATION)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-DAY)return reject(StrategyActionRejection.MAXIMUM)
        val units=if(army(r.command)) {
            val outfit=stocks.outfits[r.outfit] ?: return reject(StrategyActionRejection.AUTHORITY)
            if(outfit.country!=country || outfit.commanderId!=r.characterId)return reject(StrategyActionRejection.AUTHORITY)
            if(outfit.positionIn(g)!=OutfitPosition(p.grid,p.baseId))return reject(StrategyActionRejection.LOCATION)
            if(outfit.strategy?.destination!=null)return reject(StrategyActionRejection.BUSY)
            val local=outfit.activeGround?.values.orEmpty().filter {it.base==p.baseId && it.carrier==0}
            if(local.isEmpty())return reject(StrategyActionRejection.TROOPS)
            if(local.size>300)return reject(StrategyActionRejection.MAXIMUM)
            if(local.any {it.order!=null})return reject(StrategyActionRejection.BUSY)
            if(r.command==52 && (base.economy==null || base.economy.peace==100) ||
                r.command==54 && (base.economy==null || base.economy.approval==100))return reject(StrategyActionRejection.MAXIMUM)
            if(r.command==54 && g.bases.single {it.id==base.baseId}.kind!=0)return reject(StrategyActionRejection.LOCATION)
            if(r.command==56) {
                if(!captured(g,base.baseId,country))return reject(StrategyActionRejection.LOCATION)
                if(base.supplies==0 || outfit.supplies>=OutfitSupplies.capacity(outfit))return reject(StrategyActionRejection.RESOURCE)
            }
            local.map {it.id}.toSet()
        } else {
            if(p.spotOwner!=0 || g.facilitiesAt(base.baseId,stocks,countries).none {f->f.spots.any {
                it.id==p.spotId && lobby(it)}})return reject(StrategyActionRejection.LOCATION)
            if(stocks.civic.active.any {it.spot==p.spotId})return reject(StrategyActionRejection.BUSY)
            emptySet()
        }
        val order=CivicOrder(r.characterId,r.command,r.card,country,rank,p.baseId,if(army(r.command))r.outfit else 0,
            if(r.command==55)p.spotId else 0,now,now+DAY,if(r.command==55)CivicPhase.GUARDING else CivicPhase.WAITING,
            units,token=r.requestToken,connection=r.connection)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),160,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(p.paidWith(paid.balance),
            stocks.copy(civic=remember(stocks.civic,order)),changed=true,readyAt=order.readyAt,civic=order)
    }
    /** Offline saved characters retain jobs. Missing player context defers settlement, never fabricates eligibility. */
    fun settle(g:Galaxy,stocks:PlanetStocks,seconds:Long,players:Collection<Navigator>?,
        countries:CountryCatalog=CountryCatalogLoader.current(),eliminated:Set<CountryId> = emptySet()):PlanetStocks {
        if(stocks.civic.active.isEmpty() || players==null)return stocks
        val people=players.associateBy {it.characterId};var next=stocks
        for(o in stocks.civic.active.sortedBy {it.character}) {
            val valid=eligible(g,next,o,people[o.character],countries,eliminated)
            val phase=when { !valid->CivicPhase.INVALIDATED;seconds>=o.readyAt->CivicPhase.COMPLETED;else->o.phase }
            if(phase==o.phase)continue
            var effect=0;var final=phase
            if(phase==CivicPhase.COMPLETED && o.command!=55) {
                val b=next.bases.getValue(o.base)
                when(o.command) {
                    52,54 -> {
                        val e=b.economy
                        if(e!=null) {
                            val old=if(o.command==52)e.peace else e.approval;effect=minOf(5,100-old)
                            next=next.copy(bases=next.bases+(b.baseId to b.copy(economy=if(o.command==52)e.copy(peace=old+effect) else e.copy(approval=old+effect))))
                        }
                    }
                    56 -> {
                        var outfit=next.outfits.getValue(o.outfit)
                        effect=minOf(1000,b.supplies,(OutfitSupplies.capacity(outfit)-outfit.supplies).coerceAtLeast(0))
                        if(effect>0) {
                            outfit=outfit.copy(supplies=Math.addExact(outfit.supplies,effect))
                            if(outfit.activeShips?.values?.isNotEmpty()==true)
                                outfit=OutfitSupplies.withBalances(outfit,OutfitSupplies.distribution(outfit))
                            next=next.copy(bases=next.bases+(b.baseId to b.copy(supplies=b.supplies-effect)),outfits=next.outfits+(outfit.id to outfit))
                        }
                    }
                }
                if(effect==0)final=CivicPhase.EMPTY
            }
            next=next.copy(civic=remember(next.civic,o.copy(phase=final,effect=effect)))
        }
        return next
    }
    /** Security strength is available to spot/espionage policy; hostile infiltration/assassination remains unimplemented. */
    fun guardStrength(stocks:PlanetStocks,base:Int,spot:Int,seconds:Long)=if(stocks.civic.active.any {
        it.command==55 && it.base==base && it.spot==spot && seconds in it.acceptedAt until it.readyAt})2 else 1
}
