package org.logh7.engine

import org.logh7.engine.strategy.*

/** Manual W pp.35–37/72: ladder, one-rank changes, card loss, peerage and fiefs.
 * Unrecovered ennoblement/decoration thresholds are conservative explicit restoration
 * policy: noble origin, military rank 7 or higher; titles require progressively senior ranks;
 * decorations require 100 merit per grade. Author: 최병호. */
internal object PersonnelActions {
    val supported=setOf(0,1,2,3,4,8,9)
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val power=if(r.power==Power.EMPIRE)2 else 3
        if(r.identities.size>4096 || r.identities.distinctBy {it.id}.size!=r.identities.size)return reject(StrategyActionRejection.IDENTITY)
        val identity=r.identities.singleOrNull {it.id==r.target} ?: return reject(StrategyActionRejection.IDENTITY)
        if(identity.power!=power || identity.id==r.characterId)return reject(StrategyActionRejection.IDENTITY)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null)return reject(StrategyActionRejection.BUSY)
        val policy=OrganizationalOfficePolicy(galaxy)
        val person=stocks.strategic.people[r.target] ?: StrategicPerson()
        val rank=person.rank ?: identity.rank
        val actorRank=stocks.strategic.people[r.characterId]?.rank ?: r.rank
        fun holds(card:Int)=r.card==card && policy.holders(stocks,card).any {it.character==r.characterId}
        var next=stocks
        val cost=when(r.command){1,8,9->640L;2->320L;else->160L}
        when(r.command) {
            0,1,2 -> {
                if(rank !in 2..20 || power==3 && rank==3)return reject(StrategyActionRejection.RANK)
                var changedRank=rank+if(r.command==2)1 else -1
                if(power==3 && changedRank==3)changedRank+=if(r.command==2)1 else -1
                if(changedRank !in 2..20)return reject(StrategyActionRejection.RANK)
                // Use the senior side of a boundary until the original jurisdiction timing is recovered.
                val senior=minOf(rank,changedRank)
                val authority=when {
                    senior==2 -> if(power==2)1 else return reject(StrategyActionRejection.AUTHORITY)
                    senior<=7 -> if(power==2)17 else 135
                    else -> if(power==2)19 else 160
                }
                if(!holds(authority))return reject(StrategyActionRejection.AUTHORITY)
                val entries=r.identities.filter {it.power==power && (stocks.strategic.people[it.id]?.rank ?: it.rank)==rank}.map {id->
                    val progress=stocks.strategic.people[id.id] ?: StrategicPerson()
                    LadderEntry(id.id.toString(),progress.merit,if(power==2)progress.peerage else null,
                        progress.influence.toLong(),(progress.abilities ?: id.abilities).sumOf(Int::toLong))
                }
                val top=LadderRules.topIds(r.power,entries)
                if((r.target.toString() in top)!=(r.command==0))return reject(StrategyActionRejection.RANK)
                val limited=when(changedRank){2->LimitedRank.MARSHAL;3->LimitedRank.SENIOR_ADMIRAL;4->LimitedRank.ADMIRAL;
                    5->LimitedRank.VICE_ADMIRAL;6->LimitedRank.REAR_ADMIRAL;7->LimitedRank.COMMODORE;else->null}
                if(limited!=null) {
                    val count=r.identities.count {it.power==power && (stocks.strategic.people[it.id]?.rank ?: it.rank)==changedRank}
                    val capacity=RankRules.capacity(r.power,limited) ?: return reject(StrategyActionRejection.RANK)
                    if(count>=capacity)return reject(StrategyActionRejection.MAXIMUM)
                }
                next=StrategyActions.clearOffices(stocks,r.target).let {cleared ->cleared.copy(strategic=cleared.strategic.copy(
                    people=cleared.strategic.people+(r.target to person.copy(rank=changedRank,merit=if(r.command==2)100 else 0))))}
            }
            3 -> {
                if(power!=2 || !holds(1))return reject(StrategyActionRejection.AUTHORITY)
                val title=Peerage.entries.getOrNull(r.value) ?: return reject(StrategyActionRejection.COMMAND)
                if(identity.origin!=0 || rank !in 2..minOf(7,title.ordinal+2))return reject(StrategyActionRejection.RANK)
                if(person.peerage?.let {title.ordinal>=it.ordinal}==true)return reject(StrategyActionRejection.MAXIMUM)
                if(person.peerage==null && policy.held(stocks,r.target).size+stocks.strategic.fiefs.values.count {it==r.target}>=14)
                    return reject(StrategyActionRejection.MAXIMUM)
                next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+(r.target to person.copy(peerage=title))))
            }
            4 -> {
                val authority=if(power==2)17 else 135
                if(!holds(authority))return reject(StrategyActionRejection.AUTHORITY)
                if(r.value !in 0..127 || rank !in 2..7 || person.merit<100L*(r.value+1))return reject(StrategyActionRejection.RANK)
                if(r.value in person.decorations)return reject(StrategyActionRejection.MAXIMUM)
                next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+
                    (r.target to person.copy(decorations=person.decorations+r.value))))
            }
            8,9 -> {
                if(power!=2 || !holds(1))return reject(StrategyActionRejection.AUTHORITY)
                val base=stocks.bases[r.base] ?: return reject(StrategyActionRejection.LOCATION)
                val seed=galaxy.bases.singleOrNull {it.id==r.base} ?: return reject(StrategyActionRejection.LOCATION)
                if(base.power!=2 || base.camp!=0 || seed.kind!=0 || Capital.of(seed)!=null)return reject(StrategyActionRejection.LOCATION)
                if(r.command==8) {
                    if(person.peerage==null || person.peerage.ordinal>Peerage.BARON.ordinal)return reject(StrategyActionRejection.RANK)
                    if(r.base in stocks.strategic.fiefs)return reject(StrategyActionRejection.MAXIMUM)
                    if(policy.held(stocks,r.target).size+1+stocks.strategic.fiefs.values.count {it==r.target}>=14)
                        return reject(StrategyActionRejection.MAXIMUM)
                    next=stocks.copy(strategic=stocks.strategic.copy(fiefs=stocks.strategic.fiefs+(r.base to r.target)))
                } else {
                    if(stocks.strategic.fiefs[r.base]!=r.target)return reject(StrategyActionRejection.AUTHORITY)
                    next=stocks.copy(strategic=stocks.strategic.copy(fiefs=stocks.strategic.fiefs-r.base))
                }
            }
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,changed=true)
    }
}
