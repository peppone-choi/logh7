package org.logh7.app

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.TacticalWeapon
import org.logh7.engine.NpcFleetDialogue
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CompatibleNpcDialogueTest {
    @TempDir lateinit var root:Path

    @Test fun disabledOrBlankKeyUsesTheBuiltInLine() {
        assertNull(CompatibleNpcDialogue.from(RuntimeEnvironment.load(emptyMap(),root)))
        assertNull(CompatibleNpcDialogue.from(RuntimeEnvironment.load(mapOf("LOGH7_LLM_TYPE" to "openai_compatible"),root)))
    }

    @Test fun configuredModelReturnsOneLineAndRateLimitFallsBack() {
        val requests=AtomicInteger()
        val call=CompletableFuture<Pair<String,String>>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/chat/completions") { exchange -> exchange.use {
            requests.incrementAndGet()
            call.complete(it.requestHeaders.getFirst("Authorization") to it.requestBody.readAllBytes().decodeToString())
            val reply="""{"choices":[{"message":{"content":"우리 진로를 비켜라."}}]}""".encodeToByteArray()
            it.sendResponseHeaders(200,reply.size.toLong())
            it.responseBody.write(reply)
        } }
        server.start()
        try {
            service(server).use { dialogue ->
                val result=CompletableFuture<String?>()
                dialogue.request(TacticalWeapon.BEAM,NpcFleetDialogue(style="차분함",prompt="짧게 말한다."),result::complete)
                assertEquals("우리 진로를 비켜라.",result.get(3,TimeUnit.SECONDS))
                val (auth,body)=call.get(3,TimeUnit.SECONDS)
                assertEquals("Bearer fixture",auth)
                assertEquals("fixture-model",Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content)
                assertTrue("beam" in body && "차분함" in body && "짧게 말한다." in body)
                val limited=CompletableFuture<String?>()
                dialogue.request(TacticalWeapon.BEAM,NpcFleetDialogue(),limited::complete)
                assertNull(limited.get(1,TimeUnit.SECONDS))
                assertEquals(1,requests.get())
            }
        } finally {server.stop(0)}
    }

    @Test fun stalledResponseDoesNotKeepTheDialoguePending() {
        val release=CountDownLatch(1)
        val started=CountDownLatch(1)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/chat/completions") { exchange -> exchange.use {
            it.sendResponseHeaders(200,0)
            started.countDown()
            release.await(4,TimeUnit.SECONDS)
        } }
        server.start()
        try {
            service(server,timeout=800).use { dialogue ->
                val result=CompletableFuture<String?>()
                dialogue.request(TacticalWeapon.GUN,NpcFleetDialogue(),result::complete)
                assertTrue(started.await(3,TimeUnit.SECONDS))
                assertNull(result.get(3,TimeUnit.SECONDS))
            }
        } finally {release.countDown();server.stop(0)}
    }

    private fun service(server:HttpServer,timeout:Int=2000)=checkNotNull(CompatibleNpcDialogue.from(RuntimeEnvironment.load(mapOf(
        "LOGH7_LLM_TYPE" to "openai_compatible", "LOGH7_LLM_API_KEY" to "fixture", "LOGH7_LLM_MODEL" to "fixture-model",
        "LOGH7_LLM_BASE_URL" to "http://127.0.0.1:${server.address.port}/v1", "LOGH7_AI_TIMEOUT_MS" to "$timeout"),root)))
}
