package org.logh7.engine

import java.text.Normalizer
import org.logh7.engine.worldseed.*

/** Locations/numbers: manual p.75. Strength and reserve placements are designed server policy. */
data class InitialOutfitPolicy(val battleships: Int = 10, val cruisers: Int = 10, val destroyers: Int = 20,
    val patrolDestroyers: Int = 2, val groundInfantry: Int = 5,
    val fleetSupplies: Int = 100_000, val patrolSupplies: Int = 10_000, val groundSupplies: Int = 1_000) {
    init {
        require(listOf(battleships,cruisers,destroyers,patrolDestroyers,groundInfantry).all { it in 1..60 })
        require(battleships + cruisers + destroyers <= 60)
        require(listOf(fleetSupplies,patrolSupplies,groundSupplies).all { it >= 0 })
    }
}

class InitialOutfitDeployment(private val galaxy: Galaxy, private val policy: InitialOutfitPolicy = InitialOutfitPolicy()) {
    data class Placement(val power: Int, val kind: OutfitKind, val number: Int, val base: Int,
        val source: ManualSource?, val designedLocation: Boolean = false)
    private val systems = mapOf("シュバーラ" to "シュパーラ", "ニブルヘイム" to "ニヴルヘイム", "バルドレ" to "バルドル",
        "ルイトボルディング" to "ルイトポルディング", "ロフォーデン" to "ロフォーテン",
        "ヴィテルスバッハ" to "ヴィッテルスバッハ", "ヴィレンンシュタイン" to "ヴィレンシュタイン")
    private val bases = mapOf("ガイエスブルグ" to "ガイエスブルク", "サンア・アナ" to "サンタ・アナ",
        "ボルゲーゼ" to "ボルケーゼ", "フリングホルニ" to "フリングオルニ", "レンテンベルク" to "レンテンベルグ")
    private fun base(system: String, name: String) = galaxy.bases.singleOrNull { b ->
        val star = galaxy.systems.single { it.grid == b.grid }
        (systems[star.name] ?: star.name) == (systems[system] ?: system) &&
            (bases[b.name] ?: b.name) == (bases[name] ?: name)
    }
    private fun capital(power: Int) = if (power == 2) base("ヴァルハラ","オーディン") else base("バーラト","ハイネセン")
    private val missing = mutableListOf<InitialDeployment>()
    val unmapped: List<InitialDeployment> get() = java.util.Collections.unmodifiableList(missing)
    val placements: List<Placement> = buildList {
        for (row in InitialDeploymentSeedLoader.load().entries) {
            val power = if (row.faction == SeedFaction.EMPIRE) 2 else 3
            val kind = when (row.unitKind) {
                DeploymentUnitKind.FLEET -> OutfitKind.FLEET
                DeploymentUnitKind.PATROL -> OutfitKind.PATROL
                DeploymentUnitKind.GROUND -> OutfitKind.GROUND
            }
            val suffix = when (kind) { OutfitKind.FLEET -> "艦隊"; OutfitKind.PATROL -> "巡察隊"; else -> "地上部隊" }
            val label = Normalizer.normalize(row.unitLabel.trim(),Normalizer.Form.NFKC)
            val match = requireNotNull(Regex("第([0-9]+)$suffix(?: / [~〜]第([0-9]+)$suffix)?").matchEntire(label)) {
                "Unrecognized deployment range at ${row.source}"
            }
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: first
            require(first in 1..last && last <= if (kind == OutfitKind.FLEET) 12 else 60)
            val target = if (row.starSystem.isBlank()) capital(power) else base(row.starSystem,row.location)
            if (target == null) { missing.add(row); continue }
            require(galaxy.conditions.getValue(target.id).power == power) { "Deployment belongs to another power: ${row.source}" }
            for (number in first..last) add(Placement(power,kind,number,target.id,row.source,row.starSystem.isBlank()))
        }
        // The manual omits Empire patrol 48 and leaves 53..60 without a location. Reserve them at the capital.
        capital(2)?.let { home ->
            if (none { it.power == 2 && it.kind == OutfitKind.PATROL && it.number == 48 })
                add(Placement(2,OutfitKind.PATROL,48,home.id,null,true))
        }
    }.sortedWith(compareBy({ it.power },{ it.kind.wire },{ it.number })).let(java.util.Collections::unmodifiableList)

    fun initialize(stocks: PlanetStocks): PlanetStocks {
        if (stocks.deploymentInitialized) return stocks
        // Respect an existing operator/fixture deployment; never overwrite or refill it.
        if (stocks.outfits.isNotEmpty() || stocks.warehouses.isNotEmpty()) return stocks.copy(deploymentInitialized=true)
        val outfits = linkedMapOf<Int,OutfitStock>()
        val warehouses = linkedMapOf<Int,OutfitWarehouse>()
        for (placement in placements) {
            val p = placement.power; val kind = placement.kind
            val id = (p-2)*132 + when (kind) {
                OutfitKind.FLEET -> placement.number
                OutfitKind.PATROL -> 12 + placement.number
                OutfitKind.GROUND -> 72 + placement.number
                else -> error("No manual transport deployment")
            }
            val shared = kind.warehouseKind == OutfitKind.PATROL
            val warehouse = if (shared) 100_000 + p*65536 + placement.base else 1000 + id
            val inventory = inventory(p,kind)
            val supplies = when (kind) { OutfitKind.FLEET -> policy.fleetSupplies; OutfitKind.PATROL -> policy.patrolSupplies; else -> policy.groundSupplies }
            require(outfits.put(id,OutfitStock(id,p,kind,placement.base,warehouse,units=inventory,
                number=placement.number,supplies=supplies)) == null) { "Duplicate initial outfit identity" }
            warehouses.putIfAbsent(warehouse,OutfitWarehouse(warehouse,p,kind.warehouseKind,placement.base,if (shared) 0 else id))
        }
        return stocks.copy(outfits=outfits,warehouses=warehouses,deploymentInitialized=true)
    }
    private fun inventory(power: Int, kind: OutfitKind): UnitInventory {
        if (kind == OutfitKind.GROUND)
            return UnitInventory(troops=mapOf(UnitStack(ProductionUnits.find(power,"軽装陸戦兵").kind) to policy.groundInfantry))
        val ships = if (kind == OutfitKind.FLEET) mapOf(
            UnitStack(ProductionUnits.find(power,"戦艦Ⅰ").kind) to policy.battleships,
            UnitStack(ProductionUnits.find(power,"巡航艦Ⅰ").kind) to policy.cruisers,
            UnitStack(ProductionUnits.find(power,"駆逐艦Ⅰ").kind) to policy.destroyers)
        else mapOf(UnitStack(ProductionUnits.find(power,"駆逐艦Ⅰ").kind) to policy.patrolDestroyers)
        val required = ships.entries.sumOf { (stack,quantity) -> quantity * UnitSpecifications.ships.single { it.kind == stack.kind }
            .number("必要乗組員ユニット数:必要乗組員ユニット数") }
        val crew = UnitSpecifications.crews.single { it.power == power && it.type == 3 }.kind
        return UnitInventory(ships,if (required == 0) emptyMap() else mapOf(UnitStack(crew) to required))
    }
}
