package org.logh7.persistence

import org.logh7.engine.NavigationStore
import org.logh7.engine.Navigator
import org.logh7.engine.Power
import org.logh7.engine.TacticalEnergy
import org.logh7.engine.TacticalRetreat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*

/** Forced file contents and atomic replacement. The OS/filesystem owns directory crash durability. */
class FileNavigationStore(private val path: Path, worldOwner: Path? = null) : NavigationStore {
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    init {
        WorldStateOwnership.check(path, worldOwner)
        Files.createDirectories(path.toAbsolutePath().parent)
        channel = FileChannel.open(path.resolveSibling("${path.fileName}.lock"), CREATE, WRITE)
        lock = try { checkNotNull(channel.tryLock()) { "Navigation store is already open" } }
        catch (failure: Throwable) { channel.close(); throw failure }
        try { WorldStateOwnership.check(path, worldOwner) }
        catch (failure: Throwable) {
            try { lock.release() } finally { channel.close() }
            throw failure
        }
    }
    override fun load(): Map<String, Navigator> {
        if (!Files.exists(path)) return emptyMap()
        require(Files.size(path) <= 4_000_000) { "Navigation store is too large" }
        val lines = Files.readAllLines(path)
        val version = lines.firstOrNull()
        val columns = when (version) { "LOGH7-NAV-1" -> 9; "LOGH7-NAV-2" -> 13; "LOGH7-NAV-3" -> 15; "LOGH7-NAV-4" -> 16; "LOGH7-NAV-5" -> 17; "LOGH7-NAV-6" -> 18; "LOGH7-NAV-7" -> 19; "LOGH7-NAV-8" -> 20; "LOGH7-NAV-9" -> 21; "LOGH7-NAV-10" -> 22
            else -> error("Unknown navigation store format") }
        val players = linkedMapOf<String, Navigator>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            require(f.size == columns && f[0].matches(Regex("[A-Za-z0-9_.@-]{1,30}"))) { "Invalid navigation row" }
            val player = Navigator(f[1].toLong(), f[2].toInt(), Power.valueOf(f[3]), f[4].toInt(),
                f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toLong(),
                if (columns >= 13) f[9].toInt() else 0, if (columns >= 13) f[10].toInt() else 5,
                if (columns >= 13) f[11].toInt() else 0, if (columns >= 13) f[12].toInt() else 0,
                if (columns >= 15 && f[13] != "-") f[13].toLong() else null,
                if (columns >= 15) when (f[14]) { "0" -> false; "1" -> true; else -> error("Invalid CP pause flag") } else false,
                if (columns >= 16 && f[15] != "-") f[15].toLong() else null,
                if (columns >= 17 && f[16] != "-") StrategyCheckpointCodec.decode(f[16]) else null,
                if (columns >= 18 && f[17] != "-") f[17].toInt() else null,
                if (columns >= 19) f[18].toInt() else 0,
                if (columns >= 20 && f[19] != "-") TacticalEnergy(f[19].split(',').map(String::toInt)) else null,
                if (columns >= 21 && f[20] != "-") TacticalMotionCodec.decode(f[20]) else null,
                if (columns >= 22 && f[21] != "-") f[21].split(',').also { require(it.size == 4) }.let {
                    TacticalRetreat(it[0].toInt(), it[1].toLong(), it[2].toLong(), it[3].toLong())
                } else null)
            require(players.put(f[0], player) == null) { "Duplicate navigation account" }
        }
        return players
    }
    override fun save(players: Map<String, Navigator>) {
        val retreats = players.values.any { it.retreat != null }
        val motions = retreats || players.values.any { it.motion != null }
        val bytes = buildString {
            append(if (retreats) "LOGH7-NAV-10\n" else if (motions) "LOGH7-NAV-9\n" else "LOGH7-NAV-8\n")
            players.toSortedMap().forEach { (account, p) ->
                require(account.matches(Regex("[A-Za-z0-9_.@-]{1,30}")))
                append((listOf(account, p.generation, p.characterId, p.power.name, p.grid, p.pcp, p.mcp, p.cruise,
                    p.moves, p.baseId, p.mode, p.spotId, p.spotOwner, p.cpRecoveryAt ?: "-", if (p.cpPaused) 1 else 0,
                    p.fuelReadyAt ?: "-", p.strategy?.let(StrategyCheckpointCodec::encode) ?: "-", p.dockTarget ?: "-", p.returnBase,
                    p.energy?.values?.joinToString(",") ?: "-") +
                    (if (motions) listOf(p.motion?.let(TacticalMotionCodec::encode) ?: "-") else emptyList()) +
                    if (retreats) listOf(p.retreat?.let {
                        listOf(it.destination,it.acceptedAt,it.waitSeconds,it.preparationSeconds).joinToString(",")
                    } ?: "-") else emptyList()).joinToString("\t"))
                append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".navigation-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (failure: Exception) { throw IOException("Could not persist navigation", failure) }
        finally { Files.deleteIfExists(temporary) }
    }
    override fun close() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
}
