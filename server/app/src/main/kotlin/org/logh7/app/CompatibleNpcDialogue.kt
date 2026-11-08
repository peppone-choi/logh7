package org.logh7.app

import kotlinx.serialization.json.*
import org.logh7.engine.TacticalWeapon
import org.logh7.engine.NpcFleetDialogue
import org.logh7.gateway.NpcCombatDialogue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Optional dialogue through an operator-configured chat-completions endpoint, such as Ollama. */
internal class CompatibleNpcDialogue private constructor(
    private val endpoint:URI, private val key:String, private val model:String,
    private val timeout:Duration, private val intervalNanos:Long, private val maxInFlight:Int,
) : NpcCombatDialogue, AutoCloseable {
    private val client=HttpClient.newBuilder().connectTimeout(timeout).build()
    private var lastRequestAt:Long?=null
    private var inFlight=0

    @Synchronized private fun reserve():Boolean {
        val now=System.nanoTime()
        if(inFlight>=maxInFlight || lastRequestAt?.let {now-it<intervalNanos}==true)return false
        lastRequestAt=now
        inFlight++
        return true
    }

    @Synchronized private fun release() { inFlight-- }

    override fun request(weapon:TacticalWeapon, profile:NpcFleetDialogue, complete:(String?)->Unit) {
        if(!reserve()) { complete(null); return }
        val response=try {
            val request=HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization","Bearer $key").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(weapon,profile))).build()
            client.sendAsync(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),16_384))
        } catch(_:RuntimeException) {
            release(); complete(null); return
        }
        CompletableFuture.delayedExecutor(timeout.toMillis(),TimeUnit.MILLISECONDS).execute { response.cancel(true) }
        response.whenComplete { reply,error ->
            release()
            val text=if(error!=null || reply.statusCode()!=200)null else runCatching {
                val value=Json.parseToJsonElement(reply.body()).jsonObject.getValue("choices").jsonArray.single().jsonObject
                    .getValue("message").jsonObject.getValue("content").jsonPrimitive
                value.content.takeIf {value.isString}
            }.getOrNull()
            complete(text)
        }
    }

    private fun requestBody(weapon:TacticalWeapon,profile:NpcFleetDialogue)=buildJsonObject {
        put("model",model)
        put("stream",false)
        put("max_tokens",80)
        putJsonArray("messages") {
            addJsonObject {
                put("role","system")
                put("content","You voice a fictional space fleet captain. Write only one Korean radio warning, up to 16 characters, " +
                    "with no names, role labels, quotations, or claims about rules or rewards.")
            }
            addJsonObject {
                put("role","user")
                put("content",buildString {
                    append("The fleet has just landed a ${weapon.name.lowercase()} shot. Warn the opposing captain to withdraw.")
                    if(profile.style.isNotEmpty())append("\nVoice style: ${profile.style}")
                    if(profile.prompt.isNotEmpty())append("\nCharacter direction: ${profile.prompt}")
                })
            }
        }
    }.toString()

    override fun close()=client.shutdownNow()

    companion object {
        fun from(env:RuntimeEnvironment):CompatibleNpcDialogue? {
            if(env["LOGH7_LLM_TYPE"]?.trim()!="openai_compatible")return null
            val key=env["LOGH7_LLM_API_KEY"]?.trim()?.takeIf {it.isNotEmpty()} ?: return null
            require(key.length<=2048 && key.all {it in '!'..'~'}) {"Invalid LOGH7_LLM_API_KEY format"}
            val model=env["LOGH7_LLM_MODEL"]?.trim()?.takeIf {it.isNotEmpty() && it.length<=128}
            requireNotNull(model) {"LOGH7_LLM_MODEL is required for NPC dialogue"}
            val base=env["LOGH7_LLM_BASE_URL"]?.trim()?.trimEnd('/')
            val uri=base?.let {runCatching {URI.create(it)}.getOrNull()}
            require(uri!=null && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null &&
                (uri.scheme=="https" || uri.scheme=="http" && uri.host in setOf("localhost","127.0.0.1","[::1]"))) {
                "LOGH7_LLM_BASE_URL must be an HTTPS base URL or loopback HTTP URL"
            }
            fun number(name:String,default:Int,range:IntRange):Int {
                val configured=env[name]?.takeIf {it.isNotBlank()} ?: return default
                return requireNotNull(configured.toIntOrNull()?.takeIf {it in range}) {"$name must be in $range"}
            }
            return CompatibleNpcDialogue(URI.create("$base/chat/completions"),key,model,
                Duration.ofMillis(number("LOGH7_AI_TIMEOUT_MS",2000,100..5000).toLong()),
                TimeUnit.MILLISECONDS.toNanos(number("LOGH7_AI_INTERVAL_MS",15000,100..300000).toLong()),
                number("LOGH7_AI_MAX_IN_FLIGHT",2,1..8))
        }
    }
}
