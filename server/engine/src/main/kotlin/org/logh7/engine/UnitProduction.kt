package org.logh7.engine

import org.logh7.engine.worldseed.AutomaticProductionSeedLoader
import org.logh7.engine.worldseed.SeedFaction

data class UnitProductionPolicy(val peoplePerUnit: Int = 1000, val foodPerTroop: Int = 100,
    val suppliesPerTroop: Int = 20, val mineralPerTroop: Int = 10,
    val mineralPerShipHour: Int = 10, val suppliesPerShipHour: Int = 5) {
    init { require(listOf(peoplePerUnit, foodPerTroop, suppliesPerTroop, mineralPerTroop,
        mineralPerShipHour, suppliesPerShipHour).all { it in 1..100000 }) }
}

/** Manual products/terms; hours, batch interpretation and material/manpower costs are designed policy. */
internal class UnitProduction(private val galaxy: Galaxy, private val policy: UnitProductionPolicy,
    private val policyEconomy: PlanetEconomyPolicy, private val maximumStock: Int,
    private val countries:CountryCatalog?=null) {
    data class Line(val unit: ProductionUnit, val batch: Int)
    private val names = mapOf("ギンヌンガガプ" to "ギンヌンガガガブ", "テルモピュレー" to "テルモビュレー",
        "ラヴンストラップ" to "ラブンストラップ", "タフテ・ジャムシード" to "タフテ・ジャムジード", "アパダナ" to "アバダナ",
        "ピエヴェッタ" to "ビエヴェッタ", "ボルゲーゼ" to "ボルケーゼ", "ニーダー・ヴィンディンゲ" to "ニーダーヴィンデンボルン",
        "パルテンキルヒェン" to "ヴァルテンキルヒェン", "イーヴァルディ" to "イーヴァンルディ", "カパール" to "カバール",
        "フリングホルニ" to "フリングオルニ", "バクタプール" to "バグタプール", "スヴォルヴェア" to "スボルヴェア",
        "ヒミンビョルグ" to "ヒミンギョルグ", "ギャラルホルン" to "ギャランホルン", "ボーデルスベルク" to "ボーデルスベルグ",
        "レンテンベルク" to "レンテンベルグ")
    val lines: Map<Int, List<Line>> = buildMap {
        val systems = galaxy.systems.associateBy { it.grid }
        val source = AutomaticProductionSeedLoader.load().entries.distinctBy {
            listOf(it.faction, it.starSystem, it.planet, it.shipUnits, it.crewUnits, it.groundUnits)
        }
        for (base in galaxy.bases) {
            val system = systems.getValue(base.grid)
            val rows = source.filter { it.starSystem == system.name && (names[it.planet] ?: it.planet) == base.name }
            val products = rows.flatMap { row ->
                val power = if (row.faction == SeedFaction.EMPIRE) 2 else 3
                (row.shipUnits + row.crewUnits + row.groundUnits).map { ProductionUnits.find(power, it) }
            }
            if (products.isNotEmpty()) put(base.id, products.groupingBy { it }.eachCount().map { Line(it.key, it.value) })
        }
    }
    // Occupation policy: restart shared named recipes in the captor's catalogue.
    // Faction-exclusive technology has no inferred replacement. Original manual rows stay intact.
    private val modelLines = (2..3).associateWith { power ->
        lines.mapValues { (_, source) ->
            source.mapNotNull { line ->
                ProductionUnits.definitions.singleOrNull {
                    it.power == power && it.ship == line.unit.ship && it.name == line.unit.name
                }?.let { Line(it, line.batch) }
            }.groupBy { it.unit }.map { (unit, batches) -> Line(unit, batches.sumOf { it.batch }) }
        }
    }
    fun forCountry(base:Int,country:CountryId?,catalog:CountryCatalog=countries ?: CountryCatalogLoader.current()):List<Line> =
        country?.takeIf {it.value in catalog.countries}?.let {modelLines[catalog.shipStyle(it).nativePower]?.get(base)}.orEmpty()

    fun advance(stock: PlanetStock, dayStart: PlanetStock, shipyardOpen: Boolean? = null,
        countries:CountryCatalog=this.countries ?: CountryCatalogLoader.current()): PlanetStock {
        val country=stock.country?.takeIf {it.value in countries.countries} ?: return stock
        val style=countries.shipStyle(country)
        val people = checkNotNull(stock.economy)
        val startPeople = checkNotNull(dayStart.economy)
        val base = galaxy.conditions.getValue(stock.baseId)
        // Use the same pre-growth daily food test as the economy; do not build during a famine.
        val farm = if (base.habitability != 0) startPeople.adults.toLong() / policyEconomy.foodWorkersPerUnit else 0
        val available = if (dayStart.food >= maximumStock) dayStart.food.toLong() else minOf(maximumStock.toLong(), dayStart.food + farm)
        val needed = (startPeople.population.toLong() + policyEconomy.foodPeoplePerUnit - 1) / policyEconomy.foodPeoplePerUnit
        if (available < needed || stock.camp != 0 || people.adults == 0) return stock
        // U-08: a built or paused shipyard overrides the original facility list.
        val shipyard = shipyardOpen ?: galaxy.facilities.getValue(stock.baseId).any { it.kind == 10 }
        val ships = stock.units.ships.toMutableMap(); val troops = stock.units.troops.toMutableMap()
        val configured=forCountry(stock.baseId,country,countries).associateBy {it.unit.key}
        val work=stock.units.work.filterKeys {key->when(stock.productionCountry) {
            country->true
            null->ProductionUnits.definitions.singleOrNull {it.key==key}?.let {it.power==country.value} ?: true
            else->false
        }}.toMutableMap()
        work.keys.removeIf {it !in configured && work.getValue(it)==0}
        // A style change finishes each already-started old recipe once. Occupation clears its ownership.
        val pending=work.keys.filter {it !in configured}.mapNotNull {key->
            val unit=ProductionUnits.definitions.singleOrNull {it.key==key} ?: return@mapNotNull null
            modelLines[unit.power]?.get(stock.baseId)?.singleOrNull {it.unit.key==key}
        }
        var food = stock.food; var mineral = stock.mineral; var supplies = stock.supplies; var population = people.population; var adults = people.adults
        for (line in (configured.values+pending).sortedBy { it.unit.key }) {
            val unit = line.unit
            if (unit.ship && !shipyard) continue
            val finishing=unit.key !in configured
            var hours = (work[unit.key] ?: 0) + 24
            val target = if (unit.ship) ships else troops
            val key = UnitStack(unit.kind)
            while (hours >= unit.hours) {
                val room = if (unit.ship) warehouseShipProductionRoom(unit.kind, ships, stock.spareShips)
                    else 65535 - (target[key] ?: 0)
                val count = minOf(line.batch, room)
                val mineralCost = count.toLong() * if (unit.ship) unit.hours.toLong() * policy.mineralPerShipHour else policy.mineralPerTroop.toLong()
                val supplyCost = count.toLong() * if (unit.ship) unit.hours.toLong() * policy.suppliesPerShipHour else policy.suppliesPerTroop.toLong()
                val foodCost = if (unit.ship) 0 else count.toLong() * policy.foodPerTroop
                val peopleCost = if (unit.ship) 0 else count.toLong() * policy.peoplePerUnit
                if (count <= 0 || (key !in target && target.size >= if (unit.ship) 99 else 24) ||
                    mineralCost > mineral || supplyCost > supplies || foodCost > food || peopleCost > adults) { hours = unit.hours; break }
                target[key] = (target[key] ?: 0) + count
                mineral -= mineralCost.toInt(); supplies -= supplyCost.toInt(); food -= foodCost.toInt()
                population -= peopleCost.toInt(); adults -= peopleCost.toInt()
                hours -= unit.hours
                if(finishing) {hours=0;break}
            }
            if(finishing && hours==0)work.remove(unit.key) else work[unit.key] = hours
        }
        return stock.copy(supplies = supplies, food = food, mineral = mineral, economy = people.copy(population = population, adults = adults),
            units = UnitInventory(ships, troops, work),
            productionCountry=if(stock.productionCountry!=null || style.nativePower!=country.value)country else null)
    }
}
