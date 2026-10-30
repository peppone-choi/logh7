package org.logh7.engine

/** A committed battlefield exit addressed to the current authenticated connection. Author: 최병호. */
data class TacticalEnd(val generation:Long,val character:Int,val grid:Int,val connection:String)

/** Manual W46: hostile units sharing a grid enter tactics. Author: 최병호. */
internal object TacticalContact {
    fun contested(forces: List<GridForces.Force>): Boolean = forces.any { own ->
        own.units > 0 && forces.any { it.camp != own.camp && (it.units > 0 || it.base) }
    }
    fun endUncontested(galaxy:Galaxy,players:MutableMap<String,Navigator>,stocks:PlanetStocks,now:Long) {
        if(players.values.none {it.cpPaused})return
        val forces=GridForces.collect(galaxy,stocks.generation,players.values,stocks)
        for((account,p) in players.toMap()) if(p.cpPaused &&
            (p.baseId!=0 || p.mode!=5 || forces[p.grid].orEmpty().none {
                it.camp!=Camp(p.power,false) && (it.units>0 || it.base)
            })) {
            players[account]=p.copy(cpPaused=false,cpRecoveryAt=now,motion=null,retreat=null,
                charge=null,commandRange=null,attack=null,fire=p.fire?.copy(pending=null))
        }
    }
    fun active(galaxy: Galaxy, player: Navigator, players: Collection<Navigator>, stocks: PlanetStocks?): Boolean {
        if (player.baseId != 0 || player.mode != 5) return false
        val forces = GridForces.collect(galaxy,player.generation,
            players.filter { it.characterId != player.characterId } + player,stocks)[player.grid].orEmpty()
        val own = Camp(player.power,false)
        return forces.any { it.camp != own && (it.units > 0 || it.base) }
    }
}
