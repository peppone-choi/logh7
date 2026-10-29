package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.WorldMessages
import org.logh7.protocol.LoginMessages
import org.logh7.engine.UnitSpecifications
import org.logh7.engine.FleetOffice
import org.logh7.engine.members
import org.logh7.engine.positionIn
import org.logh7.protocol.OutfitMessages
import org.logh7.protocol.OutfitPartyMessages
import org.logh7.protocol.LogisticsMessages

class WorldBootstrap(val galaxy: Galaxy) {
    private val officeCards=org.logh7.engine.OrganizationOffices.all.mapValues {(_,role)->
        val commands=buildList {
            addAll(WorldMessages.commandsForCard(role.card))
            when(role.card) {
                1->addAll(listOf(0,1,2,3,8,9))
                17,135->addAll(listOf(0,1,2,4))
                19,160->addAll(listOf(0,1,2))
                24,25,26,147,148,149->addAll(listOf(22,23))
                28,29,30,169,170,171->add(24)
            }
            if(org.logh7.engine.OrganizationOffices.all.values.any {it.authority==role.card}) {add(5);add(6)}
            add(7)
        }.distinct()
        WorldMessages.CardDefinition(role.card,role.power,role.camp,role.institution,role.authority ?: 0,
            role.ranks.max(),role.ranks.min(),commands)
    }.plus(((60..66).toList()+(124..130).toList()).associateWith {card ->
        WorldMessages.CardDefinition(card,2,if(card>=124)1 else 0,if(card in setOf(66,130))16 else 0,1,20,0,
            if(card in setOf(66,130))listOf(11) else emptyList())
    })
    private val baseOffices=BaseOfficeCards(galaxy)
    private val logisticsOffices=org.logh7.engine.LogisticsOfficePolicy(galaxy)
    /** Stored base offices, never inferred from the viewer or a stationed outfit. */
    fun cardCharacters(card: Int, stocks: org.logh7.engine.PlanetStocks,
        characterById: (Int) -> org.logh7.protocol.CharacterMessages.Generate?): ByteArray {
        val role=org.logh7.engine.OrganizationOffices.of(card)
        require(role!=null || org.logh7.protocol.OfficeCardKinds.isPeerage(card) || org.logh7.protocol.OfficeCardKinds.isFief(card))
        val power=role?.power ?: 2
        val policy=org.logh7.engine.OrganizationalOfficePolicy(galaxy)
        val holdings=if(role!=null)policy.holders(stocks,card).map {holding ->holding.character to
            BootstrapMessages.OfficeCard(card,policy.target(role,holding.seat))} else if(card in 60..65)
            stocks.strategic.people.filterValues {it.peerage?.ordinal==card-60}.keys.map {it to BootstrapMessages.OfficeCard(card,0)}
        else if(card==66)stocks.strategic.fiefs.map {(base,owner)->owner to BootstrapMessages.OfficeCard(66,base)} else emptyList()
        val appointed=holdings.groupBy({it.first},{it.second}).toSortedMap()
        if(appointed.size>64) return org.logh7.protocol.CardCharacterMessages.response(card,emptyList(),failed=true)
        require(appointed.values.all { it.size<=14 }) { "Office cards exceed CD capacity" }
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
        val position = outfit.positionIn(galaxy)
        require(outfit.power == power && (query.base == 0 || query.base == position.baseId)) { "Outfit identity mismatch" }
        val owner=stocks.bases.getValue(outfit.baseId)
        val allocator=editor>0 && stocks.logisticsOfficers[editor]?.permits(power,outfit.kind)==true &&
            owner.power==power && owner.camp==0
        require(query.mode == 0 || owner.power == power && owner.camp == 0 && editor > 0 &&
            (outfit.commanderId == editor && query.base == outfit.baseId && position.baseId == outfit.baseId || allocator && query.base == 0)) {
            "Only the stationed commander or authorized allocator may open the editor"
        }
        val members = outfit.members().map {id ->
            val commander = checkNotNull(characterById(id)) { "Outfit member is missing" }
            require(commander.id == id.toLong() && commander.power == outfit.power) { "Outfit member identity mismatch" }
            // Same kind (0), rank and display surname as the current 0323 character record.
            OutfitPartyMessages.Member(id,0,commander.rank,commander.surname)
        }
        val units = checkNotNull(outfit.activeShips) { "Active ship identities are not initialized" }
        val groups = units.values.groupBy { it.stack.kind }.toSortedMap().flatMap { (kind, ships) ->
            ships.sortedBy { it.id }.chunked(70).map { group ->
                OutfitPartyMessages.Ships(kind,group.sumOf {it.boats},group.map { it.id })
            }
        }
        val troops = outfit.units.troops.entries.sortedWith(compareBy({ it.key.kind },{ it.key.level }))
            .map { OutfitPartyMessages.Troops(1000+it.key.kind,it.key.level,it.value) }
        val maximum = org.logh7.engine.OutfitSupplies.visibleCapacity(outfit)
        return LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query.copy(base=if(query.mode==1)outfit.baseId else position.baseId),power,
            outfit.kind.wire,outfit.number,groups,troops,outfit.supplies,maximum,members))
    }
    /** Explicit revival query mode 2: original 0331 rows, at most 70 ground identities after the cursor.
     * No command or CP mutation; actual saved carriers, remote bases and unresolved legacy locations survive. */
    fun outfitGround(query:OutfitPartyMessages.Query,power:Int,stocks:org.logh7.engine.PlanetStocks):List<ByteArray> {
        require(query.mode==2 && query.base in 0..999999)
        val outfit=requireNotNull(stocks.outfits[query.outfit])
        require(outfit.power==power) {"Ground location access denied"}
        val position=outfit.positionIn(galaxy)
        val rows=outfit.activeGround?.values.orEmpty().filter {it.id>query.base}.sortedBy {it.id}.take(70).map {u->
            val grid=if(u.base>0)galaxy.bases.single {it.id==u.base}.grid else position.grid
            OutfitPartyMessages.UnitInformation(u.id,1000+u.stack.kind,grid,outfit.id,u.base,carrier=u.carrier,
                mode=if(u.carrier>0)10 else if(u.base>0)if(u.origin>0)9 else 4 else 0)
        }
        // Echo the extension identity/cursor before 0331, including an empty page. This
        // prevents an unrelated or delayed native unit reply from satisfying the page.
        return listOf(LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query,power,outfit.kind.wire,
            outfit.number,emptyList(),emptyList(),0,0)),LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(rows)))
    }
    fun outfitUnit(id: Int, power: Int, stocks: org.logh7.engine.PlanetStocks, base: Int = 0): ByteArray {
        val outfit = stocks.outfits.values.singleOrNull { owner -> owner.activeShips?.values?.any { it.id == id } == true }
        val units = if (outfit == null) emptyList() else {
            val position = outfit.positionIn(galaxy)
            require(outfit.power == power && (base == 0 || base == position.baseId)) { "Outfit unit access denied" }
            val unit = outfit.activeShips!!.values.single { it.id == id }
            listOf(OutfitPartyMessages.UnitInformation(id,unit.stack.kind,position.grid,outfit.id,position.baseId,
                org.logh7.engine.OutfitSupplies.distribution(outfit).getValue(id),unit.unavailable,
                UnitSpecifications.ships.single {it.kind==unit.stack.kind}.number-unit.boats))
        }
        return LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(units))
    }
    fun outfits(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks): ByteArray = LoginMessages.sessionMessage(0x032b,
        OutfitMessages.information(ids.distinct().mapNotNull(stocks.outfits::get).map {
            OutfitMessages.Definition(it.id,it.kind.wire,it.power,0,it.number)
        }))
    fun gridOutfits(query: OutfitMessages.GridQuery, stocks: org.logh7.engine.PlanetStocks): ByteArray =
        LoginMessages.sessionMessage(0x032d,OutfitMessages.grid(stocks.outfits.values.filter { outfit ->
            (query.grid == -1 || outfit.positionIn(galaxy).grid == query.grid) &&
                (query.power == 255 || outfit.power == query.power) && (query.camp == 255 || query.camp == 0)
        }.sortedBy { it.id }.map { OutfitMessages.Entry(it.id,it.kind.wire,it.power,0,it.number,it.supplies) }))
    private fun warehouseShips(values: Map<org.logh7.engine.UnitStack,Int>?,
        spare: Map<org.logh7.engine.UnitStack,Int> = emptyMap()) = (values.orEmpty().keys+spare.keys).map {it.kind}.distinct().sorted().map { kind ->
            val stacks=values.orEmpty().entries.filter {it.key.kind==kind}
            val count = stacks.sumOf { it.value.toLong() }
            val spec = UnitSpecifications.ships.single { it.kind == kind }
            val boats = Math.multiplyExact(count,spec.number.toLong())+spare.filterKeys {it.kind==kind}.values.sumOf {it.toLong()}
            val slots=(boats+spec.number-1)/spec.number
            require(slots in 1..255 && boats in 1..65535) { "Ship stock exceeds native warehouse capacity" }
            WorldMessages.WarehouseShips(kind,slots.toInt(),boats.toInt())
        }
    fun assignmentInventory(units: org.logh7.engine.UnitInventory,
        spare: org.logh7.engine.SpareShipBoats = org.logh7.engine.SpareShipBoats()) = LogisticsMessages.Inventory(
        warehouseShips(units.ships,spare.values).map { LogisticsMessages.ShipStock(it.kind,it.units,it.boats) },
        units.troops.entries.sortedWith(compareBy({ it.key.kind },{ it.key.level }))
            .map { LogisticsMessages.TroopStock(1000+it.key.kind,it.key.level,it.value) })
    fun outfitWarehouse(base: Int, outfitId: Int, character: Int, power: Int, stocks: org.logh7.engine.PlanetStocks): ByteArray {
        val outfit = requireNotNull(stocks.outfits[outfitId]) { "Unknown outfit" }
        require(outfit.baseId == base && outfit.power == power) { "Outfit warehouse identity mismatch" }
        val owner = stocks.bases.getValue(base)
        require(owner.power == power && owner.camp == 0) { "Outfit warehouse base access denied" }
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        val commandsSharedWarehouse = stocks.outfits.values.any { it.warehouseId == warehouse.id && it.commanderId == character }
        require(character > 0 && (commandsSharedWarehouse || stocks.logisticsOfficers[character]?.permits(power,outfit.kind) == true)) {
            "Outfit warehouse access denied"
        }
        fun entries(values: Map<org.logh7.engine.UnitStack,Int>) = values.entries
            .sortedWith(compareBy({ it.key.kind },{ it.key.level })).map { WorldMessages.Stock(it.key.kind,it.key.level,it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base,warehouse.supplies,warehouse.food,warehouse.mineral,
            warehouseShips(warehouse.units.ships,warehouse.spareShips.values),entries(warehouse.units.troops),outfitId))
    }
    fun warehouse(base: Int, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray {
        val state = requireNotNull(galaxy.conditions[base]) { "Unknown warehouse base" }
        val stored = stocks?.let { checkNotNull(it.bases[base]) }
        fun entries(values: Map<org.logh7.engine.UnitStack, Int>?) = values.orEmpty().entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.level })).map { WorldMessages.Stock(it.key.kind, it.key.level, it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base, stored?.supplies ?: state.supplies,
            stored?.food ?: state.food, stored?.mineral ?: 100_000, warehouseShips(stored?.units?.ships,stored?.spareShips?.values.orEmpty()), entries(stored?.units?.troops)))
    }
    internal fun baseWire(base: org.logh7.engine.GalaxyBase) = WorldMessages.Base(base.id, base.grid,
        base.model, base.kind, KoreanDisplayNames.base(base.name), base.physicalClass, base.radius, base.cycle, base.direction, base.angle, base.diameter)
    fun initialize(grid: Int, directory: Boolean = false, stocks: org.logh7.engine.PlanetStocks? = null): List<ByteArray> {
        val ids = galaxy.bases.filter { it.grid == grid }.map { it.id }
        // 004b76e0 builds the native standing-outfit cache after initialization
        // completes. A later standalone 032b query only fills its detail slot.
        return (if (directory) galaxy.bases.chunked(200).map { chunk ->
            LoginMessages.sessionMessage(0x1204, WorldMessages.simpleBases(chunk.map(::baseWire)))
        } else emptyList()) + (stocks?.let { state -> state.outfits.values
            .filter { it.positionIn(galaxy).grid == grid }.sortedBy { it.id }.map { it.id }
            .chunked(100).ifEmpty { listOf(emptyList()) }.map { outfits(it,state) } }.orEmpty()) +
            // Ownership is public map state. The original 031f cache holds only four
            // records; the compatibility map keeps each decoded base independently.
            // Resend all current owners on login, not the seed's original system power.
            (if (directory) galaxy.bases.map { it.id }.chunked(4).map { conditions(it,stocks) }
                else emptyList()) +
            // Restore the native four-record current-grid cache after the map snapshot.
            listOf(conditions(ids,stocks), places(ids, stocks))
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
    fun places(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray = LoginMessages.sessionMessage(0x0321,
        WorldMessages.institutions(ids.mapNotNull { id -> galaxy.bases.find { it.id == id } }.map { base ->
            WorldMessages.BaseInstitutions(base.id, galaxy.facilitiesAt(base.id, stocks).map { facility ->
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
        0x0306 to WorldMessages.captainCommands(officeCards.mapValues {it.value.commands}),
        0x0304 to WorldMessages.cards(officeCards),
        0x0308 to WorldMessages.powerDirection(),
        0x0310 to WorldMessages.armaments(org.logh7.engine.TacticalArmaments.profiles),
        0x030a to WorldMessages.ships(UnitSpecifications.ships.map { ship ->
            val beam = org.logh7.engine.TacticalArmaments.mount(ship, org.logh7.engine.TacticalWeapon.BEAM)
            val gun = org.logh7.engine.TacticalArmaments.mount(ship, org.logh7.engine.TacticalWeapon.GUN)
            val missile = org.logh7.engine.TacticalArmaments.mount(ship, org.logh7.engine.TacticalWeapon.MISSILE)
            // 004c5d50/00533980 use native +0x21e as the shared 0..19
            // class index, not the provisional hull-role IDs in ship.type.
            WorldMessages.Ship(ship.kind, ship.category, ship.model, KoreanDisplayNames.catalogue(ship.name),
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
                cargo = ship.number("物資搭載量:物資搭載量"),
                beamArms = beam.profile, beamAngles = beam.angles, gunArms = gun.profile, gunAngles = gun.angles,
                missileArms = missile.profile, missileAngles = missile.angles,
                commandRange=if(ship.number==1)org.logh7.engine.TacticalSupportRules.COMMAND_RANGE else 0f)
        }),
        0x030c to WorldMessages.crews(UnitSpecifications.crews.map { crew ->
            WorldMessages.Crew(crew.kind, crew.category, crew.category, crew.manual.training,
                crew.manual.groundAttack ?: 0, crew.manual.groundDefense ?: 0)
        }),
    )
}
