package org.logh7.engine

/** U-28 politician-ladder special rules (CD manual p.38, evidence:manual CD edition).
 * Every 30 game days a politician (rank 1) with zero merit is handled by faction:
 * the alliance turns them into a civilian official at lieutenant-commander rank (10),
 * the empire lowers one random ability. When the emperor's seat is vacant for a reason other
 * than a coup, the top of the empire politician ladder (highest merit, then lowest ID) becomes
 * emperor; while an empire revolt is under way (U-10) the seat stays to the coup's outcome.
 * The ability decrease of 1 and the deterministic per-person random are evidence:guess.
 * U-19 (proposal 2026-11-02, evidence:guess): at each 30-day boundary the alliance 信任ボックス is tallied and the
 * chairman is removed when "against" exceeds "for" (the emperor faces no vote); both boxes are then emptied.
 * At the same boundary a vacant chairman seat is filled by the alliance politician with the largest 支持ボックス total
 * (ties and an empty box fall to the ladder: merit, then lowest ID); the support box is emptied after the election.
 * Author: Peppone Choi. */
object PoliticianLadder {
    const val PERIOD_DAYS=30L
    const val POLITICIAN=1
    const val CONVERTED_RANK=10
    const val EMPEROR_CARD=1
    const val CHAIRMAN_CARD=132
    fun monthly(galaxy:Galaxy,stocks:PlanetStocks,identities:List<StrategicIdentity>,month:Long,catalog:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        var next=stocks
        for(identity in identities.sortedBy {it.id}) {
            val country=identity.country ?: continue
            val definition=catalog.countries[country.value] ?: continue
            val ranks=catalog.families.getValue(definition.family).ranks
            if(ranks !in setOf("empire","alliance") || OrganizationOffices.system(country,catalog)==null)continue
            val person=next.strategic.people[identity.id] ?: StrategicPerson()
            if((person.rank ?: identity.rank)!=POLITICIAN || person.merit!=0L || person.deceasedAt!=0L)continue
            val updated=if(ranks=="alliance") {
                next=StrategyActions.clearOffices(next,identity.id)
                person.copy(rank=CONVERTED_RANK,militaryRank=0)
            } else {
                val abilities=(person.abilities ?: identity.abilities).toMutableList()
                val index=java.util.Random(month*1_000_003L+identity.id).nextInt(abilities.size)
                abilities[index]=maxOf(0,abilities[index]-1)
                person.copy(abilities=abilities)
            }
            next=next.copy(strategic=next.strategic.copy(people=next.strategic.people+(identity.id to updated)))
        }
        val policy=OrganizationalOfficePolicy(galaxy,catalog)
        for(id in catalog.countries.keys.sorted()) {
            val country=CountryId(id)
            if(OrganizationOffices.system(country,catalog)!=3)continue
            val funds=next.social.funds
            val (yes,no)=funds.confidence[id] ?: (0L to 0L)
            if(no>yes)policy.holders(next,CHAIRMAN_CARD,country).forEach {next=policy.set(next,it,remove=true)}
            if(id in funds.confidence)next=next.copy(social=next.social.copy(funds=funds.copy(confidence=funds.confidence-id)))
        }
        // Imperial succession (SuccessionActions, user decision 2026-11-02) replaces the politician-ladder heir.
        next=SuccessionActions.accede(galaxy,next,identities,month*PERIOD_DAYS*Justice.DAY,catalog)
        return election(galaxy,next,identities,catalog)
    }
    fun chairmanVacant(galaxy:Galaxy,stocks:PlanetStocks,country:CountryId=CountryId(3),catalog:CountryCatalog=CountryCatalogLoader.current()):Boolean {
        val policy=OrganizationalOfficePolicy(galaxy,catalog)
        val capital=policy.capital(stocks,country) ?: return false
        val role=policy.role(CHAIRMAN_CARD,country) ?: return false
        return policy.active(stocks,role,capital) && policy.holders(stocks,CHAIRMAN_CARD,country).isEmpty() && !stocks.social.coup.revolting(country)
    }
    /** 議長選出: fills a vacant chairman seat from the alliance politicians (see the class comment). */
    fun election(galaxy:Galaxy,stocks:PlanetStocks,identities:List<StrategicIdentity>,catalog:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        var next=stocks
        for(id in catalog.countries.keys.sorted()) {
            val country=CountryId(id)
            if(!chairmanVacant(galaxy,next,country,catalog))continue
            val people=next.strategic.people;val support=next.social.funds.support
            val candidates=identities.filter {it.country==country && (people[it.id]?.rank ?: it.rank)==POLITICIAN && (people[it.id]?.deceasedAt ?: 0)==0L && !CoupActions.rebel(next,it.id,country)}
            val winner=candidates.sortedWith(compareByDescending<StrategicIdentity> {support[it.id] ?: 0L}
                .thenByDescending {people[it.id]?.merit ?: 0L}.thenBy {it.id}).firstOrNull() ?: continue
            val policy=OrganizationalOfficePolicy(galaxy,catalog)
            val capital=checkNotNull(policy.capital(next,country))
            val elected=policy.set(next,OfficeHolding(CHAIRMAN_CARD,capital,winner.id,country))
            val ids=candidates.map {it.id}.toSet()
            next=elected.copy(social=elected.social.copy(funds=elected.social.funds.copy(support=support.filterKeys {it !in ids})))
        }
        return next
    }
}
