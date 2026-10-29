package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameNavigation {
    fun apply(request: NavigationRequest): CompletableFuture<NavigationResult>
    fun synchronizeTime(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    fun snapshot(): WorldSnapshot? = null
    fun observeTacticalEnds(observer:(TacticalEnd)->Unit):AutoCloseable = AutoCloseable {}
    fun observeCombat(observer: (TacticalImpact) -> Unit): AutoCloseable = AutoCloseable {}
    fun observeMovements(observer:(TacticalMovement)->Unit):AutoCloseable = AutoCloseable {}
    fun observeStrategicMovements(observer:(NpcStrategicMovement)->Unit):AutoCloseable = AutoCloseable {}
    fun observeSupport(observer:(TacticalSupportResult)->Unit):AutoCloseable = AutoCloseable {}
    fun observeOccupations(observer:(BaseOccupation)->Unit):AutoCloseable = AutoCloseable {}
    fun observeGroundBattles(observer:(GroundBattleResult)->Unit):AutoCloseable = AutoCloseable {}
    fun observeGround(observer:(TacticalGroundResult)->Unit):AutoCloseable = AutoCloseable {}
    fun observePostures(observer:(TacticalPostureResult)->Unit):AutoCloseable = AutoCloseable {}
}

class EngineGameNavigation(private val engine: WorldEngine, private val scope: CoroutineScope) : GameNavigation {
    override fun observeTacticalEnds(observer:(TacticalEnd)->Unit) = engine.observeTacticalEnds(observer)
    override fun observeCombat(observer: (TacticalImpact) -> Unit) = engine.observeCombat(observer)
    override fun observeMovements(observer:(TacticalMovement)->Unit) = engine.observeMovements(observer)
    override fun observeStrategicMovements(observer:(NpcStrategicMovement)->Unit) = engine.observeStrategicMovements(observer)
    override fun observeSupport(observer:(TacticalSupportResult)->Unit) = engine.observeSupport(observer)
    override fun observeOccupations(observer:(BaseOccupation)->Unit) = engine.observeOccupations(observer)
    override fun observeGroundBattles(observer:(GroundBattleResult)->Unit) = engine.observeGroundBattles(observer)
    override fun observeGround(observer:(TacticalGroundResult)->Unit) = engine.observeGround(observer)
    override fun observePostures(observer:(TacticalPostureResult)->Unit) = engine.observePostures(observer)
    override fun snapshot() = engine.snapshot()
    override fun synchronizeTime():CompletableFuture<Unit> {
        val future=CompletableFuture<Unit>()
        scope.launch {
            try {engine.synchronizeTime();future.complete(Unit)}
            catch(failure:Throwable){future.completeExceptionally(failure)}
        }
        return future
    }
    override fun apply(request: NavigationRequest): CompletableFuture<NavigationResult> {
        val future = CompletableFuture<NavigationResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<NavigationResult>()
                engine.submit(WorldCommand.Navigate(request, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
}
