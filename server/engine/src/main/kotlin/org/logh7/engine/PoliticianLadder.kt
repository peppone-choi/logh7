package org.logh7.engine

/** U-28 politician-ladder special rules (CD manual p.38, evidence:manual CD edition).
 * Every 30 game days a politician (rank 1) with zero merit is handled by faction:
 * the alliance turns them into a civilian official at lieutenant-commander rank (10),
 * the empire lowers one random ability. When the emperor's seat is vacant (no coup
 * mechanism exists yet, so every vacancy counts as non-coup), the top of the empire
 * politician ladder (highest merit, then lowest ID) becomes emperor.
 * The ability decrease of 1 and the deterministic per-person random are evidence:guess.
 * Author: Peppone Choi. */
object PoliticianLadder {
    const val PERIOD_DAYS=30L
    const val POLITICIAN=1
    const val CONVERTED_RANK=10
    const val EMPEROR_CARD=1
    fun monthly(galaxy:Galaxy,stocks:PlanetStocks,identities:List<StrategicIdentity>,month:Long):PlanetStocks {
        var people=stocks.strategic.people
        for(identity in identities.sortedBy {it.id}) {
            val person=people[identity.id] ?: StrategicPerson()
            if((person.rank ?: identity.rank)!=POLITICIAN || person.merit!=0L)continue
            people=people+(identity.id to if(identity.power==3)person.copy(rank=CONVERTED_RANK) else {
                val abilities=(person.abilities ?: identity.abilities).toMutableList()
                val index=java.util.Random(month*1_000_003L+identity.id).nextInt(abilities.size)
                abilities[index]=maxOf(0,abilities[index]-1)
                person.copy(abilities=abilities)
            })
        }
        var next=stocks.copy(strategic=stocks.strategic.copy(people=people))
        val policy=OrganizationalOfficePolicy(galaxy)
        val capital=galaxy.bases.singleOrNull {Capital.of(it)==Capital.EMPIRE}
        val role=OrganizationOffices.of(EMPEROR_CARD)
        if(capital!=null && role!=null && policy.active(next,role,capital.id) && policy.holders(next,EMPEROR_CARD).isEmpty()) {
            val heir=identities.filter {it.power==2 && (people[it.id]?.rank ?: it.rank)==POLITICIAN}
                .sortedWith(compareByDescending<StrategicIdentity> {people[it.id]?.merit ?: 0}.thenBy {it.id}).firstOrNull()
            if(heir!=null)next=policy.set(next,OfficeHolding(EMPEROR_CARD,capital.id,heir.id))
        }
        return next
    }
}
