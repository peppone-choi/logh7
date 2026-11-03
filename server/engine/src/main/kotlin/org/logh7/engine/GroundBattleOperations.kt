package org.logh7.engine

/** Committed ground exchange. Formula/cadence are revival policy; manual p.51 supplies
 * automatic combat, per-side 30-unit boxes and crew tables supply attack/defense. Author: Peppone Choi. */
data class GroundBattleResult(val generation:Long,val grid:Int,val base:Int,val seconds:Long,
    val damaged:List<Int>,val destroyed:List<Int>,val recipients:Map<Int,String>)

internal object GroundBattleOperations {
    private val specifications=UnitSpecifications.crews.associateBy {it.kind}
    fun participants(stocks:PlanetStocks,base:GalaxyBase,power:Int):List<ActiveGroundUnit> =
        stocks.outfits.values.filter {it.power==power}.flatMap {it.activeGround?.values.orEmpty()}
            .filter {it.base==base.id && GroundBattleRules.admitsCrewType(base,specifications.getValue(it.stack.kind).type)}
            .sortedBy {it.id}.take(GroundBattleRules.BOX_CAPACITY_PER_FACTION)

    fun contested(stocks:PlanetStocks,base:GalaxyBase)=participants(stocks,base,2).isNotEmpty() && participants(stocks,base,3).isNotEmpty()

    fun advance(session:SessionState,galaxy:Galaxy,players:Map<String,Navigator>,stocks:PlanetStocks,
        scale:Long,events:MutableList<GroundBattleResult>):PlanetStocks {
        if(session.ended!=null)return stocks
        require(scale in 1..86400)
        val now=session.gameMillis/1000;val step=5*scale
        var next=stocks
        val clocks=stocks.groundBattles.toMutableMap()
        for(base in galaxy.bases.sortedBy {it.id}) {
            if(!contested(next,base)){clocks.remove(base.id);continue}
            var at=clocks[base.id] ?: now
            val damaged=linkedSetOf<Int>();val destroyed=linkedSetOf<Int>()
            // Bound each actor pass; the durable clock retains unprocessed exchanges.
            var rounds=0
            while(at<=now-step && rounds++<256 && contested(next,base)) {
                val empire=participants(next,base,2);val alliance=participants(next,base,3)
                val losses=mutableMapOf<Int,Int>()
                fun attack(from:List<ActiveGroundUnit>,targets:List<ActiveGroundUnit>) {
                    from.forEachIndexed {i,attacker->
                        // An ordered target (reconstructed 0417) is attacked while it still fights here; otherwise round-robin.
                        val target=next.tacticalCommands.groundTargets[attacker.id]?.let {chosen->targets.singleOrNull {it.id==chosen}}
                            ?: targets[i%targets.size]
                        val offense=checkNotNull(specifications.getValue(attacker.stack.kind).manual.groundAttack)
                        val defense=checkNotNull(specifications.getValue(target.stack.kind).manual.groundDefense)
                        val amount=(offense.toLong()*(100+20*attacker.stack.level)*attacker.strength*StrategicGroundTactics.factor(next,attacker.id) /
                            (maxOf(1,defense).toLong()*(100+20*target.stack.level)*5*StrategicGroundTactics.factor(next,target.id))).coerceAtLeast(1).toInt()
                        losses[target.id]=(losses[target.id] ?: 0)+amount
                    }
                }
                // Both sides attack the same pre-exchange roster, including simultaneous losses.
                attack(empire,alliance);attack(alliance,empire)
                val outfits=next.outfits.mapValues {(_,outfit)->
                    val old=outfit.activeGround
                    if(old==null || old.values.none {it.id in losses})outfit else {
                        val roster=ActiveGroundUnits(old.values.mapNotNull {unit->
                            val loss=losses[unit.id] ?: return@mapNotNull unit
                            damaged+=unit.id
                            if(loss>=unit.strength){destroyed+=unit.id;null} else unit.copy(strength=unit.strength-loss)
                        })
                        val crew=outfit.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}
                        outfit.copy(units=UnitInventory(outfit.units.ships,crew+roster.quantities()),activeGround=roster)
                    }
                }
                next=next.copy(outfits=outfits);at+=step
            }
            if(contested(next,base))clocks[base.id]=at else clocks.remove(base.id)
            if(damaged.isNotEmpty()) {
                val recipients=session.participants.mapNotNull {(account,member)->players[account]?.takeIf {
                    member.online && member.connection!=null && it.generation==session.generation &&
                        it.cpPaused && it.baseId==0 && it.grid==base.grid
                }?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
                events+=GroundBattleResult(session.generation,base.grid,base.id,at,damaged.toList(),destroyed.toList(),recipients)
            }
        }
        // Orders end with either troop: a destroyed, withdrawn or re-embarked unit no longer holds or receives one.
        val landed=next.outfits.values.flatMap {it.activeGround?.values.orEmpty()}.filter {it.base>0}.map {it.id}.toSet()
        val orders=next.tacticalCommands.groundTargets.filter {(unit,target)->unit in landed && target in landed}
        if(orders!=next.tacticalCommands.groundTargets)next=next.copy(tacticalCommands=next.tacticalCommands.copy(groundTargets=orders))
        return if(next==stocks && clocks==stocks.groundBattles)stocks else next.copy(groundBattles=clocks)
    }
}
