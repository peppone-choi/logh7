package org.logh7.engine

/** Personal beam fire first; fleet/CRC authority and other weapons remain separate work. Author: 최병호. */
data class TacticalShoot(val units: List<Int>, val arms: Int, val targetKind: Int, val target: Int)

data class TacticalPendingFire(val sequence: Long, val grid: Int, val shipKind: Int, val target: Int,
    val acceptedAt: Long, val firesAt: Long) {
    init { require(sequence > 0 && grid in 0..4999 && Flagships.find(shipKind) != null && target in 1..999999 &&
        acceptedAt in 0..0xffffffffL && firesAt in acceptedAt..0xffffffffL) }
}
data class TacticalHit(val sequence: Long, val grid: Int, val seconds: Long, val target: Int, val destroyed: Int) {
    init { require(sequence > 0 && grid in 0..4999 && seconds in 0..0xffffffffL && target in 1..999999 && destroyed in 1..300) }
}
data class TacticalFire(val sequence: Long, val pending: TacticalPendingFire? = null, val last: TacticalHit? = null) {
    init { require(sequence > 0 && (pending == null || pending.sequence == sequence) &&
        (last == null || last.sequence <= sequence) && (pending == null || last == null || last.sequence < sequence)) }
}

/** World actor only. Pending fire and the resulting losses share the world replacement transaction.
 * Five real seconds of command delay, range profiles and casualty arithmetic are replacement-server
 * policy. No random retry, login or query can duplicate an accepted shot.
 */
internal object TacticalCombat {
    private fun target(id: Int, grid: Int, power: Power, stocks: PlanetStocks, galaxy: Galaxy): Pair<OutfitStock, ActiveShipUnit>? =
        stocks.outfits.values.asSequence().filter { it.power != if(power == Power.EMPIRE) 2 else 3 }
            .filter { it.positionIn(galaxy) == OutfitPosition(grid) }
            .mapNotNull { outfit -> outfit.activeShips?.values?.singleOrNull { it.id == id && it.boats > 0 }?.let { outfit to it } }
            .singleOrNull()

    fun accept(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        stocks: PlanetStocks, committer: WorldStateCommitter?, scale: Long): NavigationResult {
        val preview = navigation.apply(request.copy(tacticalShoot = null), session, stocks, persist = false)
        val player = preview.player ?: return preview
        fun reject(reason: NavigationRejection) = NavigationResult(null, player, reason)
        if (committer == null) return reject(NavigationRejection.DISABLED)
        if (!player.cpPaused || player.baseId != 0 || player.mode != 5) return reject(NavigationRejection.TACTICAL)
        val shoot = checkNotNull(request.tacticalShoot)
        if (shoot.units != listOf(player.unitId)) return reject(NavigationRejection.UNIT)
        if (request.captainCard != if(player.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
        val ship = Flagships.find(checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
        if (ship.power != if(player.power == Power.EMPIRE) 2 else 3) return reject(NavigationRejection.UNIT)
        if (shoot.arms != 0 || shoot.targetKind != 1) return reject(NavigationRejection.WEAPON)
        if (player.retreat != null || player.fire?.pending != null) return reject(NavigationRejection.BUSY)
        val now = session.gameMillis / 1000
        if (now > 0xffffffffL - 5 * scale || player.fire?.sequence == Long.MAX_VALUE) return reject(NavigationRejection.CLOCK)
        val victim = target(shoot.target, player.grid, player.power, stocks, galaxy) ?: return reject(NavigationRejection.TARGET)
        val players = navigation.snapshot(session.generation)
        if (effectiveness(player, ship.kind, victim, now, players.values, stocks, galaxy) == 0) return reject(NavigationRejection.RANGE)
        val charge = player.charge?.settle(now, player.energy ?: TacticalEnergy.ZERO)
        if (charge == null || charge.beam != 1000 * charge.clockScale) return reject(NavigationRejection.ENERGY)
        val sequence = (player.fire?.sequence ?: 0) + 1
        val next = player.copy(charge = charge, fire = TacticalFire(sequence,
            TacticalPendingFire(sequence, player.grid, ship.kind, shoot.target, now, now + 5 * scale), player.fire?.last))
        navigation.saveReplacement(players, players + (request.account to next), session.generation)
        return NavigationResult(next, player)
    }

    private fun effectiveness(player: Navigator, kind: Int, victim: Pair<OutfitStock, ActiveShipUnit>, now: Long,
        players: Collection<Navigator>, stocks: PlanetStocks, galaxy: Galaxy): Int {
        val ship = UnitSpecifications.ships.single { it.kind == kind }
        val source = player.motion?.pose(now) ?: TacticalDeployment.initial(player.unitId, player.power, player.grid, players, stocks, galaxy)
        val targetPower = if(victim.first.power == 2) Power.EMPIRE else Power.ALLIANCE
        val target = TacticalDeployment.initial(victim.second.id, targetPower, player.grid, players, stocks, galaxy)
        return TacticalArmaments.effectiveness(TacticalArmaments.mount(ship, TacticalWeapon.BEAM), source, target.position)
    }

    fun settle(session: SessionState, galaxy: Galaxy, navigation: Navigation, production: PlanetProduction,
        committer: WorldStateCommitter?) {
        val original = navigation.snapshot(session.generation)
        val due = original.entries.filter { it.value.fire?.pending?.firesAt?.let { at -> at <= session.gameMillis / 1000 } == true }
            .sortedWith(compareBy({it.value.fire!!.pending!!.firesAt}, {it.value.unitId}))
        if (due.isEmpty()) return
        val stocks = checkNotNull(production.snapshot(session.generation))
        val players = original.toMutableMap()
        var inventory = stocks
        for ((account, _) in due) {
            val player = players.getValue(account)
            val fire = checkNotNull(player.fire)
            val shot = checkNotNull(fire.pending)
            var next = player.copy(fire = fire.copy(pending = null))
            val victim = target(shot.target, shot.grid, player.power, inventory, galaxy)
            if (session.ended == null && player.cpPaused && player.grid == shot.grid && player.baseId == 0 &&
                player.retreat == null && victim != null && player.charge?.at?.let { it <= shot.firesAt } == true) {
                val charge = player.charge.settle(shot.firesAt, player.energy ?: TacticalEnergy.ZERO)
                val effect = effectiveness(player, shot.shipKind, victim, shot.firesAt, players.values, inventory, galaxy)
                if (effect > 0 && charge.beam == 1000 * charge.clockScale) {
                    val spec = UnitSpecifications.ships.single { it.kind == shot.shipKind }
                    val hull = UnitSpecifications.ships.single { it.kind == victim.second.stack.kind }
                    // Conservative frontal armour; one flagship contributes one ship's weapon power.
                    val divisor = 100L * maxOf(1, hull.number("装甲:前"))
                    val losses = ((TacticalArmaments.mount(spec,TacticalWeapon.BEAM).power.toLong() * effect + divisor - 1) / divisor)
                        .coerceIn(1, victim.second.boats.toLong()).toInt()
                    val remaining = victim.second.boats - losses
                    val outfit = victim.first
                    // Keep a zero-boat formation as a wreck: IDs/initial formation slots remain stable.
                    val ships = ActiveShipUnits(outfit.activeShips!!.values.map {
                        if(it.id != shot.target) it else it.copy(boats = remaining)
                    })
                    val units = UnitInventory(ships.quantities(), outfit.units.troops)
                    inventory = inventory.copy(outfits = inventory.outfits + (outfit.id to outfit.copy(
                        activeShips = ships, units = units, supplies = minOf(outfit.supplies, OutfitSupplies.capacity(units)))))
                    next = next.copy(charge = charge.copy(beam = 0), fire = fire.copy(pending = null,
                        last = TacticalHit(shot.sequence, shot.grid, shot.firesAt, shot.target, hull.number - remaining)))
                }
            }
            players[account] = next
        }
        val prepared = production.prepareReplacement(stocks, inventory)
        checkNotNull(committer) { "Pending combat requires durable world storage" }.saveWorld(players, prepared)
        navigation.publishCommitted(original, players, session.generation)
        production.publishCommitted(stocks, prepared)
    }
}
