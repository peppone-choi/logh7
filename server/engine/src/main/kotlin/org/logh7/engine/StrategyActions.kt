package org.logh7.engine

import org.logh7.engine.strategy.*

/** Manual command numbers are native constmsg group 18; these are not packet opcodes.
 * Training increments and suppression sentiment deltas are revival policy (evidence:guess).
 * Author: 최병호. */
data class StrategicPerson(val rank:Int?=null,val groundTactics:Int=0,val retiredUntil:Long=0,
    val peerage:Peerage?=null,val merit:Long=0,val influence:Int=0,val evaluation:Long=0,
    val money:Long=0,val abilities:List<Int>?=null,val decorations:Set<Int> = emptySet()) {
    init {require((rank==null || rank in 0..20) && groundTactics in 0..3 && retiredUntil in 0..0xffffffffL &&
        merit in 0..0xffffffffL && influence in 0..100 && evaluation in 0..0xffffffffL && money in 0..0xffffffffL &&
        (abilities==null || abilities.size==8 && abilities.all {it in 0..255}) && decorations.size<=128 && decorations.all {it in 0..127})}
}
data class SuppressionOrder(val character:Int,val outfit:Int,val base:Int,val power:Int,val readyAt:Long) {
    init {require(character>0 && outfit>0 && base in 1..65535 && power in 2..3 && readyAt in 0..0xffffffffL)}
}
data class StrategicState(val people:Map<Int,StrategicPerson> = emptyMap(),val suppressions:List<SuppressionOrder> = emptyList(),
    val offices:List<OfficeHolding> = emptyList(),val fiefs:Map<Int,Int> = emptyMap(),
    val operations:List<StrategicOperation> = emptyList()) {
    init {
        require(people.size<=4096 && people.keys.all {it>0} && suppressions.size<=128)
        require(suppressions.distinctBy {it.character}.size==suppressions.size)
        require(offices.size<=4096 && offices.distinct().size==offices.size)
        require(offices.all {OrganizationOffices.of(it.card)!=null})
        require(offices.groupBy {it.card to it.seat}.all {(key,seats)->seats.size<=OrganizationOffices.all.getValue(key.first).seats})
        require(fiefs.size<=350 && fiefs.all {(base,owner)->base in 1..65535 && owner>0})
        require(operations.size<=256 && operations.distinctBy {it.id}.size==operations.size && operations.count {it.live}<=128)
    }
    /** Freeze nested collections as well as the top-level checkpoint before publication. */
    fun detached():StrategicState {
        fun <T> list(values:Collection<T>):List<T> = java.util.Collections.unmodifiableList(ArrayList(values))
        fun <T> set(values:Set<T>):Set<T> = java.util.Collections.unmodifiableSet(LinkedHashSet(values))
        fun <K,V> map(values:Map<K,V>):Map<K,V> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        return copy(people=map(people.mapValues {(_,p)->p.copy(abilities=p.abilities?.let {list(it)},decorations=set(p.decorations))}),
            suppressions=list(suppressions),offices=list(offices),fiefs=map(fiefs),
            operations=list(operations.map {it.copy(outfits=set(it.outfits),characters=set(it.characters),defended=set(it.defended))}))
    }
}
data class StrategyActionRequest(val account:String,val connection:String,val characterId:Int,val power:Power,
    val generation:Long,val command:Int,val card:Int,val rank:Int,val outfit:Int=0,
    val politics:Int=50,val administration:Int=50,val target:Int=0,val base:Int=0,val value:Int=0,
    val amount:Long=0,val identities:List<StrategicIdentity> = emptyList(),val argument:Int=0,
    val navigators:Map<String,Navigator> = emptyMap())
data class StrategicIdentity(val id:Int,val power:Int,val rank:Int,val origin:Int,val abilities:List<Int>) {
    init {require(id>0 && power in 2..3 && rank in 0..20 && abilities.size==8 && abilities.all {it in 0..255})}
}
enum class StrategyActionRejection { SESSION,IDENTITY,COMMAND,CARD,AUTHORITY,LOCATION,BUSY,TROOPS,MAXIMUM,RANK,CP,STORAGE,RESOURCE,POINTS,TARGET,CREW }
data class StrategyActionResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:StrategyActionRejection?=null,
    val changed:Boolean=false,val recovery:NavigationResult?=null,val readyAt:Long=0,
    val navigators:Map<String,Navigator> = emptyMap())

internal object StrategyActions {
    val supported=setOf(48,50,53,84,85)+PersonnelActions.supported+StrategicOperations.supported+
        CompleteRepairActions.supported+FlagshipMarketActions.supported
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator?,stocks:PlanetStocks):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val member=s.participants[r.account]
        if(s.ended!=null || s.generation!=r.generation || stocks.generation!=r.generation || member==null ||
            !member.online || member.connection!=r.connection)return reject(StrategyActionRejection.SESSION)
        if(p==null || p.generation!=r.generation || p.characterId!=r.characterId || p.power!=r.power || member.power!=r.power)
            return reject(StrategyActionRejection.IDENTITY)
        if(r.command !in supported)return reject(StrategyActionRejection.COMMAND)
        if(r.command in PersonnelActions.supported)return PersonnelActions.evaluate(galaxy,r,s,p,stocks)
        if(r.command in StrategicOperations.supported)return StrategicOperations.evaluate(galaxy,r,s,p,stocks)
        if(r.command in CompleteRepairActions.supported)return CompleteRepairActions.evaluate(galaxy,r,s,p,stocks)
        if(r.command in FlagshipMarketActions.supported)return FlagshipMarketActions.evaluate(galaxy,r,s,p,stocks)
        val power=if(r.power==Power.EMPIRE)2 else 3
        if(r.card!=if(r.command==84)0 else if(power==2)59 else 195)return reject(StrategyActionRejection.CARD)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null ||
            stocks.strategic.suppressions.any {it.character==r.characterId})return reject(StrategyActionRejection.BUSY)
        val person=stocks.strategic.people[r.characterId] ?: StrategicPerson()
        val rank=person.rank ?: r.rank
        val now=s.gameMillis/1000
        var after=p
        var next=stocks
        var readyAt=0L
        when(r.command) {
            48,50,53 -> {
                if(rank !in 2..20)return reject(StrategyActionRejection.RANK)
                val o=stocks.outfits[r.outfit] ?: return reject(StrategyActionRejection.AUTHORITY)
                if(o.commanderId!=r.characterId || o.power!=power)return reject(StrategyActionRejection.AUTHORITY)
                val position=o.positionIn(galaxy)
                if(position.grid!=p.grid || position.baseId!=p.baseId)return reject(StrategyActionRejection.LOCATION)
                if(o.activeGround?.values.orEmpty().isEmpty())return reject(StrategyActionRejection.TROOPS)
                if(o.activeGround?.values.orEmpty().any {it.order!=null} ||
                    o.activeShips?.values.orEmpty().any {it.tactical!=null || it.support!=null})return reject(StrategyActionRejection.BUSY)
                when(r.command) {
                    48 -> {
                        val old=checkNotNull(o.activeGround)
                        if(old.values.all {it.stack.level==3})return reject(StrategyActionRejection.MAXIMUM)
                        val trained=ActiveGroundUnits(old.values.map {it.copy(stack=it.stack.copy(level=minOf(3,it.stack.level+1)))})
                        val crew=o.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}
                        next=stocks.copy(outfits=stocks.outfits+(o.id to o.copy(activeGround=trained,
                            units=UnitInventory(o.units.ships,crew+trained.quantities()))))
                    }
                    50 -> {
                        if(person.groundTactics==3)return reject(StrategyActionRejection.MAXIMUM)
                        next=stocks.copy(strategic=stocks.strategic.copy(people=stocks.strategic.people+
                            (r.characterId to person.copy(groundTactics=person.groundTactics+1))))
                    }
                    53 -> {
                        val base=stocks.bases[p.baseId] ?: return reject(StrategyActionRejection.LOCATION)
                        if(base.power!=power || base.camp!=0 || o.activeGround?.values.orEmpty().none {it.base==base.baseId})
                            return reject(StrategyActionRejection.TROOPS)
                        if(base.economy?.peace==100)return reject(StrategyActionRejection.MAXIMUM)
                        if(now>0xffffffffL-86400)return reject(StrategyActionRejection.BUSY)
                        readyAt=now+86400
                        next=stocks.copy(strategic=stocks.strategic.copy(suppressions=stocks.strategic.suppressions+
                            SuppressionOrder(r.characterId,o.id,base.baseId,power,readyAt)))
                    }
                }
            }
            84,85 -> {
                if(r.outfit!=0)return reject(StrategyActionRejection.AUTHORITY)
                if(r.command==85 && (rank!=1 || now<person.retiredUntil) || r.command==84 && rank !in 2..20)
                    return reject(StrategyActionRejection.RANK)
                if(r.command==84 && now>0xffffffffL-30*86400)return reject(StrategyActionRejection.BUSY)
                next=clearOffices(stocks,r.characterId).let {cleared ->cleared.copy(strategic=cleared.strategic.copy(
                    people=cleared.strategic.people+(r.characterId to person.copy(rank=if(r.command==85)10 else 1,
                        retiredUntil=if(r.command==84)now+30*86400 else person.retiredUntil))))}
                if(r.command==85) {
                    val ship=Flagships.forType(power,0)
                    after=p.copy(hull=FlagshipHull(ship.kind,now),supplies=10_000)
                }
            }
        }
        val cost=if(r.command in setOf(48,50))80L else 160L
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return StrategyActionResult(after.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,
            changed=true,readyAt=readyAt)
    }
    /** Retirement abandons offices, retaining other people's appointments in the same outfit. */
    fun clearOffices(stocks:PlanetStocks,character:Int):PlanetStocks {
        val bases=stocks.bases.mapValues {(_,b)->b.copy(
            governorId=b.governorId.takeUnless {it==character} ?: 0,
            garrisonCommanderId=b.garrisonCommanderId.takeUnless {it==character} ?: 0,
            capitalAdministratorId=b.capitalAdministratorId.takeUnless {it==character} ?: 0,
            capitalDefenseCommanderId=b.capitalDefenseCommanderId.takeUnless {it==character} ?: 0,
            guardInspectorId=b.guardInspectorId.takeUnless {it==character} ?: 0)}
        val outfits=stocks.outfits.mapValues {(_,o)->o.copy(commanderId=o.commanderId.takeUnless {it==character} ?: 0,
            aideId=o.aideId.takeUnless {it==character} ?: 0,staff=FleetStaff(o.staff.deputy.takeUnless {it==character} ?: 0,
                o.staff.chief.takeUnless {it==character} ?: 0,o.staff.officers-character))}
        return stocks.copy(bases=bases,outfits=outfits,nationalOffices=stocks.nationalOffices.filterValues {it!=character},
            logisticsOfficers=stocks.logisticsOfficers-character,
            strategic=stocks.strategic.copy(offices=stocks.strategic.offices.filter {it.character!=character}))
    }
    fun settle(stocks:PlanetStocks,seconds:Long,galaxy:Galaxy?=null):PlanetStocks {
        val market=FlagshipMarketActions.settle(stocks,seconds)
        val original=if(galaxy==null)market else StrategicOperations.settle(galaxy,market,seconds)
        return settleSuppression(original,seconds)
    }
    private fun settleSuppression(stocks:PlanetStocks,seconds:Long):PlanetStocks {
        val due=stocks.strategic.suppressions.filter {it.readyAt<=seconds}
        if(due.isEmpty())return stocks
        val bases=stocks.bases.toMutableMap()
        for(order in due) {
            val base=bases[order.base] ?: continue
            val troops=stocks.outfits[order.outfit]
            // A lost office/army/base cannot continue policing after the reservation matures.
            if(base.power!=order.power || base.camp!=0 || troops==null || troops.commanderId!=order.character ||
                troops.activeGround?.values.orEmpty().none {it.base==base.baseId})continue
            val people=base.economy ?: continue
            bases[base.baseId]=base.copy(economy=people.copy(peace=minOf(100,people.peace+5),approval=maxOf(0,people.approval-2)))
        }
        return stocks.copy(bases=bases,strategic=stocks.strategic.copy(suppressions=stocks.strategic.suppressions-due.toSet()))
    }
}

/** Training affects the existing automatic ground exchange, including offline commanders.
 * Ten percent per learned stage (maximum three) is an explicit restoration coefficient. */
object StrategicGroundTactics {
    fun factor(stocks:PlanetStocks,unit:Int):Int {
        val commander=stocks.outfits.values.singleOrNull {o->o.activeGround?.values.orEmpty().any {it.id==unit}}?.commanderId ?: 0
        return 100+10*(stocks.strategic.people[commander]?.groundTactics ?: 0)
    }
}
