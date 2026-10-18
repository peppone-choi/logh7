package org.logh7.app

import kotlinx.coroutines.*
import org.logh7.engine.WorldEngine
import org.logh7.engine.SessionRules
import org.logh7.engine.MemorySessionGenerationStore
import org.logh7.persistence.FileSessionGenerationStore
import org.logh7.persistence.FileSessionStore
import org.logh7.gateway.Gateway
import org.logh7.gateway.loadTestAccounts
import org.logh7.gateway.EngineGameAdmission
import org.logh7.ops.startOpsApi
import org.logh7.ops.OpsAudit
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val resources = ArrayDeque<AutoCloseable>()
    resources.addFirst(AutoCloseable { scope.cancel() })
    fun closeResources(): Unit = synchronized(resources) {
        var failure: Throwable? = null
        while (resources.isNotEmpty()) {
            try { resources.removeFirst().close() }
            catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }
    try {
        val rules = System.getenv("LOGH7_SESSION_RULES")?.let { path -> java.nio.file.Files.newInputStream(Path.of(path)).use { SessionRules.load(it) } } ?: SessionRules.load()
        val characterFile = System.getenv("LOGH7_CHARACTERS_FILE")?.let { Path.of(it) }
        val generationFile = System.getenv("LOGH7_SESSION_GENERATION_FILE")?.let { Path.of(it) }
            ?: characterFile?.resolveSibling("${characterFile.fileName}.session-generation")
        val generations = generationFile?.let(::FileSessionGenerationStore) ?: MemorySessionGenerationStore()
        val sessionDirectory = System.getenv("LOGH7_SESSION_STORE_DIR")?.let { Path.of(it) }
        val store = sessionDirectory?.let { FileSessionStore(it, rules, initialGeneration = generations.current()) }
        val engine = try { WorldEngine(scope, rules, generationStore = generations, durability = store) }
        catch (failure: Throwable) {
            try { store?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        resources.addFirst(AutoCloseable { runBlocking { engine.drainAndClose() } })
        val gateway = Gateway(Path.of(System.getenv("LOGH7_CAPTURE_DIR") ?: "E:/logh7/work/logh7-dynamic-p2/captures"),
            loadTestAccounts(System.getenv("LOGH7_ACCOUNTS_FILE")?.let { Path.of(it) }),
            characterFile,
            gameSeconds = { checkNotNull(engine.snapshot().session).gameSeconds }, admission = EngineGameAdmission(engine, scope))
        resources.addFirst(gateway)
        val audit = System.getenv("LOGH7_OPS_AUDIT_FILE")?.let { OpsAudit.open(Path.of(it)) }
        if (audit != null) resources.addFirst(audit)
        val ops = startOpsApi(engine, engine, audit = audit)
        resources.addFirst(ops)
        val bindAddress = System.getenv("LOGH7_BIND_ADDRESS") ?: "127.0.0.1"
        val sessionPort = System.getenv("LOGH7_SESSION_PORT")?.toInt() ?: 47903
        gateway.start(bindAddress = bindAddress, sessionPort = sessionPort, sessionAddress = System.getenv("LOGH7_SESSION_ADDRESS") ?: bindAddress)
        Runtime.getRuntime().addShutdownHook(Thread { closeResources() })
        println("LOGH7 stubs: $bindAddress:47900 / 47902 / $sessionPort; ops: 127.0.0.1:47901")
        CountDownLatch(1).await()
    } catch (failure: Throwable) {
        try { closeResources() } catch (closing: Throwable) { failure.addSuppressed(closing) }
        throw failure
    } finally { closeResources() }
}
