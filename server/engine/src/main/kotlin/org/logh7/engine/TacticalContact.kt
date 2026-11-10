package org.logh7.engine

/** A committed battlefield exit addressed to the current authenticated connection. Author: Peppone Choi. */
data class TacticalEnd(val generation:Long,val character:Int,val grid:Int,val connection:String)

/** Manual W46: hostile units sharing a grid enter tactics. Author: Peppone Choi. */
internal object TacticalContact {
    private fun canEnter(player:Navigator,stocks:PlanetStocks?,eliminated:Set<CountryId>) =
        player.baseId==0 && player.mode==5 && player.country !in eliminated &&
            !BattleDeath.deceased(stocks,player.characterId) &&
            stocks?.social?.justice?.detained(player.characterId)!=true

    fun contested(forces: List<GridForces.Force>, territorial: Boolean = false): Boolean = forces.any { own ->
        own.units > 0 && forces.any { it.camp != own.camp && (it.combatPresence || territorial && it.base) }
    }
    fun endUncontested(galaxy:Galaxy,players:MutableMap<String,Navigator>,stocks:PlanetStocks,now:Long,
        eliminated:Set<CountryId> = emptySet()) {
        if(players.values.none {it.cpPaused})return
        val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks,eliminated)
        for((account,p) in players.toMap()) if(p.cpPaused &&
            (!canEnter(p,stocks,eliminated) || forces[p.grid].orEmpty().none {
                it.camp!=Camp.of(p,CoupActions.rebel(stocks,p.characterId,p.country)) && it.combatPresence
            })) {
            players[account]=p.copy(cpPaused=false,cpRecoveryAt=now,motion=null,retreat=null,
                charge=null,commandRange=null,attack=null,fire=p.fire?.copy(pending=null))
        }
    }
    fun active(galaxy: Galaxy, player: Navigator, players: Collection<Navigator>, stocks: PlanetStocks?,
        eliminated: Set<CountryId> = emptySet()): Boolean {
        if (!canEnter(player,stocks,eliminated)) return false
        val forces = GridForces.collect(galaxy,player.generation,
            players.filter { it.characterId != player.characterId } + player,stocks,eliminated)[player.grid].orEmpty()
        val own = Camp.of(player,stocks?.let { CoupActions.rebel(it,player.characterId,player.country) } == true)
        return forces.any { it.camp != own && it.combatPresence }
    }
}
