package org.logh7.persistence

import org.logh7.engine.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*

/** Two complete slots, selected by one forced atomic pointer replacement. Author: 최병호. */
class FileWorldStateStore(private val directory: Path, legacyNavigation: Path? = null,
    legacyStocks: Path? = null) : AutoCloseable, WorldStateCommitter {
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    private var legacyNav: FileNavigationStore? = null
    private var legacyStock: FilePlanetStockStore? = null
    private var active = -1
    private var players: Map<String, Navigator> = emptyMap()
    private var stocks: PlanetStocks? = null
    private var closed = false
    private var failed = false
    internal var beforeSwitch: () -> Unit = {}

    init {
        Files.createDirectories(directory)
        channel = FileChannel.open(directory.resolve("writer.lock"), CREATE, WRITE)
        lock = try { checkNotNull(channel.tryLock()) { "World state already has a writer" } }
        catch (failure: Throwable) { channel.close(); throw failure }
        try {
            // Retain legacy locks, including after migration, so an older server cannot
            // concurrently mutate the import sources while this writer is running.
            legacyNav = legacyNavigation?.let { FileNavigationStore(it, directory) }
            legacyStock = legacyStocks?.let { FilePlanetStockStore(it, directory) }
            val pointer = directory.resolve("CURRENT")
            if (Files.exists(pointer)) {
                require(Files.size(pointer) <= 64) { "Invalid world state pointer size" }
                val fields = Files.readString(pointer).trimEnd('\n').split('\t')
                require(fields.size == 3 && fields[0] == "LOGH7-WORLD-1" && fields[1] in listOf("0", "1") &&
                    fields[2] in listOf("0", "1")) { "Invalid world state pointer" }
                active = fields[1].toInt()
                val loaded = readSlot(active, fields[2] == "1")
                players = loaded.first; stocks = loaded.second
            } else {
                require((0..1).none { Files.exists(slot(it)) }) { "World slots exist without a commit pointer" }
                players = legacyNav?.load().orEmpty().toMap()
                stocks = legacyStock?.load()
            }
            // Bind even before the first commit: configuration loss must fail
            // closed after a migration attempt, rather than resume old files.
            legacyNavigation?.let { WorldStateOwnership.claim(it, directory) }
            legacyStocks?.let { WorldStateOwnership.claim(it, directory) }
        } catch (failure: Throwable) {
            try { close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun slot(index: Int) = directory.resolve("slot-$index")
    private fun readSlot(index: Int, hasStocks: Boolean): Pair<Map<String, Navigator>, PlanetStocks?> {
        val nav = slot(index).resolve("navigation.tsv")
        val stock = slot(index).resolve("stocks.tsv")
        require(Files.isRegularFile(nav) && (if (hasStocks) Files.isRegularFile(stock) else !Files.exists(stock))) {
            "Incomplete or inconsistent committed world slot"
        }
        val loadedNav = FileNavigationStore(nav).use { it.load().toMap() }
        val loadedStock = if (hasStocks) FilePlanetStockStore(stock).use { checkNotNull(it.load()) } else null
        return loadedNav to loadedStock
    }
    private fun detached(value: PlanetStocks?) = value?.copy(bases = value.bases.toMap(), outfits = value.outfits.toMap(),
        warehouses = value.warehouses.toMap(), logisticsOfficers = value.logisticsOfficers.toMap(),
        strategic=value.strategic.detached(),tacticalCommands=value.tacticalCommands.detached(),
        flagshipMarket=value.flagshipMarket.detached(),civic=value.civic.detached(),budget=value.budget.detached())

    @Synchronized fun loadNavigation(): Map<String, Navigator> { check(!closed); return players.toMap() }
    @Synchronized fun loadStocks(): PlanetStocks? { check(!closed); return detached(stocks) }

    /** Both after-images become authoritative together; failure publishes neither. */
    @Synchronized override fun saveWorld(navigation: Map<String, Navigator>, inventory: PlanetStocks?) {
        check(!closed && !failed) { "World state writer is unavailable" }
        val next = if (active == 0) 1 else 0
        val temporary = directory.resolve("CURRENT.tmp")
        try {
            Files.createDirectories(slot(next))
            FileNavigationStore(slot(next).resolve("navigation.tsv")).use { it.save(navigation) }
            val stock = slot(next).resolve("stocks.tsv")
            if (inventory == null) Files.deleteIfExists(stock)
            else FilePlanetStockStore(stock).use { it.save(inventory) }
            // Decode the complete after-images before committing the pointer.
            val verified = readSlot(next, inventory != null)
            val bytes = "LOGH7-WORLD-1\t$next\t${if (inventory == null) 0 else 1}\n".toByteArray(Charsets.UTF_8)
            FileChannel.open(temporary, CREATE, WRITE, TRUNCATE_EXISTING).use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            beforeSwitch()
            Files.move(temporary, directory.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            active = next; players = verified.first; stocks = verified.second
        } catch (failure: Exception) {
            failed = true
            throw IOException("Could not commit world state", failure)
        } finally {
            // The pointer already decides the transaction. Staging cleanup must
            // never turn a successful commit into a reported failure.
            try { Files.deleteIfExists(temporary) } catch (_: IOException) { }
        }
    }

    val navigationStore: NavigationStore = object : NavigationStore {
        override fun load() = loadNavigation()
        override fun save(players: Map<String, Navigator>) = synchronized(this@FileWorldStateStore) { saveWorld(players, stocks) }
        override fun close() = this@FileWorldStateStore.close()
    }
    val stockStore: PlanetStockStore = object : PlanetStockStore {
        override fun load() = loadStocks()
        override fun save(stocks: PlanetStocks) = synchronized(this@FileWorldStateStore) { saveWorld(players, stocks) }
        override fun close() = this@FileWorldStateStore.close()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { legacyStock?.close() } finally {
            try { legacyNav?.close() } finally {
                try { if (lock.isValid) lock.release() } finally { channel.close() }
            }
        }
    }
}
