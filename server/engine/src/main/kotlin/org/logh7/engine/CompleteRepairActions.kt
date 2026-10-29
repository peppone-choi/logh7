package org.logh7.engine

import org.logh7.engine.strategy.*

/** W71: MCP160, repair surviving ships, consume all carried supplies. The personal
 * captain path, same-location member flagships and zero-damage free no-op are explicit
 * restoration policy. Destroyed boats and injured characters are never resurrected.
 * Author: 최병호. */
internal object CompleteRepairActions {
    val supported=setOf(61)
    private fun busy(p:Navigator)=p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null
    private val commanders=mapOf(39 to OutfitKind.FLEET,44 to OutfitKind.TRANSPORT,47 to OutfitKind.PATROL,
        50 to OutfitKind.GROUND,175 to OutfitKind.FLEET,180 to OutfitKind.TRANSPORT,183 to OutfitKind.PATROL,186 to OutfitKind.GROUND)
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val power=if(p.power==Power.EMPIRE)2 else 3
        val rank=stocks.strategic.people[r.characterId]?.rank ?: r.rank
        if(rank==1 || rank !in 0..20)return reject(StrategyActionRejection.RANK)
        if(busy(p))return reject(StrategyActionRejection.BUSY)
        val now=s.gameMillis/1000
        if((p.hull?.woundedUntil ?: 0)>now)return reject(StrategyActionRejection.BUSY)
        val base=stocks.bases[p.baseId] ?: return reject(StrategyActionRejection.LOCATION)
        if(base.power!=power || base.camp!=0 || p.mode !in 1..4)return reject(StrategyActionRejection.LOCATION)
        var after=p
        var next=stocks
        var updates=emptyMap<String,Navigator>()
        if(r.outfit==0) {
            if(r.card!=if(power==2)59 else 195)return reject(StrategyActionRejection.CARD)
            val hull=p.hull ?: return reject(StrategyActionRejection.TROOPS)
            if(hull.hp==100)return StrategyActionResult(p,stocks)
            if(p.supplies==0)return reject(StrategyActionRejection.RESOURCE)
            after=p.copy(hull=hull.copy(hp=100),supplies=0)
        } else {
            val outfit=stocks.outfits[r.outfit] ?: return reject(StrategyActionRejection.AUTHORITY)
            if(commanders[r.card]!=outfit.kind || OrganizationOffices.of(r.card)?.power!=power)
                return reject(StrategyActionRejection.CARD)
            if(outfit.power!=power || outfit.commanderId!=r.characterId ||
                OrganizationalOfficePolicy(galaxy).holders(stocks,r.card).none {it.character==r.characterId && it.seat==outfit.id})
                return reject(StrategyActionRejection.AUTHORITY)
            if(outfit.positionIn(galaxy)!=OutfitPosition(p.grid,p.baseId))return reject(StrategyActionRejection.LOCATION)
            val ships=outfit.activeShips ?: return reject(StrategyActionRejection.TROOPS)
            if(ships.values.any {it.tactical!=null || it.support!=null || it.attack!=null || it.shot!=null} ||
                outfit.activeGround?.values.orEmpty().any {it.order!=null})return reject(StrategyActionRejection.BUSY)
            val members=outfit.members()
            val nearby=r.navigators.filterValues {it.generation==r.generation && it.characterId in members &&
                it.power==p.power && it.grid==p.grid && it.baseId==p.baseId}+(r.account to p)
            if(nearby.values.any(::busy))return reject(StrategyActionRejection.BUSY)
            val damage=ships.values.any {it.boats>0 && it.damaged>0} || nearby.values.any {it.hull?.hp?.let {hp->hp<100}==true}
            if(!damage)return StrategyActionResult(p,stocks)
            if(outfit.supplies==0)return reject(StrategyActionRejection.RESOURCE)
            val repaired=outfit.copy(activeShips=ActiveShipUnits(ships.values.map {it.copy(damaged=0)}),supplies=0)
            val emptyCargo=if(ships.values.isEmpty())repaired else OutfitSupplies.withBalances(repaired,ships.values.associate {it.id to 0})
            next=stocks.copy(outfits=stocks.outfits+(outfit.id to emptyCargo))
            updates=nearby.mapValues {(_,member)->member.hull?.let {member.copy(hull=it.copy(hp=100))} ?: member}
            after=updates.getValue(r.account)
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),160,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(after.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,
            changed=true,navigators=updates-r.account)
    }
}
