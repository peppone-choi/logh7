package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameAdministration {
    fun tax(request: TaxRequest): CompletableFuture<TaxResult>
    fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> = emptyList()
}
class EngineGameAdministration(private val engine: WorldEngine, private val scope: CoroutineScope) : GameAdministration {
    override fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> {
        val stocks = engine.snapshot().planetStocks ?: return emptyList()
        if (stocks.generation != generation) return emptyList()
        val governors = stocks.bases.values.filter { it.governorId == characterId && it.power == power && it.camp == 0 }
            .sortedBy { it.baseId }.map { org.logh7.protocol.BootstrapMessages.OfficeCard(if (power == 2) 54 else 190,it.baseId) }
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
        return (governors + commanders).take(14)
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
