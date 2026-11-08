package org.logh7.app

import kotlinx.serialization.json.*
import org.logh7.engine.NpcFireDecision
import org.logh7.engine.TacticalReload
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Shared across sessions. Combat reads completed advice only and keeps firing under its own rules. */
internal class JevNpcTargeting private constructor(
    private val endpoint: URI, private val apiKey: String, private val model: String,
    private val timeout: Duration, private val maxInFlight: Int, private val intervalNanos: Long,
) : AutoCloseable {
    private data class DecisionKey(val session: Int, val generation: Long, val grid: Int, val unit: Int, val weapon: Int)
    private data class Pending(val decision: NpcFireDecision, val expiresAt: Long, val result: CompletableFuture<Int?>)

    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val requests = mutableMapOf<DecisionKey, Pending>()
    private var lastRequestAt: Long? = null

    @Synchronized
    fun choose(session: Int, decision: NpcFireDecision): Int? {
        val now = System.nanoTime()
        requests.values.removeIf { now - it.expiresAt >= 0 }
        val key = DecisionKey(session, decision.generation, decision.grid, decision.unit, decision.weapon.wire)
        val pending = requests[key]
        if (pending != null) {
            if (!pending.result.isDone) return null
            requests.remove(key)
            if (pending.decision == decision) return pending.result.getNow(null)
        }
        if (lastRequestAt?.let { now - it < intervalNanos } == true ||
            requests.values.count { !it.result.isDone } >= maxInFlight) return null

        lastRequestAt = now
        val request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(decision))).build()
        val response = try {
            client.sendAsync(request) { BoundedBody() }
        } catch (_: IllegalStateException) {
            return null
        }
        // Cancel even if the remote side sends headers and then stalls the body.
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute { response.cancel(true) }
        val result = response.handle { reply, error ->
            if (error != null || reply.statusCode() != 200) null else parse(reply.body(), decision)
        }
        val usableUntil = now + TimeUnit.SECONDS.toNanos(TacticalReload.seconds(decision.weapon) + 5)
        requests[key] = Pending(decision, usableUntil, result)
        return null
    }

    private fun body(decision: NpcFireDecision) = buildJsonObject {
        put("model", model)
        putJsonObject("state") { put("weapon", decision.weapon.name) }
        putJsonObject("questions") {
            putJsonObject("target") {
                put("type", "choice")
                put("instructions", "Choose the enemy contact that this shot can hit most effectively. Prefer earlier contacts on ties.")
                putJsonObject("criteria") {
                    decision.targets.forEachIndexed { index, target ->
                        put("contact_$index", "Estimated weapon effectiveness ${target.effectiveness} out of 100")
                    }
                }
            }
        }
    }.toString()

    private fun parse(bytes: ByteArray, decision: NpcFireDecision): Int? = runCatching {
        val answer = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("answers").jsonObject
            .getValue("target").jsonObject
        val confidence = answer.getValue("confidence").jsonPrimitive
        if (answer.getValue("type").jsonPrimitive.content != "choice" || confidence.isString ||
            (confidence.doubleOrNull ?: return null) !in 0.7..1.0) return null
        val choice = answer.getValue("choice").jsonPrimitive.content
        decision.targets.withIndex().firstOrNull { "contact_${it.index}" == choice }?.value?.id
    }.getOrNull()

    override fun close() = client.shutdownNow()

    private class BoundedBody(
        private val delegate: HttpResponse.BodySubscriber<ByteArray> = HttpResponse.BodySubscribers.ofByteArray(),
    ) : HttpResponse.BodySubscriber<ByteArray> by delegate {
        private lateinit var subscription: Flow.Subscription
        private var length = 0L

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            delegate.onSubscribe(subscription)
        }

        override fun onNext(buffers: List<ByteBuffer>) {
            length += buffers.sumOf { it.remaining().toLong() }
            if (length > 16_384) {
                subscription.cancel()
                delegate.onError(IllegalArgumentException("Jev response too large"))
            } else delegate.onNext(buffers)
        }
    }

    companion object {
        fun from(env: RuntimeEnvironment): JevNpcTargeting? {
            val key = env["LOGH7_JEV_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            require(key.length <= 2048 && key.all { it in '!'..'~' }) { "Invalid LOGH7_JEV_API_KEY format" }
            val base = env["LOGH7_JEV_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: "https://api.typesafe.ai"
            val uri = runCatching { URI.create(base) }.getOrNull()
            require(uri != null && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]"))) {
                "LOGH7_JEV_BASE_URL must be an HTTPS base URL or a loopback HTTP fixture"
            }
            fun number(name: String, default: Int, range: IntRange): Int {
                val configured = env[name]?.takeIf { it.isNotBlank() } ?: return default
                return requireNotNull(configured.toIntOrNull()?.takeIf { it in range }) { "$name must be in $range" }
            }
            val model = env["LOGH7_JEV_MODEL"]?.trim()?.takeIf { it.isNotEmpty() } ?: "jev-latest"
            require(model.length <= 128) { "LOGH7_JEV_MODEL is too long" }
            return JevNpcTargeting(URI.create(base.trimEnd('/') + "/v1/systemone"), key, model,
                Duration.ofMillis(number("LOGH7_AI_TIMEOUT_MS", 2000, 100..5000).toLong()),
                number("LOGH7_AI_MAX_IN_FLIGHT", 2, 1..8),
                TimeUnit.MILLISECONDS.toNanos(number("LOGH7_AI_INTERVAL_MS", 15000, 100..300000).toLong()))
        }
    }
}
