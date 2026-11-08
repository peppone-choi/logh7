package org.logh7.engine

import org.logh7.engine.strategy.*

/** Manual W pp.35–37/72: ladder, one-rank changes, card loss, peerage and fiefs.
 * Unrecovered ennoblement/decoration thresholds are conservative explicit restoration
 * policy: noble origin, military rank 7 or higher; titles require progressively senior ranks;
 * decorations require 100 merit per grade. Author: Peppone Choi. */
internal object PersonnelActions {
    val supported=setOf(0,1,2,3,4,8,9)
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val country=r.country ?: return reject(StrategyActionRejection.IDENTITY)
        val definition=countries.countries[country.value] ?: return reject(StrategyActionRejection.AUTHORITY)
        val family=countries.families.getValue(definition.family)
        val ranks=when(family.ranks) {"empire"->Power.EMPIRE;"alliance"->Power.ALLIANCE;else->return reject(StrategyActionRejection.AUTHORITY)}
        val offices=OrganizationOffices.system(country,countries) ?: return reject(StrategyActionRejection.AUTHORITY)
        if(r.command !in supported)return reject(StrategyActionRejection.COMMAND)
        if(r.identities.size>4096 || r.identities.distinctBy {it.id}.size!=r.identities.size)return reject(StrategyActionRejection.IDENTITY)
        // Existing estates can still be recovered from a deceased holder.
        val eligible=r.identities.filter {it.country==country && (r.command==9 || !BattleDeath.deceased(stocks,it.id))}
        val identity=eligible.singleOrNull {it.id==r.target} ?: return reject(StrategyActionRejection.IDENTITY)
        if(identity.country!=p.country || identity.id==r.characterId)return reject(StrategyActionRejection.IDENTITY)
        if(p.cpPaused || p.navigationPending)return reject(StrategyActionRejection.BUSY)
        val policy=OrganizationalOfficePolicy(galaxy,countries)
        val person=stocks.strategic.people[r.target] ?: StrategicPerson()
        val rank=person.rank ?: identity.rank
        // U-28: an empire politician is promoted on the military ladder of its kept rank.
        fun ladder(id:StrategicIdentity)=(stocks.strategic.people[id.id] ?: StrategicPerson()).let {
            if(ranks==Power.EMPIRE)it.ladderRank(id.rank) else it.rank ?: id.rank}
        fun holds(card:Int)=r.card==card && policy.holders(stocks,card,country).any {it.character==r.characterId}
        fun cardCount()=policy.held(stocks,r.target).count {it.country==country}+
            FundActions.governed(galaxy,stocks,r.target,country,countries,card=66).size
        var next=stocks
        var promoted=emptyMap<String,Navigator>()
        val cost=when(r.command){1,8,9->640L;2->320L;else->160L}
        when(r.command) {
            0,1,2 -> {
                val rank=ladder(identity)
                if(rank !in 2..20 || ranks==Power.ALLIANCE && rank==3)return reject(StrategyActionRejection.RANK)
                var changedRank=rank+if(r.command==2)1 else -1
                if(ranks==Power.ALLIANCE && changedRank==3)changedRank+=if(r.command==2)1 else -1
                if(changedRank !in 2..20)return reject(StrategyActionRejection.RANK)
                // Use the senior side of a boundary until the original jurisdiction timing is recovered.
                val senior=minOf(rank,changedRank)
                val authority=when {
                    senior==2 -> if(offices==2)1 else return reject(StrategyActionRejection.AUTHORITY)
                    senior<=7 -> if(offices==2)17 else 135
                    else -> if(offices==2)19 else 160
                }
                if(!holds(authority))return reject(StrategyActionRejection.AUTHORITY)
                val entries=eligible.filter {ladder(it)==rank}.map {id->
                    val progress=stocks.strategic.people[id.id] ?: StrategicPerson()
                    LadderEntry(id.id.toString(),progress.merit,if(ranks==Power.EMPIRE && family.peerage=="empire")progress.peerage else null,
                        progress.influence.toLong(),(progress.abilities ?: id.abilities).sumOf(Int::toLong),
                        progress.decorations.maxOrNull() ?: -1)
                }
                val top=LadderRules.topIds(ranks,entries)
                if((r.target.toString() in top)!=(r.command==0))return reject(StrategyActionRejection.RANK)
                val limited=when(changedRank){2->LimitedRank.MARSHAL;3->LimitedRank.SENIOR_ADMIRAL;4->LimitedRank.ADMIRAL;
                    5->LimitedRank.VICE_ADMIRAL;6->LimitedRank.REAR_ADMIRAL;7->LimitedRank.COMMODORE;else->null}
                if(limited!=null) {
                    val count=eligible.count {ladder(it)==changedRank}
                    val capacity=RankRules.capacity(ranks,limited) ?: return reject(StrategyActionRejection.RANK)
                    if(count>=capacity)return reject(StrategyActionRejection.MAXIMUM)
                }
                next=StrategyActions.clearOffices(stocks,r.target).let {cleared ->cleared.copy(strategic=cleared.strategic.copy(
                    people=cleared.strategic.people+(r.target to (if(person.militaryRank>0)person.copy(militaryRank=changedRank)
                        else person.copy(rank=changedRank)).copy(merit=if(r.command==2)100 else 0))))}
                // U-24: a promoted character outside battle receives the new rank's default flagship.
                if(r.command!=2)r.navigators.entries.singleOrNull {it.value.characterId==r.target && it.value.generation==r.generation}?.let {(key,other)->
                    val hull=other.hull;val now=s.gameMillis/1000
                    if(other.country==p.country && hull!=null && !other.cpPaused && hull.woundedUntil<=now) {
                        val upgrade=Flagships.rankDefault(other.shipStyle.nativePower,changedRank)
                        if(Flagships.tier(upgrade.kind)>Flagships.tier(hull.kind))
                            promoted=mapOf(key to other.copy(hull=FlagshipHull(upgrade.kind,now,clockScale=hull.clockScale),energy=null,
                                motion=null,retreat=null,charge=null,fire=null,attack=null,commandRange=null,posture=null))
                    }
                }
            }
            3 -> {
                if(family.peerage!="empire" || !holds(1))return reject(StrategyActionRejection.AUTHORITY)
                val title=Peerage.entries.getOrNull(r.value) ?: return reject(StrategyActionRejection.COMMAND)
                if(identity.origin!=0 || rank !in 2..minOf(7,title.ordinal+2))return reject(StrategyActionRejection.RANK)
                if(person.peerage?.let {title.ordinal>=it.ordinal}==true)return reject(StrategyActionRejection.MAXIMUM)
                if(person.peerage==null && cardCount()>=14)
                    return reject(StrategyActionRejection.MAXIMUM)
                next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+(r.target to person.copy(peerage=title))))
            }
            4 -> {
                val authority=if(offices==2)17 else 135
                if(!holds(authority))return reject(StrategyActionRejection.AUTHORITY)
                if(r.value !in 0..127 || rank !in 2..7 || person.merit<100L*(r.value+1))return reject(StrategyActionRejection.RANK)
                if(r.value in person.decorations)return reject(StrategyActionRejection.MAXIMUM)
                next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+
                    (r.target to person.copy(decorations=person.decorations+r.value))))
            }
            8,9 -> {
                if(family.peerage!="empire" || !holds(1))return reject(StrategyActionRejection.AUTHORITY)
                val base=stocks.bases[r.base] ?: return reject(StrategyActionRejection.LOCATION)
                val seed=galaxy.bases.singleOrNull {it.id==r.base} ?: return reject(StrategyActionRejection.LOCATION)
                if(!base.belongsTo(country) || base.camp!=0 || seed.kind!=0 || r.base in stocks.strategic.capitals.values ||
                    policy.capital(stocks,country)==r.base)return reject(StrategyActionRejection.LOCATION)
                if(r.command==8) {
                    if(person.peerage==null || person.peerage.ordinal>Peerage.BARON.ordinal)return reject(StrategyActionRejection.RANK)
                    if(r.base in stocks.strategic.fiefs)return reject(StrategyActionRejection.MAXIMUM)
                    if(cardCount()+1>=14)
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
        return StrategyActionResult(p.paidWith(paid.balance),next,changed=true,navigators=promoted)
    }
}
