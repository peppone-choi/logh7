package org.logh7.gateway

import java.nio.file.Path
import org.logh7.engine.PublishedOriginalCandidates
import org.logh7.engine.WorldSnapshot

/** Preserve previous generations' files; a new session never reuses its previous characters. */
class CharacterStores(private val file: Path?,sharedFame:FameLedger?=null,
    private val publishedOriginals:PublishedOriginalCandidates=PublishedOriginalCandidates(),
    private val worldSnapshot: () -> WorldSnapshot? = { null }) {
    /** U-27: account fame carried across generations. */
    val fame = sharedFame ?: FameLedger(file?.resolveSibling("${file.fileName}.fame"))
    private var cached: Pair<Long, CharacterCreation>? = null
    private fun path(generation: Long) = if (generation == 1L) file else file?.resolveSibling("${file.fileName}.session-$generation")
    @Synchronized fun get(generation: Long): CharacterCreation {
        require(generation > 0)
        cached?.takeIf { it.first == generation }?.let { return it.second }
        return open(generation).also { cached = generation to it }
    }
    private fun open(generation:Long)=CharacterCreation(path(generation),accountFame=fame.accounts,publishedOriginals=publishedOriginals,
        worldOccupiedOriginals = {
            val snapshot = worldSnapshot()?.takeIf { it.session?.generation == generation }
            val navigators = snapshot?.navigators.orEmpty().values.asSequence()
                .filter { it.generation == generation }.map { it.characterId }
            // Preserve retired/deceased IDs, and reserve living personnel even without an account record.
            val personnel = snapshot?.planetStocks?.takeIf { it.generation == generation }
                ?.strategic?.people.orEmpty().keys.asSequence()
            (navigators + personnel).filter { it in 1..9999 }.map { it.toLong() }.toSet()
        })
    /** U-27 carry-over (W p.11): writes this generation's qualifying characters into the next generation's store. */
    @Synchronized fun carryOver(snapshot: WorldSnapshot): List<String> {
        val generation = snapshot.session?.generation ?: return emptyList()
        if (snapshot.result == null) return emptyList()
        val current=get(generation)
        val profiles=WorldCharacters.profiles(current.profiles(),snapshot)
        val carried = CharacterCarryOver.select(snapshot, profiles.filterValues {it.character.generated}.mapValues {it.value.character})
        if (carried.isEmpty()) return emptyList()
        val next = cached?.takeIf { it.first == generation + 1 }?.second ?: open(generation + 1)
        return carried.filter { (account, data) ->
            val profile=profiles.getValue(account)
            val kind=org.logh7.engine.Flagships.forType(profile.shipStyle.nativePower,data.shipType).kind
            val founded=org.logh7.engine.CountryFounding.isFounded(
                profile.country?.let {org.logh7.engine.CountryCatalogLoader.current().countries[it.value]})
            val carriedProfile=if(founded)profile.copy(country=null) else profile
            next.carry(account,carriedProfile.copy(character=data.copy(shipKind=kind,rank=if(founded)13 else data.rank)))
        }.keys.toList()
    }
}
