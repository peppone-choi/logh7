package org.logh7.engine

import org.logh7.engine.strategy.*

/** Manual W pp.35–37/72: ladder, one-rank changes, card loss, peerage and fiefs.
 * Unrecovered ennoblement/decoration thresholds are conservative explicit restoration
 * policy: noble origin, military rank 7 or higher; titles require progressively senior ranks;
 * decorations require 100 merit per grade. Author: Peppone Choi. */
internal object PersonnelActions {
    val supported=setOf(0,1,2,3,4,8,9)
    data class MonthlyPromotion(val character:Int,val country:CountryId,val rank:Int,val at:Long)
    class RankDirectory(identities:List<StrategicIdentity>,stocks:PlanetStocks,country:CountryId?,recoverEstates:Boolean=false) {
        val byId=identities.filter {it.country==country && (recoverEstates || !BattleDeath.deceased(stocks,it.id))}.associateBy {it.id}
        val valid=identities.size<=4096 && identities.distinctBy {it.id}.size==identities.size
        val top=mutableMapOf<Int,Set<String>>()
        val counts=mutableMapOf<Int,Int>()
    }
    private fun ladderRank(stocks:PlanetStocks,id:StrategicIdentity,ranks:Power):Int {
        val person=stocks.strategic.people[id.id] ?: StrategicPerson()
        return if(ranks==Power.EMPIRE)person.ladderRank(id.rank) else person.rank ?: id.rank
    }

    private fun ladderEntry(stocks:PlanetStocks,id:StrategicIdentity,peerage:Boolean):LadderEntry {
        val person=stocks.strategic.people[id.id] ?: StrategicPerson()
        return LadderEntry(id.id.toString(),TacticalPersonnel.merit(stocks,id.id),person.peerage.takeIf {peerage},
            person.influence.toLong(),(person.abilities ?: id.abilities).sumOf(Int::toLong),person.decorations.maxOrNull() ?: -1)
    }

    private fun changeRank(stocks:PlanetStocks,character:Int,rank:Int,merit:Long):PlanetStocks {
        val person=stocks.strategic.people[character] ?: StrategicPerson()
        val cleared=StrategyActions.clearOffices(stocks,character)
        val ranked=if(person.militaryRank>0)person.copy(militaryRank=rank) else person.copy(rank=rank)
        val ledger=cleared.tacticalCommands
        // A rank change replaces all prior merit, including pending tactical rewards in older saves.
        val credits=ledger.merits[character]?.let {ledger.creditedMerits+(character to it)} ?: ledger.creditedMerits
        return cleared.copy(strategic=cleared.strategic.copy(people=cleared.strategic.people+(character to ranked.copy(merit=merit))),
            tacticalCommands=ledger.copy(creditedMerits=credits))
    }

    fun upgradeFlagship(player:Navigator,rank:Int,now:Long):Navigator {
        val hull=player.hull ?: return player
        if(player.cpPaused || hull.woundedUntil>now)return player
        val upgrade=Flagships.rankDefault(player.shipStyle.nativePower,rank)
        if(Flagships.tier(upgrade.kind)<=Flagships.tier(hull.kind))return player
        return replaceFlagship(player,upgrade.kind,now,hull.clockScale)
    }

    private fun downgradeFlagship(player:Navigator,oldRank:Int,newRank:Int,now:Long):Navigator {
        val hull=player.hull ?: return player
        if(player.cpPaused || hull.woundedUntil>now || hull.marketAcquired)return player
        val power=player.shipStyle.nativePower
        // CD p.37 describes changing to the new rank's flagship. Protect non-default hulls and active combat or injury.
        if(hull.kind!=Flagships.rankDefault(power,oldRank).kind)return player
        val downgrade=Flagships.rankDefault(power,newRank)
        if(Flagships.tier(downgrade.kind)>=Flagships.tier(hull.kind))return player
        return replaceFlagship(player,downgrade.kind,now,hull.clockScale)
    }

    private fun replaceFlagship(player:Navigator,kind:Int,now:Long,clockScale:Long):Navigator =
        player.copy(hull=FlagshipHull(kind,now,clockScale=clockScale),energy=null,motion=null,
            retreat=null,charge=null,fire=null,attack=null,commandRange=null,posture=null)

    private fun limitedRank(rank:Int)=when(rank) {
        2->LimitedRank.MARSHAL;3->LimitedRank.SENIOR_ADMIRAL;4->LimitedRank.ADMIRAL
        5->LimitedRank.VICE_ADMIRAL;6->LimitedRank.REAR_ADMIRAL;7->LimitedRank.COMMODORE
        else->null
    }

    /** W pp.35–36: one unique first place per military ladder, using its round-opening ranking and quotas. */
    fun promoteMonthly(stocks:PlanetStocks,identities:List<StrategicIdentity>,eliminated:Set<CountryId>,at:Long,
        promoted:MutableList<MonthlyPromotion>,countries:CountryCatalog=CountryCatalogLoader.current(),
        actingNpcMinister:Boolean=false):PlanetStocks {
        if(identities.size>4096 || identities.distinctBy {it.id}.size!=identities.size)return stocks
        var next=stocks
        for((country,people) in identities.groupBy {it.country}) {
            if(country==null || country in eliminated || OrganizationOffices.system(country,countries)==null)continue
            val definition=countries.countries.getValue(country.value)
            val family=countries.families.getValue(definition.family)
            val ranks=when(family.ranks) {"empire"->Power.EMPIRE;"alliance"->Power.ALLIANCE;else->continue}
            val byRank=people.filterNot {BattleDeath.deceased(stocks,it.id)}.groupBy {ladderRank(stocks,it,ranks)}
            val usePeerage=ranks==Power.EMPIRE && family.peerage=="empire"
            // The Imperial minister can promote through senior admiral; marshals require the emperor.
            val npcPromotions=country==Power.EMPIRE.country && actingNpcMinister
            for(rank in (if(npcPromotions)4 else 8)..20) {
                val source=byRank[rank].orEmpty()
                val destination=byRank[rank-1].orEmpty()
                val quota=limitedRank(rank-1)?.let {RankRules.capacity(ranks,it)}
                if(source.isEmpty() || quota!=null && destination.size>=quota)continue
                val winner=LadderRules.topIds(ranks,source.map {ladderEntry(stocks,it,usePeerage)})
                    .singleOrNull()?.toInt() ?: continue
                if(winner !in next.strategic.people && next.strategic.people.size>=4096)continue
                if(rank<8 && (stocks.social.justice.detained(winner) || CoupActions.rebel(stocks,winner,country)))continue
                val merit=if(rank<8 || destination.isEmpty())0L
                    else destination.sumOf {TacticalPersonnel.merit(stocks,it.id)}/destination.size
                next=changeRank(next,winner,rank-1,merit)
                promoted+=MonthlyPromotion(winner,country,rank-1,at)
            }
        }
        return next
    }

    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,now:Long,p:Navigator,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current(),chargeCost:Boolean=true,
        directory:RankDirectory?=null):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val country=r.country ?: return reject(StrategyActionRejection.IDENTITY)
        val definition=countries.countries[country.value] ?: return reject(StrategyActionRejection.AUTHORITY)
        val family=countries.families.getValue(definition.family)
        val ranks=when(family.ranks) {"empire"->Power.EMPIRE;"alliance"->Power.ALLIANCE;else->return reject(StrategyActionRejection.AUTHORITY)}
        val offices=OrganizationOffices.system(country,countries) ?: return reject(StrategyActionRejection.AUTHORITY)
        if(r.command !in supported)return reject(StrategyActionRejection.COMMAND)
        // Existing estates can still be recovered from a deceased holder.
        val people=directory ?: RankDirectory(r.identities,stocks,country,r.command==9)
        if(!people.valid)return reject(StrategyActionRejection.IDENTITY)
        val eligible=people.byId.values
        val identity=people.byId[r.target] ?: return reject(StrategyActionRejection.IDENTITY)
        if(identity.country!=p.country || identity.id==r.characterId)return reject(StrategyActionRejection.IDENTITY)
        if(p.cpPaused || p.navigationPending)return reject(StrategyActionRejection.BUSY)
        val policy=OrganizationalOfficePolicy(galaxy,countries)
        val person=stocks.strategic.people[r.target] ?: StrategicPerson()
        val rank=person.rank ?: identity.rank
        // U-28: an empire politician is promoted on the military ladder of its kept rank.
        fun ladder(id:StrategicIdentity)=ladderRank(stocks,id,ranks)
        fun holds(card:Int)=r.card==card && policy.holders(stocks,card,country).any {it.character==r.characterId}
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
                val top=people.top.getOrPut(rank) {
                    val entries=eligible.filter {ladder(it)==rank}.map {
                        ladderEntry(stocks,it,ranks==Power.EMPIRE && family.peerage=="empire")
                    }
                    LadderRules.topIds(ranks,entries)
                }
                if((r.target.toString() in top)!=(r.command==0))return reject(StrategyActionRejection.RANK)
                val limited=limitedRank(changedRank)
                if(limited!=null) {
                    val count=people.counts.getOrPut(changedRank) {eligible.count {ladder(it)==changedRank}}
                    val capacity=RankRules.capacity(ranks,limited) ?: return reject(StrategyActionRejection.RANK)
                    if(count>=capacity)return reject(StrategyActionRejection.MAXIMUM)
                }
                if(directory!=null)return StrategyActionResult(p,stocks,changed=true)
                next=changeRank(stocks,r.target,changedRank,if(r.command==2)100 else 0)
                r.navigators.entries.singleOrNull {it.value.characterId==r.target && it.value.generation==r.generation}?.let {(key,other)->
                    if(other.country==p.country) {
                        val updated=if(r.command==2)downgradeFlagship(other,rank,changedRank,now)
                            else upgradeFlagship(other,changedRank,now)
                        if(updated!==other)promoted=mapOf(key to updated)
                    }
                }
            }
            3 -> {
                if(family.peerage!="empire" || !holds(1))return reject(StrategyActionRejection.AUTHORITY)
                val title=Peerage.entries.getOrNull(r.value) ?: return reject(StrategyActionRejection.COMMAND)
                if(identity.origin!=0 || rank !in 2..minOf(7,title.ordinal+2))return reject(StrategyActionRejection.RANK)
                if(person.peerage?.let {title.ordinal>=it.ordinal}==true)return reject(StrategyActionRejection.MAXIMUM)
                if(person.peerage==null && policy.cardCount(stocks,r.target)>=14)
                    return reject(StrategyActionRejection.MAXIMUM)
                next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+(r.target to person.copy(peerage=title))))
            }
            4 -> {
                val authority=if(offices==2)17 else 135
                if(!holds(authority))return reject(StrategyActionRejection.AUTHORITY)
                if(r.value !in 0..127 || rank !in 2..7 || TacticalPersonnel.merit(stocks,r.target)<100L*(r.value+1))
                    return reject(StrategyActionRejection.RANK)
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
                    if(policy.cardCount(stocks,r.target)>=14)
                        return reject(StrategyActionRejection.MAXIMUM)
                    next=stocks.copy(strategic=stocks.strategic.copy(fiefs=stocks.strategic.fiefs+(r.base to r.target)))
                } else {
                    if(stocks.strategic.fiefs[r.base]!=r.target)return reject(StrategyActionRejection.AUTHORITY)
                    next=stocks.copy(strategic=stocks.strategic.copy(fiefs=stocks.strategic.fiefs-r.base))
                }
            }
        }
        if(!chargeCost)return StrategyActionResult(p,next,changed=true,navigators=promoted)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(p.paidWith(paid.balance),next,changed=true,navigators=promoted)
    }
}
