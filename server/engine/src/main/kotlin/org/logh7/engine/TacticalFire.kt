package org.logh7.engine

/** Personal flagship weapons; fleet/CRC authority remains separate work. Author: 최병호. */
data class TacticalShoot(val units: List<Int>, val arms: Int, val targetKind: Int, val target: Int)

/** Native 0405 uses its suffix byte as attack mode (0 stop, 1 salvo, 2 continuous).
 * Salvo waits for every fitted charged weapon; continuous fires each ready weapon.
 * The cadence and salvo charge gate are replacement-server policy. Author: 최병호. */
data class TacticalAttack(val units: List<Int>, val mode: Int, val target: Int = 0)
data class TacticalAttackOrder(val mode: Int, val acceptedAt: Long, val startsAt: Long, val nextAt: Long) {
    init { require(mode in 1..2 && acceptedAt in 0..0xffffffffL && startsAt in acceptedAt..0xffffffffL &&
        nextAt in startsAt..0xffffffffL) }
}

data class TacticalPendingFire(val sequence: Long, val grid: Int, val shipKind: Int, val target: Int,
    val acceptedAt: Long, val firesAt: Long, val weapon: TacticalWeapon = TacticalWeapon.BEAM) {
    init { require(sequence > 0 && grid in 0..4999 && Flagships.find(shipKind) != null && validCombatTarget(target) &&
        acceptedAt in 0..0xffffffffL && firesAt in acceptedAt..0xffffffffL) }
}
data class TacticalHit(val sequence: Long, val grid: Int, val seconds: Long, val target: Int, val destroyed: Int,
    val damaged: Int = 0, val shieldFace: Int = 255, val shieldDamage: Int = 0) {
    init { require(sequence > 0 && grid in 0..4999 && seconds in 0..0xffffffffL && validCombatTarget(target) && destroyed in 0..300 && damaged in 0..300 &&
        (destroyed>0 || damaged>0 || shieldDamage>0) && (shieldFace in 0..5 || shieldFace==255) &&
        shieldDamage in 0..65535 && (shieldFace!=255 || shieldDamage==0)) }
}
internal fun validCombatTarget(id:Int) = id in 1..999999 || id in 1_000_001..Int.MAX_VALUE

/** Committed impact and its authenticated audience at execution time. No wire or gateway state. Author: 최병호. */
data class TacticalImpact(val generation: Long, val source: Int, val weapon: TacticalWeapon, val hit: TacticalHit,
    val recipients: Map<Int,String>, val departed: Navigator? = null) {
    init { require(generation > 0 && source > 0 && recipients.keys.all { it > 0 } && recipients.values.all { it.isNotBlank() }) }
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
    internal data class Target(val regular:Pair<OutfitStock,ActiveShipUnit>?=null,
        val personal:Pair<String,Navigator>?=null,val spec:ShipSpecification)
    internal fun target(id:Int,grid:Int,power:Power,stocks:PlanetStocks,galaxy:Galaxy,
        players:Map<String,Navigator>):Target? {
        if(id>1_000_000) {
            val victim=players.entries.singleOrNull { (_,p)->p.unitId==id && p.grid==grid && p.baseId==0 &&
                p.cpPaused && p.power!=power && p.hull!=null } ?: return null
            return Target(personal=victim.key to victim.value,
                spec=UnitSpecifications.ships.single {it.kind==victim.value.hull!!.kind})
        }
        return stocks.outfits.values.asSequence().filter {it.power != if(power==Power.EMPIRE)2 else 3}
            .filter {it.positionIn(galaxy)==OutfitPosition(grid)}
            .mapNotNull {o->o.activeShips?.values?.singleOrNull {it.id==id && it.boats>0}?.let {o to it}}
            .singleOrNull()?.let {Target(regular=it,spec=UnitSpecifications.ships.single {s->s.kind==it.second.stack.kind})}
    }

    fun accept(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        stocks: PlanetStocks, committer: WorldStateCommitter?, scale: Long, persist:Boolean=true): NavigationResult {
        val preview = navigation.apply(request.copy(tacticalShoot = null), session, stocks, persist = false)
        val player = preview.player ?: return preview
        fun reject(reason: NavigationRejection) = NavigationResult(null, player, reason)
        if (committer == null) return reject(NavigationRejection.DISABLED)
        if (!player.cpPaused || player.baseId != 0 || player.mode != 5) return reject(NavigationRejection.TACTICAL)
        val shoot = checkNotNull(request.tacticalShoot)
        if (shoot.units != listOf(player.unitId)) return reject(NavigationRejection.UNIT)
        if (request.captainCard != if(player.power == Power.EMPIRE) 59 else 195) return reject(NavigationRejection.CARD)
        if(player.hull!=null && player.hull.kind!=request.shipKind) return reject(NavigationRejection.UNIT)
        val ship = Flagships.find(player.hull?.kind ?: checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
        if (ship.power != if(player.power == Power.EMPIRE) 2 else 3) return reject(NavigationRejection.UNIT)
        val weapon = TacticalWeapon.entries.singleOrNull { it.wire == shoot.arms }
            ?: return reject(NavigationRejection.WEAPON)
        val mount = TacticalArmaments.mount(UnitSpecifications.ships.single {it.kind == ship.kind}, weapon)
        if (mount.power == 0 || shoot.targetKind != 1) return reject(NavigationRejection.WEAPON)
        if (player.supplies < supplyCost(mount, weapon)) return reject(NavigationRejection.SUPPLY)
        if (player.retreat != null || player.fire?.pending != null) return reject(NavigationRejection.BUSY)
        val now = session.gameMillis / 1000
        if (now > 0xffffffffL - 5 * scale || player.fire?.sequence == Long.MAX_VALUE) return reject(NavigationRejection.CLOCK)
        val players = navigation.snapshot(session.generation)
        val victim = target(shoot.target, player.grid, player.power, stocks, galaxy, players) ?: return reject(NavigationRejection.TARGET)
        if (effectiveness(player, mount, victim, now, players.values, stocks, galaxy) == 0) return reject(NavigationRejection.RANGE)
        val charge = player.charge?.settle(now, player.energy ?: TacticalEnergy.ZERO)
        if (!ready(charge, weapon)) return reject(NavigationRejection.ENERGY)
        val sequence = (player.fire?.sequence ?: 0) + 1
        val next = player.copy(attack = null, charge = charge, fire = TacticalFire(sequence,
            TacticalPendingFire(sequence, player.grid, ship.kind, shoot.target, now, now + 5 * scale, weapon), player.fire?.last))
        if(persist)navigation.saveReplacement(players, players + (request.account to next), session.generation)
        return NavigationResult(next, player)
    }

    fun attack(request: NavigationRequest, session: SessionState, galaxy: Galaxy, navigation: Navigation,
        stocks: PlanetStocks, committer: WorldStateCommitter?, scale: Long): NavigationResult {
        val preview=navigation.apply(request.copy(tacticalAttack=null),session,stocks,persist=false)
        val player=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,player,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        if(!player.cpPaused || player.baseId!=0 || player.mode!=5)return reject(NavigationRejection.TACTICAL)
        val command=checkNotNull(request.tacticalAttack)
        if(command.units!=listOf(player.unitId) || player.hull?.kind!=request.shipKind)return reject(NavigationRejection.UNIT)
        if(request.captainCard!=if(player.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        if(command.mode !in 0..2 || command.target!=0)return reject(NavigationRejection.TARGET)
        if(command.mode!=0 && (player.retreat!=null || player.fire?.pending!=null))return reject(NavigationRejection.BUSY)
        val now=session.gameMillis/1000
        if(now>0xffffffffL-5*scale)return reject(NavigationRejection.CLOCK)
        val next=player.copy(attack=if(command.mode==0)null else TacticalAttackOrder(command.mode,now,now+5*scale,now+5*scale),
            fire=if(command.mode==0)player.fire?.copy(pending=null) else player.fire)
        val players=navigation.snapshot(session.generation)
        navigation.saveReplacement(players,players+(request.account to next),session.generation)
        return NavigationResult(next,player)
    }

    private fun automaticTarget(player:Navigator,weapon:TacticalWeapon,now:Long,players:Map<String,Navigator>,
        stocks:PlanetStocks,galaxy:Galaxy):Int? {
        val spec=UnitSpecifications.ships.single {it.kind==player.hull!!.kind}
        val mount=TacticalArmaments.mount(spec,weapon)
        if(mount.power==0 || player.supplies<supplyCost(mount,weapon) ||
            !ready(player.charge?.settle(now,player.energy ?: TacticalEnergy.ZERO),weapon))return null
        val ids=players.values.map {it.unitId}+stocks.outfits.values.flatMap {it.activeShips?.values.orEmpty().map {u->u.id}}
        return ids.distinct().sorted().firstOrNull {id -> target(id,player.grid,player.power,stocks,galaxy,players)?.let {
            effectiveness(player,mount,it,now,players.values,stocks,galaxy)>0 } == true }
    }

    // CD manual omits later ammunition costs. One supply per flagship shot is explicit replacement policy.
    private fun supplyCost(mount: TacticalWeaponMount, weapon: TacticalWeapon): Int =
        if (weapon == TacticalWeapon.BEAM) 0 else maxOf(1, mount.consumption)

    private fun ready(charge: TacticalCharge?, weapon: TacticalWeapon): Boolean = when(weapon) {
        TacticalWeapon.MISSILE -> true // No native missile charge field; command delay still applies.
        TacticalWeapon.BEAM -> charge != null && charge.beam == 1000 * charge.clockScale
        TacticalWeapon.GUN -> charge != null && charge.gun == 1000 * charge.clockScale
    }

    private fun effectiveness(player: Navigator, mount: TacticalWeaponMount, victim: Target, now: Long,
        players: Collection<Navigator>, stocks: PlanetStocks, galaxy: Galaxy): Int {
        val source = player.motion?.pose(now) ?: TacticalDeployment.initial(player.unitId, player.power, player.grid, players, stocks, galaxy)
        val target = victim.personal?.second?.let {p -> p.motion?.pose(now) ?:
            TacticalDeployment.initial(p.unitId,p.power,p.grid,players,stocks,galaxy)} ?: victim.regular!!.let {
            it.second.tactical?.takeIf {t->t.motion.grid==player.grid}?.motion?.pose(now) ?: TacticalDeployment.initial(it.second.id,if(it.first.power==2)Power.EMPIRE else Power.ALLIANCE,
                player.grid,players,stocks,galaxy)}
        return TacticalArmaments.effectiveness(mount, source, target.position)
    }

    internal fun strike(session:SessionState,galaxy:Galaxy,players:MutableMap<String,Navigator>,stocks:PlanetStocks,
        source:Int,grid:Int,sequence:Long,seconds:Long,targetId:Int,weapon:TacticalWeapon,
        mount:TacticalWeaponMount,effect:Int,origin:TacticalPose,victim:Target):Pair<PlanetStocks,TacticalImpact> {
        var inventory=stocks
        val divisor = 100L * maxOf(1, victim.spec.number("装甲:前"))
        val damage = ((mount.power.toLong()*effect+divisor-1)/divisor).coerceAtLeast(1).toInt()
        val audience = players.mapNotNull { (viewerAccount, viewer) ->
            val member=session.participants[viewerAccount]
            if(viewer.grid!=grid || viewer.baseId!=0 || !viewer.cpPaused ||
                member?.online!=true || member.power!=viewer.power) null
            else member.connection?.let {viewer.characterId to it}
        }.toMap()
        var departed:Navigator?=null
        val hit = if(victim.regular!=null) {
            val (outfit,unit)=victim.regular
            val changed=TacticalLosses.apply(outfit,unit.id,damage)
            val survivor=changed.activeShips!!.values.single {it.id==targetId}
            inventory=inventory.copy(outfits=inventory.outfits+(outfit.id to changed))
            TacticalHit(sequence,grid,seconds,targetId,victim.spec.number-survivor.boats,
                damaged=survivor.unavailable)
        } else {
            val (victimAccount,defender)=checkNotNull(victim.personal)
            val hull=checkNotNull(defender.hull).settle(seconds,defender.energy ?: TacticalEnergy.ZERO)
            val pose=defender.motion?.pose(seconds) ?:
                TacticalDeployment.initial(defender.unitId,defender.power,defender.grid,players.values,inventory,galaxy)
            val angle=kotlin.math.atan2(origin.position.x-pose.position.x,origin.position.y-pose.position.y)-pose.direction
            val circle=2*Math.PI
            val face=kotlin.math.floor(((angle+Math.PI/6)%circle+circle)%circle/(Math.PI/3)).toInt().coerceIn(0,5)
            val absorbed=minOf(damage,hull.levels()[face])
            val hp=hull.hp-(damage-absorbed)
            val shields=hull.shields.toMutableList().also {it[face]-=absorbed*100*hull.clockScale}
            val lost=FlagshipHull.capacity(hull.kind)-(shields[face]/(100*hull.clockScale)).toInt()
            if(hp<=0) {
                departed=FlagshipRecovery.defeated(defender,seconds,galaxy,inventory)
                players[victimAccount]=departed
            } else players[victimAccount]=defender.copy(hull=hull.copy(hp=hp,shields=shields))
            TacticalHit(sequence,grid,seconds,targetId,if(hp<=0)1 else 0,
                if(hp<hull.hp || hull.hp<100)1 else 0,face,lost)
        }
        return inventory to TacticalImpact(session.generation,source,weapon,hit,audience,departed)
    }

    private fun clearFinishedOrders(players:MutableMap<String,Navigator>,galaxy:Galaxy,stocks:PlanetStocks) {
        val ids=players.values.map {it.unitId}+stocks.outfits.values.flatMap {it.activeShips?.values.orEmpty().map {u->u.id}}
        // Hostile bases/ground troops can retain tactical contact but are never auto-fire targets.
        for((account,p) in players.toMap()) if(p.attack!=null &&
            ids.none {target(it,p.grid,p.power,stocks,galaxy,players)!=null})players[account]=p.copy(attack=null)
    }

    fun settle(session: SessionState, galaxy: Galaxy, navigation: Navigation, production: PlanetProduction,
        committer: WorldStateCommitter?, npcPolicy:NpcTacticalPolicy = NpcTacticalPolicy(enabled=false),
        scale:Long=24,onMovement:(TacticalMovement)->Unit = {},onSupport:(TacticalSupportResult)->Unit = {}): List<TacticalImpact> {
        val original = navigation.snapshot(session.generation)
        val players = original.toMutableMap()
        val now=session.gameMillis/1000
        val stocks = checkNotNull(production.snapshot(session.generation))
        clearFinishedOrders(players,galaxy,stocks)
        data class Due(val account:String,val shot:TacticalPendingFire,val automatic:Boolean=false)
        val due=original.mapNotNull { (account,p)->p.fire?.pending?.takeIf {it.firesAt<=now}?.let {Due(account,it)} }.toMutableList()
        if(session.ended==null) for((account,p) in players.toMap()) {
            val order=p.attack ?: continue
            if(order.nextAt>now || p.retreat!=null || p.hull==null || p.fire?.pending!=null ||
                p.fire?.sequence==Long.MAX_VALUE || now>0xffffffffL-5*(p.charge?.clockScale ?: p.hull.clockScale))continue
            val spec=UnitSpecifications.ships.single {it.kind==p.hull.kind}
            val charge=p.charge?.settle(now,p.energy ?: TacticalEnergy.ZERO)
            if(order.mode==1 && TacticalWeapon.entries.filter {it!=TacticalWeapon.MISSILE && TacticalArmaments.mount(spec,it).power>0}
                .any {!ready(charge,it)})continue
            for(weapon in TacticalWeapon.entries) automaticTarget(p,weapon,now,original,stocks,galaxy)?.let {id ->
                due+=Due(account,TacticalPendingFire((p.fire?.sequence ?: 0)+1,p.grid,p.hull.kind,id,now,now,weapon),true)
            }
        }
        if(due.isEmpty() && players==original && !npcPolicy.enabled && stocks.outfits.values.none {
            it.activeShips?.values?.any {u->u.support!=null || u.attack!=null || u.shot!=null}==true})return emptyList()
        due.sortWith(compareBy({it.shot.firesAt},{original.getValue(it.account).unitId},{it.shot.weapon.wire}))
        var inventory = stocks
        val impacts = mutableListOf<TacticalImpact>()
        for ((account, scheduled, automatic) in due) {
            val player = players.getValue(account)
            if(automatic && (player.attack==null || player.hull==null || player.fire?.sequence==Long.MAX_VALUE))continue
            val shot=if(automatic) {
                val id=automaticTarget(player,scheduled.weapon,now,players,inventory,galaxy) ?: continue
                scheduled.copy(sequence=(player.fire?.sequence ?: 0)+1,target=id)
            } else player.fire?.pending?.takeIf {it==scheduled} ?: continue
            val fire=if(automatic)TacticalFire(shot.sequence,last=player.fire?.last) else checkNotNull(player.fire)
            var next = player.copy(fire = fire.copy(pending = null))
            val victim = target(shot.target, shot.grid, player.power, inventory, galaxy, players)
            if (session.ended == null && player.cpPaused && player.grid == shot.grid && player.baseId == 0 &&
                player.retreat == null && victim != null && (player.charge == null || player.charge.at <= shot.firesAt)) {
                val charge = player.charge?.settle(shot.firesAt, player.energy ?: TacticalEnergy.ZERO)
                val spec = UnitSpecifications.ships.single { it.kind == shot.shipKind }
                val mount = TacticalArmaments.mount(spec, shot.weapon)
                val effect = effectiveness(player, mount, victim, shot.firesAt, players.values, inventory, galaxy)
                if (effect > 0 && ready(charge, shot.weapon) && player.supplies >= supplyCost(mount, shot.weapon)) {
                    val origin=player.motion?.pose(shot.firesAt) ?: TacticalDeployment.initial(player.unitId,
                        player.power,player.grid,players.values,inventory,galaxy)
                    val resolved=strike(session,galaxy,players,inventory,player.unitId,shot.grid,shot.sequence,
                        shot.firesAt,shot.target,shot.weapon,mount,effect,origin,victim)
                    inventory=resolved.first
                    val hit=resolved.second.hit
                    val spent = when(shot.weapon) {
                        TacticalWeapon.BEAM -> checkNotNull(charge).copy(beam = 0)
                        TacticalWeapon.GUN -> checkNotNull(charge).copy(gun = 0)
                        TacticalWeapon.MISSILE -> charge
                    }
                    next = next.copy(charge = spent, supplies = player.supplies - supplyCost(mount, shot.weapon),
                        attack=player.attack?.copy(nextAt=shot.firesAt+5*(charge?.clockScale ?: player.hull!!.clockScale)),
                        fire = fire.copy(pending = null, last = hit))
                    impacts += resolved.second
                }
            }
            players[account] = next
        }
        if(session.ended==null)inventory=TacticalFleetShoot.advance(session,galaxy,players,inventory,impacts)
        if(session.ended==null)inventory=TacticalFleetAttack.advance(session,galaxy,players,inventory,scale,impacts)
        val movements=mutableListOf<TacticalMovement>()
        if(npcPolicy.enabled && session.ended==null) {
            inventory=NpcTactics.advance(session,galaxy,players,inventory,npcPolicy,scale,impacts,movements)
        }
        val support=mutableListOf<TacticalSupportResult>()
        inventory=TacticalSupportOperations.advance(session,galaxy,players,inventory,support)
        clearFinishedOrders(players,galaxy,inventory)
        if(players==original && inventory==stocks)return emptyList()
        val prepared = production.prepareReplacement(stocks, inventory)
        checkNotNull(committer) { "Pending combat requires durable world storage" }.saveWorld(players, prepared)
        navigation.publishCommitted(original, players, session.generation)
        production.publishCommitted(stocks, prepared)
        movements.forEach(onMovement)
        support.forEach(onSupport)
        return impacts
    }
}
