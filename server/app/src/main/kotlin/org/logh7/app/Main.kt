package org.logh7.app

import kotlinx.coroutines.*
import org.logh7.engine.WorldEngine
import org.logh7.gateway.Gateway
import org.logh7.ops.startOpsApi
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = WorldEngine(scope)
    val gateway = Gateway(Path.of(System.getenv("LOGH7_CAPTURE_DIR") ?: "E:/logh7/work/logh7-dynamic-p2/captures"))
    val ops = startOpsApi(engine, engine)
    val bindAddress = System.getenv("LOGH7_BIND_ADDRESS") ?: "127.0.0.1"
    try { gateway.start(bindAddress = bindAddress) } catch (failure: Throwable) { ops.close(); engine.close(); scope.cancel(); throw failure }
    Runtime.getRuntime().addShutdownHook(Thread { gateway.close(); ops.close(); engine.close(); scope.cancel() })
    println("LOGH7 stubs: $bindAddress:47900 / 47902; ops: 127.0.0.1:47901")
    CountDownLatch(1).await()
}
