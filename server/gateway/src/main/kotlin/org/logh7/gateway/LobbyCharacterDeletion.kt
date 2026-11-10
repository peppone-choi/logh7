package org.logh7.gateway

import org.logh7.engine.CharacterDeletionResult
import java.util.concurrent.CompletableFuture

// The CD's ordinary 032e encoder is available in the lobby. 2008 has no confirmed encoder.
internal const val LOBBY_DELETE_MODE = 24

/** The caller supplies the authenticated account. Generation and character must match the confirmed lobby selection. */
internal fun deleteLobbyCharacter(account: String, generation: Long, character: Int, currentGeneration: Long,
    characters: CharacterCreation, stock: GameStock?): CompletableFuture<CharacterDeletionResult> {
    val registered = characters.character(account)
    if (generation != currentGeneration || registered?.generated != true || registered.id != character.toLong())
        return CompletableFuture.completedFuture(CharacterDeletionResult.INELIGIBLE)
    if (stock == null) return CompletableFuture.completedFuture(CharacterDeletionResult.UNAVAILABLE)
    return stock.deleteGeneratedCharacter(account, generation, character, registered.rank).thenApply { result ->
        if (result == CharacterDeletionResult.DELETED) characters.reconcileVoluntaryDeletion(account, setOf(character))
        result
    }
}
