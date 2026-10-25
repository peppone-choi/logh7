package org.logh7.persistence

import org.logh7.engine.PlanetStock
import org.logh7.engine.PlanetStocks
import org.logh7.engine.PlanetStockStore
import org.logh7.engine.PlanetEconomy
import org.logh7.engine.OutfitStock
import org.logh7.engine.OutfitWarehouse
import org.logh7.engine.OutfitKind
import org.logh7.engine.LogisticsOffice
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
        if (header.firstOrNull() == "LOGH7-STOCK-4") return loadCurrent(header, lines.drop(1))
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
    private fun loadCurrent(header: List<String>, lines: List<String>): PlanetStocks {
        require(header.size == 7) { "Invalid inventory header" }
        val counts = header.drop(3).map(String::toInt)
        require(counts[0] in 0..350 && counts.drop(1).all { it in 0..1000 } && lines.size == counts.sum()) {
            "Inventory row count mismatch"
        }
        val bases = linkedMapOf<Int, PlanetStock>()
        val outfits = linkedMapOf<Int, OutfitStock>()
        val warehouses = linkedMapOf<Int, OutfitWarehouse>()
        val officers = linkedMapOf<Int, LogisticsOffice>()
        for (line in lines) {
            val f = line.split('\t')
            when (f[0]) {
                "P" -> {
                    require(f.size == 12)
                    val people = if (f.subList(7, 11).all { it == "-" }) null else
                        PlanetEconomy(f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt())
                    val base = PlanetStock(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(),
                        f[5].toInt(), f[6].toInt(), people, UnitInventoryCodec.decode(f[11]))
                    require(bases.put(base.baseId, base) == null) { "Duplicate stock base" }
                }
                "O" -> {
                    require(f.size == 8)
                    val outfit = OutfitStock(f[1].toInt(), f[2].toInt(), OutfitKind.valueOf(f[3]), f[4].toInt(),
                        f[5].toInt(), f[6].toInt(), UnitInventoryCodec.decode(f[7]))
                    require(outfits.put(outfit.id, outfit) == null) { "Duplicate outfit" }
                }
                "W" -> {
                    require(f.size == 10)
                    val warehouse = OutfitWarehouse(f[1].toInt(), f[2].toInt(), OutfitKind.valueOf(f[3]),
                        f[4].toInt(), f[5].toInt(), UnitInventoryCodec.decode(f[9]), f[6].toInt(), f[7].toInt(), f[8].toInt())
                    require(warehouses.put(warehouse.id, warehouse) == null) { "Duplicate outfit warehouse" }
                }
                "A" -> {
                    require(f.size == 3)
                    require(officers.put(f[1].toInt(), LogisticsOffice.valueOf(f[2])) == null) { "Duplicate logistics appointment" }
                }
                else -> throw IllegalArgumentException("Unknown inventory row")
            }
        }
        require(listOf(bases.size, outfits.size, warehouses.size, officers.size) == counts) { "Inventory section count mismatch" }
        return PlanetStocks(header[1].toLong(), header[2].toLong(), bases, outfits, warehouses, officers)
    }
    override fun save(stocks: PlanetStocks) {
        val bytes = buildString {
            append(listOf("LOGH7-STOCK-4", stocks.generation, stocks.producedDays, stocks.bases.size,
                stocks.outfits.size, stocks.warehouses.size, stocks.logisticsOfficers.size).joinToString("\t"))
            append('\n')
            stocks.bases.toSortedMap().values.forEach { stock ->
                val people = stock.economy
                append(listOf("P", stock.baseId, stock.power, stock.camp, stock.supplies, stock.food, stock.mineral,
                    people?.population ?: "-", people?.adults ?: "-", people?.approval ?: "-", people?.peace ?: "-",
                    UnitInventoryCodec.encode(stock.units)).joinToString("\t"))
                append('\n')
            }
            stocks.outfits.toSortedMap().values.forEach { outfit ->
                append(listOf("O", outfit.id, outfit.power, outfit.kind.name, outfit.baseId, outfit.warehouseId,
                    outfit.commanderId, UnitInventoryCodec.encode(outfit.units)).joinToString("\t")); append('\n')
            }
            stocks.warehouses.toSortedMap().values.forEach { warehouse ->
                append(listOf("W", warehouse.id, warehouse.power, warehouse.kind.name, warehouse.baseId, warehouse.outfitId, warehouse.supplies,
                    warehouse.food, warehouse.mineral, UnitInventoryCodec.encode(warehouse.units)).joinToString("\t")); append('\n')
            }
            stocks.logisticsOfficers.toSortedMap().forEach { (character, office) ->
                append("A\t$character\t${office.name}\n")
            }
        }.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4_000_000) { "Planet stock store is too large" }
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
