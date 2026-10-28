package org.logh7.persistence

import org.logh7.engine.PlanetStock
import org.logh7.engine.PlanetStocks
import org.logh7.engine.PlanetStockStore
import org.logh7.engine.PlanetEconomy
import org.logh7.engine.OutfitStock
import org.logh7.engine.OutfitPosition
import org.logh7.engine.OutfitWarehouse
import org.logh7.engine.OutfitKind
import org.logh7.engine.LogisticsOffice
import org.logh7.engine.NationalOffice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*

/** Forced file contents and atomic replacement; directory power-loss durability is OS-managed. */
class FilePlanetStockStore(private val path: Path, worldOwner: Path? = null) : PlanetStockStore {
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    init {
        WorldStateOwnership.check(path, worldOwner)
        Files.createDirectories(path.toAbsolutePath().parent)
        channel = FileChannel.open(path.resolveSibling("${path.fileName}.lock"), CREATE, WRITE)
        lock = try { checkNotNull(channel.tryLock()) { "Planet stock store already has a writer" } }
        catch (failure: Exception) { channel.close(); throw failure }
        try { WorldStateOwnership.check(path, worldOwner) }
        catch (failure: Throwable) {
            try { lock.release() } finally { channel.close() }
            throw failure
        }
    }
    override fun load(): PlanetStocks? {
        if (!Files.exists(path)) return null
        require(Files.size(path) <= 4_000_000) { "Planet stock store is too large" }
        val lines = Files.readAllLines(path)
        val header = lines.firstOrNull()?.split('\t').orEmpty()
        if (header.firstOrNull() in setOf("LOGH7-STOCK-4","LOGH7-STOCK-5","LOGH7-STOCK-6","LOGH7-STOCK-7","LOGH7-STOCK-8","LOGH7-STOCK-9","LOGH7-STOCK-10","LOGH7-STOCK-11","LOGH7-STOCK-12","LOGH7-STOCK-13","LOGH7-STOCK-14","LOGH7-STOCK-15","LOGH7-STOCK-16","LOGH7-STOCK-17","LOGH7-STOCK-18","LOGH7-STOCK-19","LOGH7-STOCK-20","LOGH7-STOCK-21","LOGH7-STOCK-22","LOGH7-STOCK-23","LOGH7-STOCK-24")) return loadCurrent(header, lines.drop(1))
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
        val grounds=header[0]=="LOGH7-STOCK-24"
        val postures=grounds || header[0]=="LOGH7-STOCK-23"
        val reloads=postures || header[0]=="LOGH7-STOCK-22"
        val shots=reloads || header[0]=="LOGH7-STOCK-21"
        val attacks=shots || header[0]=="LOGH7-STOCK-20"
        val commanded=attacks || header[0]=="LOGH7-STOCK-19"
        val support=commanded || header[0]=="LOGH7-STOCK-18"
        val cargo=support || header[0]=="LOGH7-STOCK-17"
        val tactics=cargo || header[0]=="LOGH7-STOCK-16"
        val positions=tactics || header[0]=="LOGH7-STOCK-15"
        val baseReplacements=positions || header[0]=="LOGH7-STOCK-14"
        val replacements=baseReplacements || header[0]=="LOGH7-STOCK-13"
        val staff = replacements || header[0]=="LOGH7-STOCK-12"
        val aides = staff || header[0]=="LOGH7-STOCK-11"
        val national = aides || header[0]=="LOGH7-STOCK-10"
        val capitals = national || header[0] == "LOGH7-STOCK-9"
        val garrison = capitals || header[0] == "LOGH7-STOCK-8"
        val administration = garrison || header[0] == "LOGH7-STOCK-7"
        val identities = administration || header[0] == "LOGH7-STOCK-6"
        val current = header[0] != "LOGH7-STOCK-4"
        require(header.size == if(national)10 else if (identities) 9 else if (current) 8 else 7) { "Invalid inventory header" }
        val initialized = if (current) when (header[7]) { "0" -> false; "1" -> true; else -> error("Invalid deployment marker") } else false
        val counts = header.subList(3,7).map(String::toInt)+(if(national)listOf(header[9].toInt()) else emptyList())
        require(counts[0] in 0..350 && counts.drop(1).all { it in 0..1000 } && lines.size == counts.sum()) {
            "Inventory row count mismatch"
        }
        val bases = linkedMapOf<Int, PlanetStock>()
        val outfits = linkedMapOf<Int, OutfitStock>()
        val warehouses = linkedMapOf<Int, OutfitWarehouse>()
        val officers = linkedMapOf<Int, LogisticsOffice>()
        val nationalOffices=linkedMapOf<NationalOffice,Int>()
        for (line in lines) {
            val f = line.split('\t')
            when (f[0]) {
                "P" -> {
                    require(f.size == if(baseReplacements)19 else if (capitals) 18 else if (garrison) 15 else if (administration) 14 else 12)
                    val people = if (f.subList(7, 11).all { it == "-" }) null else
                        PlanetEconomy(f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt())
                    val base = PlanetStock(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(),
                        f[5].toInt(), f[6].toInt(), people, UnitInventoryCodec.decode(f[11]),
                        if (administration && f[12] != "-") f[12].toInt() else null, if (administration) f[13].toInt() else 0, if (garrison) f[14].toInt() else 0,
                        if(capitals) f[15].toInt() else 0, if(capitals) f[16].toInt() else 0, if(capitals) f[17].toInt() else 0,
                        if(baseReplacements)SpareShipBoatCodec.decode(f[18]) else org.logh7.engine.SpareShipBoats())
                    require(bases.put(base.baseId, base) == null) { "Duplicate stock base" }
                }
                "O" -> {
                    require(f.size == if(grounds)16 else if(positions)15 else if(staff)13 else if (aides) 12 else if (identities) 11 else if (current) 10 else 8)
                    val inventory = UnitInventoryCodec.decode(f[7])
                    val outfit = OutfitStock(f[1].toInt(), f[2].toInt(), OutfitKind.valueOf(f[3]), f[4].toInt(),
                        f[5].toInt(), f[6].toInt(), inventory,
                        if (current) f[8].toInt() else 0, if (current) f[9].toInt() else 0,
                        if (identities) ActiveShipUnitCodec.decode(f[10],legacyFull=!replacements,tactics=tactics,cargo=cargo,support=support,commanded=commanded,attacks=attacks,shots=shots,reloads=reloads,postures=postures) else
                            if (inventory.ships.isEmpty()) org.logh7.engine.ActiveShipUnits(emptyList()) else null,
                        if(aides) f[11].toInt() else 0,if(staff)FleetStaffCodec.decode(f[12]) else org.logh7.engine.FleetStaff(),
                        if(positions && f[13]!="-") OutfitPosition(f[13].toInt(),f[14].toInt()) else null,
                        if(grounds) ActiveGroundUnitCodec.decode(f[15]) else null)
                    require(!positions || (f[13]!="-" || f[14]=="-")) { "Incomplete outfit position" }
                    require(outfits.put(outfit.id, outfit) == null) { "Duplicate outfit" }
                }
                "W" -> {
                    require(f.size == if(replacements)11 else 10)
                    val warehouse = OutfitWarehouse(f[1].toInt(), f[2].toInt(), OutfitKind.valueOf(f[3]),
                        f[4].toInt(), f[5].toInt(), UnitInventoryCodec.decode(f[9]), f[6].toInt(), f[7].toInt(), f[8].toInt(),
                        if(replacements)SpareShipBoatCodec.decode(f[10]) else org.logh7.engine.SpareShipBoats())
                    require(warehouses.put(warehouse.id, warehouse) == null) { "Duplicate outfit warehouse" }
                }
                "A" -> {
                    require(f.size == 3)
                    require(officers.put(f[1].toInt(), LogisticsOffice.valueOf(f[2])) == null) { "Duplicate logistics appointment" }
                }
                "N" -> {
                    require(national && f.size==3)
                    val office=requireNotNull(NationalOffice.of(f[1].toInt())) { "Unknown national office" }
                    val holder=f[2].toInt();require(holder>0)
                    require(nationalOffices.put(office,holder)==null) { "Duplicate national office" }
                }
                else -> throw IllegalArgumentException("Unknown inventory row")
            }
        }
        require(listOf(bases.size, outfits.size, warehouses.size, officers.size)+(if(national)listOf(nationalOffices.size) else emptyList()) == counts) { "Inventory section count mismatch" }
        return PlanetStocks(header[1].toLong(), header[2].toLong(), bases, outfits, warehouses, officers,initialized,
            if (identities) header[8].toInt() else 1,nationalOffices)
    }
    override fun save(stocks: PlanetStocks) {
        val grounds = stocks.outfits.values.any {it.activeGround!=null}
        val postures = grounds || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.posture!=null}==true}
        val reloads = postures || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.reload!=null}==true}
        val shots = reloads || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.shot!=null}==true}
        val attacks = shots || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.attack!=null}==true}
        val commanded = attacks || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.motionCommander!=0}==true}
        val support = commanded || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.support!=null || u.damaged>0}==true}
        val cargo = support || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.supplies!=null}==true}
        val tactics = cargo || stocks.outfits.values.any {it.activeShips?.values?.any {u->u.tactical!=null}==true}
        val positions = tactics || stocks.outfits.values.any { it.position != null }
        val bytes = buildString {
            append(listOf(if(grounds)"LOGH7-STOCK-24" else if(postures)"LOGH7-STOCK-23" else if(reloads)"LOGH7-STOCK-22" else if(shots)"LOGH7-STOCK-21" else if(attacks)"LOGH7-STOCK-20" else if(commanded)"LOGH7-STOCK-19" else if(support)"LOGH7-STOCK-18" else if(cargo)"LOGH7-STOCK-17" else if(tactics)"LOGH7-STOCK-16" else if(positions)"LOGH7-STOCK-15" else "LOGH7-STOCK-14", stocks.generation, stocks.producedDays, stocks.bases.size,
                stocks.outfits.size, stocks.warehouses.size, stocks.logisticsOfficers.size,
                if (stocks.deploymentInitialized) 1 else 0, stocks.nextShipId,stocks.nationalOffices.size).joinToString("\t"))
            append('\n')
            stocks.bases.toSortedMap().values.forEach { stock ->
                val people = stock.economy
                append(listOf("P", stock.baseId, stock.power, stock.camp, stock.supplies, stock.food, stock.mineral,
                    people?.population ?: "-", people?.adults ?: "-", people?.approval ?: "-", people?.peace ?: "-",
                    UnitInventoryCodec.encode(stock.units), stock.taxRate ?: "-", stock.governorId, stock.garrisonCommanderId,
                    stock.capitalAdministratorId, stock.capitalDefenseCommanderId, stock.guardInspectorId,
                    SpareShipBoatCodec.encode(stock.spareShips)).joinToString("\t"))
                append('\n')
            }
            stocks.outfits.toSortedMap().values.forEach { outfit ->
                append((listOf("O", outfit.id, outfit.power, outfit.kind.name, outfit.baseId, outfit.warehouseId,
                    outfit.commanderId, UnitInventoryCodec.encode(outfit.units), outfit.number, outfit.supplies,
                    ActiveShipUnitCodec.encode(outfit.activeShips),outfit.aideId,FleetStaffCodec.encode(outfit.staff)) +
                    (if(positions) listOf(outfit.position?.grid ?: "-",outfit.position?.baseId ?: "-") else emptyList()) +
                    (if(grounds) listOf(ActiveGroundUnitCodec.encode(outfit.activeGround)) else emptyList()))
                    .joinToString("\t")); append('\n')
            }
            stocks.warehouses.toSortedMap().values.forEach { warehouse ->
                append(listOf("W", warehouse.id, warehouse.power, warehouse.kind.name, warehouse.baseId, warehouse.outfitId, warehouse.supplies,
                    warehouse.food, warehouse.mineral, UnitInventoryCodec.encode(warehouse.units),
                    SpareShipBoatCodec.encode(warehouse.spareShips)).joinToString("\t")); append('\n')
            }
            stocks.logisticsOfficers.toSortedMap().forEach { (character, office) ->
                append("A\t$character\t${office.name}\n")
            }
            stocks.nationalOffices.toSortedMap(compareBy { it.card }).forEach { (office,holder) ->
                append("N\t${office.card}\t$holder\n")
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
