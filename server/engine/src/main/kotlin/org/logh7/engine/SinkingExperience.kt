package org.logh7.engine

/** U-24 (2004-06-11 patch, evidence:manual): a sunk flagship costs every ability of its character
 * experience by rank — emperor/marshal 50, senior admiral..commodore 30, captain..lieutenant
 * commander 10, lieutenant..sub-lieutenant 5, warrant officer..private 1 — and an ability whose
 * experience runs out may fall. Borrowing a full 100 from the ability (value -1, floor 1) and the
 * politician band (emperor 50, other politicians 1) are evidence:guess. Author: Peppone Choi. */
object SinkingExperience {
    fun penalty(rank:Int,emperor:Boolean):Int = when {
        emperor || rank==2 -> 50
        rank in 3..7 -> 30
        rank in 8..10 -> 10
        rank in 11..13 -> 5
        else -> 1
    }
    fun apply(stocks:PlanetStocks,victims:List<Int>,identities:List<StrategicIdentity>):PlanetStocks {
        var people=stocks.strategic.people
        var experience=stocks.social.academy.experience
        for(id in victims) {
            val identity=identities.singleOrNull {it.id==id}
            val person=people[id] ?: StrategicPerson()
            val rank=person.rank ?: identity?.rank ?: continue
            val values=(person.abilities ?: identity?.abilities ?: continue).toMutableList()
            if(id !in people && people.size>=4096 || id !in experience && experience.size>=4096)continue
            val loss=penalty(rank,stocks.strategic.offices.any {it.card==1 && it.character==id})
            val points=(experience[id] ?: List(8){0}).toMutableList()
            for(k in 0 until 8) {
                if(points[k]>=loss)points[k]-=loss
                else if(values[k]>1) {values[k]-=1;points[k]+=AbilityGrowthRules.EXPERIENCE_PER_POINT-loss}
                else points[k]=0
            }
            people=people+(id to person.copy(abilities=values.toList()))
            experience=experience+(id to points.toList())
        }
        return if(victims.isEmpty())stocks else stocks.copy(strategic=stocks.strategic.copy(people=people),
            social=stocks.social.copy(academy=stocks.social.academy.copy(experience=experience)))
    }
}
