package org.logh7.engine

/** Manual W46: hostile units sharing a grid enter tactics. Author: 최병호. */
internal object TacticalContact {
    fun contested(forces: List<GridForces.Force>): Boolean = forces.any { own ->
        own.units > 0 && forces.any { it.camp != own.camp && (it.units > 0 || it.base) }
    }
    fun active(galaxy: Galaxy, player: Navigator, players: Collection<Navigator>, stocks: PlanetStocks?): Boolean {
        if (player.baseId != 0 || player.mode != 5) return false
        val forces = GridForces.collect(galaxy,player.generation,
            players.filter { it.characterId != player.characterId } + player,stocks)[player.grid].orEmpty()
        val own = Camp(player.power,false)
        return forces.any { it.camp != own && (it.units > 0 || it.base) }
    }
}
