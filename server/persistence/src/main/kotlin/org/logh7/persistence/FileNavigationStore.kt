package org.logh7.persistence

import org.logh7.engine.NavigationStore
import org.logh7.engine.NavigationRejection
import org.logh7.engine.Navigator
import org.logh7.engine.Power
import org.logh7.engine.CountryId
import org.logh7.engine.ShipStyleId
import org.logh7.engine.country
import org.logh7.engine.TacticalEnergy
import org.logh7.engine.TacticalRetreat
import org.logh7.engine.TacticalCharge
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
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
        val columns = when (version) { "LOGH7-NAV-1" -> 9; "LOGH7-NAV-2" -> 13; "LOGH7-NAV-3" -> 15; "LOGH7-NAV-4" -> 16; "LOGH7-NAV-5" -> 17; "LOGH7-NAV-6" -> 18; "LOGH7-NAV-7" -> 19; "LOGH7-NAV-8" -> 20; "LOGH7-NAV-9" -> 21; "LOGH7-NAV-10" -> 22; "LOGH7-NAV-11" -> 23; "LOGH7-NAV-12" -> 24; "LOGH7-NAV-13" -> 25; "LOGH7-NAV-14" -> 26
            "LOGH7-NAV-15" -> 27
            "LOGH7-NAV-16" -> 28
            "LOGH7-NAV-17" -> 29
            "LOGH7-NAV-18" -> 30
            "LOGH7-NAV-19" -> 31
            "LOGH7-NAV-20" -> 32
            "LOGH7-NAV-21" -> 33
            "LOGH7-NAV-22", "LOGH7-NAV-23", "LOGH7-NAV-24" -> 34
            else -> error("Unknown navigation store format") }
        val players = linkedMapOf<String, Navigator>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            require(f.size == columns && f[0].matches(Regex("[A-Za-z0-9_.@-]{1,30}"))) { "Invalid navigation row" }
            val country=if(columns>=32)readCountry(f[3]) else Power.valueOf(f[3]).country
            val style=if(columns>=32)ShipStyleId(f[31]) else ShipStyleId.fromLegacy(Power.valueOf(f[3]))
            val player = Navigator(f[1].toLong(), f[2].toInt(), country, f[4].toInt(),
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
                if (columns >= 22 && f[21] != "-") f[21].split(',').also { require(it.size == 4 || it.size == 5 && it[4] == "ai") }.let {
                    TacticalRetreat(it[0].toInt(), it[1].toLong(), it[2].toLong(), it[3].toLong(), issuedByOfflineAi=it.size == 5)
                } else null,
                if (columns >= 23 && f[22] != "-") f[22].split(',').also { require(it.size == 4) }.let {
                    TacticalCharge(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3].toLong())
                } else null,
                if (columns >= 24 && f[23] != "-") TacticalFireCodec.decode(f[23]) else null,
                if (columns >= 25) f[24].toInt() else 10_000,
                if (columns >= 26 && f[25] != "-") FlagshipHullCodec.decode(f[25],version=="LOGH7-NAV-24") else null,
                if (columns >= 27 && f[26] != "-") f[26].split(',').also {require(it.size==4)}.let {
                    org.logh7.engine.TacticalAttackOrder(it[0].toInt(),it[1].toLong(),it[2].toLong(),it[3].toLong())
                } else null,
                if(columns>=28 && f[27]!="-")f[27].split(',').also {require(it.size==4)}.let {
                    org.logh7.engine.TacticalCommandRange(it[0].toLong(),it[1].toFloat(),it[2].toInt(),it[3].toInt())
                } else null,
                if(columns>=29 && f[28]!="-")TacticalPostureCodec.decode(f[28]) else null,
                if(columns>=30 && f[29]!="-")f[29].toInt() else null,
                if(columns>=31)f[30].split(',').also {require(it.size==2)}[0].toInt() else 0,
                if(columns>=31)f[30].split(',')[1].toInt() else 0,style,
                if(columns>=33 && f[32]!="-")NavigationRejection.valueOf(f[32]) else null,
                originBase = if (columns >= 34) f[33].toInt() else 0)
            require(players.put(f[0], player) == null) { "Duplicate navigation account" }
        }
        return players
    }
    override fun save(players: Map<String, Navigator>) {
        val aiRetreats = players.values.any { it.retreat?.issuedByOfflineAi == true }
        val marketHulls = players.values.any { it.hull?.marketAcquired == true }
        val origins = marketHulls || aiRetreats || players.values.any { it.originBase != 0 }
        val warpNotices = origins || players.values.any { it.offlineWarpCancellation != null }
        val countries=warpNotices || players.values.any {(it.country?.value ?: 0) !in 2..3 ||
            it.shipStyle!=ShipStyleId.fromLegacy(it.power)}
        val used=countries || players.values.any {it.pcpUsed!=0 || it.mcpUsed!=0}
        val warps=used || players.values.any {it.warpTarget!=null}
        val postures=warps || players.values.any {it.posture!=null}
        val ranges=postures || players.values.any {it.commandRange!=null}
        val attacks = ranges || players.values.any {it.attack != null}
        val hulls = attacks || players.values.any {it.hull != null}
        val supplies = hulls || players.values.any { it.supplies != 10_000 || it.fire?.pending?.weapon?.let { w -> w != org.logh7.engine.TacticalWeapon.BEAM } == true }
        val fires = supplies || players.values.any { it.fire != null }
        val charges = fires || players.values.any { it.charge != null }
        val retreats = charges || players.values.any { it.retreat != null }
        val motions = retreats || players.values.any { it.motion != null }
        val bytes = buildString {
            append(if(marketHulls) "LOGH7-NAV-24\n" else if(aiRetreats) "LOGH7-NAV-23\n" else if(origins) "LOGH7-NAV-22\n" else if(warpNotices) "LOGH7-NAV-21\n" else if(countries) "LOGH7-NAV-20\n" else if(used) "LOGH7-NAV-19\n" else if(warps) "LOGH7-NAV-18\n" else if(postures) "LOGH7-NAV-17\n" else if(ranges) "LOGH7-NAV-16\n" else if (attacks) "LOGH7-NAV-15\n" else if (hulls) "LOGH7-NAV-14\n" else if (supplies) "LOGH7-NAV-13\n" else if (fires) "LOGH7-NAV-12\n" else if (charges) "LOGH7-NAV-11\n" else if (retreats) "LOGH7-NAV-10\n" else if (motions) "LOGH7-NAV-9\n" else "LOGH7-NAV-8\n")
            players.toSortedMap().forEach { (account, p) ->
                require(account.matches(Regex("[A-Za-z0-9_.@-]{1,30}")))
                append((listOf(account, p.generation, p.characterId, if(countries)p.country?.value ?: "-" else p.power.name, p.grid, p.pcp, p.mcp, p.cruise,
                    p.moves, p.baseId, p.mode, p.spotId, p.spotOwner, p.cpRecoveryAt ?: "-", if (p.cpPaused) 1 else 0,
                    p.fuelReadyAt ?: "-", p.strategy?.let(StrategyCheckpointCodec::encode) ?: "-", p.dockTarget ?: "-", p.returnBase,
                    p.energy?.values?.joinToString(",") ?: "-") +
                    (if (motions) listOf(p.motion?.let(TacticalMotionCodec::encode) ?: "-") else emptyList()) +
                    (if (retreats) listOf(p.retreat?.let {
                        listOf(it.destination,it.acceptedAt,it.waitSeconds,it.preparationSeconds).joinToString(",") +
                            if(it.issuedByOfflineAi)",ai" else ""
                    } ?: "-") else emptyList()) +
                    (if (charges) listOf(p.charge?.let {
                        listOf(it.at,it.beam,it.gun,it.clockScale).joinToString(",")
                    } ?: "-") else emptyList()) +
                    (if (fires) listOf(p.fire?.let(TacticalFireCodec::encode) ?: "-") else emptyList()) +
                    (if (supplies) listOf(p.supplies) else emptyList()) +
                    (if (hulls) listOf(p.hull?.let(FlagshipHullCodec::encode) ?: "-") else emptyList()) +
                    (if (attacks) listOf(p.attack?.let {
                        listOf(it.mode,it.acceptedAt,it.startsAt,it.nextAt).joinToString(",")
                    } ?: "-") else emptyList()) +
                    (if(ranges)listOf(p.commandRange?.let {
                        listOf(it.at,it.radius,it.command,it.stamina).joinToString(",")
                    } ?: "-") else emptyList()) +
                    (if(postures)listOf(p.posture?.let(TacticalPostureCodec::encode) ?: "-") else emptyList()) +
                    (if(warps)listOf(p.warpTarget ?: "-") else emptyList()) +
                    (if(used)listOf("${p.pcpUsed},${p.mcpUsed}") else emptyList())+
                    (if(countries)listOf(p.shipStyle.value) else emptyList()) +
                    (if(warpNotices)listOf(p.offlineWarpCancellation?.name ?: "-") else emptyList()) +
                    (if(origins)listOf(p.originBase) else emptyList())).joinToString("\t"))
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
            replaceAtomically(temporary, path)
        } catch (failure: Exception) { throw IOException("Could not persist navigation", failure) }
        finally { Files.deleteIfExists(temporary) }
    }
    private fun readCountry(value:String):CountryId? {
        if(value=="-")return null
        val country=CountryId(value.toInt())
        require(value==country.value.toString()) {"Non-canonical navigation country"}
        return country
    }
    override fun close() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
}
