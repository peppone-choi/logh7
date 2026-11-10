package org.logh7.engine

/** Shared deployed-force accounting for map reports and movement. Author: Peppone Choi. */
object GridForces {
    private val ground = UnitSpecifications.crews.filter { it.category != 19 }.map { it.kind }.toSet()
    private fun ships(outfit: OutfitStock): Long = outfit.activeShips?.values?.count { it.boats > 0 }?.toLong()
        ?: outfit.units.ships.values.sumOf { it.toLong() }
    fun units(outfit: OutfitStock): Long = ships(outfit) +
        outfit.units.troops.filterKeys { it.kind in ground }.values.sumOf { it.toLong() }
    /** Ashore units remain at their base when the carrier outfit moves elsewhere. */
    fun mobileUnits(outfit: OutfitStock): Long = ships(outfit) + (outfit.activeGround?.values?.count {it.base==0}?.toLong()
        ?: outfit.units.troops.filterKeys {it.kind in ground}.values.sumOf {it.toLong()})
    data class Force(val camp: Camp, val units: Long = 0, val nonSolo: Boolean = false,
        val ship: Boolean = false, val base: Boolean = false, val deployedShip: Boolean = false)

    fun collect(galaxy: Galaxy, generation: Long, navigators: Collection<Navigator>,
        stocks: PlanetStocks?, eliminated: Set<CountryId> = emptySet()): Map<Int, List<Force>> {
        require(stocks == null || stocks.generation == generation)
        val result = linkedMapOf<Int, MutableMap<Camp, Force>>()
        fun add(grid: Int, force: Force) {
            val camps = result.getOrPut(grid) { linkedMapOf() }
            val previous = camps[force.camp] ?: Force(force.camp)
            camps[force.camp] = Force(force.camp, Math.addExact(previous.units, force.units),
                previous.nonSolo || force.nonSolo, previous.ship || force.ship, previous.base || force.base,
                previous.deployedShip || force.deployedShip)
        }
        navigators.filter {
            it.generation == generation && it.country !in eliminated && !BattleDeath.deceased(stocks, it.characterId) &&
                stocks?.social?.justice?.detained(it.characterId) != true
        }.forEach {
            val player = it
            // U-10: a revolt's rebels count as their side's rebel camp (manual p.31).
            val camp = stocks?.let { TacticalAuthority.camp(player, it) } ?: Camp.of(player)
            val grouped = stocks != null && stocks.outfits.values.any { outfit ->
                player.characterId in outfit.members() && TacticalAuthority.camp(outfit, stocks) == camp &&
                    outfit.positionIn(galaxy).let { position -> position.grid == player.grid && position.baseId == player.baseId }
            }
            add(player.grid, Force(camp, 1, nonSolo = grouped, ship = true,
                deployedShip = player.baseId == 0 && player.mode == 5))
        }
        galaxy.bases.forEach { base ->
            val stock = stocks?.bases?.get(base.id)
            if(stocks!=null && stock==null)return@forEach
            val initial = galaxy.conditions.getValue(base.id)
            val owner=stock?.owner ?: galaxy.initialOwners.getValue(base.id)
            val camp=when(owner) {
                is Allegiance.Country -> Camp(owner.id,(stock?.camp ?: initial.camp)==1)
                is Allegiance.Independent -> Camp(Camp.Owner.Independent(owner.characterId))
                else -> null
            }
            if(camp!=null)add(base.grid, Force(camp, base = true))
            // U-07 (CD p.39, evidence:guess): after a violation, neutral Fezzan is a planet troops may land on and occupy.
            if (stocks != null && stocks.social.trade.violations.isNotEmpty() && TradeActions.fezzan(galaxy, stocks) == base.id)
                add(base.grid, Force(Camp.FEZZAN, base = true))
        }
        stocks?.outfits?.values?.forEach { outfit ->
            if(outfit.country in eliminated)return@forEach
            val ships = ships(outfit)
            val count = mobileUnits(outfit)
            if (count > 0) {
                val position = outfit.positionIn(galaxy)
                add(position.grid, Force(Camp(outfit.country, CoupActions.outfitCamp(stocks, outfit)==1),
                    count, nonSolo = true, ship = ships > 0, deployedShip = ships > 0 && position.baseId == 0))
            }
            outfit.activeGround?.values.orEmpty().filter {it.base>0}.groupingBy {it.base}.eachCount().forEach { (base,count) ->
                add(galaxy.bases.single {it.id==base}.grid,
                    Force(Camp(outfit.country, CoupActions.outfitCamp(stocks, outfit)==1),count.toLong(),nonSolo=true))
            }
        }
        stocks?.social?.pirates?.fleets?.values?.filter { it.alive }?.forEach { fleet ->
            add(fleet.grid, Force(Camp.PIRATES, fleet.units.count { it.boats > 0 }.toLong(), nonSolo = true, ship = true, deployedShip = true))
        }
        return result.mapValues { it.value.values.toList() }
    }

    fun state(galaxy: Galaxy, grid: Int, forces: Map<Int, List<Force>>): GridState =
        GridState(galaxy.kind(grid), galaxy.terrain(grid), TacticalContact.contested(forces[grid].orEmpty()), forces[grid].orEmpty().map {
            // Capacity decisions need only distinguish counts above 300. Keep the
            // full Long total in collect(), including for native map reports.
            CampPresence(it.camp, it.units.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it.nonSolo, it.deployedShip, it.base)
        })
}
