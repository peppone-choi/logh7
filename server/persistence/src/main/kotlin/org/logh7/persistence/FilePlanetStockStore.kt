package org.logh7.persistence

import org.logh7.engine.PlanetStock
import org.logh7.engine.PlanetStocks
import org.logh7.engine.PlanetStockStore
import org.logh7.engine.PlanetEconomy
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*

/** Forced file contents and atomic replacement; directory power-loss durability is OS-managed. */
class FilePlanetStockStore(private val path: Path) : PlanetStockStore {
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        channel = FileChannel.open(path.resolveSibling("${path.fileName}.lock"), CREATE, WRITE)
        lock = try { checkNotNull(channel.tryLock()) { "Planet stock store already has a writer" } }
        catch (failure: Exception) { channel.close(); throw failure }
    }
    override fun load(): PlanetStocks? {
        if (!Files.exists(path)) return null
        require(Files.size(path) <= 4_000_000) { "Planet stock store is too large" }
        val lines = Files.readAllLines(path)
        val header = lines.firstOrNull()?.split('\t').orEmpty()
        require(header.size == 3 && header[0] in setOf("LOGH7-STOCK-1", "LOGH7-STOCK-2", "LOGH7-STOCK-3")) { "Invalid planet stock header" }
        val columns = when (header[0]) { "LOGH7-STOCK-1" -> 6; "LOGH7-STOCK-2" -> 10; else -> 11 }
        val rows = linkedMapOf<Int, PlanetStock>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            require(fields.size == columns) { "Invalid planet stock row" }
            val economy = if (columns == 6 || fields.subList(6, 10).all { it == "-" }) null else
                PlanetEconomy(fields[6].toInt(), fields[7].toInt(), fields[8].toInt(), fields[9].toInt())
            val stock = PlanetStock(fields[0].toInt(), fields[1].toInt(), fields[2].toInt(), fields[3].toInt(),
                fields[4].toInt(), fields[5].toInt(), economy,
                if (columns >= 11) UnitInventoryCodec.decode(fields[10]) else org.logh7.engine.UnitInventory())
            require(rows.put(stock.baseId, stock) == null) { "Duplicate stock base" }
        }
        return PlanetStocks(header[1].toLong(), header[2].toLong(), rows)
    }
    override fun save(stocks: PlanetStocks) {
        val bytes = buildString {
            append("LOGH7-STOCK-3\t${stocks.generation}\t${stocks.producedDays}\n")
            stocks.bases.toSortedMap().values.forEach { stock ->
                val people = stock.economy
                append(listOf(stock.baseId, stock.power, stock.camp, stock.supplies, stock.food, stock.mineral,
                    people?.population ?: "-", people?.adults ?: "-", people?.approval ?: "-", people?.peace ?: "-",
                    UnitInventoryCodec.encode(stock.units)).joinToString("\t"))
                append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".planet-stock-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { target ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) target.write(buffer)
                target.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) { throw IOException("Could not persist planet stocks", failure) }
        finally { Files.deleteIfExists(temporary) }
    }
    override fun close() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
}
