package org.logh7.engine

/**
 * U-32a 우주해적 토벌 phase 1a (design-pirates.md, approved 2026-11-02). The original client names power 4 海賊 and ship
 * category 15 海賊船 (evidence:client); patch P14 introduced pirates without movement or counterattack (evidence:manual).
 * Pirate fleets are server NPC fleets that every side may fight. Spawn grids, sizes and rewards are evidence:guess.
 * Author: Peppone Choi.
 */
data class PirateUnit(val id:Int,val boats:Int) {
    init {require(id>0 && boats in 0..PirateActions.UNIT_BOATS)}
}
data class PirateFleet(val id:Int,val grid:Int,val since:Long,val units:List<PirateUnit>,
    /** U-32: game second of the fleet's last counterattack volley (0 = never). */
    val firedAt:Long=0) {
    init {require(id>0 && grid in 0..4999 && since in 0..0xffffffffL && units.size in 1..PirateActions.MAX_UNITS &&
        units.map {it.id}.distinct().size==units.size && firedAt in 0..0xffffffffL)}
    val alive get()=units.any {it.boats>0}
}
data class PirateState(val fleets:Map<Int,PirateFleet> = emptyMap(),val nextId:Int=1,val nextSpawnAt:Long=0,
    /** Phase 2: game second through which the daily public-order harm has been applied (0 = not started). */
    val harmedThrough:Long=0) {
    init {require(fleets.size<=PirateActions.MAX_FLEETS && fleets.all {(k,v)->k==v.id && v.id<nextId} && nextId>0 &&
        nextSpawnAt in 0..0xffffffffL && harmedThrough in 0..0xffffffffL)}
    /** Grids a living pirate fleet holds. */
    fun grids()=fleets.values.filter {it.alive}.map {it.grid}.toSet()
    fun unit(id:Int)=fleets.values.firstNotNullOfOrNull {f->f.units.singleOrNull {it.id==id}?.let {f to it}}
    fun detached()=copy(fleets=java.util.Collections.unmodifiableMap(LinkedHashMap(fleets)))
}

object PirateActions {
    const val POWER=4
    const val MAX_FLEETS=4
    const val MAX_UNITS=3
    const val UNIT_BOATS=300
    const val SPAWN_DAYS=7L
    /** Pirate unit ids sit far above ordinary ship ids and below personal flagship ids (1,000,000+). */
    const val UNIT_BASE=900_000
    const val MERIT=50L
    const val PEACE_REWARD=5
    const val HARM=1
    /** U-32 (user decision 2026-11-02, evidence:guess): a volley every 20 game seconds, beam power 900 per full unit. */
    const val FIRE_SECONDS=20L
    const val UNIT_POWER=900L
    /** Strategic move: every [SPAWN_DAYS] a fleet shifts to an open grid within [MOVE_CELLS], next to trade-route bases first. */
    const val MOVE_CELLS=3

    /** Frontier grids: systems with no base held by a country. */
    fun frontier(g:Galaxy,s:PlanetStocks)=g.systems.map {it.grid}.filter {grid->
        g.bases.none {b->b.grid==grid && s.bases[b.id]?.country!=null}}.distinct().sorted()

    /** U-32 strategic movement: fleets not in battle drift to a nearby frontier grid, preferring trade-route ends. */
    fun move(g:Galaxy,s:PlanetStocks,now:Long,engaged:Set<Int>):PlanetStocks {
        var p=s.social.pirates
        val due=p.fleets.values.filter {it.alive && it.grid !in engaged && now-it.since>=SPAWN_DAYS*Justice.DAY}
        if(due.isEmpty())return s
        // Any passable grid without a country's base, open space included.
        val owned=g.bases.filter {s.bases[it.id]?.country!=null}.map {it.grid}.toSet()
        fun open(grid:Int)=grid in 0..4999 && grid !in owned && g.kind(grid)!=GridKind.IMPASSABLE && g.terrain(grid)==GridTerrain.NONE
        val routeGrids=s.social.trade.routes.values.flatMap {r->listOf(r.from,r.to)}.mapNotNull {id->g.bases.singleOrNull {it.id==id}?.grid}.toSet()
        for(f in due.sortedBy {it.id}) {
            val taken=p.fleets.values.filter {it.id!=f.id}.map {it.grid}.toSet()
            val near=(-MOVE_CELLS..MOVE_CELLS).flatMap {dy->(-MOVE_CELLS..MOVE_CELLS).mapNotNull {dx->
                val x=f.grid%100+dx;val y=f.grid/100+dy
                if(x !in 0..99 || y !in 0..49 || dx==0 && dy==0)null else y*100+x}}.filter {it !in taken && open(it)}
            val targets=g.bases.filter {it.grid in routeGrids}.map {it.grid}.toSet()
            val choice=near.minByOrNull {grid->(if(targets.any {EspionageActions.cells(it,grid)<=1})0 else 1000)+java.util.Random(now*7+f.id+grid).nextInt(1000)}
            p=p.copy(fleets=p.fleets+(f.id to f.copy(grid=choice ?: f.grid,since=now)))
        }
        return s.copy(social=s.social.copy(pirates=p))
    }
    /** U-32 counterattack: each living pirate unit fires at the nearest ship in range in its grid every [FIRE_SECONDS]. */
    internal fun counter(session:SessionState,g:Galaxy,players:MutableMap<String,Navigator>,start:PlanetStocks,now:Long,
        impacts:MutableList<TacticalImpact>):PlanetStocks {
        var s=start
        for(fleet in start.social.pirates.fleets.values.filter {it.alive && now-it.firedAt>=FIRE_SECONDS}.sortedBy {it.id}) {
            val engaged=players.values.any {it.cpPaused && it.grid==fleet.grid && it.baseId==0}
            if(!engaged)continue
            for(unit in fleet.units.filter {it.boats>0}) {
                val origin=pose(s,unit.id) ?: continue
                val candidates=players.values.filter {it.cpPaused && it.grid==fleet.grid && it.baseId==0 && it.hull!=null}.map {it.unitId} +
                    s.outfits.values.filter {it.positionIn(g)==OutfitPosition(fleet.grid)}.flatMap {o->o.activeShips?.values.orEmpty()
                        .filter {it.boats>0}.map {it.id}}
                val victim=candidates.mapNotNull {id->
                    TacticalCombat.target(id,fleet.grid,Camp.PIRATES,s,g,players)?.let {t->
                        val at=t.personal?.second?.let {p->p.motion?.pose(now,p.posture,g,s)?.position ?:
                            TacticalDeployment.initial(p.unitId,p.grid,players.values,s,g).position} ?:
                            t.regular?.second?.tactical?.motion?.pose(now,t.regular.second.posture,g,s)?.position ?:
                            TacticalDeployment.initial(id,fleet.grid,players.values,s,g).position
                        if(TacticalLineOfFire.blocked(unit.id,fleet.grid,Camp.PIRATES,origin.position,at,now,players.values,s,g))null
                        else Triple(id,t,at)}}
                    .minByOrNull {(_,_,at)->kotlin.math.hypot((at.x-origin.position.x).toDouble(),(at.y-origin.position.y).toDouble())} ?: continue
                val (id,target,at)=victim
                // The turret faces its target (pirate ships are not manoeuvred).
                val aim=TacticalPose(origin.position,kotlin.math.atan2((at.x-origin.position.x).toDouble(),(at.y-origin.position.y).toDouble()).toFloat())
                val mount=TacticalWeaponMount(0,63,(UNIT_POWER*unit.boats/UNIT_BOATS).toInt().coerceAtLeast(1),0)
                val effect=TacticalArmaments.effectiveness(mount,aim,at)
                if(effect<=0)continue
                val resolved=TacticalCombat.strike(session,g,players,s,unit.id,fleet.grid,now,now,id,TacticalWeapon.BEAM,mount,effect,aim,target)
                s=resolved.first;impacts+=resolved.second
            }
            val current=s.social.pirates.fleets[fleet.id] ?: continue
            s=s.copy(social=s.social.copy(pirates=s.social.pirates.copy(fleets=s.social.pirates.fleets+(fleet.id to current.copy(firedAt=now)))))
        }
        return s
    }
    /** Every [SPAWN_DAYS] one fleet of 1..3 units appears on a frontier grid, up to [MAX_FLEETS] alive. */
    fun settle(g:Galaxy,s:PlanetStocks,now:Long):PlanetStocks {
        var p=s.social.pirates;var bases=s.bases
        // A map with no fleet and no frontier system has nothing to clock (and no world save to make).
        if(p.fleets.isEmpty() && frontier(g,s).isEmpty())return s
        if(p.nextSpawnAt==0L)p=p.copy(nextSpawnAt=now+SPAWN_DAYS*Justice.DAY)
        // A wiped-out fleet is suppressed: the bases of its grid regain public order.
        val wiped=p.fleets.values.filter {!it.alive}
        for(f in wiped)for(b in g.bases.filter {it.grid==f.grid})bases[b.id]?.let {stock->stock.economy?.let {e->
            bases=bases+(b.id to stock.copy(economy=e.copy(peace=minOf(100,e.peace+PEACE_REWARD))))}}
        if(wiped.isNotEmpty())p=p.copy(fleets=p.fleets-wiped.map {it.id}.toSet())
        // Phase 2: each whole day a living fleet costs the bases of its grid one point of public order.
        if(p.harmedThrough==0L)p=p.copy(harmedThrough=now)
        val days=(now-p.harmedThrough)/Justice.DAY
        if(days>0) {
            val grids=p.grids()
            for(b in g.bases.filter {it.grid in grids})bases[b.id]?.let {stock->stock.economy?.let {e->
                bases=bases+(b.id to stock.copy(economy=e.copy(peace=maxOf(0,e.peace-days.toInt()*HARM))))}}
            p=p.copy(harmedThrough=p.harmedThrough+days*Justice.DAY)
        }
        if(now>=p.nextSpawnAt) {
            val grids=frontier(g,s).filter {grid->p.fleets.values.none {it.grid==grid}}
            if(p.fleets.size<MAX_FLEETS && grids.isNotEmpty()) {
                val random=java.util.Random(p.nextSpawnAt*31+p.nextId)
                val count=1+random.nextInt(MAX_UNITS)
                val first=UNIT_BASE+p.nextId*MAX_UNITS
                val fleet=PirateFleet(p.nextId,grids[random.nextInt(grids.size)],now,List(count) {PirateUnit(first+it,UNIT_BOATS)})
                p=p.copy(fleets=p.fleets+(fleet.id to fleet),nextId=p.nextId+1)
            }
            p=p.copy(nextSpawnAt=now+SPAWN_DAYS*Justice.DAY)
        }
        return if(p==s.social.pirates && bases==s.bases)s else s.copy(bases=bases,social=s.social.copy(pirates=p))
    }

    /** Pirate fleets travel to the client as outfit records of power 4 with these ids. */
    const val OUTFIT_BASE=800_000
    /** Pirate units are drawn with the merchant ship model (no 海賊船 specification survives). */
    fun kind()=spec().kind
    fun ids(s:PlanetStocks)=s.social.pirates.fleets.values.flatMap {f->f.units.filter {it.boats>0}.map {it.id}}
    /** Pirate ships use the merchant ship's specification; their armour counts 1.5 times (approved design). */
    fun spec()=UnitSpecifications.ships.first {it.category==14}
    /** Pirates hold a column at the grid's centre facing north. */
    fun pose(s:PlanetStocks,unit:Int):TacticalPose? {
        val (fleet,_)=s.social.pirates.unit(unit) ?: return null
        val index=fleet.units.indexOfFirst {it.id==unit}
        return TacticalPose(TacticalPoint(0f,(index-1)*10f),0f)
    }
    /** Applies losses to a pirate unit; returns the new state and how many boats were destroyed. */
    fun damage(s:PlanetStocks,unit:Int,boats:Int):Pair<PlanetStocks,Int> {
        val (fleet,u)=s.social.pirates.unit(unit) ?: return s to 0
        val lost=minOf(u.boats,maxOf(0,boats))
        val next=fleet.copy(units=fleet.units.map {if(it.id==unit)it.copy(boats=it.boats-lost) else it})
        return s.copy(social=s.social.copy(pirates=s.social.pirates.copy(fleets=s.social.pirates.fleets+(fleet.id to next)))) to lost
    }

    /** Reward for sinking a pirate unit: merit to the shooter. */
    fun reward(s:PlanetStocks,shooter:Int):PlanetStocks {
        if(shooter<=0)return s
        val person=s.strategic.people[shooter] ?: StrategicPerson()
        return s.copy(strategic=s.strategic.copy(people=s.strategic.people+(shooter to person.copy(merit=person.merit+MERIT))))
    }
}
