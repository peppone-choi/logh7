package org.logh7.engine

/** Actor-owned soft editing leases. No stock/CP changes before COMMIT; disconnect,
 * session change or 120 seconds of inactivity releases the lease. Author: 최병호. */
internal class OutfitFormationService(private val clock:()->Long) {
    private data class Draft(val id:Int,val request:OutfitFormationRequest,val rows:Map<Int,Int>,val supplies:Int,val expires:Long)
    private var sequence=0
    private val drafts=linkedMapOf<String,Draft>()
    private fun prune(s:SessionState) {
        val now=clock()
        drafts.entries.removeIf {(_,d)->d.expires<=now || d.request.identity.generation!=s.generation ||
            s.participants[d.request.identity.account]?.let {!it.online || it.connection!=d.request.identity.connection}!=false}
    }
    fun blocksBase(s:SessionState,base:Int):Boolean {prune(s);return drafts.values.any {it.request.base==base}}
    fun complete(r:OutfitFormationRequest) {if(r.phase==FormationPhase.COMMIT)drafts.remove(r.identity.account)}
    fun evaluate(galaxy:Galaxy,request:OutfitFormationRequest,s:SessionState,p:Navigator?,stocks:PlanetStocks):OutfitFormationResult {
        prune(s)
        val i=request.identity
        fun reject(reason:StrategyActionRejection)=OutfitFormationResult(p,stocks,reason,base=request.base)
        if(s.ended!=null || s.generation!=i.generation || stocks.generation!=i.generation ||
            s.participants[i.account]?.let {it.online && it.connection==i.connection && it.power==i.power}!=true)
            return reject(StrategyActionRejection.SESSION)
        if(p==null || p.characterId!=i.characterId || p.power!=i.power || p.generation!=i.generation)return reject(StrategyActionRejection.IDENTITY)
        val r=request.copy(base=request.base.takeIf {it>0} ?: p.baseId)
        val old=drafts[i.account]
        // A moved character may always cancel its own correctly identified draft.
        if(r.phase==FormationPhase.CANCEL) {
            if(i.command!=25)return reject(StrategyActionRejection.COMMAND)
            if(old==null || old.id!=r.target)return reject(StrategyActionRejection.TARGET)
            drafts.remove(i.account);return OutfitFormationResult(p,stocks,base=old.request.base)
        }
        OutfitFormationPolicy.authority(galaxy,r,p,stocks)?.let {return reject(it)}
        if(i.command==26) {
            if(r.phase !in setOf(FormationPhase.BEGIN,FormationPhase.DELETE))return reject(StrategyActionRejection.COMMAND)
            if(r.phase==FormationPhase.BEGIN) {
                val rows=stocks.outfits.values.filter {it.power==if(i.power==Power.EMPIRE)2 else 3}.filter {
                    it.kind==r.kind && it.positionIn(galaxy)==OutfitPosition(p.grid,r.base)}.sortedBy {it.id}
                    .map {FormationStock(it.id,GridForces.units(it).coerceAtMost(65535).toInt())}
                if(rows.size>200)return reject(StrategyActionRejection.MAXIMUM)
                return OutfitFormationResult(p,stocks,base=r.base,items=rows)
            }
            if(drafts.values.any {it.request.base==r.base || it.request.base==stocks.outfits[r.target]?.baseId})return reject(StrategyActionRejection.BUSY)
            return guarded(p,stocks,r.base) {OutfitFormationPolicy.dissolve(galaxy,r,p,stocks)}
        }
        if(i.command!=25 || r.phase==FormationPhase.DELETE)return reject(StrategyActionRejection.COMMAND)
        if(r.phase==FormationPhase.BEGIN) {
            if(old!=null)return if(old.request.base==r.base && old.request.kind==r.kind && old.request.identity.card==i.card)
                view(p,stocks,old) else reject(StrategyActionRejection.BUSY)
            if(drafts.values.any {it.request.base==r.base})return reject(StrategyActionRejection.BUSY)
            if(sequence==Int.MAX_VALUE)return reject(StrategyActionRejection.MAXIMUM)
            val draft=Draft(++sequence,r,emptyMap(),0,Math.addExact(clock(),120_000))
            drafts[i.account]=draft;return view(p,stocks,draft)
        }
        if(old==null || old.id!=r.target || old.request.base!=r.base || old.request.kind!=r.kind || old.request.identity.card!=i.card)
            return reject(StrategyActionRejection.TARGET)
        val available=OutfitFormationPolicy.available(stocks,r.base,if(i.power==Power.EMPIRE)2 else 3,r.kind)
        var draft=old.copy(expires=Math.addExact(clock(),120_000))
        when(r.phase) {
            FormationPhase.SET_UNIT -> {
                if(r.amount !in 0..65535 || r.amount>(available[r.unit] ?: 0) || r.amount>0 && r.unit !in available)
                    return reject(StrategyActionRejection.RESOURCE)
                draft=draft.copy(rows=if(r.amount==0)draft.rows-r.unit else draft.rows+(r.unit to r.amount))
            }
            FormationPhase.SET_SUPPLIES -> {
                if(r.unit !in 0..stocks.bases.getValue(r.base).supplies)return reject(StrategyActionRejection.RESOURCE)
                draft=draft.copy(supplies=r.unit)
            }
            FormationPhase.COMMIT -> return guarded(p,stocks,r.base) {OutfitFormationPolicy.create(galaxy,r,p,stocks,draft.rows,draft.supplies)}
                .copy(lease=draft.id)
            FormationPhase.REFRESH -> Unit
            else -> return reject(StrategyActionRejection.COMMAND)
        }
        drafts[i.account]=draft;return view(p,stocks,draft)
    }
    private fun view(p:Navigator,stocks:PlanetStocks,d:Draft):OutfitFormationResult {
        val power=if(p.power==Power.EMPIRE)2 else 3
        val available=OutfitFormationPolicy.available(stocks,d.request.base,power,d.request.kind)
        val rows=(available.keys+d.rows.keys).sorted().map {FormationStock(it,available[it] ?: 0,d.rows[it] ?: 0)}
        return OutfitFormationResult(p,stocks,lease=d.id,base=d.request.base,items=rows,supplies=d.supplies)
    }
    private inline fun guarded(p:Navigator,stocks:PlanetStocks,base:Int,block:()->OutfitFormationResult):OutfitFormationResult = try {block()}
        catch(_:IllegalArgumentException){OutfitFormationResult(p,stocks,StrategyActionRejection.MAXIMUM,base=base)}
        catch(_:ArithmeticException){OutfitFormationResult(p,stocks,StrategyActionRejection.MAXIMUM,base=base)}
}
