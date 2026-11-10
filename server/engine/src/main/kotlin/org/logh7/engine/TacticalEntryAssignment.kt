package org.logh7.engine

/** Assigns unclaimed units at an actual tactical entry using the world actor's presence snapshot. */
internal object TacticalEntryAssignment {
    fun settle(session: SessionState, galaxy: Galaxy, before: Map<String, Navigator>,
        after: Map<String, Navigator>, stocks: PlanetStocks,
        identities: () -> Collection<StrategicIdentity>): PlanetStocks {
        if (session.ended != null) return stocks
        fun tactical(player: Navigator) = player.cpPaused && player.baseId == 0 && player.mode == 5
        val field = after.filterValues(::tactical)
        val entering = field.filter { (account, player) ->
            before[account]?.let { tactical(it) && it.grid == player.grid && it.characterId == player.characterId } != true
        }.values.mapTo(hashSetOf()) { it.characterId }
        val existing = stocks.tacticalCommands.entryAuthority
        if (entering.isEmpty() && existing.isEmpty()) return stocks

        val participants = field.entries.groupBy { it.value.grid }
        val profiles by lazy { identities().associateBy { it.id } }
        val grids = galaxy.bases.associate { it.id to it.grid }
        val assigned = linkedMapOf<Int, Int>()
        for (outfit in stocks.outfits.values) {
            if (outfit.country in session.eliminated) continue
            val camp = TacticalAuthority.camp(outfit, stocks)
            val position = outfit.positionIn(galaxy)
            val units = linkedMapOf<Int, Int>()
            if (position.baseId == 0) outfit.activeShips?.values.orEmpty().filter { it.boats > 0 }
                .forEach { units[it.id] = position.grid }
            for (unit in outfit.activeGround?.values.orEmpty()) {
                val grid = if (unit.base != 0) grids[unit.base] else position.grid.takeIf { position.baseId == 0 }
                if (grid != null) units[unit.id] = grid
            }
            for ((grid, unitIds) in units.entries.groupBy({ it.value }, { it.key })) {
                val eligible = participants[grid].orEmpty().filter { (_, player) ->
                    player.characterId in outfit.members() && TacticalAuthority.camp(player, stocks) == camp &&
                        !BattleDeath.deceased(stocks, player.characterId) && !stocks.social.justice.detained(player.characterId) &&
                        (player.hull?.woundedUntil ?: 0) <= session.gameMillis / 1000
                }
                val holders = eligible.mapTo(hashSetOf()) { it.value.characterId }
                val available = unitIds.filterNot { it in stocks.tacticalCommands.authority }
                for (unit in available) existing[unit]?.takeIf { it in holders }?.let { assigned[unit] = it }
                val unassigned = available.filterNot { it in assigned }
                if (unassigned.isEmpty() || holders.none { it in entering }) continue
                val candidates = eligible.distinctBy { it.value.characterId }.map { (account, player) ->
                    val id = player.characterId
                    val person = stocks.strategic.people[id]
                    val fallbackRank = profiles[id]?.rank ?: Int.MAX_VALUE
                    val rank = person?.ladderRank(fallbackRank) ?: fallbackRank
                    CommandCandidate(id.toString(), session.participants[account]?.online == true,
                        Int.MAX_VALUE - rank, person?.evaluation ?: 0, person?.merit ?: 0)
                }
                val winners = TacticalCommandRules.authorityHolders(candidates).map { it.toInt() }
                val winner = outfit.commanderId.takeIf { it in winners } ?: winners.minOrNull() ?: continue
                for (unit in unassigned) assigned[unit] = winner
            }
        }
        return if (assigned == existing) stocks else stocks.copy(tacticalCommands = stocks.tacticalCommands.copy(entryAuthority = assigned))
    }
}
