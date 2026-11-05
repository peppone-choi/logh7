package org.logh7.gateway

import java.nio.file.Path
import org.logh7.engine.WorldSnapshot

/** Preserve previous generations' files; a new session never reuses its previous characters. */
class CharacterStores(private val file: Path?,sharedFame:FameLedger?=null) {
    /** U-27: account fame carried across generations. */
    val fame = sharedFame ?: FameLedger(file?.resolveSibling("${file.fileName}.fame"))
    private var cached: Pair<Long, CharacterCreation>? = null
    private fun path(generation: Long) = if (generation == 1L) file else file?.resolveSibling("${file.fileName}.session-$generation")
    @Synchronized fun get(generation: Long): CharacterCreation {
        require(generation > 0)
        cached?.takeIf { it.first == generation }?.let { return it.second }
        return CharacterCreation(path(generation), accountFame = fame.accounts).also { cached = generation to it }
    }
    /** U-27 carry-over (W p.11): writes this generation's qualifying characters into the next generation's store. */
    @Synchronized fun carryOver(snapshot: WorldSnapshot): List<String> {
        val generation = snapshot.session?.generation ?: return emptyList()
        if (snapshot.result == null) return emptyList()
        val current=get(generation)
        val profiles=WorldCharacters.profiles(current.profiles(),snapshot)
        val carried = CharacterCarryOver.select(snapshot, profiles.filterValues {it.character.generated}.mapValues {it.value.character})
        if (carried.isEmpty()) return emptyList()
        val next = cached?.takeIf { it.first == generation + 1 }?.second ?: CharacterCreation(path(generation + 1), accountFame = fame.accounts)
        return carried.filter { (account, data) ->
            val profile=profiles.getValue(account)
            val kind=org.logh7.engine.Flagships.forType(profile.shipStyle.nativePower,data.shipType).kind
            next.carry(account,profile.copy(character=data.copy(shipKind=kind)))
        }.keys.toList()
    }
}
