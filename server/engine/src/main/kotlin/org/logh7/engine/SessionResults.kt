package org.logh7.engine

/** Manual W pp.12-13. UNRESOLVED preserves missing political conditions instead of granting an award. */
enum class SessionGrade { DECISIVE, LIMITED, LOCAL, DEFEAT, UNRESOLVED }
data class SessionStatistics(val planets:Int=0,val fortresses:Int=0,val population:Long=0,
    val shipUnits:Long=0,val groundUnits:Long=0) {
    init {require(planets>=0 && fortresses>=0 && population>=0 && shipUnits>=0 && groundUnits>=0)}
}
data class VictoryConditions(val noCoupAtEnd:Boolean?=null,val imperialLeaderAtEnemyCapital:Boolean?=null)
data class SessionPowerResult(val statistics:SessionStatistics,val grade:SessionGrade,
    val populationDominance:Boolean,val fleetDominance:Boolean)
data class SessionResult(val end:SessionEnd,val total:SessionStatistics,val powers:Map<Power,SessionPowerResult>,
    val conditions:VictoryConditions)

object SessionResults {
    fun assess(end:SessionEnd,total:SessionStatistics,powers:Map<Power,SessionStatistics>,
        conditions:VictoryConditions=VictoryConditions()):SessionResult {
        require(powers.keys==Power.entries.toSet())
        require(powers.values.sumOf {it.population.toBigInteger()}<=total.population.toBigInteger())
        val capitalWinner=if(end.reason==EndReason.CAPITAL_CAPTURED && end.affected.size==1)
            Power.entries.single {it !in end.affected} else null
        val localWinner=if(end.reason==EndReason.CAPITAL_CAPTURED)null else
            if(powers.getValue(Power.EMPIRE).population>powers.getValue(Power.ALLIANCE).population)Power.EMPIRE else Power.ALLIANCE
        val results=Power.entries.associateWith {side->
            val own=powers.getValue(side);val enemy=powers.getValue(Power.entries.single {it!=side})
            val population=total.population>0 && own.population.toBigInteger()*10.toBigInteger()>=total.population.toBigInteger()*9.toBigInteger()
            // Zero against zero is not fleet superiority; a nonzero force against zero is.
            val fleet=own.shipUnits>0 && own.shipUnits.toBigInteger()>=enemy.shipUnits.toBigInteger()*10.toBigInteger()
            val requirements=listOf(population,fleet,conditions.noCoupAtEnd)+
                if(side==Power.EMPIRE)listOf(conditions.imperialLeaderAtEnemyCapital) else emptyList()
            val grade=when {
                end.reason==EndReason.CAPITAL_CAPTURED && capitalWinner==null->SessionGrade.UNRESOLVED
                side==capitalWinner->when {
                    requirements.any {it==false}->SessionGrade.LIMITED
                    requirements.any {it==null}->SessionGrade.UNRESOLVED
                    else->SessionGrade.DECISIVE
                }
                side==localWinner->SessionGrade.LOCAL
                else->SessionGrade.DEFEAT
            }
            SessionPowerResult(own,grade,population,fleet)
        }
        return SessionResult(end,total,results,conditions)
    }

    /** Current ownership and surviving physical units; aggregate outfit quantities are not counted twice.
     * Warehoused/unused full units and one selectable unit per partial stack are included (revival policy).
     * Personal flagships count separately; unfinished construction does not count as commissioned forces. */
    fun fromWorld(galaxy:Galaxy,snapshot:WorldSnapshot,conditions:VictoryConditions=VictoryConditions()):SessionResult {
        val session=checkNotNull(snapshot.session);val end=checkNotNull(session.ended)
        val stocks=checkNotNull(snapshot.planetStocks)
        require(stocks.generation==session.generation && stocks.bases.keys==galaxy.bases.map {it.id}.toSet())
        data class Totals(var planets:Int=0,var fortresses:Int=0,var population:Long=0,var ships:Long=0,var ground:Long=0) {
            fun value()=SessionStatistics(planets,fortresses,population,ships,ground)
        }
        val total=Totals();val sides=Power.entries.associateWith {Totals()}
        fun side(wire:Int)=when(wire){2->Power.EMPIRE;3->Power.ALLIANCE;else->null}
        fun add(side:Power?,planets:Int=0,fortresses:Int=0,people:Long=0,ships:Long=0,ground:Long=0) {
            for(t in listOfNotNull(total,side?.let(sides::get))) {
                t.planets+=planets;t.fortresses+=fortresses;t.population+=people;t.ships+=ships;t.ground+=ground
            }
        }
        fun troops(units:UnitInventory)=units.troops.filterKeys {GroundUnits.isGround(it.kind)}.values.sumOf(Int::toLong)
        fun ships(units:UnitInventory,spare:SpareShipBoats)=units.ships.values.sumOf(Int::toLong)+spare.values.size
        for(base in galaxy.bases) {
            val stock=stocks.bases.getValue(base.id)
            val owner=if(stock.camp==0)side(stock.power) else null
            add(owner,if(base.kind==0)1 else 0,if(base.kind==1)1 else 0,
                (stock.economy?.population ?: galaxy.conditions.getValue(base.id).population).toLong(),
                ships(stock.units,stock.spareShips),troops(stock.units))
        }
        for(stock in stocks.warehouses.values) add(side(stock.power),ships=ships(stock.units,stock.spareShips),
            ground=troops(stock.units))
        for(outfit in stocks.outfits.values) add(side(outfit.power),
            ships=outfit.activeShips?.values?.count {it.boats>0}?.toLong() ?: outfit.units.ships.values.sumOf(Int::toLong),
            ground=outfit.activeGround?.values?.size?.toLong() ?: troops(outfit.units))
        // A navigator always owns its personal unit; a legacy null hull is the default hull,
        // and defeat replaces rather than deletes it. Offline units remain in the world.
        for(player in snapshot.navigators.values)if(player.generation==session.generation)
            add(player.power,ships=1)
        return assess(end,total.value(),sides.mapValues {it.value.value()},conditions)
    }
}
