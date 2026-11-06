package org.logh7.engine

/** Committed ground exchange. Formula/cadence are revival policy; manual p.51 supplies
 * automatic combat, per-side 30-unit boxes and crew tables supply attack/defense. Author: Peppone Choi. */
data class GroundBattleResult(val generation:Long,val grid:Int,val base:Int,val seconds:Long,
    val damaged:List<Int>,val destroyed:List<Int>,val recipients:Map<Int,String>)

/** Public read of who actually fights on a base (manual p.51: 30 units per side; the rest wait in reserve). */
object GroundBattleView {
    fun fighting(stocks:PlanetStocks,base:GalaxyBase):Set<Int> =
        GroundBattleOperations.countries(stocks).flatMap {GroundBattleOperations.participants(stocks,base,it)}.map {it.id}.toSet()
}
internal object GroundBattleOperations {
    private val specifications=UnitSpecifications.crews.associateBy {it.kind}
    fun countries(stocks:PlanetStocks)=stocks.outfits.values.map {it.country}.distinct().sortedBy {it.value}

    fun participants(stocks:PlanetStocks,base:GalaxyBase,country:CountryId):List<ActiveGroundUnit> =
        stocks.outfits.values.filter {it.country==country}.flatMap {it.activeGround?.values.orEmpty()}
            .filter {it.base==base.id && GroundBattleRules.admitsCrewType(base,specifications.getValue(it.stack.kind).type)}
            .sortedBy {it.id}.take(GroundBattleRules.BOX_CAPACITY_PER_FACTION)

    private fun sides(stocks:PlanetStocks,base:GalaxyBase)=countries(stocks).mapNotNull {country ->
        participants(stocks,base,country).takeIf {it.isNotEmpty()}?.let {country to it}
    }
    fun contested(stocks:PlanetStocks,base:GalaxyBase)=sides(stocks,base).size>1

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
                val sides=sides(next,base)
                val losses=mutableMapOf<Int,Int>()
                fun attack(from:List<ActiveGroundUnit>,targets:List<ActiveGroundUnit>) {
                    from.forEachIndexed {i,attacker->
                        // An ordered target (reconstructed 0417) is attacked while it still fights here; otherwise round-robin.
                        val target=next.tacticalCommands.groundTargets[attacker.id]?.let {chosen->targets.singleOrNull {it.id==chosen}}
                            ?: targets[i%targets.size]
                        val offense=checkNotNull(specifications.getValue(attacker.stack.kind).manual.groundAttack)
                        val defense=checkNotNull(specifications.getValue(target.stack.kind).manual.groundDefense)
                        val full=(offense.toLong()*(100+20*attacker.stack.level)*attacker.strength*StrategicGroundTactics.factor(next,attacker.id) /
                            (maxOf(1,defense).toLong()*(100+20*target.stack.level)*5*StrategicGroundTactics.factor(next,target.id))).coerceAtLeast(1).toInt()
                        // CD p.48: low morale confuses a unit; a confused unit strikes at half strength (evidence:guess).
                        val amount=if(next.tacticalCommands.confused(attacker.id))maxOf(1,full/2) else full
                        losses[target.id]=(losses[target.id] ?: 0)+amount
                    }
                }
                // Both sides attack the same pre-exchange roster, including simultaneous losses.
                for((country,units) in sides)attack(units,sides.filter {it.first!=country}.flatMap {it.second})
                val morale=next.tacticalCommands.morale.toMutableMap()
                val outfits=next.outfits.mapValues {(_,outfit)->
                    val old=outfit.activeGround
                    if(old==null || old.values.none {it.id in losses})outfit else {
                        val discipline=OutfitDiscipline.value(next,outfit.id,at)
                        val roster=ActiveGroundUnits(old.values.mapNotNull {unit->
                            val loss=losses[unit.id] ?: return@mapNotNull unit
                            damaged+=unit.id
                            // CD p.48: damage lowers morale and a unit at 0 surrenders and leaves the field. The ship rule
                            // is reused on the share of strength lost, capped at 20 per exchange (evidence:guess).
                            val left=(morale[unit.id] ?: 100)-OutfitDiscipline.moraleLoss(
                                minOf(20,((loss.toLong()*100+unit.strength-1)/unit.strength).toInt()),discipline)
                            if(loss>=unit.strength || left<=0){destroyed+=unit.id;morale-=unit.id;null}
                            else {morale[unit.id]=left;unit.copy(strength=unit.strength-loss)}
                        })
                        val crew=outfit.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}
                        outfit.copy(units=UnitInventory(outfit.units.ships,crew+roster.quantities()),activeGround=roster)
                    }
                }
                next=next.copy(outfits=outfits,tacticalCommands=next.tacticalCommands.copy(morale=morale));at+=step
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
