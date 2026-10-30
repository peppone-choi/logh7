package org.logh7.engine

import org.logh7.engine.strategy.*

/** Offer/accept is an explicit revival extension requested for flagship transfers.
 * Both owners consent, keep their own supplies, and exchange the entire surviving hull.
 * Offers expire after 24 game hours; no fee before acceptance. Author: Peppone Choi. */
data class FlagshipExchangeOffer(val giver:Int,val receiver:Int,val giverKind:Int,val receiverKind:Int,
    val base:Int,val spot:Int,val expiresAt:Long) {
    init {require(giver>0 && receiver>0 && giver!=receiver && Flagships.find(giverKind)!=null &&
        Flagships.find(receiverKind)?.power==Flagships.find(giverKind)?.power && base in 1..65535 && spot>0 && expiresAt in 0..0xffffffffL)}
}
data class FlagshipMarketState(val offers:List<FlagshipExchangeOffer> = emptyList()) {
    init {require(offers.size<=128 && offers.flatMap {listOf(it.giver,it.receiver)}.distinct().size==offers.size*2)}
    fun detached()=copy(offers=java.util.Collections.unmodifiableList(ArrayList(offers)))
}
data class FlagshipPurchaseOption(val index:Int,val kind:Int,val price:Long,val maximumRank:Int)

/** REVIEW05: personal card, owned capital flagship-yard spot, evaluation currency,
 * rank limit. The four supported classes, prices/rank thresholds and exchange acceptance
 * PCP80 are evidence:guess. Special flagship definitions are not fabricated.
 * Author: Peppone Choi. */
object FlagshipMarketCatalog {
    fun options(power:Int):List<FlagshipPurchaseOption> {
        require(power in 2..3)
        return listOf(Triple(3,50L,20),Triple(2,200L,10),Triple(0,500L,7),Triple(4,150L,10)).mapIndexed {i,(type,price,rank)->
            FlagshipPurchaseOption(i,Flagships.forType(power,type).kind,price,rank)}
    }
    fun atYard(galaxy:Galaxy,stocks:PlanetStocks,p:Navigator):Boolean {
        val power=if(p.power==Power.EMPIRE)2 else 3
        val base=galaxy.bases.singleOrNull {it.id==p.baseId && Capital.of(it)?.power==power} ?: return false
        if(stocks.bases[base.id]?.let {it.power==power && it.camp==0}!=true || p.mode !in 1..4 || p.spotOwner!=0)return false
        // Native constmsg73/74: flagship yard23, reception21. The CD already has the
        // place even though its later purchase-command factory is absent.
        return galaxy.facilitiesAt(base.id,stocks).any {f->f.kind==23 && f.spots.any {it.kind==21 && it.id==p.spotId}}
    }
}
internal object FlagshipMarketActions {
    val supported=setOf(96)
    private fun busy(p:Navigator,now:Long)=p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null || (p.hull?.woundedUntil ?: 0)>now
    fun settle(stocks:PlanetStocks,now:Long):PlanetStocks {
        val remaining=stocks.flagshipMarket.offers.filter {it.expiresAt>now}
        return if(remaining.size==stocks.flagshipMarket.offers.size)stocks else stocks.copy(flagshipMarket=FlagshipMarketState(remaining))
    }
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        if(r.card!=0 || r.outfit!=0)return reject(StrategyActionRejection.CARD)
        val now=s.gameMillis/1000
        val power=if(p.power==Power.EMPIRE)2 else 3
        val rank=stocks.strategic.people[r.characterId]?.rank ?: r.rank
        val live=stocks.flagshipMarket.offers.filter {it.expiresAt>now}
        var next=stocks
        var after=p
        var updates=emptyMap<String,Navigator>()
        var cost=0L
        if(r.argument !in 0..3)return reject(StrategyActionRejection.COMMAND)
        // Cancellation remains available after moving, injury or loss of the capital.
        if(r.argument==3) {
            val remaining=live.filter {it.giver!=r.characterId && it.receiver!=r.characterId}
            if(remaining.size==live.size)return StrategyActionResult(p,stocks)
            return StrategyActionResult(p,stocks.copy(flagshipMarket=FlagshipMarketState(remaining)),changed=true)
        }
        if(rank!=0 && rank !in 2..20)return reject(StrategyActionRejection.RANK)
        if(busy(p,now))return reject(StrategyActionRejection.BUSY)
        if(!FlagshipMarketCatalog.atYard(galaxy,stocks,p))return reject(StrategyActionRejection.LOCATION)
        val hull=p.hull ?: return reject(StrategyActionRejection.TROOPS)
        if(r.argument==0) {
            if(r.target!=0)return reject(StrategyActionRejection.COMMAND)
            if(live.any {it.giver==r.characterId || it.receiver==r.characterId})return reject(StrategyActionRejection.BUSY)
            val choice=FlagshipMarketCatalog.options(power).singleOrNull {it.index==r.value} ?: return reject(StrategyActionRejection.COMMAND)
            if(rank>choice.maximumRank)return reject(StrategyActionRejection.RANK)
            if(choice.kind==hull.kind)return reject(StrategyActionRejection.MAXIMUM)
            val person=stocks.strategic.people[r.characterId] ?: StrategicPerson()
            if(person.evaluation<choice.price)return reject(StrategyActionRejection.POINTS)
            next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+
                (r.characterId to person.copy(evaluation=person.evaluation-choice.price))))
            after=p.copy(hull=FlagshipHull(choice.kind,now,clockScale=hull.clockScale),energy=null,
                motion=null,retreat=null,charge=null,fire=null,attack=null,commandRange=null,posture=null)
            cost=80
        } else {
            if(r.target<=0 || r.target==r.characterId || r.value!=0)return reject(StrategyActionRejection.TARGET)
            val target=r.navigators.entries.singleOrNull {it.value.characterId==r.target && it.value.generation==r.generation}
                ?: return reject(StrategyActionRejection.TARGET)
            val other=target.value
            if(other.power!=p.power || !FlagshipMarketCatalog.atYard(galaxy,stocks,other) ||
                other.baseId!=p.baseId || other.spotId!=p.spotId || other.spotOwner!=p.spotOwner)
                return reject(StrategyActionRejection.LOCATION)
            if(busy(other,now))return reject(StrategyActionRejection.BUSY)
            val otherHull=other.hull ?: return reject(StrategyActionRejection.TROOPS)
            val otherRank=stocks.strategic.people[r.target]?.rank ?: r.identities.singleOrNull {it.id==r.target}?.rank
                ?: return reject(StrategyActionRejection.IDENTITY)
            if(otherRank!=0 && otherRank !in 2..20)return reject(StrategyActionRejection.RANK)
            if(r.argument==1) {
                if(live.any {it.giver in setOf(r.characterId,r.target) || it.receiver in setOf(r.characterId,r.target)})
                    return reject(StrategyActionRejection.BUSY)
                if(live.size>=128 || now>0xffffffffL-86400)return reject(StrategyActionRejection.MAXIMUM)
                if(hull.kind==otherHull.kind)return reject(StrategyActionRejection.MAXIMUM)
                val offer=FlagshipExchangeOffer(r.characterId,r.target,hull.kind,otherHull.kind,p.baseId,p.spotId,now+86400)
                next=stocks.copy(flagshipMarket=FlagshipMarketState(live+offer))
            } else {
                val offer=live.singleOrNull {it.giver==r.target && it.receiver==r.characterId}
                    ?: return reject(StrategyActionRejection.AUTHORITY)
                if(offer.base!=p.baseId || offer.spot!=p.spotId || offer.giverKind!=otherHull.kind || offer.receiverKind!=hull.kind)
                    return reject(StrategyActionRejection.TARGET)
                fun permitted(characterRank:Int,kind:Int)=FlagshipMarketCatalog.options(power).singleOrNull {it.kind==kind}
                    ?.let {characterRank<=it.maximumRank}==true
                if(!permitted(rank,otherHull.kind) || !permitted(otherRank,hull.kind))return reject(StrategyActionRejection.RANK)
                after=p.copy(hull=otherHull.copy(at=now),energy=null)
                updates=mapOf(target.key to other.copy(hull=hull.copy(at=now),energy=null))
                next=stocks.copy(flagshipMarket=FlagshipMarketState(live-offer))
                cost=80
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.PCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(after.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,
            changed=true,navigators=updates)
    }
}
