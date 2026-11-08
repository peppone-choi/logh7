package org.logh7.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.persistence.FileWorldStateStore
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class JevNpcTargetingTest {
    @TempDir lateinit var root: Path

    private val decision = NpcFireDecision(10, 54321, 3900, TacticalWeapon.BEAM,
        listOf(NpcFireDecision.Target(1012345, 40), NpcFireDecision.Target(1012346, 85)))
    private val valid = """{"answers":{"target":{"type":"choice","choice":"contact_1","confidence":0.9}}}"""

    @Test fun noKeyLeavesTheBuiltInPolicyInControl() {
        assertNull(JevNpcTargeting.from(RuntimeEnvironment.load(mapOf("LOGH7_JEV_BASE_URL" to "unused"), root)))
    }

    @Test fun gameActorFiresWhileWaitingThenUsesTheReplyAndPersistsTheDamage() {
        val received = CountDownLatch(1)
        val allowReply = CountDownLatch(1)
        val sent = CountDownLatch(1)
        withFixture({ exchange ->
            received.countDown()
            allowReply.await(3, TimeUnit.SECONDS)
            exchange.respond(valid)
            sent.countDown()
        }) { advisor -> runBlocking {
            val rules = SessionRules.load()
            val now = rules.startSeconds
            val grid = 2488
            val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 3), GalaxySystem(4, grid, 4, "Battle", 3)),
                listOf(GalaxyBase(1000, 2487, 80, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)),
                Power.entries.associateWith { 2487 })
            fun motion(point: TacticalPoint, facing: Float = 0f) =
                TacticalMotion(grid, now, TacticalPose(point, facing), 0f, facing, listOf(point))
            fun pilot(id: Int, point: TacticalPoint) = Navigator(1, id, Power.ALLIANCE, grid, 1000, 1000, 900,
                cpPaused = true, hull = FlagshipHull(89, now), motion = motion(point))
            val first = pilot(10000, TacticalPoint(-180f, -95f))
            val second = pilot(10001, TacticalPoint(-181f, -94.5f))
            val ship = UnitStack(32)
            val unit = ActiveShipUnit(1, ship, tactical = NpcTacticalState(
                motion(TacticalPoint(-178f, -95f), (-Math.PI / 2).toFloat()), now + 1, now))
            val outfit = OutfitStock(1, 2, OutfitKind.FLEET, 1000, 10, units = UnitInventory(mapOf(ship to 1)),
                activeShips = ActiveShipUnits(listOf(unit)), position = OutfitPosition(grid))
            val stocks = PlanetStocks(1, 0, mapOf(1000 to PlanetStock(1000, 2, 0, 100, 100, 100)),
                outfits = mapOf(1 to outfit), warehouses = mapOf(10 to OutfitWarehouse(10, 2, OutfitKind.FLEET, 1000, 1)),
                deploymentInitialized = true, nextShipId = 2)
            val path = root.resolve("world")
            FileWorldStateStore(path).use { store ->
                store.saveWorld(mapOf("first" to first, "second" to second), stocks)
                val engine = WorldEngine(this, rules, ticking = false, galaxy = galaxy, navigationStore = store.navigationStore,
                    stockStore = store.stockStore, worldStateCommitter = store,
                    npcPolicy = NpcTacticalPolicy(chooseFireTarget = { advisor.choose(1, it) }))
                val impacts = mutableListOf<TacticalImpact>()
                engine.observeCombat(impacts::add).use {
                    suspend fun step(elapsed: Long) {
                        val done = CompletableDeferred<Unit>()
                        engine.submit(WorldCommand.AdvanceTo(elapsed))
                        engine.submit(WorldCommand.Quiesce({}, done))
                        withTimeout(3000) { done.await() }
                    }
                    try {
                        step(0)
                        assertEquals(listOf(first.unitId), impacts.map { it.hit.target })
                        assertTrue(received.await(3, TimeUnit.SECONDS))
                        allowReply.countDown()
                        assertTrue(sent.await(3, TimeUnit.SECONDS))
                        delay(250)
                        step(TacticalReload.seconds(TacticalWeapon.BEAM) * 1000)
                        assertEquals(listOf(first.unitId, second.unitId), impacts.map { it.hit.target })
                    } finally {
                        allowReply.countDown()
                        engine.drainAndClose()
                    }
                }
            }
            FileWorldStateStore(path).use { reopened ->
                val saved = reopened.loadNavigation()
                assertTrue(saved.getValue("first").hull!!.shields.sum() < first.hull!!.shields.sum())
                assertTrue(saved.getValue("second").hull!!.shields.sum() < second.hull!!.shields.sum())
            }
        } }
    }

    @Test fun acceptsACompletedChoiceOnceAndSendsOnlyAnonymousCombatData() {
        val received = AtomicReference<Pair<String?, String>>()
        withFixture({ exchange ->
            received.set(exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.readAllBytes().decodeToString())
            exchange.respond(valid)
        }) { advisor ->
            assertNull(advisor.choose(1, decision))
            assertEquals(1012346, awaitChoice(advisor, decision))
            assertNull(advisor.choose(1, decision))
            val (auth, body) = received.get()
            assertEquals("Bearer fixture-key", auth)
            val request = Json.parseToJsonElement(body).jsonObject
            assertEquals("fixture-model", request.getValue("model").jsonPrimitive.content)
            assertEquals(setOf("contact_0", "contact_1"), request.getValue("questions").jsonObject.getValue("target")
                .jsonObject.getValue("criteria").jsonObject.keys)
            assertTrue(listOf("1012345", "1012346", "54321", "3900").none { it in body })
        }
    }

    @Test fun aReplyCannotCrossSessionsOrBeAppliedToChangedTargets() {
        val sent = CountDownLatch(1)
        withFixture({ it.respond(valid); sent.countDown() }) { advisor ->
            assertNull(advisor.choose(1, decision))
            assertTrue(sent.await(3, TimeUnit.SECONDS))
            assertNull(advisor.choose(2, decision))
            assertNull(advisor.choose(1, decision.copy(generation = 11)))
            assertEquals(1012346, awaitChoice(advisor, decision))
        }
        withFixture({ it.respond(valid) }) { advisor ->
            assertNull(advisor.choose(1, decision))
            val moved = decision.copy(targets = listOf(decision.targets.first(), decision.targets.last().copy(effectiveness = 65)))
            assertNull(awaitChoice(advisor, moved, milliseconds = 400))
        }
    }

    @Test fun malformedLowConfidenceAndUnknownChoicesFallBack() {
        val invalid = listOf("{", valid.replace("0.9", "0.5"), valid.replace("contact_1", "contact_9"), "x".repeat(16_385))
        for (body in invalid) {
            withFixture({ it.respond(body) }) { advisor ->
                assertNull(advisor.choose(1, decision))
                assertNull(awaitChoice(advisor, decision, milliseconds = 300))
            }
        }
    }

    @Test fun stalledBodyUsesFallbackAndReleasesTheSharedRequestSlot() {
        val first = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        withFixture({ exchange ->
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, 0)
                first.countDown()
                release.await(3, TimeUnit.SECONDS)
                exchange.close()
            } else exchange.respond(valid)
        }, timeoutMs = 800, intervalMs = 100) { advisor ->
            try {
                assertNull(advisor.choose(1, decision))
                assertTrue(first.await(3, TimeUnit.SECONDS))
                Thread.sleep(150)
                val other = decision.copy(unit = 54322)
                assertNull(advisor.choose(2, other))
                assertEquals(1, requests.get())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var choice: Int? = null
                while (choice == null && System.nanoTime() < deadline) {
                    choice = advisor.choose(2, other)
                    if (choice == null) Thread.sleep(10)
                }
                assertEquals(1012346, choice)
                assertEquals(2, requests.get())
            } finally {
                release.countDown()
            }
        }
    }

    private fun awaitChoice(advisor: JevNpcTargeting, decision: NpcFireDecision, milliseconds: Long = 3000): Int? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        while (System.nanoTime() < deadline) {
            advisor.choose(1, decision)?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    private fun withFixture(handler: (HttpExchange) -> Unit, timeoutMs: Int = 2000, intervalMs: Int = 5000,
        test: (JevNpcTargeting) -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            http.executor = executor
            http.createContext("/v1/systemone") { exchange -> exchange.use(handler) }
            http.start()
            try {
                val env = RuntimeEnvironment.load(mapOf("LOGH7_JEV_API_KEY" to "fixture-key", "LOGH7_JEV_MODEL" to "fixture-model",
                    "LOGH7_JEV_BASE_URL" to "http://127.0.0.1:${http.address.port}", "LOGH7_AI_TIMEOUT_MS" to "$timeoutMs",
                    "LOGH7_AI_MAX_IN_FLIGHT" to "1", "LOGH7_AI_INTERVAL_MS" to "$intervalMs"), root)
                checkNotNull(JevNpcTargeting.from(env)).use(test)
            } finally {
                http.stop(0)
            }
        }
    }

    private fun HttpExchange.respond(text: String) {
        val bytes = text.encodeToByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
