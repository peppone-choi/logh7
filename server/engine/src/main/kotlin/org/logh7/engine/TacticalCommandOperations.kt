package org.logh7.engine

import kotlin.math.*

/** Actor-only native command execution. The complete after-image is saved before any event.
 * Manual facts: CRC, 10 supplies per aircraft launch, friendly-fire fortress cannon,
 * six missions and their time limits. All unrecorded costs/magnitudes below are design policy.
 * Author: Peppone Choi. */
internal object TacticalCommandOperations {
    private data class Ship(val outfit:OutfitStock,val unit:ActiveShipUnit)
    private fun ship(id:Int,s:PlanetStocks)=s.outfits.values.mapNotNull {o->o.activeShips?.values?.singleOrNull {it.id==id}?.let {Ship(o,it)}}.singleOrNull()
    private fun wire(p:Navigator)=if(p.power==Power.EMPIRE)2 else 3
    private fun baseCommander(base:GalaxyBase,owner:PlanetStock,s:PlanetStocks,g:Galaxy):Int =
        if(base.kind==1)OrganizationalOfficePolicy(g).holders(s,if(owner.power==2)51 else 187)
            .singleOrNull {it.seat==base.id}?.character ?: 0
        else owner.capitalDefenseCommanderId.takeIf {it>0} ?: owner.garrisonCommanderId
    private fun audience(session:SessionState,grid:Int,players:Map<String,Navigator>)=session.participants.mapNotNull {(a,m)->
        players[a]?.takeIf {m.online && m.connection!=null && it.grid==grid && (it.cpPaused || it.baseId>0)}
            ?.let {it.characterId to checkNotNull(m.connection)}}.toMap()
    private fun pose(id:Int,grid:Int,now:Long,players:Map<String,Navigator>,s:PlanetStocks,g:Galaxy):TacticalPose? {
        val p=players.values.singleOrNull {it.unitId==id && it.grid==grid && it.baseId==0 && it.cpPaused}
        if(p!=null)return p.motion?.pose(now,p.posture,g,s) ?: TacticalDeployment.initial(id,p.power,grid,players.values,s,g)
        val u=ship(id,s) ?: return null
        if(u.unit.boats==0 || u.outfit.positionIn(g)!=OutfitPosition(grid) || u.unit.posture?.mode==TacticalShipMode.STATIONED)return null
        return u.unit.tactical?.motion?.takeIf {it.grid==grid}?.pose(now,u.unit.posture,g,s) ?:
            TacticalDeployment.initial(id,if(u.outfit.power==2)Power.EMPIRE else Power.ALLIANCE,grid,players.values,s,g)
    }
    fun basePoint(base:GalaxyBase,now:Long,stocks:PlanetStocks)=stocks.tacticalCommands.fortresses[base.id]
        ?.takeIf {it.grid==base.grid}?.motion?.pose(now)?.position ?: TacticalBasePosition.at(base,now)
    /** The native repair/supply picker enumerates mode-4 ships at the selected base.
     * They have no exposed tactical pose, but their exact berth is authoritative. */
    private fun supportPoint(id:Int,base:GalaxyBase,now:Long,players:Map<String,Navigator>,s:PlanetStocks,g:Galaxy):TacticalPoint? {
        val u=ship(id,s)
        if(u?.unit?.posture?.mode==TacticalShipMode.STATIONED) {
            val berth=u.unit.posture;val location=u.outfit.positionIn(g)
            return if(u.unit.boats>0 && berth.grid==base.grid && berth.base==base.id &&
                location.grid==base.grid && location.baseId in setOf(0,base.id))basePoint(base,now,s) else null
        }
        return pose(id,base.grid,now,players,s,g)?.position
    }
    /** Rebase berth offsets before replacing a fortress path; old anchor timestamps must not
     * start referring to the new path's origin after another move or stop. */
    private fun rebaseBerths(base:Int,now:Long,players:MutableMap<String,Navigator>,initial:PlanetStocks,g:Galaxy):PlanetStocks {
        var stocks=initial
        for((account,p) in players.toMap())if(p.cpPaused && p.baseId==0 && p.posture?.let {it.base==base && it.mode.requiresBase}==true) {
            val m=p.motion ?: continue;val at=m.pose(now,p.posture,g,initial)
            players[account]=p.copy(motion=TacticalMotion(p.grid,now,at,0f,at.direction,listOf(at.position)))
        }
        for((id,o) in initial.outfits) {
            val roster=o.activeShips ?: continue
            val changed=roster.values.map {u->
                if(u.posture?.let {it.base==base && it.mode.requiresBase}!=true)return@map u
                val state=u.tactical ?: return@map u;val at=state.motion.pose(now,u.posture,g,initial)
                u.copy(tactical=state.copy(motion=TacticalMotion(state.motion.grid,now,at,0f,at.direction,listOf(at.position)),nextDecisionAt=now))
            }
            if(changed!=roster.values)stocks=stocks.copy(outfits=stocks.outfits+(id to o.copy(activeShips=ActiveShipUnits(changed))))
        }
        return stocks
    }
    private fun idle(u:ActiveShipUnit,now:Long)=u.support==null && u.attack==null && u.shot==null && u.posture?.order==null &&
        u.tactical?.retreat==null && u.tactical?.motion?.let {it.velocity==0f && it.rotation==null || it.completedWaypoints(now)==it.waypoints.size}!=false
    private fun selection(r:NavigationRequest)=r.tacticalCommand?.units ?: r.tacticalMove?.units ?: r.tacticalRetreat ?:
        r.tacticalAttack?.units ?: r.tacticalShoot?.units ?: r.tacticalSupport?.let {listOf(it.unit)} ?:
        r.tacticalPosture?.units ?: r.tacticalGround?.units ?: emptyList()
    /** A pending order locks its selected sources across all old and new command families. */
    fun guard(r:NavigationRequest,session:SessionState,n:Navigation,s:PlanetStocks):NavigationResult? {
        if(r.tacticalStop!=null || r.tacticalCommand?.opcode==0x41e)return null
        val p=n.snapshot(session.generation)[r.account] ?: return null
        val m=session.participants[r.account] ?: return null
        if(!m.online || m.connection!=r.connection || p.characterId!=r.characterId || p.power!=r.power || r.generation!=session.generation)return null
        val ids=selection(r)
        if(ids.any {s.tacticalCommands.confused(it) && it!=p.unitId})return NavigationResult(null,p,NavigationRejection.ACCESS)
        if(s.tacticalCommands.orders.any {o->o.grid==p.grid && (o.command.units.any {it in ids} ||
                r.tacticalCommand?.base?.takeIf {it>0}==o.command.base && o.command.base>0)})
            return NavigationResult(null,p,NavigationRejection.BUSY)
        return null
    }
    private fun supreme(p:Navigator,players:Map<String,Navigator>,s:PlanetStocks):Boolean {
        // Native rank numbers decrease with rank. Merit breaks equal ranks (update04).
        val candidates=players.values.filter {it.grid==p.grid && it.power==p.power && (it.cpPaused || it.baseId>0)}
        val best=candidates.maxWithOrNull(compareBy<Navigator> {-TacticalPersonnel.rank(s,it.characterId)}
            .thenBy {TacticalPersonnel.merit(s,it.characterId)}.thenByDescending {it.characterId}) ?: return false
        return p.characterId==best.characterId
    }
    private fun validate(c:TacticalCommand,p:Navigator,now:Long,players:Map<String,Navigator>,s:PlanetStocks,g:Galaxy,
        checkRange:Boolean,sessionOnline:Map<String,Boolean> = emptyMap()):NavigationRejection? {
        if(p.hull?.woundedUntil?.let {it>now}==true)return NavigationRejection.WOUNDED
        val baseOnly=c.opcode in setOf(0x419,0x41d,0x41e,0x41f,0x422)
        val baseSupport=c.opcode in setOf(0x41b,0x41c)
        if((baseOnly || baseSupport) && c.base==0 || !baseOnly && !baseSupport && c.base!=0)return NavigationRejection.BASE
        if(baseOnly && c.units.isNotEmpty() || !baseOnly && c.units.isEmpty())return NavigationRejection.UNIT
        val base=if(c.base>0)g.bases.singleOrNull {it.id==c.base && it.grid==p.grid} else null
        if(c.base>0) {
            val owner=s.bases[c.base] ?: return NavigationRejection.BASE
            if(base==null || owner.power!=wire(p) || owner.camp!=0)return NavigationRejection.BASE
            val openSupply=c.opcode==0x41c && s.tacticalCommands.emergencySupplies[c.base]==wire(p)
            val commandsBase=if(base.kind==1)baseCommander(base,owner,s,g)==p.characterId
                else owner.garrisonCommanderId==p.characterId || owner.capitalDefenseCommanderId==p.characterId
            if(c.opcode!=0x422 && !openSupply && !commandsBase)return NavigationRejection.CARD
            if(!TacticalContact.contested(GridForces.collect(g,p.generation,players.values,s)[p.grid].orEmpty()))return NavigationRejection.TACTICAL
        } else if(!p.cpPaused || p.baseId!=0 || p.mode!=5)return NavigationRejection.TACTICAL
        val centre=if(base!=null)basePoint(base,now,s) else pose(p.unitId,p.grid,now,players,s,g)?.position ?: return NavigationRejection.UNIT
        val range=if(base!=null)TacticalCommandRange.MAXIMUM else p.commandRange?.radius(now) ?: 0f
        val transfer=c.opcode in setOf(0x40b,0x420)
        val mission=c.opcode in setOf(0x408,0x421)
        for(id in c.units) {
            val personal=players.values.singleOrNull {it.unitId==id && it.grid==p.grid && it.power==p.power && it.cpPaused && it.baseId==0}
            val unit=ship(id,s)
            if(personal==null && (unit==null || unit.outfit.power!=wire(p) || unit.unit.boats==0 ||
                    (if(baseSupport)unit.outfit.positionIn(g).let {it.grid!=p.grid || it.baseId !in setOf(0,c.base)}
                     else unit.outfit.positionIn(g)!=OutfitPosition(p.grid))))return NavigationRejection.UNIT
            if(!transfer && !mission && !baseSupport && personal?.unitId!=p.unitId &&
                (unit==null || TacticalAuthority.commander(unit.outfit,unit.unit,s)!=p.characterId))return NavigationRejection.CARD
            if(!transfer && !mission && !baseSupport && unit?.unit?.let {it.support!=null || it.posture?.order!=null || it.tactical?.retreat!=null}==true)
                return NavigationRejection.BUSY
            val at=if(baseSupport)supportPoint(id,base!!,now,players,s,g) else pose(id,p.grid,now,players,s,g)?.position
            if(at==null)return NavigationRejection.BASE
            if(checkRange && !transfer && !mission && id!=p.unitId && hypot((at.x-centre.x).toDouble(),(at.y-centre.y).toDouble())>range)
                return NavigationRejection.RANGE
            if(!transfer && !mission && !baseSupport && id!=p.unitId && s.tacticalCommands.confused(id))return NavigationRejection.ACCESS
        }
        when(c.opcode) {
            0x406 -> {
                // Explicit compatibility marker: the CD menu loses AA vs missile.
                // AA disperses the selected hostile carrier's wing; it never hits its hull.
                if(c.kind!=3 || c.targetKind!=0x81)return NavigationRejection.WEAPON
                if(TacticalCombat.target(c.target,p.grid,p.power,s,g,players)==null ||
                    s.tacticalCommands.air[c.target]?.grid!=p.grid)return NavigationRejection.TARGET
                val target=pose(c.target,p.grid,now,players,s,g)?.position ?: return NavigationRejection.TARGET
                for(id in c.units) {
                    val u=ship(id,s)?.unit
                    val kind=if(id==p.unitId)p.hull?.kind else u?.stack?.kind
                    if(kind==null || UnitSpecifications.ships.single {it.kind==kind}.number("対空兵装破壊力:対空兵装破壊力")==0)
                        return NavigationRejection.WEAPON
                    if(if(id==p.unitId)p.fire?.pending!=null else u?.shot!=null)return NavigationRejection.BUSY
                    val at=pose(id,p.grid,now,players,s,g)!!.position
                    if(hypot((at.x-target.x).toDouble(),(at.y-target.y).toDouble())>=8.0)return NavigationRejection.RANGE
                }
            }
            0x407 -> {
                if(c.units!=listOf(p.unitId) || p.hull?.kind?.let {k->UnitSpecifications.ships.single {it.kind==k}.type}!=4)return NavigationRejection.UNIT
                val target=players.values.singleOrNull {it.unitId==c.target && it.grid==p.grid && it.power!=p.power && it.cpPaused && it.baseId==0}
                    ?: return NavigationRejection.TARGET
                val at=pose(target.unitId,p.grid,now,players,s,g)?.position ?: return NavigationRejection.TARGET
                if(hypot((at.x-centre.x).toDouble(),(at.y-centre.y).toDouble())>=1.0)return NavigationRejection.RANGE
            }
            0x409 -> {
                if(c.units!=listOf(p.unitId))return NavigationRejection.UNIT
                if(s.tacticalCommands.morale.none {(id,v)->v<100 && (id==p.unitId || ship(id,s)?.let {
                        it.outfit.positionIn(g)==OutfitPosition(p.grid) && TacticalAuthority.commander(it.outfit,it.unit,s)==p.characterId}==true)})
                    return NavigationRejection.TARGET
            }
            0x40b,0x420 -> {
                val commander=players.values.singleOrNull {it.unitId==c.target && it.power==p.power && it.grid==p.grid && it.cpPaused}
                    ?: return NavigationRejection.TARGET
                if(commander.characterId!=p.characterId)return NavigationRejection.CARD
                for(id in c.units) {
                    val unit=ship(id,s) ?: return NavigationRejection.UNIT
                    if(!idle(unit.unit,now) || s.tacticalCommands.air.containsKey(id))return NavigationRejection.BUSY
                    val at=pose(id,p.grid,now,players,s,g)!!.position
                    if(players.values.any {other->other.characterId!=p.characterId && other.power==p.power && other.grid==p.grid && other.cpPaused &&
                        pose(other.unitId,p.grid,now,players,s,g)?.position?.let {pt->hypot((pt.x-at.x).toDouble(),(pt.y-at.y).toDouble())<=
                            (other.commandRange?.radius(now) ?: 0f)}==true})return NavigationRejection.RANGE
                }
            }
            0x40d -> if(c.kind !in 0..7)return NavigationRejection.TARGET
            0x40e -> {
                if(c.kind !in 0..2)return NavigationRejection.TARGET
                for(id in c.units) {
                    val kind=if(id==p.unitId)p.hull?.kind else ship(id,s)?.unit?.stack?.kind
                    if(kind==null || UnitSpecifications.ships.single {it.kind==kind}.type !in 9..10)return NavigationRejection.UNIT
                    if(c.kind==2 && id !in s.tacticalCommands.air)return NavigationRejection.TARGET
                    if(c.kind!=2 && id in s.tacticalCommands.air)return NavigationRejection.BUSY
                    if(c.kind!=2 && (if(id==p.unitId)p.supplies else ship(id,s)!!.let {OutfitSupplies.distribution(it.outfit).getValue(id)})<10)
                        return NavigationRejection.SUPPLY
                }
                if(c.kind==1 && TacticalCombat.target(c.target,p.grid,p.power,s,g,players)==null)return NavigationRejection.TARGET
                if(c.kind==0 && c.target!=0 && players.values.none {it.unitId==c.target && it.grid==p.grid && it.power==p.power && it.cpPaused} &&
                    ship(c.target,s)?.let {it.outfit.power==wire(p) && it.outfit.positionIn(g)==OutfitPosition(p.grid) && it.unit.boats>0}!=true)
                    return NavigationRejection.TARGET
            }
            0x419 -> if(base?.kind!=1)return NavigationRejection.BASE
            0x41b,0x41c -> {
                if(s.bases.getValue(c.base).supplies==0)return NavigationRejection.SUPPLY
                for(id in c.units) {
                    val at=supportPoint(id,base!!,now,players,s,g) ?: return NavigationRejection.TARGET
                    if(hypot((at.x-centre.x).toDouble(),(at.y-centre.y).toDouble())>maxOf(1f,base.diameter))return NavigationRejection.RANGE
                }
            }
            0x41f -> if(base?.kind!=1 || c.points.isEmpty() || c.velocity<=0f)return NavigationRejection.BASE
            0x408,0x421 -> {
                if(c.kind !in 0..5)return NavigationRejection.TARGET
                val chief=supreme(p,players,s)
                if(c.opcode==0x408 && chief)return NavigationRejection.CARD
                if(chief && c.units.any {id->players.values.none {it.unitId==id}})return NavigationRejection.UNIT
                if(chief && c.units.any {id->players.entries.single {it.value.unitId==id}.let {(account,person)->
                    sessionOnline[account]==false && !s.tacticalCommands.tacticalAi(person.characterId)}})return NavigationRejection.ACCESS
                if(!chief && c.units!=listOf(p.unitId))return NavigationRejection.CARD
                if(c.kind in 1..2) {
                    val b=g.bases.singleOrNull {it.id==c.target && it.grid==p.grid} ?: return NavigationRejection.TARGET
                    val own=s.bases.getValue(b.id).let {it.power==wire(p) && it.camp==0}
                    if(own!=(c.kind==1))return NavigationRejection.TARGET
                }
                if(c.kind in 3..4 && TacticalCombat.target(c.target,p.grid,p.power,s,g,players)==null)return NavigationRejection.TARGET
            }
            0x422 -> {
                if(!supreme(p,players,s))return NavigationRejection.CARD
                if(c.base==0 || c.units.isNotEmpty())return NavigationRejection.BASE
                if(s.tacticalCommands.emergencySupplies[c.base]==wire(p))return NavigationRejection.BUSY
            }
        }
        return null
    }
    fun accept(r:NavigationRequest,session:SessionState,g:Galaxy,n:Navigation,production:PlanetProduction,s:PlanetStocks,
        committer:WorldStateCommitter?,scale:Long):NavigationResult {
        val preview=n.apply(r.copy(tacticalCommand=null),session,s,persist=false)
        var p=preview.player ?: return preview
        fun reject(reason:NavigationRejection)=NavigationResult(null,p,reason)
        if(committer==null)return reject(NavigationRejection.DISABLED)
        val c=checkNotNull(r.tacticalCommand);val now=session.gameMillis/1000
        val players=(n.snapshot(session.generation)+(r.account to p)).toMutableMap()
        if(c.base==0 && r.captainCard!=if(p.power==Power.EMPIRE)59 else 195)return reject(NavigationRejection.CARD)
        if(c.base==0 && p.hull?.kind!=r.shipKind)return reject(NavigationRejection.UNIT)
        var next=s.copy(tacticalCommands=s.tacticalCommands.copy(ranks=s.tacticalCommands.ranks+r.tacticalRanks+(p.characterId to r.rank)))
        validate(c,p,now,players,next,g,true,session.participants.mapValues {it.value.online})?.let {return reject(it)}
        val duration=when(c.opcode){0x41e->0L;0x419->35L;else->5L}*scale
        if(now>0xffffffffL-duration)return reject(NavigationRejection.CLOCK)
        // Personal abilities and supreme emergency supply spend PCP; squadron commands use CRC.
        val cost=if(c.opcode in setOf(0x407,0x409,0x422))10 else 0
        if(p.pcp<cost)return reject(NavigationRejection.CP)
        if(c.opcode==0x40e && c.kind!=2 && c.units.any {id->
            if(id==p.unitId)p.supplies<10 else ship(id,next)!!.let {OutfitSupplies.distribution(it.outfit).getValue(id)<10}})
            return reject(NavigationRejection.SUPPLY)
        val order=TacticalQueuedCommand(p.characterId,p.grid,now,now+duration,c)
        val events=mutableListOf<TacticalCommandResult>()
        if(c.opcode==0x41e) {
            next=rebaseBerths(c.base,now,players,next,g);p=players.getValue(r.account)
            val cancelled=next.tacticalCommands.orders.filter {it.command.base==c.base}
            val fortress=next.tacticalCommands.fortresses[c.base]
            val stopped=fortress?.let {f->val at=f.motion.pose(now)
                f.copy(motion=TacticalMotion(f.grid,now,at,0f,at.direction,listOf(at.position)),reportedAt=now)}
            next=next.copy(tacticalCommands=next.tacticalCommands.copy(orders=next.tacticalCommands.orders-cancelled.toSet(),
                fortresses=if(stopped==null)next.tacticalCommands.fortresses else next.tacticalCommands.fortresses+(c.base to stopped)))
            events+=cancelled.map {TacticalCommandResult(session.generation,it,now,audience(session,it.grid,players),true)}
            events+=TacticalCommandResult(session.generation,order,now,audience(session,p.grid,players))
        } else next=next.copy(tacticalCommands=next.tacticalCommands.copy(orders=next.tacticalCommands.orders+order))
        p=p.copy(pcp=p.pcp-cost,commandRange=if(c.base==0 && c.units.any {it!=p.unitId} && c.opcode !in setOf(0x408,0x421,0x420,0x40b))
            p.commandRange?.reset(now) else p.commandRange)
        val prepared=production.prepareReplacement(s,next);val after=players+(r.account to p)
        committer.saveWorld(after,prepared);n.publishCommitted(n.snapshot(session.generation),after,session.generation)
        production.publishCommitted(s,prepared)
        return NavigationResult(p,preview.previous,commandReadyAt=order.readyAt,commandNotifications=events)
    }

    fun cancel(s:PlanetStocks,units:List<Int>):PlanetStocks=s.copy(tacticalCommands=s.tacticalCommands.copy(
        orders=s.tacticalCommands.orders.filterNot {it.command.units.any {id->id in units}}))

    fun stopBases(s:PlanetStocks,bases:List<Int>,p:Navigator,now:Long,session:SessionState,players:MutableMap<String,Navigator>,g:Galaxy):
        Pair<PlanetStocks,List<TacticalCommandResult>>? {
        var stocks=s;val events=mutableListOf<TacticalCommandResult>()
        for(base in bases) {
            val c=TacticalCommand(0x41e,base=base)
            if(validate(c,p,now,players,s,g,false)!=null)return null
            stocks=rebaseBerths(base,now,players,stocks,g)
            val cancelled=stocks.tacticalCommands.orders.filter {it.command.base==base}
            val moved=stocks.tacticalCommands.fortresses[base]?.let {f->val at=f.motion.pose(now)
                f.copy(motion=TacticalMotion(f.grid,now,at,0f,at.direction,listOf(at.position)),reportedAt=now)}
            stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(orders=stocks.tacticalCommands.orders-cancelled.toSet(),
                fortresses=if(moved==null)stocks.tacticalCommands.fortresses else stocks.tacticalCommands.fortresses+(base to moved)))
            events+=cancelled.map {TacticalCommandResult(session.generation,it,now,audience(session,p.grid,players),true)}
            events+=TacticalCommandResult(session.generation,TacticalQueuedCommand(p.characterId,p.grid,now,now,c),now,audience(session,p.grid,players))
        }
        return stocks to events
    }

    private class Battle(val session:SessionState,val g:Galaxy,val players:MutableMap<String,Navigator>,var stocks:PlanetStocks,
        val scale:Long,val events:MutableList<TacticalCommandResult>,val impacts:MutableList<TacticalImpact>) {
        val now=session.gameMillis/1000
        fun replace(id:Int,change:(ActiveShipUnit)->ActiveShipUnit) {
            val u=ship(id,stocks) ?: return;val roster=ActiveShipUnits(u.outfit.activeShips!!.values.map {if(it.id==id)change(it) else it})
            stocks=stocks.copy(outfits=stocks.outfits+(u.outfit.id to u.outfit.copy(activeShips=roster)))
        }
        fun personal(id:Int,change:(Navigator)->Navigator) {players.entries.singleOrNull {it.value.unitId==id}?.let {players[it.key]=change(it.value)}}
        fun spend(id:Int,amount:Int) {
            val u=ship(id,stocks)
            if(u==null)personal(id){it.copy(supplies=it.supplies-amount)}
            else stocks=stocks.copy(outfits=stocks.outfits+(u.outfit.id to OutfitSupplies.change(u.outfit,id,-amount)))
        }
        fun motion(id:Int,motion:TacticalMotion,character:Int) {
            if(id>1000000)personal(id){it.copy(motion=motion)}
            else replace(id){it.copy(tactical=it.tactical?.copy(motion=motion,target=0,nextDecisionAt=now,nextFireAt=now) ?:
                NpcTacticalState(motion,now,now),motionCommander=character)}
        }
        fun healOrSupply(id:Int,base:Int,repair:Boolean):Boolean {
            val stock=stocks.bases.getValue(base);val u=ship(id,stocks);val p=players.values.singleOrNull {it.unitId==id}
            val available=stock.supplies
            if(repair) {
                val kind=u?.unit?.stack?.kind ?: p?.hull?.kind ?: return false
                val cost=UnitSpecifications.ships.single {it.kind==kind}.number("修理消費物資(１隻):修理消費物資(１隻)").coerceAtLeast(1)
                val damaged=u?.unit?.damaged ?: if(p!!.hull!!.hp<100)1 else 0
                val count=minOf(damaged,available/cost);if(count==0)return false
                if(u!=null)replace(id){it.copy(damaged=it.damaged-count)} else personal(id){it.copy(hull=it.hull!!.copy(hp=100))}
                stocks=stocks.copy(bases=stocks.bases+(base to stock.copy(supplies=available-count*cost)))
            } else {
                val capacity=u?.unit?.let {OutfitSupplies.unitCapacity(it)} ?: 10000
                val balance=u?.let {OutfitSupplies.distribution(it.outfit).getValue(id)} ?: p?.supplies ?: return false
                val count=minOf(available,capacity-balance);if(count==0)return false
                if(u==null)personal(id){it.copy(supplies=it.supplies+count)} else spend(id,-count)
                stocks=stocks.copy(bases=stocks.bases+(base to stock.copy(supplies=available-count)))
            }
            return true
        }
        fun execute(o:TacticalQueuedCommand,p:Navigator):Pair<Boolean,Map<Int,TacticalMotion>> {
            val c=o.command;val motions=linkedMapOf<Int,TacticalMotion>()
            var effective=true
            when(c.opcode) {
                0x406 -> removeWing(c.target)
                0x407 -> {
                    val entry=players.entries.single {it.value.unitId==c.target};val victim=entry.value
                    val recovered=FlagshipRecovery.defeated(victim,now,g,stocks).copy(supplies=victim.supplies,
                        hull=victim.hull!!.copy(at=now,woundedUntil=minOf(0xffffffffL,now+86400)))
                    players[entry.key]=recovered
                    impacts+=TacticalImpact(session.generation,p.unitId,TacticalWeapon.GUN,
                        TacticalHit(o.acceptedAt+1,p.grid,now,victim.unitId,0,1),audience(session,p.grid,players+(entry.key to victim)),recovered,boarding=true)
                }
                0x409,0x41d -> {
                    val ids=if(c.base==0) listOf(p.unitId)+stocks.outfits.values.flatMap {outfit->outfit.activeShips?.values.orEmpty().filter {
                        TacticalAuthority.commander(outfit,it,stocks)==p.characterId && outfit.positionIn(g)==OutfitPosition(p.grid)}.map {it.id}}
                        else stocks.outfits.values.filter {it.power==wire(p) && it.positionIn(g)==OutfitPosition(p.grid)}.flatMap {it.activeShips?.values.orEmpty().map {u->u.id}}
                    val centre=if(c.base==0)pose(p.unitId,p.grid,now,players,stocks,g)!!.position else basePoint(g.bases.single {it.id==c.base},now,stocks)
                    val eligible=ids.filter {id->pose(id,p.grid,now,players,stocks,g)?.position?.let {hypot((it.x-centre.x).toDouble(),(it.y-centre.y).toDouble())<=TacticalCommandRange.MAXIMUM}==true}
                    val morale=stocks.tacticalCommands.morale.toMutableMap()
                    for(id in eligible)morale[id]=minOf(100,(morale[id] ?: 100)+20)
                    stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(morale=morale))
                }
                0x40b,0x420 -> stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(
                    authority=stocks.tacticalCommands.authority+c.units.associateWith {p.characterId},
                    formationLeaders=stocks.tacticalCommands.formationLeaders.filter {(id,leader)->id !in c.units && leader !in c.units}))
                0x40d -> {
                    val anchor=pose(c.units.first(),p.grid,now,players,stocks,g)!!
                    for((index,id) in c.units.withIndex()) {
                        if(c.kind==7)continue
                        val at=pose(id,p.grid,now,players,stocks,g)!!
                        val offset=formationOffset(c.kind,index,c.units.size,ship(id,stocks)?.unit?.stack?.kind ?: 0)
                        val x=anchor.position.x+offset.x*cos(anchor.direction)+offset.y*sin(anchor.direction)
                        val y=anchor.position.y-offset.x*sin(anchor.direction)+offset.y*cos(anchor.direction)
                        val target=if(c.kind==7)at.position else TacticalPoint(x,y)
                        val m=TacticalMotion(p.grid,now,at,if(target==at.position)0f else .5f,anchor.direction,listOf(target),waitSeconds=0)
                        motion(id,m,p.characterId);motions[id]=m
                    }
                    stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(formations=stocks.tacticalCommands.formations+(p.characterId to c.kind),
                        formationLeaders=if(c.kind==7)stocks.tacticalCommands.formationLeaders.filterKeys {it !in c.units} else
                            stocks.tacticalCommands.formationLeaders+c.units.associateWith {c.units.first()}))
                }
                0x40e -> for(id in c.units) {
                    if(c.kind==2)removeWing(id) else {
                        spend(id,10)
                        stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(air=stocks.tacticalCommands.air+
                            (id to TacticalAirWing(p.characterId,p.grid,id,c.target,c.kind,now))))
                    }
                }
                0x419 -> {
                    val base=g.bases.single {it.id==c.base};val from=basePoint(base,now,stocks)
                    val dx=sin(c.direction);val dy=cos(c.direction)
                    val ids=players.values.filter {it.grid==p.grid && it.baseId==0 && it.cpPaused}.map {it.unitId}+
                        stocks.outfits.values.filter {it.positionIn(g)==OutfitPosition(p.grid)}.flatMap {it.activeShips?.values.orEmpty().filter {u->u.boats>0}.map {u->u.id}}
                    for(id in ids.sorted()) {
                        val at=pose(id,p.grid,now,players,stocks,g)?.position ?: continue
                        val x=at.x-from.x;val y=at.y-from.y;val along=x*dx+y*dy
                        if(along<0f || along>500f || abs(x*dy-y*dx)>1f)continue
                        // The cannon hits friendly ships too; select the target's opposite power only for lookup.
                        val targetPower=players.values.singleOrNull {it.unitId==id}?.power ?: ship(id,stocks)?.outfit?.power?.let {if(it==2)Power.EMPIRE else Power.ALLIANCE} ?: continue
                        val hostile=if(targetPower==Power.EMPIRE)Power.ALLIANCE else Power.EMPIRE
                        val victim=TacticalCombat.target(id,p.grid,hostile,stocks,g,players) ?: continue
                        val hit=TacticalCombat.strike(session,g,players,stocks,c.base,p.grid,o.acceptedAt+1,now,id,TacticalWeapon.BEAM,
                            TacticalWeaponMount(0,63,5000,0),100,TacticalPose(from,c.direction),victim)
                        stocks=hit.first;impacts+=hit.second.copy(fortress=true)
                    }
                }
                0x41b,0x41c -> {effective=false;for(id in c.units)effective=healOrSupply(id,c.base,c.opcode==0x41b) || effective}
                0x41f -> {
                    // Start at the accepted deadline, even when the next actor tick is late.
                    // Otherwise a restart/time jump postpones the same saved fortress order.
                    val base=g.bases.single {it.id==c.base};val at=basePoint(base,o.readyAt,stocks)
                    stocks=rebaseBerths(c.base,o.readyAt,players,stocks,g)
                    val m=TacticalMotion(p.grid,o.readyAt,TacticalPose(at,0f),c.velocity,0f,c.points,waitSeconds=0)
                    stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(fortresses=stocks.tacticalCommands.fortresses+
                        (c.base to TacticalFortressMotion(p.grid,m))))
                }
                0x408,0x421 -> {
                    val mission=TacticalMissionState(p.characterId,c.kind,c.targetKind,c.target,now,grid=p.grid)
                    if(!supreme(p,players,stocks)) stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(
                        proposals=stocks.tacticalCommands.proposals+(p.characterId to mission)))
                    else {
                        val assignments=c.units.map {id->players.values.single {it.unitId==id}.characterId}.associateWith {mission.copy(character=it)}
                        stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(missions=stocks.tacticalCommands.missions+assignments))
                    }
                }
                0x422 -> stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(
                    emergencySupplies=stocks.tacticalCommands.emergencySupplies+(c.base to wire(p))))
            }
            return effective to motions
        }
        fun removeWing(id:Int) {
            val wing=stocks.tacticalCommands.air[id] ?: return
            stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(air=stocks.tacticalCommands.air-id))
            if(wing.slowVelocity!=null && stocks.tacticalCommands.air.values.none {it.target==wing.target && it.mode==1}) {
                val p=players.values.singleOrNull {it.unitId==wing.target}
                val m=p?.motion ?: ship(wing.target,stocks)?.unit?.tactical?.motion
                if(m!=null && m.grid==wing.grid && m.velocity>0f) {
                    val at=pose(wing.target,wing.grid,now,players,stocks,g) ?: return
                    motion(wing.target,m.copy(acceptedAt=now,origin=at,velocity=wing.slowVelocity,waypoints=m.remaining(now),reportedWaypoints=0,waitSeconds=0),
                        p?.characterId ?: ship(wing.target,stocks)?.let {TacticalAuthority.commander(it.outfit,it.unit,stocks)} ?: 0)
                }
            }
        }
        fun advanceWings() {
            for(id in stocks.tacticalCommands.air.keys.sorted()) {
                var wing=stocks.tacticalCommands.air[id] ?: continue
                val p=players.values.singleOrNull {it.characterId==wing.character}
                val at=pose(id,wing.grid,now,players,stocks,g)
                if(session.ended!=null || p==null || p.grid!=wing.grid || !p.cpPaused || at==null) {removeWing(id);continue}
                if(wing.nextAt>now)continue
                if(wing.mode==0) {
                    val hostiles=stocks.tacticalCommands.air.filterValues {other->other.grid==wing.grid && other.character!=wing.character &&
                        players.values.any {it.characterId==other.character && it.power!=p.power} && other.target==(wing.target.takeIf {it>0} ?: id)}
                    for(enemy in hostiles.keys)removeWing(enemy)
                } else {
                    val victim=TacticalCombat.target(wing.target,wing.grid,p.power,stocks,g,players)
                    val target=pose(wing.target,wing.grid,now,players,stocks,g)
                    if(victim==null || target==null) {removeWing(id);continue}
                    if(hypot((at.position.x-target.position.x).toDouble(),(at.position.y-target.position.y).toDouble())<=8.0) {
                        val opposing=stocks.tacticalCommands.air.filterValues {it.grid==wing.grid && it.target==id && it.mode==1}.keys
                        for(enemy in opposing)removeWing(enemy)
                        val result=TacticalCombat.strike(session,g,players,stocks,id,wing.grid,now+1,now,wing.target,TacticalWeapon.GUN,
                            TacticalWeaponMount(1,63,1,0),100,at,victim)
                        stocks=result.first;impacts+=result.second.copy(airBattle=true)
                        val moving=players.values.singleOrNull {it.unitId==wing.target}?.motion ?: ship(wing.target,stocks)?.unit?.tactical?.motion
                        if(moving!=null && moving.velocity>0 && wing.slowVelocity==null) {
                            val existing=stocks.tacticalCommands.air.values.firstOrNull {it.source!=id && it.target==wing.target && it.slowVelocity!=null}?.slowVelocity
                            wing=wing.copy(slowVelocity=existing ?: moving.velocity)
                            if(existing==null)motion(wing.target,moving.copy(acceptedAt=now,origin=target,velocity=moving.velocity*.5f,waypoints=moving.remaining(now),reportedWaypoints=0,waitSeconds=0),
                                    players.values.singleOrNull {it.unitId==wing.target}?.characterId ?: ship(wing.target,stocks)?.let {TacticalAuthority.commander(it.outfit,it.unit,stocks)} ?: 0)
                        }
                    }
                }
                if(id in stocks.tacticalCommands.air)stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(air=stocks.tacticalCommands.air+
                    (id to wing.copy(nextAt=minOf(0xffffffffL,now+5*scale)))))
            }
        }
        fun advanceMissions() {
            val state=stocks.tacticalCommands;val missions=state.missions.toMutableMap();val merits=state.merits.toMutableMap()
            for((id,m) in state.missions) {
                if(m.completed || m.mission==0)continue
                if(session.ended!=null) {missions[id]=m.copy(completed=true);continue}
                val p=players.values.singleOrNull {it.characterId==id} ?: continue
                val checks=TacticalMissionRules.completedCheckIntervals(m.checkedAt,now)
                var reward=0L;var completed=false
                if(m.mission!=5 && m.grid!=null && p.grid!=m.grid) {missions[id]=m.copy(completed=true);continue}
                val active=TacticalContact.contested(GridForces.collect(g,session.generation,players.values,stocks)[p.grid].orEmpty())
                when(m.mission) {
                    1 -> if((m.grid==null || p.grid==m.grid) && stocks.bases[m.target]?.let {it.power==wire(p) && it.camp==0}==true && active)reward=checks else completed=true
                    2 -> if(stocks.bases[m.target]?.let {it.power==wire(p) && it.camp==0}==true){reward=10;completed=true}
                    3 -> if(impacts.any {impact->impact.hit.target==m.target &&
                            (impact.departed!=null || ship(m.target,stocks)?.unit?.boats==0) &&
                            (impact.source==p.unitId || ship(impact.source,stocks)?.let {
                                TacticalAuthority.commander(it.outfit,it.unit,stocks)==id}==true)}) {
                        reward=TacticalMissionRules.interceptionMerit(10,true);completed=true
                    }
                    4 -> {
                        val from=pose(p.unitId,p.grid,now,players,stocks,g);val to=pose(m.target,p.grid,now,players,stocks,g)
                        if(active && from!=null && to!=null && hypot((from.position.x-to.position.x).toDouble(),(from.position.y-to.position.y).toDouble())<=12.0)reward=checks
                    }
                    5 -> if((m.grid?.let {p.grid!=it} ?: !p.cpPaused) && TacticalMissionRules.withdrawalInTime(m.assignedAt,now)){reward=10;completed=true}
                        else if(now-m.assignedAt>TacticalMissionRules.WITHDRAWAL_LIMIT_GAME_SECONDS)completed=true
                }
                if(!active && m.mission!=5)completed=true
                if(reward>0)merits[id]=Math.addExact(merits[id] ?: 0,reward)
                if(checks>0 || completed)missions[id]=m.copy(checkedAt=m.checkedAt+checks*TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS,completed=completed)
            }
            val before=stocks.strategic.people
            stocks=TacticalPersonnel.credit(stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(missions=missions,merits=merits)))
            for((id,profile) in stocks.strategic.people)if(profile.merit!=(before[id]?.merit ?: 0)) {
                val p=players.values.singleOrNull {it.characterId==id} ?: continue
                events+=TacticalCommandResult(session.generation,TacticalQueuedCommand(id,p.grid,now,now,
                    TacticalCommand(0x421)),now,audience(session,p.grid,players),stateOnly=true,merits=mapOf(id to profile.merit))
            }
        }
        fun advanceFortresses() {
            for((id,state) in stocks.tacticalCommands.fortresses.toMap()) {
                if(state.motion.velocity==0f || now<=state.reportedAt)continue
                val owner=stocks.bases[id] ?: continue
                val base=g.bases.singleOrNull {it.id==id} ?: continue
                val commander=baseCommander(base,owner,stocks,g)
                val at=state.motion.pose(now)
                val done=session.ended!=null || commander==0 || state.motion.completedWaypoints(now)==state.motion.waypoints.size
                if(done)stocks=rebaseBerths(id,now,players,stocks,g)
                val changed=state.copy(reportedAt=now,motion=if(done)TacticalMotion(state.grid,now,at,0f,at.direction,listOf(at.position)) else state.motion)
                stocks=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(fortresses=stocks.tacticalCommands.fortresses+(id to changed)))
                if(commander>0)events+=TacticalCommandResult(session.generation,TacticalQueuedCommand(commander,state.grid,now,now,
                    TacticalCommand(0x41f,base=id,velocity=changed.motion.velocity,points=changed.motion.waypoints)),now,audience(session,state.grid,players))
            }
        }
    }
    /** Called after every weapon, ground exchange and occupation, before the same durable commit. */
    fun finishMissions(session:SessionState,g:Galaxy,players:MutableMap<String,Navigator>,stocks:PlanetStocks,scale:Long,
        events:MutableList<TacticalCommandResult>,impacts:MutableList<TacticalImpact>):PlanetStocks {
        val battle=Battle(session,g,players,stocks,scale,events,impacts)
        battle.advanceMissions()
        return battle.stocks
    }
    internal fun formationOffset(kind:Int,index:Int,count:Int,shipKind:Int):TacticalPoint {
        require(kind in 0..7 && index in 0 until count)
        if(index==0)return TacticalPoint(0f,0f)
        val row=(index+1)/2;val side=if(index%2==0)1 else -1
        return when(kind) {
            0->TacticalPoint(side*row*1.5f,-row*1.5f)
            1->TacticalPoint(side*minOf(row,count-row)*1.2f,-row*2f)
            2->TacticalPoint((shipKind%3-1)*2f,-index*1.5f)
            3->TacticalPoint((shipKind%4-1.5f)*2f,-index*1.5f)
            4->TacticalPoint((index%2-.5f)*2f,-row*2f)
            5->TacticalPoint(side*2f,-row*2f)
            6->TacticalPoint((index%3-1)*2f,-(index/3)*2f)
            else->TacticalPoint(0f,0f)
        }
    }
    fun advance(session:SessionState,g:Galaxy,players:MutableMap<String,Navigator>,initial:PlanetStocks,scale:Long,
        events:MutableList<TacticalCommandResult>,impacts:MutableList<TacticalImpact>):PlanetStocks {
        val battle=Battle(session,g,players,initial,scale,events,impacts)
        for(o in initial.tacticalCommands.orders.sortedWith(compareBy({it.readyAt},{it.character}))) {
            val p=players.values.singleOrNull {it.characterId==o.character && it.grid==o.grid}
            val valid=session.ended==null && p!=null && validate(o.command,p,battle.now,players,battle.stocks,g,false,session.participants.mapValues {it.value.online})==null
            if(valid && o.readyAt>battle.now)continue
            battle.stocks=battle.stocks.copy(tacticalCommands=battle.stocks.tacticalCommands.copy(orders=battle.stocks.tacticalCommands.orders-o))
            val before=impacts.size
            val beforeMorale=battle.stocks.tacticalCommands.morale
            val effect=if(valid)battle.execute(o,p!!) else false to emptyMap<Int,TacticalMotion>()
            val destroyed=if(o.command.opcode==0x419)impacts.drop(before).filter {it.fortress &&
                (it.departed!=null || ship(it.hit.target,battle.stocks)?.unit?.boats==0)}.map {it.hit.target}.distinct() else emptyList()
            val morale=battle.stocks.tacticalCommands.morale.filter {(id,value)->beforeMorale[id]!=value}
            events+=TacticalCommandResult(session.generation,o,battle.now,audience(session,o.grid,players),!effect.first,effect.second,destroyed,morale)
        }
        battle.advanceWings();battle.advanceFortresses()
        if(battle.stocks.tacticalCommands.emergencySupplies.isNotEmpty()) {
            val forces=GridForces.collect(g,session.generation,players.values,battle.stocks)
            val open=battle.stocks.tacticalCommands.emergencySupplies.filter {(base,power)->
                battle.stocks.bases[base]?.let {it.power==power && it.camp==0}==true &&
                    g.bases.singleOrNull {it.id==base}?.let {TacticalContact.contested(forces[it.grid].orEmpty())}==true}
            battle.stocks=battle.stocks.copy(tacticalCommands=battle.stocks.tacticalCommands.copy(emergencySupplies=open))
        }
        return battle.stocks
    }
}
