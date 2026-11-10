package org.logh7.engine

import org.logh7.engine.strategy.*

enum class StrategicForce { FLEET, AUXILIARY, SOLO }
enum class StrategicPurpose { OCCUPY, DEFEND, SWEEP }
enum class StrategicPlanState { PLANNED, ISSUED, STARTED, WITHDRAWN, ENDED }

/** Manual W38–40 lifecycle. CP interpolation and the 100/50 merit award are restoration
 * coefficients, not recovered server formulas. A plan announces an objective; it does
 * not replace a player's navigation orders. Author: Peppone Choi. */
data class StrategicOperation(val id:Int,val planner:Int,val card:Int,val country:CountryId,val grid:Int,
    val force:StrategicForce,val purpose:StrategicPurpose,val units:Int,val activatesAt:Long,
    val state:StrategicPlanState=StrategicPlanState.PLANNED,val issuedAt:Long?=null,
    val outfits:Set<Int> = emptySet(),val characters:Set<Int> = emptySet(),val defended:Set<Int> = emptySet(),
    val outcome:OperationOutcome?=null,val issuer:Int=0,val defenseBreached:Boolean=false) {
    val live get()=state in setOf(StrategicPlanState.PLANNED,StrategicPlanState.ISSUED,StrategicPlanState.STARTED)
    init {
        require(id>0 && planner>0 && card in 1..260 && grid in 0..4999 && units in 1..65535 &&
            activatesAt in 0..0xffffffffL && (issuedAt==null || issuedAt in activatesAt..0xffffffffL) && issuer>=0)
        require(outfits.size<=1000 && outfits.all {it>0} && characters.size<=4096 && characters.all {it>0})
        require(defended.size<=350 && defended.all {it in 1..65535})
        require(purpose!=StrategicPurpose.SWEEP || force==StrategicForce.SOLO)
        require(state !in setOf(StrategicPlanState.ISSUED,StrategicPlanState.STARTED,StrategicPlanState.ENDED) || issuedAt!=null)
        require(outcome==null || state==StrategicPlanState.ENDED)
        require(state!=StrategicPlanState.ENDED || purpose==StrategicPurpose.SWEEP || outcome!=null)
        require(issuedAt==null || issuer>0)
        require(!defenseBreached || purpose==StrategicPurpose.DEFEND && issuedAt!=null &&
            state in setOf(StrategicPlanState.STARTED,StrategicPlanState.WITHDRAWN,StrategicPlanState.ENDED))
    }
}

internal object StrategicOperations {
    val supported=setOf(22,23,24)
    fun plannerForce(card:Int):StrategicForce?=when(card) {
        24,147->StrategicForce.FLEET;25,148->StrategicForce.AUXILIARY;26,149->StrategicForce.SOLO;else->null
    }
    fun issuerForce(card:Int):StrategicForce?=when(card) {
        28,169->StrategicForce.FLEET;29,170->StrategicForce.AUXILIARY;30,171->StrategicForce.SOLO;else->null
    }
    private fun fits(force:StrategicForce,kind:OutfitKind)=when(force) {
        StrategicForce.FLEET->kind==OutfitKind.FLEET
        StrategicForce.AUXILIARY->kind in setOf(OutfitKind.TRANSPORT,OutfitKind.PATROL)
        StrategicForce.SOLO->false
    }
    private fun shipUnits(outfit:OutfitStock)=outfit.activeShips?.values.orEmpty().count {it.boats>0}
    private fun bases(galaxy:Galaxy,grid:Int)=galaxy.bases.filter {it.grid==grid}.map {it.id}.toSet()
    private fun owned(stocks:PlanetStocks,ids:Set<Int>,country:CountryId)=ids.filter {stocks.bases[it]?.let {b->b.belongsTo(country) && b.camp==0}==true}.toSet()
    private fun target(galaxy:Galaxy,stocks:PlanetStocks,grid:Int,country:CountryId,purpose:StrategicPurpose):Boolean {
        val ids=bases(galaxy,grid)
        return when(purpose) {
            StrategicPurpose.OCCUPY->ids.isNotEmpty() && ids.all {stocks.bases[it]?.let {b->b.country!=null && b.country!=country && b.camp==0}==true}
            StrategicPurpose.DEFEND->owned(stocks,ids,country).isNotEmpty()
            StrategicPurpose.SWEEP->galaxy.kind(grid)==GridKind.STAR_SYSTEM
        }
    }
    private fun sole(r:StrategyActionRequest,stocks:PlanetStocks)=r.navigators.values.filter {p->
        val rank=stocks.strategic.people[p.characterId]?.rank ?: r.identities.singleOrNull {it.id==p.characterId}?.rank
        p.generation==r.generation && p.country==r.country && !CoupActions.rebel(stocks,p.characterId,p.country) &&
            rank!=null && rank in 2..20 && hasLiveFlagship(p,stocks) &&
            stocks.outfits.values.none {p.characterId in it.members()}
    }
    private fun hasLiveFlagship(player:Navigator,stocks:PlanetStocks)=
        !BattleDeath.deceased(stocks,player.characterId) && player.hull?.hp?.let {it>0}==true
    private fun soloReached(plan: StrategicOperation, stocks: PlanetStocks, navigators: Collection<Navigator>) =
        plan.force == StrategicForce.SOLO && navigators.any {
            it.generation == stocks.generation && it.characterId in plan.characters && it.grid == plan.grid &&
                TacticalAuthority.camp(it, stocks) == Camp(plan.country) && hasLiveFlagship(it, stocks)
        }
    fun evaluate(galaxy:Galaxy,r:StrategyActionRequest,s:SessionState,p:Navigator,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):StrategyActionResult {
        fun reject(reason:StrategyActionRejection)=StrategyActionResult(p,stocks,reason)
        val country=r.country ?: return reject(StrategyActionRejection.IDENTITY)
        if(country.value !in countries.countries)return reject(StrategyActionRejection.AUTHORITY)
        val regular=Camp(country)
        if(r.command !in supported)return reject(StrategyActionRejection.COMMAND)
        if(p.cpPaused || p.navigationPending)return reject(StrategyActionRejection.BUSY)
        val role=OrganizationOffices.of(r.card,country,countries) ?: return reject(StrategyActionRejection.CARD)
        if(TacticalAuthority.camp(p,stocks)!=regular || role.camp!=0 ||
            OrganizationalOfficePolicy(galaxy,countries).holders(stocks,r.card,country).none {it.character==r.characterId})
            return reject(StrategyActionRejection.AUTHORITY)
        val force=(if(r.command==24)issuerForce(r.card) else plannerForce(r.card)) ?: return reject(StrategyActionRejection.CARD)
        if(r.target !in 0..4999)return reject(StrategyActionRejection.LOCATION)
        val live=stocks.strategic.operations.filter {it.live}
        val ownLive=live.filter {it.country==country}
        val now=s.gameMillis/1000
        val actionable=ownLive.filter { plan ->
            plan.issuedAt?.let { now < OperationPlanRules.endGameSeconds(it) } != false
        }
        var plans=stocks.strategic.operations
        val cost:Long
        when(r.command) {
            22 -> {
                val purpose=StrategicPurpose.entries.getOrNull(r.value) ?: return reject(StrategyActionRejection.COMMAND)
                if(r.amount !in 1..65535 || r.argument !in 0..127 || purpose==StrategicPurpose.SWEEP && force!=StrategicForce.SOLO)
                    return reject(StrategyActionRejection.COMMAND)
                if(!target(galaxy,stocks,r.target,country,purpose))return reject(StrategyActionRejection.LOCATION)
                val committed=ownLive.sumOf {it.units}
                val effective=stocks.outfits.values.filter {TacticalAuthority.camp(it,stocks)==regular}.sumOf(::shipUnits)+sole(r,stocks).size
                if(OperationPlanLimits.canPlan(ownLive.filter {it.card==r.card}.map {it.grid.toString()}.toSet(),
                    r.target.toString(),r.amount.toInt(),committed,effective)!=OperationPlanVerdict.ALLOWED)return reject(StrategyActionRejection.MAXIMUM)
                val activation=now+r.argument*86400L
                if(activation>0xffffffffL-OperationPlanRules.DURATION_GAME_SECONDS)return reject(StrategyActionRejection.MAXIMUM)
                val id=(plans.maxOfOrNull {it.id} ?: 0).toLong()+1
                if(id>Int.MAX_VALUE || live.size>=128)return reject(StrategyActionRejection.MAXIMUM)
                val plan=StrategicOperation(id.toInt(),r.characterId,r.card,country,r.target,force,purpose,r.amount.toInt(),activation,
                    defended=if(purpose==StrategicPurpose.DEFEND)owned(stocks,bases(galaxy,r.target),country) else emptySet())
                plans=(plans.filter {it.live}+plans.filter {!it.live}.takeLast(127-live.size))+plan
                cost=(1280L/(r.argument+1)).coerceIn(10,1280)
            }
            23 -> {
                val plan=actionable.singleOrNull {it.card==r.card && it.planner==r.characterId && it.grid==r.target}
                    ?: return reject(StrategyActionRejection.AUTHORITY)
                plans=plans.map {if(it.id==plan.id)it.copy(state=StrategicPlanState.WITHDRAWN) else it}
                cost=(plan.units*5L).coerceIn(5,320)
            }
            24 -> {
                val plan=actionable.singleOrNull {it.force==force && it.grid==r.target}
                    ?: return reject(StrategyActionRejection.COMMAND)
                if(now<plan.activatesAt)return reject(StrategyActionRejection.BUSY)
                if(!target(galaxy,stocks,plan.grid,country,plan.purpose) && plan.state==StrategicPlanState.PLANNED)
                    return reject(StrategyActionRejection.LOCATION)
                var outfits=plan.outfits;var characters=plan.characters
                if(force==StrategicForce.SOLO) {
                    if(r.base in characters || ownLive.any {it.id!=plan.id && r.base in it.characters})return reject(StrategyActionRejection.BUSY)
                    val solo=sole(r,stocks).singleOrNull {it.characterId==r.base} ?: return reject(StrategyActionRejection.AUTHORITY)
                    characters=characters+solo.characterId
                } else {
                    val outfit=stocks.outfits[r.base] ?: return reject(StrategyActionRejection.AUTHORITY)
                    if(TacticalAuthority.camp(outfit,stocks)!=regular || !fits(force,outfit.kind) || shipUnits(outfit)==0)
                        return reject(StrategyActionRejection.AUTHORITY)
                    if(outfit.id in outfits || ownLive.any {it.id!=plan.id && outfit.id in it.outfits})return reject(StrategyActionRejection.BUSY)
                    if(outfit.activeShips?.values.orEmpty().any {it.tactical!=null || it.support!=null} ||
                        outfit.activeGround?.values.orEmpty().any {it.order!=null})return reject(StrategyActionRejection.BUSY)
                    outfits=outfits+outfit.id
                    characters=characters+outfit.members()
                }
                val count=if(force==StrategicForce.SOLO)characters.count {!CoupActions.rebel(stocks,it,country)}
                    else outfits.sumOf {stocks.outfits[it]?.takeIf {o->TacticalAuthority.camp(o,stocks)==regular}?.let(::shipUnits) ?: 0}
                if(count>plan.units)return reject(StrategyActionRejection.MAXIMUM)
                val assigned=plan.copy(outfits=outfits,characters=characters,
                    state=if(plan.state==StrategicPlanState.PLANNED)StrategicPlanState.ISSUED else plan.state,
                    issuedAt=plan.issuedAt ?: now,issuer=plan.issuer.takeIf {it>0} ?: r.characterId)
                val updated=if(assigned.state==StrategicPlanState.ISSUED && reached(assigned,galaxy,stocks,r.navigators.values))
                    assigned.copy(state=StrategicPlanState.STARTED) else assigned
                plans=plans.map {if(it.id==plan.id)updated else it}
                cost=count.toLong().coerceIn(1,320)
            }
            else->return reject(StrategyActionRejection.COMMAND)
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        val next=stocks.copy(strategic=stocks.strategic.copy(operations=plans))
        return StrategyActionResult(p.paidWith(paid.balance),next,changed=true)
    }
    fun startReachedSweeps(stocks: PlanetStocks, seconds: Long, navigators: Collection<Navigator>,
        eliminated: Set<CountryId>): PlanetStocks {
        val operations = stocks.strategic.operations
        val updated = operations.map { plan ->
            val issued = plan.issuedAt
            if (plan.purpose == StrategicPurpose.SWEEP && plan.state == StrategicPlanState.ISSUED &&
                plan.country !in eliminated && issued != null && seconds in issued until OperationPlanRules.endGameSeconds(issued) &&
                soloReached(plan, stocks, navigators)) {
                plan.copy(state = StrategicPlanState.STARTED)
            } else plan
        }
        return if (updated == operations) stocks else stocks.copy(strategic = stocks.strategic.copy(operations = updated))
    }

    fun awardSweep(stocks:PlanetStocks,shooter:Navigator,grid:Int,seconds:Long,destroyed:Int):PlanetStocks {
        if(destroyed<=0 || CoupActions.rebel(stocks,shooter.characterId,shooter.country))return stocks
        if(stocks.strategic.operations.none {plan->
            val issued=plan.issuedAt
            plan.state==StrategicPlanState.STARTED && plan.purpose==StrategicPurpose.SWEEP && plan.country==shooter.country &&
                shooter.characterId in plan.characters && issued!=null && seconds in issued until OperationPlanRules.endGameSeconds(issued) &&
                WarpCourse.cruiseCost(grid,plan.grid)<=400
        })return stocks
        if(stocks.outfits.values.any {shooter.characterId in it.members()})return stocks
        val people=stocks.strategic.people
        if(shooter.characterId !in people && people.size>=4096)return stocks
        val person=people[shooter.characterId] ?: StrategicPerson()
        // One merit per boat is the replacement value. The manual specifies per-boat credit without a value.
        val merit=minOf(0xffffffffL,person.merit+destroyed)
        if(merit==person.merit)return stocks
        return stocks.copy(strategic=stocks.strategic.copy(people=people+(shooter.characterId to person.copy(merit=merit))))
    }

    private fun reached(plan:StrategicOperation,galaxy:Galaxy,stocks:PlanetStocks,navigators:Collection<Navigator>?):Boolean {
        val regular=Camp(plan.country)
        return plan.outfits.any {stocks.outfits[it]?.let {o->TacticalAuthority.camp(o,stocks)==regular &&
            o.positionIn(galaxy).grid==plan.grid && shipUnits(o)>0}==true} ||
            (plan.force==StrategicForce.SOLO && navigators.orEmpty().any {it.generation==stocks.generation && TacticalAuthority.camp(it,stocks)==regular &&
                it.characterId in plan.characters && it.grid==plan.grid && hasLiveFlagship(it,stocks)})
    }

    fun recordDefenseOccupation(galaxy: Galaxy, stocks: PlanetStocks, navigators: Collection<Navigator>,
        base: GalaxyBase, loser: Camp?, winner: Camp, now: Long): PlanetStocks {
        val plans = stocks.strategic.operations
        val updated = plans.map { plan ->
            val issued = plan.issuedAt
            if (plan.defenseBreached || plan.purpose != StrategicPurpose.DEFEND || issued == null ||
                now !in issued..OperationPlanRules.endGameSeconds(issued) ||
                loser != Camp(plan.country) || winner == loser || base.grid != plan.grid || base.id !in plan.defended ||
                (plan.state != StrategicPlanState.STARTED &&
                    !(plan.state == StrategicPlanState.ISSUED && reached(plan, galaxy, stocks, navigators)))) {
                plan
            } else {
                plan.copy(state = StrategicPlanState.STARTED, defenseBreached = true)
            }
        }
        return if (updated == plans) stocks else stocks.copy(strategic = stocks.strategic.copy(operations = updated))
    }

    fun settle(galaxy:Galaxy,stocks:PlanetStocks,seconds:Long,navigators:Collection<Navigator>?=null,
        eliminated: Set<CountryId> = emptySet()):PlanetStocks {
        if(stocks.strategic.operations.none {it.live})return stocks
        var people=stocks.strategic.people
        val plans=stocks.strategic.operations.map {plan->
            if(!plan.live)return@map plan
            if (plan.country in eliminated) return@map plan.copy(state = StrategicPlanState.WITHDRAWN)
            val ids=bases(galaxy,plan.grid);val ownership=owned(stocks,ids,plan.country)
            // Before issuance, an occupation still needs an enemy-only target. After
            // issuance, capturing a base is progress and must survive until the result.
            val lost=when(plan.purpose) {
                StrategicPurpose.OCCUPY->if(plan.state==StrategicPlanState.PLANNED)
                    !target(galaxy,stocks,plan.grid,plan.country,plan.purpose)
                    else ids.isEmpty() || ids.any {stocks.bases[it]?.let {b->b.country==null || b.camp!=0}!=false}
                StrategicPurpose.DEFEND->ownership.isEmpty()
                StrategicPurpose.SWEEP->plan.state==StrategicPlanState.PLANNED &&
                    !target(galaxy,stocks,plan.grid,plan.country,plan.purpose)
            }
            if(lost)return@map plan.copy(state=StrategicPlanState.WITHDRAWN)
            val issued=plan.issuedAt ?: return@map plan
            // Startup may restore stock before navigation; missing arrival context must not erase a solo reward.
            if(plan.force==StrategicForce.SOLO && plan.state!=StrategicPlanState.STARTED && navigators==null)return@map plan
            val reached=reached(plan,galaxy,stocks,navigators)
            if(seconds<issued+OperationPlanRules.DURATION_GAME_SECONDS)
                return@map if(reached && plan.state==StrategicPlanState.ISSUED)plan.copy(state=StrategicPlanState.STARTED) else plan
            val outcome=when(plan.purpose) {
                StrategicPurpose.OCCUPY->when {ownership.size==ids.size->OperationOutcome.FULL_SUCCESS;ownership.isEmpty()->OperationOutcome.FAILURE;else->OperationOutcome.PARTIAL_SUCCESS}
                StrategicPurpose.DEFEND->if(!plan.defenseBreached && ownership.containsAll(plan.defended))
                    OperationOutcome.FULL_SUCCESS else OperationOutcome.PARTIAL_SUCCESS
                StrategicPurpose.SWEEP->null // Actual ship losses pay individually at the time of the hit.
            }
            val bonus=if(plan.state==StrategicPlanState.STARTED || reached)when(outcome) {
                OperationOutcome.FULL_SUCCESS->100;OperationOutcome.PARTIAL_SUCCESS->50;OperationOutcome.FAILURE,null->0
            } else 0
            if(bonus>0)for(id in plan.characters) {
                if(id !in people && people.size>=4096)continue
                val person=people[id] ?: StrategicPerson()
                people=people+(id to person.copy(merit=minOf(0xffffffffL,person.merit+bonus)))
            }
            plan.copy(state=StrategicPlanState.ENDED,outcome=outcome)
        }
        return stocks.copy(strategic=stocks.strategic.copy(people=people,operations=plans))
    }
}
