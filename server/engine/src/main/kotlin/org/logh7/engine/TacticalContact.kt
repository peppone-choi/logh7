package org.logh7.engine

/** A committed battlefield exit addressed to the current authenticated connection. Author: Peppone Choi. */
data class TacticalEnd(val generation:Long,val character:Int,val grid:Int,val connection:String)

/** Manual W46: hostile units sharing a grid enter tactics. Author: Peppone Choi. */
internal object TacticalContact {
    private fun canParticipate(player:Navigator,stocks:PlanetStocks?,eliminated:Set<CountryId>) =
        player.country !in eliminated &&
            !BattleDeath.deceased(stocks,player.characterId) &&
            stocks?.social?.justice?.detained(player.characterId)!=true

    fun contested(forces: List<GridForces.Force>, territorial: Boolean = false): Boolean = forces.any { own ->
        own.units > 0 && forces.any { it.camp != own.camp && (it.combatPresence || territorial && it.base) }
    }
    fun endUncontested(galaxy:Galaxy,players:MutableMap<String,Navigator>,stocks:PlanetStocks,now:Long,
        eliminated:Set<CountryId> = emptySet(), ordersSettled:Boolean = true) {
        if(players.values.none {it.cpPaused})return
        val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks,eliminated)
        for((account,p) in players.toMap()) {
            if(!p.cpPaused)continue
            val eligible=canParticipate(p,stocks,eliminated)
            // Let cancellation resolve against the last tactical pose before clearing it.
            if(!eligible && !ordersSettled)continue
            if(p.baseId==0 && p.mode==5 && eligible && forces[p.grid].orEmpty().any {
                it.camp!=Camp.of(p,CoupActions.rebel(stocks,p.characterId,p.country)) && it.combatPresence
            })continue
            players[account]=p.copy(cpPaused=false,cpRecoveryAt=now,motion=null,retreat=null,
                charge=null,commandRange=null,attack=null,fire=p.fire?.copy(pending=null))
        }
    }
    fun active(galaxy: Galaxy, player: Navigator, players: Collection<Navigator>, stocks: PlanetStocks?,
        eliminated: Set<CountryId> = emptySet()): Boolean {
        if (player.baseId!=0 || player.mode!=5 || !canParticipate(player,stocks,eliminated)) return false
        val forces = GridForces.collect(galaxy,player.generation,
            players.filter { it.characterId != player.characterId } + player,stocks,eliminated)[player.grid].orEmpty()
        val own = Camp.of(player,stocks?.let { CoupActions.rebel(it,player.characterId,player.country) } == true)
        return forces.any { it.camp != own && it.combatPresence }
    }
}
