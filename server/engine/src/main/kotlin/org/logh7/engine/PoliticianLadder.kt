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
    fun monthly(galaxy:Galaxy,stocks:PlanetStocks,identities:List<StrategicIdentity>,month:Long):PlanetStocks {
        var people=stocks.strategic.people
        for(identity in identities.sortedBy {it.id}) {
            val person=people[identity.id] ?: StrategicPerson()
            if((person.rank ?: identity.rank)!=POLITICIAN || person.merit!=0L)continue
            people=people+(identity.id to if(identity.power==3)person.copy(rank=CONVERTED_RANK,militaryRank=0) else {
                val abilities=(person.abilities ?: identity.abilities).toMutableList()
                val index=java.util.Random(month*1_000_003L+identity.id).nextInt(abilities.size)
                abilities[index]=maxOf(0,abilities[index]-1)
                person.copy(abilities=abilities)
            })
        }
        var next=stocks.copy(strategic=stocks.strategic.copy(people=people))
        val policy=OrganizationalOfficePolicy(galaxy)
        val funds=next.social.funds
        val (yes,no)=funds.confidence[3] ?: (0L to 0L)
        if(no>yes)policy.holders(next,CHAIRMAN_CARD).forEach {next=policy.set(next,it,remove=true)}
        if(funds.confidence.isNotEmpty())next=next.copy(social=next.social.copy(funds=funds.copy(confidence=emptyMap())))
        // Imperial succession (SuccessionActions, user decision 2026-11-02) replaces the politician-ladder heir.
        next=SuccessionActions.accede(galaxy,next,identities,month*PERIOD_DAYS*Justice.DAY)
        return election(galaxy,next,identities)
    }
    fun chairmanVacant(galaxy:Galaxy,stocks:PlanetStocks):Boolean {
        val policy=OrganizationalOfficePolicy(galaxy)
        val capital=galaxy.bases.singleOrNull {Capital.of(it)==Capital.ALLIANCE} ?: return false
        val role=OrganizationOffices.of(CHAIRMAN_CARD) ?: return false
        return policy.active(stocks,role,capital.id) && policy.holders(stocks,CHAIRMAN_CARD).isEmpty() && !stocks.social.coup.revolting(3)
    }
    /** 議長選出: fills a vacant chairman seat from the alliance politicians (see the class comment). */
    fun election(galaxy:Galaxy,stocks:PlanetStocks,identities:List<StrategicIdentity>):PlanetStocks {
        if(!chairmanVacant(galaxy,stocks))return stocks
        val people=stocks.strategic.people;val support=stocks.social.funds.support
        val candidates=identities.filter {it.power==3 && (people[it.id]?.rank ?: it.rank)==POLITICIAN && (people[it.id]?.deceasedAt ?: 0)==0L}
        val winner=candidates.sortedWith(compareByDescending<StrategicIdentity> {support[it.id] ?: 0L}
            .thenByDescending {people[it.id]?.merit ?: 0L}.thenBy {it.id}).firstOrNull() ?: return stocks
        val capital=galaxy.bases.single {Capital.of(it)==Capital.ALLIANCE}
        val elected=OrganizationalOfficePolicy(galaxy).set(stocks,OfficeHolding(CHAIRMAN_CARD,capital.id,winner.id))
        val ids=candidates.map {it.id}.toSet()
        return elected.copy(social=elected.social.copy(funds=elected.social.funds.copy(support=support.filterKeys {it !in ids})))
    }
}
