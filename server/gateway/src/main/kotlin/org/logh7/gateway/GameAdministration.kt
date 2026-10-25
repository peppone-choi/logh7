package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameAdministration {
    fun tax(request: TaxRequest): CompletableFuture<TaxResult>
    fun reorganize(request: ReorganizationRequest): CompletableFuture<ReorganizationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Reorganization is unavailable"))
    fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> = emptyList()
    fun stationedOutfit(player: Navigator): Int = 0
}
class EngineGameAdministration(private val engine: WorldEngine, private val scope: CoroutineScope, galaxy: Galaxy) : GameAdministration {
    private val baseOffices=BaseOfficeCards(galaxy)
    override fun stationedOutfit(player: Navigator): Int {
        if (player.mode != 4 || player.baseId == 0) return 0
        val stocks = engine.snapshot().planetStocks ?: return 0
        if (stocks.generation != player.generation) return 0
        // Designed affiliation: only an unambiguous command at this same base.
        return stocks.outfits.values.singleOrNull { it.commanderId == player.characterId &&
            it.power == (if (player.power == Power.EMPIRE) 2 else 3) && it.baseId == player.baseId }?.id ?: 0
    }
    override fun reorganize(request: ReorganizationRequest): CompletableFuture<ReorganizationResult> {
        val future = CompletableFuture<ReorganizationResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<ReorganizationResult>()
                engine.submit(WorldCommand.Reorganize(request, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> {
        val stocks = engine.snapshot().planetStocks ?: return emptyList()
        if (stocks.generation != generation) return emptyList()
        val appointed = stocks.bases.values.filter { it.power == power }.sortedBy { it.baseId }
            .flatMap { baseOffices.cards(it,characterId) }
        val commanders = stocks.outfits.values.filter { it.commanderId == characterId && it.power == power }
            .sortedBy { it.id }.map { outfit ->
                val kind = when (outfit.kind) {
                    OutfitKind.FLEET -> 39
                    OutfitKind.TRANSPORT -> 44
                    OutfitKind.PATROL -> 47
                    OutfitKind.GROUND -> 50
                } + if (power == 3) 136 else 0
                org.logh7.protocol.BootstrapMessages.OfficeCard(kind,outfit.id)
            }
        return (appointed + commanders).take(14)
    }
    override fun tax(request: TaxRequest): CompletableFuture<TaxResult> {
        val future = CompletableFuture<TaxResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<TaxResult>()
                engine.submit(WorldCommand.Tax(request, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
}
