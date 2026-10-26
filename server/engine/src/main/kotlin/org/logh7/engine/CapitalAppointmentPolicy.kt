package org.logh7.engine

/** Manual organization pp.58/64. Same-rank civil appointments use office hierarchy as explicit restoration policy. */
class CapitalAppointmentPolicy(private val galaxy:Galaxy) {
    data class Rule(val card:Int,val authority:NationalOffice,val capital:Capital,val lowest:Int) {
        val power get()=capital.power
        fun eligible(actorRank:Int,targetRank:Int):Boolean {
            if(actorRank !in authority.ranks) return false
            return if(card==192) targetRank==1 else targetRank in 2..lowest && actorRank<targetRank &&
                (power!=3 || targetRank!=3) // Alliance has no senior-admiral rank.
        }
        fun holder(base:PlanetStock)=when(card) {
            56,193->base.capitalDefenseCommanderId;57->base.guardInspectorId;192->base.capitalAdministratorId
            else->error("Unsupported capital office")
        }
        fun assign(base:PlanetStock,target:Int)=when(card) {
            56,193->base.copy(capitalDefenseCommanderId=target);57->base.copy(guardInspectorId=target)
            192->base.copy(capitalAdministratorId=target);else->error("Unsupported capital office")
        }
    }
    data class Vacancy(val rule:Rule,val base:GalaxyBase)
    fun authorized(rule:Rule,stocks:PlanetStocks,actor:Int,power:Int)=rule.power==power &&
        stocks.nationalOffices[rule.authority]==actor && rule.authority.active(galaxy,stocks)
    fun base(rule:Rule)=galaxy.bases.singleOrNull { Capital.of(it)==rule.capital }
    fun vacancies(stocks:PlanetStocks,actor:Int,power:Int,rank:Int)=rules.mapNotNull { rule ->
        if(!authorized(rule,stocks,actor,power) || rank !in rule.authority.ranks) return@mapNotNull null
        val seed=base(rule) ?: return@mapNotNull null
        val held=stocks.bases[seed.id] ?: return@mapNotNull null
        if(rule.holder(held)!=0) null else Vacancy(rule,seed)
    }
    companion object {
        val rules=listOf(Rule(56,NationalOffice.IMPERIAL_SUPREME_COMMANDER,Capital.EMPIRE,4),
            Rule(57,NationalOffice.MILITARY_MINISTER,Capital.EMPIRE,3),
            Rule(192,NationalOffice.NATIONAL_AFFAIRS_CHAIR,Capital.ALLIANCE,1),
            Rule(193,NationalOffice.DEFENSE_HEAD,Capital.ALLIANCE,8))
        fun rule(card:Int)=rules.singleOrNull { it.card==card }
    }
}
