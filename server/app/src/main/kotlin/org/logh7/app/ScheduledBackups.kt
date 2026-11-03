package org.logh7.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.logh7.engine.CommandSink
import org.logh7.engine.WorldCommand
import org.logh7.ops.StateBackups
import org.logh7.persistence.LiveStateBackup

/**
 * Live backups while the server runs (docs/ops/backup.md): the copy runs on the world actor as a Quiesce
 * command, hashing (and the optional export) afterwards. One at a time; [intervalMinutes] > 0 also takes one on that period.
 * Author: Peppone Choi.
 */
class ScheduledBackups(private val engine: CommandSink, private val backup: LiveStateBackup,
    /** LOGH7_BACKUP_EXPORT_DIR: another disk or share that receives a verified copy of every backup. */
    private val exportTo: java.nio.file.Path? = null) : StateBackups {
    private val lock = Mutex()
    @Volatile private var last: Long? = null

    override suspend fun take(): String = lock.withLock {
        val copied = CompletableDeferred<LiveStateBackup.Pending>()
        val started = System.nanoTime()
        engine.submit(WorldCommand.Quiesce({ backup.copy() }, copied))
        val pending = copied.await()
        val paused = (System.nanoTime() - started) / 1_000_000
        val target = withContext(Dispatchers.IO) { backup.finish(pending).also { done -> exportTo?.let { backup.export(done, it) } } }
        last = System.currentTimeMillis() / 1000
        println("Backup ${target.fileName}: engine held ${paused} ms")
        target.fileName.toString()
    }

    override fun lastSuccess(): Long? = last

    fun schedule(scope: CoroutineScope, intervalMinutes: Long): Job? = if (intervalMinutes <= 0) null else scope.launch {
        while (isActive) {
            delay(intervalMinutes * 60_000)
            try { take() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { System.err.println("Scheduled backup failed: ${failure.javaClass.simpleName}: ${failure.message}") }
        }
    }
}
