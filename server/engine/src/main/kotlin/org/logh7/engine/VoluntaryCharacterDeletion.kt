package org.logh7.engine

enum class CharacterDeletionResult { DELETED, INELIGIBLE, BUSY, UNAVAILABLE }

/** W p.12: generated characters of colonel rank or lower can leave play from their own room. */
internal object VoluntaryCharacterDeletion {
    fun eligibility(galaxy: Galaxy, stocks: PlanetStocks, player: Navigator, registeredRank: Int,
        countries: CountryCatalog): CharacterDeletionResult? {
        val id = player.characterId
        val rank = stocks.strategic.people[id]?.rank ?: registeredRank
        if (id < 10_000 || rank !in 8..20 || BattleDeath.deceased(stocks, id)) return CharacterDeletionResult.INELIGIBLE
        val ownRoom = player.mode in 1..4 && player.spotOwner == id &&
            galaxy.bases.any { it.id == player.baseId && it.grid == player.grid } &&
            galaxy.facilitiesAt(player.baseId, stocks, countries).any { facility ->
                facility.spots.any { spot ->
                    spot.id == player.spotId && spot.access == SpotAccess.PRIVATE &&
                        (facility.kind == 16 && spot.kind == 12 || facility.kind == 17 && spot.kind == 13)
                }
            }
        if (!ownRoom) return CharacterDeletionResult.INELIGIBLE
        if (player.cpPaused || player.navigationPending || !player.strategy?.jobs.isNullOrEmpty() ||
            stocks.social.justice.detained(id) || stocks.strategic.suppressions.any { it.character == id })
            return CharacterDeletionResult.BUSY
        return null
    }

    fun remove(stocks: PlanetStocks, account: String, id: Int): PlanetStocks {
        val released = StrategyActions.clearOffices(stocks, id)
        return released.copy(
            deletedCharacters = released.deletedCharacters + (account to (released.deletedCharacters[account].orEmpty() + id)),
            strategic = released.strategic.copy(people = released.strategic.people - id,
                fiefs = released.strategic.fiefs.filterValues { it != id }),
            tacticalCommands = released.tacticalCommands.copy(authority = released.tacticalCommands.authority.filterValues { it != id }))
    }
}
