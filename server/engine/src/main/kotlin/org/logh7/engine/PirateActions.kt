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
data class PirateFleet(val id:Int,val grid:Int,val since:Long,val units:List<PirateUnit>) {
    init {require(id>0 && grid in 0..4999 && since in 0..0xffffffffL && units.size in 1..PirateActions.MAX_UNITS &&
        units.map {it.id}.distinct().size==units.size)}
    val alive get()=units.any {it.boats>0}
}
data class PirateState(val fleets:Map<Int,PirateFleet> = emptyMap(),val nextId:Int=1,val nextSpawnAt:Long=0) {
    init {require(fleets.size<=PirateActions.MAX_FLEETS && fleets.all {(k,v)->k==v.id && v.id<nextId} && nextId>0 &&
        nextSpawnAt in 0..0xffffffffL)}
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

    /** Frontier grids: systems with no base held by the empire or the alliance. */
    fun frontier(g:Galaxy,s:PlanetStocks)=g.systems.map {it.grid}.filter {grid->
        g.bases.none {b->b.grid==grid && s.bases[b.id]?.power in 2..3}}.distinct().sorted()

    /** Every [SPAWN_DAYS] one fleet of 1..3 units appears on a frontier grid, up to [MAX_FLEETS] alive. */
    fun settle(g:Galaxy,s:PlanetStocks,now:Long):PlanetStocks {
        var p=s.social.pirates;var bases=s.bases
        if(p.nextSpawnAt==0L)p=p.copy(nextSpawnAt=now+SPAWN_DAYS*Justice.DAY)
        // A wiped-out fleet is suppressed: the bases of its grid regain public order.
        val wiped=p.fleets.values.filter {!it.alive}
        for(f in wiped)for(b in g.bases.filter {it.grid==f.grid})bases[b.id]?.let {stock->stock.economy?.let {e->
            bases=bases+(b.id to stock.copy(economy=e.copy(peace=minOf(100,e.peace+PEACE_REWARD))))}}
        if(wiped.isNotEmpty())p=p.copy(fleets=p.fleets-wiped.map {it.id}.toSet())
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
