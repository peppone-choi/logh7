package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** A display code validates presentation, never membership. Unattached creation has its own flow. */
internal fun CharacterProfile.canEnterNativeCountry(catalog:CountryCatalog=CountryCatalogLoader.current()):Boolean {
    val definition=country?.let {catalog.countries[it.value]} ?: return false
    return definition.nativePower==character.power && definition.nativePower in 2..3 && definition.nativeCamp==0 &&
        shipStyle.value in setOf("empire","alliance") && OrganizationOffices.system(checkNotNull(country),catalog) in 2..3
}

interface GameAdmission {
    val generation: Long
    val running: Boolean
    fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission>
    fun join(account:String,profile:CharacterProfile,connection:String,generation:Long):CompletableFuture<Admission> {
        if(!profile.canEnterNativeCountry())
            return CompletableFuture.completedFuture(Admission.FACTION_CHANGED)
        return join(account,profile.character,connection,generation)
    }
    fun disconnect(account: String, connection: String)
}

/** Never block a Netty event loop while the world actor decides admission. */
class EngineGameAdmission(private val engine: WorldEngine, private val scope: CoroutineScope) : GameAdmission {
    private val requests = ConcurrentHashMap<String, CompletableFuture<Admission>>()
    /** Includes live and pending connection bindings; closed bindings must be removed. */
    fun trackedConnections(): Int = requests.size
    override val generation get() = checkNotNull(engine.snapshot().session).generation
    override val running get() = checkNotNull(engine.snapshot().session).ended == null
    override fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission> {
        require(character.power in 2..3)
        return join(account,character,CountryId(character.power),connection,generation)
    }
    override fun join(account:String,profile:CharacterProfile,connection:String,generation:Long):CompletableFuture<Admission> {
        return join(account,profile.character,profile.country,connection,generation)
    }
    private fun join(account:String,character:CharacterMessages.Generate,country:CountryId?,connection:String,generation:Long):CompletableFuture<Admission> {
        val future = CompletableFuture<Admission>()
        check(requests.putIfAbsent(connection, future) == null)
        scope.launch {
            try {
                val result = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join(account, country,
                    !character.generated, result, connection, generation, StrategicPerson(rank = character.rank,
                        peerage = Peerage.entries.getOrNull(character.title - 1), merit = character.merit, influence = character.influence,
                        evaluation = character.evaluation, money = character.money, abilities = character.abilities,
                        decorations = character.decorations), characterId = character.id.toInt()))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun disconnect(account: String, connection: String) {
        // An early socket close must follow the queued join, rather than creating a ghost online member.
        requests.remove(connection)?.whenComplete { _, _ ->
            scope.launch { engine.submit(WorldCommand.Disconnect(account, connection)) }
        }
    }
}
