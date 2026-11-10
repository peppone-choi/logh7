package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameStock {
    fun snapshot(generation: Long): CompletableFuture<PlanetStocks>
    fun deleteGeneratedCharacter(account: String, generation: Long, character: Int, registeredRank: Int):
        CompletableFuture<CharacterDeletionResult> = CompletableFuture.completedFuture(CharacterDeletionResult.UNAVAILABLE)
    fun observeBaseFacilities(observer: (PlanetStocks, List<Int>, Long) -> Unit): AutoCloseable = AutoCloseable {}
    fun observeCoupBases(observer:(PlanetStocks,List<Int>)->Unit):AutoCloseable = AutoCloseable {}
    fun observeCivic(observer:(CivicChange)->Unit):AutoCloseable = AutoCloseable {}
    fun observeBudget(observer:(BudgetChange)->Unit):AutoCloseable = AutoCloseable {}
    fun observeSuccession(observer:(SuccessionNotice)->Unit):AutoCloseable = AutoCloseable {}
    fun observeTides(observer:(TidalLoss)->Unit):AutoCloseable = AutoCloseable {}
    fun observeSessionResults(observer:(WorldSnapshot)->Unit):AutoCloseable = AutoCloseable {}
    fun provideIdentities(supplier:()->List<org.logh7.engine.StrategicIdentity>):AutoCloseable = AutoCloseable {}
    fun provideOriginalBerthClaims(supplier:(Long)->List<OriginalBerthClaim>):AutoCloseable = AutoCloseable {}
}
class EngineGameStock(private val engine: WorldEngine, private val scope: CoroutineScope) : GameStock {
    override fun observeBaseFacilities(observer: (PlanetStocks, List<Int>, Long) -> Unit) = engine.observeBaseFacilities(observer)
    override fun observeCoupBases(observer:(PlanetStocks,List<Int>)->Unit)=engine.observeCoupBases(observer)
    override fun observeCivic(observer:(CivicChange)->Unit)=engine.observeCivic(observer)
    override fun observeBudget(observer:(BudgetChange)->Unit)=engine.observeBudget(observer)
    override fun observeSuccession(observer:(SuccessionNotice)->Unit)=engine.observeSuccession(observer)
    override fun observeTides(observer:(TidalLoss)->Unit)=engine.observeTides(observer)
    override fun observeSessionResults(observer:(WorldSnapshot)->Unit)=engine.observeSessionResults(observer)
    override fun provideIdentities(supplier:()->List<org.logh7.engine.StrategicIdentity>)=engine.provideIdentities(supplier)
    override fun provideOriginalBerthClaims(supplier:(Long)->List<OriginalBerthClaim>)=engine.provideOriginalBerthClaims(supplier)
    override fun deleteGeneratedCharacter(account: String, generation: Long, character: Int, registeredRank: Int):
        CompletableFuture<CharacterDeletionResult> {
        val future = CompletableFuture<CharacterDeletionResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<CharacterDeletionResult>()
                engine.submit(WorldCommand.DeleteGeneratedCharacter(account, generation, character, registeredRank, result))
                future.complete(result.await())
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }.invokeOnCompletion { failure -> if (failure != null) future.completeExceptionally(failure) }
        return future
    }
    override fun snapshot(generation: Long): CompletableFuture<PlanetStocks> {
        val future = CompletableFuture<PlanetStocks>()
        scope.launch {
            try {
                val result = CompletableDeferred<PlanetStocks>()
                engine.submit(WorldCommand.Stock(generation, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
}
