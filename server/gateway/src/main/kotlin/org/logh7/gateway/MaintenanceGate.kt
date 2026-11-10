package org.logh7.gateway

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Maintenance admission switch (checklist §14): while closed, new logins get the original client's login
 * failure reply; players already in the game stay connected until the planned restart. The original client
 * has no maintenance message on its login failure, so the operator announces it beforehand in game
 * ([GamePresence.operatorNotice]) and on the launcher/web notice (`evidence:guess`). Author: Peppone Choi.
 */
class MaintenanceGate(private val stateFile: Path? = null) {
    private val closed = AtomicBoolean(readState())
    val refused = AtomicLong()
    fun closed(): Boolean = closed.get()

    /** Publish admission changes only after a configured state file is replaced. */
    @Synchronized fun setClosed(value: Boolean): Boolean {
        if (closed.get() == value) return false
        stateFile?.toAbsolutePath()?.let { target ->
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "maintenance-", ".tmp")
            try {
                Files.writeString(temporary, if (value) "closed\n" else "open\n")
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: IOException) {
                try { Files.deleteIfExists(temporary) }
                catch (cleanup: IOException) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        closed.set(value)
        return true
    }

    private fun readState(): Boolean {
        val file = stateFile ?: return false
        val value = try { Files.readString(file).trim() }
        catch (_: NoSuchFileException) { return false }
        return when (value) {
            "closed" -> true
            "open" -> false
            else -> throw IOException("Invalid maintenance state")
        }
    }
    /** True (and counted) when a login must be refused now. */
    fun refuses(): Boolean = closed.get().also { if (it) refused.incrementAndGet() }
}
