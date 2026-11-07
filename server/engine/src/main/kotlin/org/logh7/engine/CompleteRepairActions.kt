package org.logh7.engine

import org.logh7.engine.strategy.*

/** W71: MCP160, repair surviving ships, consume all carried supplies. The personal
 * captain path, same-location member flagships and zero-damage free no-op are explicit
 * restoration policy. Destroyed boats and injured characters are never resurrected.
 * Author: Peppone Choi. */
internal object CompleteRepairActions {
    val supported=setOf(61)
    private fun busy(p:Navigator)=p.cpPaused || p.navigationPending
    private val commanders=mapOf(39 to OutfitKind.FLEET,44 to OutfitKind.TRANSPORT,47 to OutfitKind.PATROL,
        50 to OutfitKind.GROUND,175 to OutfitKind.FLEET,180 to OutfitKind.TRANSPORT,183 to OutfitKind.PATROL,186 to OutfitKind.GROUND)
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val country=p.country?.takeIf {it.value in countries.countries} ?: return reject(StrategyActionRejection.IDENTITY)
        if(country in s.eliminated)return reject(StrategyActionRejection.SESSION)
        val rank=stocks.strategic.people[r.characterId]?.rank ?: r.rank
        if(rank==1 || rank !in 0..20)return reject(StrategyActionRejection.RANK)
        if(busy(p))return reject(StrategyActionRejection.BUSY)
        val now=s.gameMillis/1000
        if((p.hull?.woundedUntil ?: 0)>now)return reject(StrategyActionRejection.BUSY)
        val base=stocks.bases[p.baseId] ?: return reject(StrategyActionRejection.LOCATION)
        if(!base.belongsTo(country) || base.camp!=0 || p.mode !in 1..4)return reject(StrategyActionRejection.LOCATION)
        val outfit=if(r.outfit==0) {
            if(r.card!=when(countries.countries[country.value]?.nativePower) {2->59;3->195;else->return reject(StrategyActionRejection.CARD)})
                return reject(StrategyActionRejection.CARD)
            if(p.hull==null)return reject(StrategyActionRejection.TROOPS)
            null
        } else {
            val outfit=stocks.outfits[r.outfit] ?: return reject(StrategyActionRejection.AUTHORITY)
            val policy=OrganizationalOfficePolicy(galaxy,countries)
            if(commanders[r.card]!=outfit.kind || policy.role(r.card,country)==null)
                return reject(StrategyActionRejection.CARD)
            if(outfit.country!=country || outfit.commanderId!=r.characterId ||
                policy.holders(stocks,r.card,country).none {it.character==r.characterId && it.seat==outfit.id})
                return reject(StrategyActionRejection.AUTHORITY)
            if(outfit.positionIn(galaxy)!=OutfitPosition(p.grid,p.baseId))return reject(StrategyActionRejection.LOCATION)
            if(outfit.activeShips==null)return reject(StrategyActionRejection.TROOPS)
            outfit
        }
        val ships=outfit?.activeShips?.values.orEmpty()
        if(ships.any {it.tactical!=null || it.support!=null || it.attack!=null || it.shot!=null} ||
            outfit?.activeGround?.values.orEmpty().any {it.order!=null})return reject(StrategyActionRejection.BUSY)
        val members=outfit?.members().orEmpty()
        val nearby=r.navigators.filterValues {it.generation==r.generation && it.characterId in members &&
            it.country==country && it.grid==p.grid && it.baseId==p.baseId}+(r.account to p)
        if(nearby.values.any(::busy))return reject(StrategyActionRejection.BUSY)
        // update03: guard ships cannot be repaired. Their damage alone must not trigger a charge.
        val damagedShips=if(outfit?.guard==null)ships.filter {it.boats>0}.sumOf {it.damaged} else 0
        val damagedFlagships=nearby.values.count {(it.hull?.hp ?: 100)<100}
        if(damagedShips==0 && damagedFlagships==0)return StrategyActionResult(p,stocks,
            repairQuote=CompleteRepairQuote(0,0,0).takeIf {r.repairPreview})
        val supplies=outfit?.supplies ?: p.supplies
        val quote=CompleteRepairQuote(supplies,damagedShips,damagedFlagships)
        fun unavailable(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason,repairQuote=quote.takeIf {r.repairPreview})
        if(supplies==0)return unavailable(StrategyActionRejection.RESOURCE)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),160,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return unavailable(StrategyActionRejection.CP)
        val cp=checkNotNull(paid.chargedPool)
        if(r.repairPreview)return StrategyActionResult(p,stocks,repairQuote=quote.copy(cp=cp))
        if(r.base!=0 && (r.base!=supplies || r.argument!=(if(cp==CpPool.MCP)1 else 2)))
            return reject(StrategyActionRejection.QUOTE_CHANGED)
        if(outfit==null)return StrategyActionResult(p.copy(hull=checkNotNull(p.hull).copy(hp=100),supplies=0)
            .paidWith(paid.balance),stocks,changed=true)
        val repaired=outfit.copy(activeShips=ActiveShipUnits(ships.map {if(outfit.guard!=null)it else it.copy(damaged=0)}),supplies=0)
        val emptyCargo=if(ships.isEmpty())repaired else OutfitSupplies.withBalances(repaired,ships.associate {it.id to 0})
        val next=stocks.copy(outfits=stocks.outfits+(outfit.id to emptyCargo))
        val updates=nearby.mapValues {(_,member)->member.hull?.let {member.copy(hull=it.copy(hp=100))} ?: member}
        return StrategyActionResult(updates.getValue(r.account).paidWith(paid.balance),next,
            changed=true,navigators=updates-r.account)
    }
}
