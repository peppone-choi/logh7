package org.logh7.engine

import java.util.Collections

data class PlanetStock(val baseId: Int, val owner: Allegiance, val camp: Int,
    val supplies: Int, val food: Int, val mineral: Int, val economy: PlanetEconomy? = null,
    val units: UnitInventory = UnitInventory(), val taxRate: Int? = null, val governorId: Int = 0, val garrisonCommanderId: Int = 0,
    val capitalAdministratorId: Int = 0, val capitalDefenseCommanderId: Int = 0, val guardInspectorId: Int = 0, val spareShips: SpareShipBoats = SpareShipBoats(),
    val treasury: Long = 0,
    /** Owner of automatic production progress; null denotes an untagged legacy checkpoint. */
    val productionCountry:CountryId?=null) {
    constructor(baseId:Int,power:Int,camp:Int,supplies:Int,food:Int,mineral:Int,economy:PlanetEconomy?=null,
        units:UnitInventory=UnitInventory(),taxRate:Int?=null,governorId:Int=0,garrisonCommanderId:Int=0,
        capitalAdministratorId:Int=0,capitalDefenseCommanderId:Int=0,guardInspectorId:Int=0,
        spareShips:SpareShipBoats=SpareShipBoats(),treasury:Long=0,productionCountry:CountryId?=null):this(baseId,Allegiance.fromLegacy(power),camp,
            supplies,food,mineral,economy,units,taxRate,governorId,garrisonCommanderId,capitalAdministratorId,
            capitalDefenseCommanderId,guardInspectorId,spareShips,treasury,productionCountry)
    val power:Int get()=owner.legacyPower()
    val country:CountryId? get()=owner.country
    fun belongsTo(country:CountryId)=owner==Allegiance.Country(country)
    init { require(baseId in 1..65535 && camp in 0..1 && supplies >= 0 && food >= 0 && mineral >= 0 &&
        (owner !is Allegiance.Independent || camp==0) &&
        (taxRate == null || taxRate in 0..100) && governorId >= 0 && (governorId == 0 || owner is Allegiance.Country && camp == 0) &&
        garrisonCommanderId >= 0 && (garrisonCommanderId == 0 || owner is Allegiance.Country && camp == 0) &&
        capitalAdministratorId>=0 && (capitalAdministratorId==0 || country==CountryId(3) && camp==0) &&
        capitalDefenseCommanderId>=0 && (capitalDefenseCommanderId==0 || owner is Allegiance.Country && camp==0) &&
        guardInspectorId>=0 && (guardInspectorId==0 || country==CountryId(2) && camp==0) && treasury>=0) }
}
data class PlanetStocks(val generation: Long, val producedDays: Long, val bases: Map<Int, PlanetStock>,
    val outfits: Map<Int, OutfitStock> = emptyMap(), val warehouses: Map<Int, OutfitWarehouse> = emptyMap(),
    val logisticsOfficers: Map<Int, LogisticsOffice> = emptyMap(), val deploymentInitialized: Boolean = false,
    val nextShipId: Int = 1, val nationalOffices:Map<NationalOffice,Int> = emptyMap(),
    val groundBattles:Map<Int,Long> = emptyMap(), val nationalTreasuries:Map<CountryId,Long> = emptyMap(),
    val strategic:StrategicState = StrategicState(),
    val tacticalCommands:TacticalCommandState=TacticalCommandState(),
    val flagshipMarket:FlagshipMarketState=FlagshipMarketState(),
    val outfitSequence:OutfitSequence=OutfitSequence(),val civic:CivicState=CivicState(),val budget:BudgetState=BudgetState(),
    val social:SocialState=SocialState(),
    /** 軍紀維持: outfit ID → stored discipline and when it was stored (OutfitDiscipline). */
    val discipline:Map<Int,OutfitDiscipline> = emptyMap(),
    /** Last game hour whose black-hole tidal loss was applied (BlackHoleTides); 0 before the first settlement. */
    val tidalHour:Long=0,
    /** Air (fighter) training level 1..3 per outfit from 空戦訓練; absent means 0. */
    val airTraining:Map<Int,Int> = emptyMap()) {
    init {
        require(generation > 0 && producedDays in 0..50000 && bases.size <= 350 && bases.all { (id, stock) -> id == stock.baseId })
        validateOutfitStocks(bases, outfits, warehouses, logisticsOfficers)
        require(nextShipId in 1..1_000_000)
        val ids = outfits.values.flatMap { it.activeShips?.values.orEmpty().map {u->u.id} + it.activeGround?.values.orEmpty().map {u->u.id} }
        require(ids.distinct().size == ids.size && ids.all { it < nextShipId }) { "Invalid active unit identity sequence" }
        require(outfits.values.all {it.activeGround?.values.orEmpty().all {u->u.base==0 || u.base in bases}}) {"Unknown ground base"}
        require(groundBattles.size<=350 && groundBattles.all {(base,at)->base in bases && at in 0..0xffffffffL})
        require(nationalOffices.values.all { it>0 }) { "Invalid national office holder" }
        require(nationalTreasuries.size<=1000 && nationalTreasuries.values.all {it>=0}) { "Invalid national treasury" }
        require(strategic.capitals.values.all {it in bases}) {"Unknown country capital"}
        require(flagshipMarket.offers.all {it.base in bases}) {"Unknown flagship exchange base"}
        require(civic.orders.values.all {it.base in bases}) {"Unknown civic order base"}
        require(budget.remittanceRates.keys.all {it in bases} && budget.quotes.values.all {it.base in bases}) {"Unknown budget base"}
        require(social.squares.keys.all {it in bases} && social.speeches.values.all {it.base in bases}) {"Unknown social base"}
        require(discipline.size<=1000 && discipline.keys.all {it>0}) {"Invalid outfit discipline"}
        require(tidalHour in 0..0xffffffffL/3600) {"Invalid tidal hour"}
        require(airTraining.size<=1000 && airTraining.all {(id,level)->id>0 && level in 1..3}) {"Invalid air training"}
    }
}
interface PlanetStockStore : AutoCloseable {
    fun load(): PlanetStocks?
    fun save(stocks: PlanetStocks)
    override fun close() {}
}
fun interface WorldStateCommitter {
    fun saveWorld(navigation: Map<String, Navigator>, inventory: PlanetStocks?)
}
/** Only the supply interval is recovered; quantities, economy formulas and caps are designed policy. */
data class PlanetProductionPolicy(val suppliesPerDay: Int = 1000, val maximumStock: Int = Int.MAX_VALUE,
    val economy: PlanetEconomyPolicy = PlanetEconomyPolicy(), val units: UnitProductionPolicy = UnitProductionPolicy(),
    val tax: PlanetTaxRevenuePolicy = PlanetTaxRevenuePolicy(1,50),
    /** National treasury cost per living ship per day (FleetUpkeep); 0 disables upkeep. */
    val fleetUpkeepPerShip: Long = 800) {
    init { require(suppliesPerDay >= 0 && maximumStock > 0 && fleetUpkeepPerShip >= 0) }
}

/** World-actor-owned. Lazy settlement gives the same result regardless of query frequency or logins. */
internal class PlanetProduction(private val galaxy: Galaxy, private val store: PlanetStockStore?,
    private val startSeconds: Long, private val policy: PlanetProductionPolicy, generation: Long = 1,
    restored:PlanetStocks?=store?.load(),
    private val civicPlayers:(Long)->Collection<Navigator>? = {null},
    private val commitOfficeRecovery:(PlanetStocks, PlanetStocks, Long)->Boolean = { _, _, _ -> false }) {
    private val units = UnitProduction(galaxy, policy.units, policy.economy, policy.maximumStock)
    private val deployment = InitialOutfitDeployment(galaxy)
    private var current = restored?.also { restored ->
        require(restored.bases.keys == galaxy.bases.map { it.id }.toSet()) { "Inventory bases differ from the world seed" }
        require(restored.outfits.values.all { outfit -> outfit.position?.let { position ->
            position.baseId == 0 || galaxy.bases.single { it.id == position.baseId }.grid == position.grid
        } != false }) { "Restored outfit position differs from its current base" }
        restored.bases.values.forEach(BaseOfficePolicy(galaxy)::validate)
        require(restored.generation <= generation) { "Inventory belongs to a newer session" }
        val terms = ProductionUnits.definitions.associate { it.key to it.hours }
        require(restored.bases.values.all { stock -> stock.units.work.all { (key, hours) ->
            terms[key]?.let { hours in 0..it } == true
        } }) { "Invalid production progress" }
    }?.let(::detached)
    private fun detached(stocks: PlanetStocks) = stocks.copy(bases = Collections.unmodifiableMap(LinkedHashMap(stocks.bases)),
        outfits = Collections.unmodifiableMap(LinkedHashMap(stocks.outfits)),
        warehouses = Collections.unmodifiableMap(LinkedHashMap(stocks.warehouses)),
        logisticsOfficers = Collections.unmodifiableMap(LinkedHashMap(stocks.logisticsOfficers)),
        nationalOffices = Collections.unmodifiableMap(LinkedHashMap(stocks.nationalOffices)),
        groundBattles = Collections.unmodifiableMap(LinkedHashMap(stocks.groundBattles)),
        nationalTreasuries = Collections.unmodifiableMap(LinkedHashMap(stocks.nationalTreasuries)),
        strategic=stocks.strategic.detached(),tacticalCommands=stocks.tacticalCommands.detached(),
        flagshipMarket=stocks.flagshipMarket.detached(),civic=stocks.civic.detached(),budget=stocks.budget.detached(),social=stocks.social.detached())
    val maximumStock: Int get() = policy.maximumStock
    val defaultRemittanceRate:Int get()=policy.tax.remittanceRate
    /** Registered characters for periodic personnel rules; supplied by the gateway's character store.
     * Null means the supplier is not wired yet: production then stops just before the next 30-day
     * boundary instead of crossing it with nobody to process (startup catch-up runs before the gateway). */
    @Volatile var identities:(()->List<StrategicIdentity>)? = {emptyList()}
    fun snapshot(generation: Long) = current?.takeIf { it.generation == generation }
    fun settle(session:SessionState)=settle(session.generation,session.gameMillis/1000,session.eliminated.keys)
    fun settle(generation: Long, seconds: Long,eliminated:Set<CountryId> = emptySet()): PlanetStocks {
        require(generation > 0 && seconds in startSeconds..0xffffffffL)
        val previous = current
        require(previous == null || previous.generation <= generation) { "Inventory belongs to a newer session" }
        val reached = (seconds - startSeconds) / 86400
        val initial = ActiveShips.initialize(deployment.initialize(previous?.takeIf { it.generation == generation } ?: PlanetStocks(generation, 0,
            galaxy.conditions.mapValues { (_, base) -> PlanetStock(base.baseId, galaxy.initialOwners.getValue(base.baseId), base.camp, base.supplies, base.food, 100_000) })))
        // Upgrade old rows at their existing watermark; never replay already-produced days.
        val initialized = if (initial.bases.values.all { it.economy != null }) initial else initial.copy(bases = initial.bases.mapValues { (id, stock) ->
            if (stock.economy != null) stock else galaxy.conditions.getValue(id).let { base ->
                stock.copy(economy = PlanetEconomy(base.population, base.adultPopulation, base.approval, base.peace))
            }
        })
        val supplier=identities
        val days=if(supplier!=null)reached else minOf(reached,maxOf(initialized.producedDays,
            (initialized.producedDays/PoliticianLadder.PERIOD_DAYS+1)*PoliticianLadder.PERIOD_DAYS-1))
        // A non-durable test clock may rewind. Keep stock and the watermark; never produce a day twice.
        val national = initialized.nationalTreasuries.toMutableMap()
        // U-20: the 封土 card text (constmsg, evidence:client) gives a fief's taxes to its lord,
        // so the whole collected amount goes to the lord's private money, not the treasuries.
        val lords = linkedMapOf<Int, Long>()
        val capitals = CapitalProvisioning.capitals(galaxy, initialized)
        val result = if (days <= initialized.producedDays) initialized else {
            val bases=initialized.bases.toMutableMap()
            // Settle capitals first each day; recipients share only today's real surplus in stable base-id order.
            val order=(capitals.values.sorted()+bases.keys.sorted()).distinct()
            val countryDefinitions=CountryCatalogLoader.current()
            val bombs=initialized.social.justice.bombs.values.groupBy(Bomb::base)
            repeat(Math.toIntExact(days - initialized.producedDays)) {day->
                val dayEnd=Math.addExact(startSeconds,Math.multiplyExact(initialized.producedDays+day+1,86400L))
                for(id in order) {
                    val previousDay=bases.getValue(id)
                    var next=previousDay
                    val base=galaxy.conditions.getValue(id)
                    // Production catches up before combat explodes pending bombs, so include their historical intervals.
                    val stopped=EspionageActions.disabled(initialized,id,dayEnd) ||
                        bombs[id].orEmpty().any {EspionageActions.stopsProductionAt(it,dayEnd)}
                    val revenue=PlanetTaxRevenue.assess(next,base,policy.tax,BudgetActions.rate(initialized,id,policy.tax.remittanceRate))
                    val lord=initialized.strategic.fiefs[id]
                    if(lord!=null)lords[lord]=Math.addExact(lords[lord] ?: 0,revenue.collected)
                    else if(revenue.central>0) {
                        val country=checkNotNull(next.country)
                        national[country]=Math.addExact(national[country] ?: 0,revenue.central)
                    }
                    val capital=capitals[next.country]?.let(bases::getValue)
                    if(!stopped)CapitalProvisioning.topUp(next,base,policy,capitals,capital?.food ?: 0)?.let {(fed,amount)->
                        next=fed
                        val source=checkNotNull(capital)
                        bases[source.baseId]=source.copy(food=source.food-amount)
                    }
                    bases[id] = (if(stopped)previousDay else units.advance(next.nextDay(base, policy), next,
                        ProductionActions.shipyard(galaxy,initialized,id,dayEnd),countryDefinitions))
                        .copy(treasury=Math.addExact(previousDay.treasury,if(lord!=null)0 else revenue.local))
                        .let {GoalActions.lean(it,previousDay,initialized.social.goals.focus[id],stopped)}
                }
                // The day's income first, then that day's upkeep on the ships a settlement at the day's end would
                // leave after black-hole losses (PR #261 review). Only a preview: the losses themselves stay with
                // the single later BlackHoleTides settlement, which keeps its per-settlement backlog cap.
                if(policy.fleetUpkeepPerShip>0)FleetUpkeep.pay(BlackHoleTides.settle(galaxy,initialized,dayEnd),
                    national,policy.fleetUpkeepPerShip)
            }
            initialized.copy(producedDays=days,bases=bases,nationalTreasuries=national).let {produced ->
                if(lords.isEmpty())produced else produced.copy(strategic=produced.strategic.copy(people=produced.strategic.people+
                    lords.mapValues {(lord,amount)->(produced.strategic.people[lord] ?: StrategicPerson()).let {
                        it.copy(money=minOf(0xffffffffL,it.money+amount))}}))
            }
        }
        // U-28: politician-ladder rules run once per crossed 30-day boundary, in order.
        var monthly=result
        val firstMonth=initialized.producedDays/PoliticianLadder.PERIOD_DAYS+1;val lastMonth=days/PoliticianLadder.PERIOD_DAYS
        if(days>initialized.producedDays && lastMonth>=firstMonth) {
            val people=checkNotNull(supplier)()
            if(people.isNotEmpty())for(month in firstMonth..lastMonth)
                monthly=AbilityAging.monthly(PoliticianLadder.monthly(galaxy,monthly,people,month),people,month)
        }
        // A throne vacated by death or removal is filled at the next settlement (SuccessionActions).
        if(monthly.social.succession.anyVacant())supplier?.invoke()?.let {monthly=SuccessionActions.accede(galaxy,monthly,it,seconds)}
        val players=civicPlayers(generation)
        val settled=CivicActions.settle(galaxy,SuccessionActions.ranked(StrategyActions.settle(monthly,seconds,galaxy,players,eliminated=eliminated),supplier?.invoke().orEmpty(),g=galaxy),seconds,players,eliminated=eliminated)
        val withCargo = TransportActions.settleCargo(settled,previous)
        if (withCargo == previous) return checkNotNull(previous)
        val next = detached(withCargo)
        if (previous == null || previous.generation != generation || !commitOfficeRecovery(previous, next, seconds))
            store?.save(next)
        current = next
        return next
    }

    /** Called only by the world actor after authorization; both sides share one durable replacement. */
    fun replace(expected: PlanetStocks, next: PlanetStocks): PlanetStocks {
        val result = prepareReplacement(expected, next)
        store?.save(result)
        publishCommitted(expected, result)
        return result
    }
    fun prepareReplacement(expected: PlanetStocks, next: PlanetStocks, cargoBaseline:PlanetStocks=expected): PlanetStocks {
        check(current === expected) { "Inventory changed during transaction" }
        require(next.generation == expected.generation && next.producedDays == expected.producedDays)
        require(next.bases.keys == expected.bases.keys)
        require(next.outfits.values.all { outfit -> outfit.position?.let { position ->
            position.baseId == 0 || galaxy.bases.single { it.id == position.baseId }.grid == position.grid
        } != false }) { "Outfit position differs from its current base" }
        return detached(TransportActions.settleCargo(next,cargoBaseline))
    }
    fun publishCommitted(expected: PlanetStocks, next: PlanetStocks) {
        check(current === expected)
        current = next
    }
}
