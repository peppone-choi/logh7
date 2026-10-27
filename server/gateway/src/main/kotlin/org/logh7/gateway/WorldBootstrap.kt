package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.WorldMessages
import org.logh7.protocol.LoginMessages
import org.logh7.engine.UnitSpecifications
import org.logh7.protocol.OutfitMessages
import org.logh7.protocol.OutfitPartyMessages
import org.logh7.protocol.LogisticsMessages

class WorldBootstrap(val galaxy: Galaxy) {
    private val baseOffices=BaseOfficeCards(galaxy)
    private val logisticsOffices=org.logh7.engine.LogisticsOfficePolicy(galaxy)
    /** Stored base offices, never inferred from the viewer or a stationed outfit. */
    fun cardCharacters(card: Int, stocks: org.logh7.engine.PlanetStocks,
        characterById: (Int) -> org.logh7.protocol.CharacterMessages.Generate?): ByteArray {
        val national=org.logh7.engine.NationalOffice.of(card)
        val logistics=org.logh7.engine.LogisticsOffice.of(card)
        require(national!=null || logistics!=null || card in setOf(39,52,54,55,56,57,188,190,191,192,193)) { "Unsupported public office roster" }
        val power=national?.power ?: logistics?.power ?: if (card<136) 2 else 3
        val baseHolders=stocks.bases.values.filter { it.power==power && it.camp==0 }.flatMap { base ->
            val id=baseOffices.holders(base)[card] ?: return@flatMap emptyList()
            listOf(id to BootstrapMessages.OfficeCard(card,base.baseId))
        }
        val nationalHolders=if(national!=null && national.active(galaxy,stocks)) stocks.nationalOffices[national]?.let {
            listOf(it to BootstrapMessages.OfficeCard(card,checkNotNull(national.seat(galaxy)))) }.orEmpty() else emptyList()
        val logisticsHolders=if (logistics==null) emptyList() else stocks.logisticsOfficers.entries
            .filter { it.value==logistics }.flatMap { (id,_) ->
                logisticsOffices.cards(stocks,id,power).map { (kind,seat) -> id to BootstrapMessages.OfficeCard(kind,seat) } }
        val fleetHolders=if(card==39) stocks.outfits.values.filter {
            it.power==2 && it.kind==org.logh7.engine.OutfitKind.FLEET && it.commanderId>0
        }.map { it.commanderId to BootstrapMessages.OfficeCard(39,it.id) } else emptyList()
        val appointed=(baseHolders+nationalHolders+logisticsHolders+fleetHolders).groupBy({it.first},{it.second}).toSortedMap()
        require(appointed.size<=64 && appointed.values.all { it.size<=14 }) { "Office roster exceeds CD capacity" }
        val records=appointed.map { (id,offices) ->
            val character=checkNotNull(characterById(id)) { "Appointed character is missing" }
            require(character.power==power) { "Character faction differs from appointment" }
            BootstrapMessages.character(character,0,0,offices=offices.sortedBy { it.target })
        }
        return org.logh7.protocol.CardCharacterMessages.response(card,records)
    }
    fun outfitParty(query: OutfitPartyMessages.Query, power: Int, stocks: org.logh7.engine.PlanetStocks,
        editor: Int = 0, characterById: (Int) -> org.logh7.protocol.CharacterMessages.Generate? = { null }): ByteArray {
        require(query.mode in 0..1) { "Unsupported outfit roster query mode" }
        val outfit = requireNotNull(stocks.outfits[query.outfit]) { "Unknown outfit" }
        require(outfit.power == power && (query.base == 0 || query.base == outfit.baseId)) { "Outfit identity mismatch" }
        val owner=stocks.bases.getValue(outfit.baseId)
        val allocator=editor>0 && stocks.logisticsOfficers[editor]?.permits(power,outfit.kind)==true &&
            owner.power==power && owner.camp==0
        require(query.mode == 0 || editor > 0 &&
            (outfit.commanderId == editor && query.base == outfit.baseId || allocator && query.base == 0)) {
            "Only the stationed commander or authorized allocator may open the editor"
        }
        val members = if (outfit.commanderId == 0) emptyList() else {
            val commander = checkNotNull(characterById(outfit.commanderId)) { "Outfit commander is missing" }
            require(commander.id == outfit.commanderId.toLong() && commander.power == outfit.power) { "Outfit commander identity mismatch" }
            // Same kind (0), rank and display surname as the current 0323 character record.
            listOf(OutfitPartyMessages.Member(outfit.commanderId,0,commander.rank,commander.surname))
        }
        val units = checkNotNull(outfit.activeShips) { "Active ship identities are not initialized" }
        val groups = units.values.groupBy { it.stack.kind }.toSortedMap().flatMap { (kind, ships) ->
            val spec = UnitSpecifications.ships.single { it.kind == kind }
            ships.sortedBy { it.id }.chunked(70).map { group ->
                OutfitPartyMessages.Ships(kind,Math.multiplyExact(spec.number,group.size),group.map { it.id })
            }
        }
        val troops = outfit.units.troops.entries.sortedWith(compareBy({ it.key.kind },{ it.key.level }))
            .map { OutfitPartyMessages.Troops(1000+it.key.kind,it.key.level,it.value) }
        val maximum = org.logh7.engine.OutfitSupplies.capacity(outfit.units)
        return LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query.copy(base=outfit.baseId),power,
            outfit.kind.wire,outfit.number,groups,troops,outfit.supplies,maximum,members))
    }
    fun outfitUnit(id: Int, power: Int, stocks: org.logh7.engine.PlanetStocks, base: Int = 0): ByteArray {
        val outfit = stocks.outfits.values.singleOrNull { owner -> owner.activeShips?.values?.any { it.id == id } == true }
        val units = if (outfit == null) emptyList() else {
            require(outfit.power == power && (base == 0 || base == outfit.baseId)) { "Outfit unit access denied" }
            val unit = outfit.activeShips!!.values.single { it.id == id }
            listOf(OutfitPartyMessages.UnitInformation(id,unit.stack.kind,galaxy.bases.single { it.id == outfit.baseId }.grid,outfit.id,outfit.baseId))
        }
        return LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(units))
    }
    fun outfits(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks): ByteArray = LoginMessages.sessionMessage(0x032b,
        OutfitMessages.information(ids.distinct().mapNotNull(stocks.outfits::get).map {
            OutfitMessages.Definition(it.id,it.kind.wire,it.power,0,it.number)
        }))
    fun gridOutfits(query: OutfitMessages.GridQuery, stocks: org.logh7.engine.PlanetStocks): ByteArray =
        LoginMessages.sessionMessage(0x032d,OutfitMessages.grid(stocks.outfits.values.filter { outfit ->
            (query.grid == -1 || galaxy.bases.single { it.id == outfit.baseId }.grid == query.grid) &&
                (query.power == 255 || outfit.power == query.power) && (query.camp == 255 || query.camp == 0)
        }.sortedBy { it.id }.map { OutfitMessages.Entry(it.id,it.kind.wire,it.power,0,it.number,it.supplies) }))
    private fun warehouseShips(values: Map<org.logh7.engine.UnitStack,Int>?) = values.orEmpty().entries
        .groupBy { it.key.kind }.toSortedMap().map { (kind,stacks) ->
            val count = stacks.sumOf { it.value.toLong() }
            val spec = UnitSpecifications.ships.single { it.kind == kind }
            val boats = Math.multiplyExact(count,spec.number.toLong())
            require(count in 1..255 && boats in 1..65535) { "Ship stock exceeds native warehouse capacity" }
            WorldMessages.WarehouseShips(kind,count.toInt(),boats.toInt())
        }
    fun assignmentInventory(units: org.logh7.engine.UnitInventory) = LogisticsMessages.Inventory(
        warehouseShips(units.ships).map { LogisticsMessages.ShipStock(it.kind,it.units,it.boats) },
        units.troops.entries.sortedWith(compareBy({ it.key.kind },{ it.key.level }))
            .map { LogisticsMessages.TroopStock(1000+it.key.kind,it.key.level,it.value) })
    fun outfitWarehouse(base: Int, outfitId: Int, character: Int, power: Int, stocks: org.logh7.engine.PlanetStocks): ByteArray {
        val outfit = requireNotNull(stocks.outfits[outfitId]) { "Unknown outfit" }
        require(outfit.baseId == base && outfit.power == power) { "Outfit warehouse identity mismatch" }
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        val commandsSharedWarehouse = stocks.outfits.values.any { it.warehouseId == warehouse.id && it.commanderId == character }
        require(character > 0 && (commandsSharedWarehouse || stocks.logisticsOfficers[character]?.permits(power,outfit.kind) == true)) {
            "Outfit warehouse access denied"
        }
        fun entries(values: Map<org.logh7.engine.UnitStack,Int>) = values.entries
            .sortedWith(compareBy({ it.key.kind },{ it.key.level })).map { WorldMessages.Stock(it.key.kind,it.key.level,it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base,warehouse.supplies,warehouse.food,warehouse.mineral,
            warehouseShips(warehouse.units.ships),entries(warehouse.units.troops),outfitId))
    }
    fun warehouse(base: Int, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray {
        val state = requireNotNull(galaxy.conditions[base]) { "Unknown warehouse base" }
        val stored = stocks?.let { checkNotNull(it.bases[base]) }
        fun entries(values: Map<org.logh7.engine.UnitStack, Int>?) = values.orEmpty().entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.level })).map { WorldMessages.Stock(it.key.kind, it.key.level, it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base, stored?.supplies ?: state.supplies,
            stored?.food ?: state.food, stored?.mineral ?: 100_000, warehouseShips(stored?.units?.ships), entries(stored?.units?.troops)))
    }
    internal fun baseWire(base: org.logh7.engine.GalaxyBase) = WorldMessages.Base(base.id, base.grid,
        base.model, base.kind, base.name, base.physicalClass, base.radius, base.cycle, base.direction, base.angle, base.diameter)
    fun initialize(grid: Int, directory: Boolean = false, stocks: org.logh7.engine.PlanetStocks? = null): List<ByteArray> {
        val ids = galaxy.bases.filter { it.grid == grid }.map { it.id }
        // 004b76e0 builds the native standing-outfit cache after initialization
        // completes. A later standalone 032b query only fills its detail slot.
        return (if (directory) galaxy.bases.chunked(200).map { chunk ->
            LoginMessages.sessionMessage(0x1204, WorldMessages.simpleBases(chunk.map(::baseWire)))
        } else emptyList()) + (stocks?.let { state -> listOf(outfits(state.outfits.values
            .filter { it.baseId in ids }.sortedBy { it.id }.map { it.id }, state)) }.orEmpty()) +
            listOf(conditions(ids, stocks), places(ids))
    }
    fun conditions(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray = LoginMessages.sessionMessage(0x031f,
        WorldMessages.conditions(ids.mapNotNull { galaxy.conditions[it] }.map {
            val stored = stocks?.let { state -> checkNotNull(state.bases[it.baseId]) }
            val people = stored?.economy
            WorldMessages.Condition(it.baseId, it.grid, stored?.power ?: it.power, stored?.camp ?: it.camp,
                people?.population ?: it.population, people?.adults ?: it.adultPopulation,
                stored?.taxRate ?: it.tax, people?.approval ?: it.approval, people?.peace ?: it.peace, it.atmosphere, it.habitability,
                stored?.supplies ?: it.supplies, stored?.food ?: it.food, it.living)
        }))
    fun places(ids: List<Int>): ByteArray = LoginMessages.sessionMessage(0x0321,
        WorldMessages.institutions(ids.mapNotNull { id -> galaxy.bases.find { it.id == id } }.map { base ->
            WorldMessages.BaseInstitutions(base.id, galaxy.facilities.getValue(base.id).map { facility ->
                WorldMessages.Institution(facility.kind, facility.id, facility.spots.map {
                    WorldMessages.Spot(it.kind, it.id, it.image)
                })
            })
        }))
    val catalogues: Map<Int, ByteArray> = mapOf(
        0x0312 to WorldMessages.types(List(89) { kind ->
            Triple(kind, if (kind >= 3) 3 else kind, galaxy.systems.find { it.kind == kind }?.star ?: 4)
        }),
        0x0314 to WorldMessages.grid(galaxy.cells),
        0x031c to WorldMessages.bases(galaxy.bases.map(::baseWire)),
        0x0306 to WorldMessages.captainCommands(),
        0x0304 to WorldMessages.cards(),
        0x0308 to WorldMessages.powerDirection(),
        0x030a to WorldMessages.ships(UnitSpecifications.ships.map { ship ->
            // 004c5d50/00533980 use native +0x21e as the shared 0..19
            // class index, not the provisional hull-role IDs in ship.type.
            WorldMessages.Ship(ship.kind, ship.category, ship.model, ship.name,
                ship.number("出力:出力"), ship.number("索敵範囲（万km）:索敵範囲（万km）").toFloat(),
                ship.number("最高速度（km/1G秒）:最高速度（km/1G秒）").toFloat(),
                ship.number("装甲:前"), ship.number("装甲:側"), ship.number("装甲:後"),
                ship.number("シールド（旗艦のみ）:防護値"), ship.number("シールド（旗艦のみ）:容量"),
                ship.number("ビーム兵装破壊力:ビーム兵装破壊力"),
                ship.number("ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資"),
                ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資"),
                ship.number("対空兵装破壊力:対空兵装破壊力"), ship.number("修理消費物資(１隻):修理消費物資(１隻)"),
                category = ship.category, number = ship.number, term = ship.number("建造工期:建造工期"),
                crew = ship.number("必要乗組員ユニット数:必要乗組員ユニット数"),
                missileConsumption = ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資", 1),
                fighterNumber = ship.number("戦闘艇/雷撃艇搭載数:戦闘艇/雷撃艇搭載数"),
                cargo = ship.number("物資搭載量:物資搭載量"))
        }),
        0x030c to WorldMessages.crews(UnitSpecifications.crews.map { crew ->
            WorldMessages.Crew(crew.kind, crew.category, crew.category, crew.manual.training,
                crew.manual.groundAttack ?: 0, crew.manual.groundDefense ?: 0)
        }),
    )
}
