package org.logh7.gateway

import java.nio.file.Path
import org.logh7.engine.CountryCatalog
import org.logh7.engine.CountryCatalogLoader
import org.logh7.engine.PublishedOriginalCandidates
import org.logh7.engine.WorldSnapshot
import org.logh7.engine.members

/** Preserve previous generations' files; a new session never reuses its previous characters. */
class CharacterStores(private val file: Path?,sharedFame:FameLedger?=null,
    private val publishedOriginals:PublishedOriginalCandidates=PublishedOriginalCandidates(),
    private val worldSnapshot: () -> WorldSnapshot? = { null }) {
    /** U-27: account fame carried across generations. */
    val fame = sharedFame ?: FameLedger(file?.resolveSibling("${file.fileName}.fame"))
    private var cached: Pair<Long, CharacterCreation>? = null
    private val memory = mutableMapOf<Long,CharacterCreation>()
    private fun path(generation: Long) = if (generation == 1L) file else file?.resolveSibling("${file.fileName}.session-$generation")
    @Synchronized fun get(generation: Long): CharacterCreation {
        require(generation > 0)
        if(file==null) {
            // Keep the two latest generations across rollover; late lookups cannot evict either.
            val oldest=maxOf(generation,memory.keys.maxOrNull() ?: generation)-1
            memory.keys.removeAll {it<oldest}
            return if(generation<oldest)open(generation) else memory.getOrPut(generation) {open(generation)}
        }
        cached?.takeIf { it.first == generation }?.let { return it.second }
        return open(generation).also { cached = generation to it }
    }
    private fun worldCharacterIds(generation: Long): Sequence<Int> {
        val snapshot = worldSnapshot()?.takeIf { it.session?.generation == generation }
        val navigators = snapshot?.navigators.orEmpty().values.asSequence()
            .filter { it.generation == generation }.map { it.characterId }
        val stocks = snapshot?.planetStocks?.takeIf { it.generation == generation } ?: return navigators
        // An initial or legacy office holder may have no navigator or strategic person row yet.
        val baseOfficers = stocks.bases.values.asSequence().flatMap {
            sequenceOf(it.governorId,it.garrisonCommanderId,it.capitalAdministratorId,it.capitalDefenseCommanderId,it.guardInspectorId)
        }
        return navigators + stocks.strategic.people.keys.asSequence() + stocks.strategic.offices.asSequence().map { it.character } +
            stocks.nationalOffices.values.asSequence() + stocks.logisticsOfficers.keys.asSequence() + baseOfficers +
            stocks.outfits.values.asSequence().flatMap { it.members() }
    }
    private fun open(generation:Long)=CharacterCreation(path(generation),accountFame=fame.accounts,publishedOriginals=publishedOriginals,
        worldOccupiedOriginals = { worldCharacterIds(generation).filter { it in 1..9999 }.map { it.toLong() }.toSet() },
        worldGeneratedIdFloor = {
            (worldCharacterIds(generation).filter {it>=10_000}.maxOrNull() ?: 9_999).toLong()+1
        },
        lotteryOpen = {
            worldSnapshot()?.session?.let { it.generation == generation && it.ended == null } ?: true
        })
    /** U-27 carry-over (W p.11): writes this generation's qualifying characters into the next generation's store. */
    @Synchronized fun carryOver(snapshot: WorldSnapshot, countries: CountryCatalog = CountryCatalogLoader.current()): List<String> {
        val generation = snapshot.session?.generation ?: return emptyList()
        if (snapshot.result == null) return emptyList()
        val current=get(generation)
        val profiles=WorldCharacters.profiles(current.profiles(),snapshot)
        val carried = CharacterCarryOver.select(snapshot, profiles.filterValues {it.character.generated}.mapValues {it.value.character})
        if (carried.isEmpty()) return emptyList()
        val nextGeneration=generation+1
        val next=if(file==null)get(nextGeneration)
            else cached?.takeIf {it.first==nextGeneration}?.second ?: open(nextGeneration)
        return carried.filter { (account, data) ->
            val profile=profiles.getValue(account)
            val kind=org.logh7.engine.Flagships.forType(profile.shipStyle.nativePower,data.shipType).kind
            val country=profile.country?.let {countries.countries[it.value]}
            val founded=org.logh7.engine.CountryFounding.isFounded(country)
            val carriedProfile=if(founded)profile.copy(country=null) else profile
            val power=if(carriedProfile.country==null)profile.shipStyle.nativePower
                else country?.nativePower?.takeIf {it in 2..3} ?: data.power
            next.carry(account,carriedProfile.copy(character=data.copy(power=power,shipKind=kind,rank=if(founded)13 else data.rank)))
        }.keys.toList()
    }
}
