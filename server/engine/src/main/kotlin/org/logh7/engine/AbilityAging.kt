package org.logh7.engine

/** U-24 age effects (2004-05-20 patch: abilities of 51+ may fall with age; 2004-06-11: young
 * characters grow over time and are exempt from ageing). The patches give no ages or odds, so the
 * numbers are the user-approved provisional proposal of 2026-10-31 (evidence:guess): at every 30-day
 * boundary a character's age is its registered age plus elapsed game years (12 periods); from 50,
 * each ability of 51+ falls by 1 with (age-49)% odds; under 20, each ability rises by 1 with
 * (20-age)*5% odds up to the cap. The per-person random is deterministic. Author: Peppone Choi. */
object AbilityAging {
    const val OLD=50
    const val YOUNG=20
    const val PERIODS_PER_YEAR=12L
    fun monthly(stocks:PlanetStocks,identities:List<StrategicIdentity>,month:Long):PlanetStocks {
        var people=stocks.strategic.people
        for(identity in identities.sortedBy {it.id}) {
            if(identity.age<=0)continue
            val age=identity.age+month/PERIODS_PER_YEAR
            if(age in YOUNG until OLD)continue
            val person=people[identity.id] ?: StrategicPerson()
            if(identity.id !in people && people.size>=4096)continue
            val values=(person.abilities ?: identity.abilities).toMutableList()
            val random=java.util.Random(month*1_000_003L+identity.id*31L+7)
            for(k in values.indices) {
                val roll=random.nextInt(100)
                if(age>=OLD && values[k]>=51 && roll<age-49)values[k]-=1
                else if(age<YOUNG && values[k]<AcademyActions.ABILITY_CAP && roll<(YOUNG-age)*5)values[k]+=1
            }
            if(values!=(person.abilities ?: identity.abilities))people=people+(identity.id to person.copy(abilities=values.toList()))
        }
        return if(people==stocks.strategic.people)stocks else stocks.copy(strategic=stocks.strategic.copy(people=people))
    }
}
