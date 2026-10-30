package org.logh7.engine

import org.logh7.engine.strategy.*

/** IDs are never reused after dissolution, including the largest legacy ID. Author: Peppone Choi. */
data class OutfitSequence(val nextOutfit:Int=1,val nextWarehouse:Int=1) {
    init {require(nextOutfit>0 && nextWarehouse>0)}
}
enum class FormationPhase { BEGIN, SET_UNIT, SET_SUPPLIES, COMMIT, CANCEL, REFRESH, DELETE }
data class OutfitFormationRequest(val identity:StrategyActionRequest,val phase:FormationPhase,val base:Int,
    val kind:OutfitKind,val target:Int=0,val unit:Int=0,val amount:Int=0)
data class FormationStock(val code:Int,val available:Int,val selected:Int=0)
data class OutfitFormationResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:StrategyActionRejection?=null,
    val changed:Boolean=false,val lease:Int=0,val outfit:Int=0,val base:Int=0,val items:List<FormationStock> = emptyList(),
    val supplies:Int=0,val crew:Int=0,val affected:Set<Int> = emptySet(),val recovery:NavigationResult?=null)

/** Native card descriptions constmsg6: 24 fleet,25 auxiliary;164 ship formations.
 * Alliance ground authority152, local presence, 255 numbers/type, ascending crew grades,
 * returning inventory and damaged-unit restrictions are evidence:guess. W70/71 fixes
 * MCP320/160, unit formation and stationed dissolution. Author: Peppone Choi. */
object OutfitFormationPolicy {
    fun kinds(card:Int):Set<OutfitKind> = when(card) {
        24->setOf(OutfitKind.FLEET)
        25->setOf(OutfitKind.TRANSPORT,OutfitKind.PATROL,OutfitKind.GROUND)
        164->setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT,OutfitKind.PATROL)
        152->setOf(OutfitKind.GROUND)
        else->emptySet()
    }
    fun code(stack:UnitStack,troop:Boolean)=((if(troop)1000+stack.kind else stack.kind)*4)+stack.level+1
    fun stack(code:Int):Pair<Boolean,UnitStack> {
        require(code>0);val raw=(code-1)/4;val level=(code-1)%4
        require(raw in 0..199 || raw in 1000..1015)
        return (raw>=1000) to UnitStack(if(raw>=1000)raw-1000 else raw,level)
    }
    fun available(stocks:PlanetStocks,base:Int,power:Int,kind:OutfitKind):Map<Int,Int> {
        val b=stocks.bases.getValue(base)
        return buildMap {
            for(stack in b.units.ships.keys+b.spareShips.values.keys) {
                if(ProductionUnits.definitions.none {it.ship && it.kind==stack.kind && it.power==power})continue
                val count=((b.units.ships[stack] ?: 0)+(if(stack in b.spareShips.values)1 else 0)).coerceAtMost(65535)
                val allowed=runCatching {OutfitShipLimits.validate(kind,UnitInventory(mapOf(stack to 1)))}.isSuccess
                if(allowed)put(code(stack,false),count)
            }
            for((stack,count) in b.units.troops)if(UnitSpecifications.crews.any {it.kind==stack.kind && it.power==power && it.type!=3})
                put(code(stack,true),count)
        }
    }
    fun authority(galaxy:Galaxy,r:OutfitFormationRequest,p:Navigator,stocks:PlanetStocks):StrategyActionRejection? {
        val role=OrganizationOffices.of(r.identity.card) ?: return StrategyActionRejection.CARD
        val power=if(p.power==Power.EMPIRE)2 else 3
        if(role.power!=power || role.camp!=0 || r.kind !in kinds(role.card))return StrategyActionRejection.CARD
        val rank=stocks.strategic.people[p.characterId]?.rank ?: r.identity.rank
        if(rank !in role.ranks || OrganizationalOfficePolicy(galaxy).holders(stocks,role.card).none {it.character==p.characterId})
            return StrategyActionRejection.AUTHORITY
        val base=stocks.bases[r.base] ?: return StrategyActionRejection.LOCATION
        if(base.power!=power || base.camp!=0 || p.baseId!=r.base || p.mode !in 1..4 ||
            galaxy.bases.singleOrNull {it.id==r.base}?.grid!=p.grid)return StrategyActionRejection.LOCATION
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null)return StrategyActionRejection.BUSY
        return null
    }
    private fun sequence(stocks:PlanetStocks):OutfitSequence {
        val nextOutfit=maxOf(stocks.outfitSequence.nextOutfit.toLong(),(stocks.outfits.keys.maxOrNull() ?: 0).toLong()+1)
        val nextWarehouse=maxOf(stocks.outfitSequence.nextWarehouse.toLong(),(stocks.warehouses.keys.maxOrNull() ?: 0).toLong()+1)
        require(nextOutfit<=Int.MAX_VALUE && nextWarehouse<=Int.MAX_VALUE)
        return OutfitSequence(nextOutfit.toInt(),nextWarehouse.toInt())
    }
    internal fun create(galaxy:Galaxy,r:OutfitFormationRequest,p:Navigator,stocks:PlanetStocks,selected:Map<Int,Int>,supplies:Int):OutfitFormationResult {
        fun reject(reason:StrategyActionRejection)=OutfitFormationResult(p,stocks,reason,base=r.base)
        if(selected.isEmpty())return reject(StrategyActionRejection.TROOPS)
        val power=if(p.power==Power.EMPIRE)2 else 3
        val source=stocks.bases.getValue(r.base)
        if(supplies !in 0..source.supplies)return reject(StrategyActionRejection.RESOURCE)
        val available=available(stocks,r.base,power,r.kind)
        if(selected.any {(code,n)->n<=0 || n>(available[code] ?: 0)})return reject(StrategyActionRejection.RESOURCE)
        if(stocks.outfits.size>=1000)return reject(StrategyActionRejection.MAXIMUM)
        if(stocks.outfits.values.count {it.positionIn(galaxy).grid==p.grid}>=300)return reject(StrategyActionRejection.MAXIMUM)
        val number=(1..255).firstOrNull {n->stocks.outfits.values.none {it.power==power && it.kind==r.kind && it.number==n}}
            ?: return reject(StrategyActionRejection.MAXIMUM)
        val ids=sequence(stocks);val id=ids.nextOutfit
        if(id==Int.MAX_VALUE)return reject(StrategyActionRejection.MAXIMUM)
        val shared=r.kind.warehouseKind==OutfitKind.PATROL
        val sharedWarehouse=if(shared)stocks.warehouses.values.singleOrNull {it.baseId==r.base && it.power==power &&
            it.kind.warehouseKind==OutfitKind.PATROL && it.outfitId==0} else null
        if(sharedWarehouse==null && stocks.warehouses.size>=1000)return reject(StrategyActionRejection.MAXIMUM)
        val warehouse=sharedWarehouse?.id ?: ids.nextWarehouse
        if(sharedWarehouse==null && warehouse==Int.MAX_VALUE)return reject(StrategyActionRejection.MAXIMUM)
        val empty=OutfitStock(id,power,r.kind,r.base,warehouse,number=number,position=OutfitPosition(p.grid,r.base))
        val shipKinds=ProductionUnits.definitions.filter {it.ship && it.power==power}.map {it.kind}.toSet()
        val troopKinds=UnitSpecifications.crews.filter {it.power==power}.map {it.kind}.toSet()
        val temporary=OutfitWarehouse(warehouse,power,r.kind.warehouseKind,r.base,if(shared)0 else id,
            UnitInventory(source.units.ships.filterKeys {it.kind in shipKinds},source.units.troops.filterKeys {it.kind in troopKinds}),
            spareShips=SpareShipBoats(source.spareShips.values.filterKeys {it.kind in shipKinds}))
        var prepared=stocks.copy(outfits=stocks.outfits+(id to empty),warehouses=stocks.warehouses+(warehouse to temporary))
        val ships=linkedMapOf<UnitStack,Int>();val troops=linkedMapOf<UnitStack,Int>()
        for((code,n) in selected) {val (troop,stack)=stack(code);if(troop)troops[stack]=n else ships[stack]=n}
        if(r.kind!=OutfitKind.GROUND && ships.isEmpty())return reject(StrategyActionRejection.TROOPS)
        prepared=ReplacementBoats.reorganize(prepared,id,UnitTransfer(ships,troops))
        var outfit=prepared.outfits.getValue(id)
        val needed=ReplacementBoats.crewRequired(outfit)
        var missing=needed
        val crew=linkedMapOf<UnitStack,Int>()
        val remaining=prepared.warehouses.getValue(warehouse)
        for((stack,n) in remaining.units.troops.entries.filter {e->UnitSpecifications.crews.any {
            it.kind==e.key.kind && it.power==power && it.type==3}}.sortedBy {it.key.level}) {
            val take=minOf(missing,n.toLong()).toInt();if(take>0)crew[stack]=take;missing-=take
        }
        if(missing>0)return reject(StrategyActionRejection.CREW)
        if(crew.isNotEmpty())prepared=ReplacementBoats.reorganize(prepared,id,UnitTransfer(troops=crew))
        outfit=prepared.outfits.getValue(id)
        if(supplies>OutfitSupplies.capacity(outfit))return reject(StrategyActionRejection.RESOURCE)
        outfit=outfit.copy(supplies=supplies,activeGround=outfit.activeGround?.let {old->
            ActiveGroundUnits(old.values.map {it.copy(carrier=0,base=r.base,origin=0)})})
        if(outfit.activeShips!!.values.isNotEmpty())outfit=OutfitSupplies.withBalances(outfit,OutfitSupplies.distribution(outfit))
        val consumed=ReplacementBoats.moveStored(source.units,source.spareShips,UnitInventory(),SpareShipBoats(),
            UnitTransfer(ships,troops.toMutableMap().apply {for((stack,n) in crew)this[stack]=Math.addExact(this[stack] ?: 0,n)}))
        prepared=prepared.copy(bases=prepared.bases+(r.base to source.copy(supplies=source.supplies-supplies,
            units=consumed.source,spareShips=consumed.sourceSpare)),
            outfits=prepared.outfits+(id to outfit),warehouses=prepared.warehouses+(warehouse to
                (sharedWarehouse ?: temporary.copy(units=UnitInventory(),spareShips=SpareShipBoats()))),deploymentInitialized=true,
            outfitSequence=OutfitSequence(id+1,if(sharedWarehouse==null)warehouse+1 else ids.nextWarehouse))
        val forces=GridForces.collect(galaxy,stocks.generation,r.identity.navigators.values,stocks)
        val count=GridForces.units(outfit)
        if(count !in 1..300 || GridEntryRules.evaluate(GridForces.state(galaxy,p.grid,forces),
                GridEntry(Camp(p.power,false),count.toInt(),false))!=GridEntryVerdict.ALLOWED)return reject(StrategyActionRejection.MAXIMUM)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),320,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return OutfitFormationResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),prepared,
            changed=true,outfit=id,base=r.base,crew=needed.toInt())
    }
    internal fun dissolve(galaxy:Galaxy,r:OutfitFormationRequest,p:Navigator,stocks:PlanetStocks):OutfitFormationResult {
        fun reject(reason:StrategyActionRejection)=OutfitFormationResult(p,stocks,reason,base=r.base)
        val outfit=stocks.outfits[r.target] ?: return reject(StrategyActionRejection.TARGET)
        val power=if(p.power==Power.EMPIRE)2 else 3
        if(outfit.power!=power || outfit.kind!=r.kind)return reject(StrategyActionRejection.AUTHORITY)
        if(outfit.positionIn(galaxy)!=OutfitPosition(p.grid,r.base))return reject(StrategyActionRejection.LOCATION)
        val ships=outfit.activeShips ?: return reject(StrategyActionRejection.TROOPS)
        val ground=outfit.activeGround?.values.orEmpty()
        val removed=(ships.values.map {it.id}+ground.map {it.id}).toSet()
        if(ships.values.any {it.damaged>0 || it.support!=null || it.shot!=null || it.attack!=null || it.reload!=null ||
                it.tactical?.retreat!=null || it.posture?.order!=null} || ground.any {it.order!=null || it.strength<1000 ||
                it.base==0 && it.carrier==0 || it.base>0 && it.base!=r.base} || outfit.strategy?.destination!=null ||
            stocks.strategic.suppressions.any {it.outfit==outfit.id} || stocks.civic.active.any {it.outfit==outfit.id} ||
            stocks.strategic.operations.any {it.live && outfit.id in it.outfits} ||
            stocks.tacticalCommands.orders.any {it.command.units.any {id->id in removed} || it.command.target in removed} ||
            stocks.tacticalCommands.air.values.any {it.source in removed || it.target in removed})return reject(StrategyActionRejection.BUSY)
        val members=outfit.members().toSet()+stocks.strategic.offices.filter {
            OrganizationOffices.of(it.card)?.scope==OfficeScope.OUTFIT && it.seat==outfit.id}.map {it.character}
        if(r.identity.navigators.values.any {it.characterId in members && (it.cpPaused || it.dockTarget!=null || it.fuelReadyAt!=null)})
            return reject(StrategyActionRejection.BUSY)
        val warehouse=stocks.warehouses.getValue(outfit.warehouseId)
        val private=warehouse.outfitId>0
        val home=stocks.bases.getValue(outfit.baseId)
        if(private && (home.power!=power || home.camp!=0))return reject(StrategyActionRejection.LOCATION)
        val bases=stocks.bases.toMutableMap()
        fun add(baseId:Int,units:UnitInventory,spare:SpareShipBoats,supply:Int,food:Int=0,mineral:Int=0) {
            val b=bases.getValue(baseId)
            val sums=b.units.ships.toMutableMap();val remainders=b.spareShips.values.toMutableMap()
            for(stack in (units.ships.keys+spare.values.keys)) {
                val size=UnitSpecifications.ships.single {it.kind==stack.kind}.number
                val boats=(sums[stack] ?: 0).toLong()*size+(remainders[stack] ?: 0)+(units.ships[stack] ?: 0).toLong()*size+(spare.values[stack] ?: 0)
                if(boats/size==0L)sums.remove(stack) else sums[stack]=Math.toIntExact(boats/size)
                if(boats%size==0L)remainders.remove(stack) else remainders[stack]=(boats%size).toInt()
            }
            val troops=b.units.troops.toMutableMap();for((stack,n) in units.troops)troops[stack]=Math.addExact(troops[stack] ?: 0,n)
            val next=b.copy(units=UnitInventory(sums,troops,b.units.work),spareShips=SpareShipBoats(remainders),
                supplies=Math.addExact(b.supplies,supply),food=Math.addExact(b.food,food),mineral=Math.addExact(b.mineral,mineral))
            StockAssignment.validateReply(next.units,next.spareShips);bases[baseId]=next
        }
        val full=linkedMapOf<UnitStack,Int>();val spare=linkedMapOf<UnitStack,Int>()
        for((stack,units) in ships.values.groupBy {it.stack}) {
            val size=UnitSpecifications.ships.single {it.kind==stack.kind}.number
            val boats=units.sumOf {it.boats.toLong()}
            if(boats/size>0)full[stack]=Math.toIntExact(boats/size)
            if(boats%size>0)spare[stack]=(boats%size).toInt()
        }
        add(r.base,UnitInventory(full,outfit.units.troops),SpareShipBoats(spare),outfit.supplies)
        if(private)add(outfit.baseId,warehouse.units,warehouse.spareShips,warehouse.supplies,warehouse.food,warehouse.mineral)
        val ids=sequence(stocks)
        val affected=members
        val next=stocks.copy(bases=bases,outfits=stocks.outfits-outfit.id,warehouses=if(private)stocks.warehouses-warehouse.id else stocks.warehouses,
            strategic=stocks.strategic.copy(offices=stocks.strategic.offices.filterNot {OrganizationOffices.of(it.card)?.scope==OfficeScope.OUTFIT && it.seat==outfit.id}),
            tacticalCommands=stocks.tacticalCommands.copy(authority=stocks.tacticalCommands.authority-removed,
                morale=stocks.tacticalCommands.morale-removed,formations=stocks.tacticalCommands.formations-removed,
                formationLeaders=stocks.tacticalCommands.formationLeaders.filter {(unit,leader)->unit !in removed && leader !in removed}),
            outfitSequence=ids,deploymentInitialized=true)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),160,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(StrategyActionRejection.CP)
        return OutfitFormationResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,
            changed=true,outfit=outfit.id,base=r.base,affected=affected)
    }
}
