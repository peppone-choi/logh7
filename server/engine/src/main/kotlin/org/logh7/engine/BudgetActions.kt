package org.logh7.engine

import org.logh7.engine.strategy.*

enum class BudgetPhase { QUOTED, COMPLETED, CANCELLED }
data class BudgetQuote(val id:Int,val character:Int,val command:Int,val card:Int,val power:Int,val base:Int,
    val rate:Int,val beforeRate:Int,val national:Long,val local:Long,val amount:Long,val createdAt:Long,
    val expiresAt:Long,val phase:BudgetPhase=BudgetPhase.QUOTED) {
    init {require(id in 1 until Int.MAX_VALUE && character>0 && command in setOf(16,18) &&
        card==if(power==2)8 else 136);require(power in 2..3 && base in 1..65535 && rate in 0..100 && beforeRate in 0..100 &&
        national>=0 && local>=0 && amount in 0..national && createdAt in 0..0xffffffffL && expiresAt in createdAt..0xffffffffL &&
        (if(command==16)amount==0L else rate>0 && amount>0 && amount==BudgetActions.percentage(national,rate) && local<=Long.MAX_VALUE-amount))}
}
data class BudgetState(val remittanceRates:Map<Int,Int> = emptyMap(),val nextId:Int=1,val quotes:Map<Int,BudgetQuote> = emptyMap()) {
    init {require(remittanceRates.size<=350 && remittanceRates.all {(base,rate)->base in 1..65535 && rate in 0..100} && nextId>0 &&
        quotes.size<=256 && quotes.all {(character,q)->character==q.character && q.id<nextId} &&
        quotes.values.map {it.id}.distinct().size==quotes.size)}
    fun detached()=copy(remittanceRates=java.util.Collections.unmodifiableMap(LinkedHashMap(remittanceRates)),
        quotes=java.util.Collections.unmodifiableMap(LinkedHashMap(quotes)))
}
data class BudgetRequest(val identity:StrategyActionRequest,val phase:Int=0,val base:Int=0,val rate:Int=0,val quote:Int=0)
enum class BudgetRejection { SESSION, IDENTITY, COMMAND, CARD, AUTHORITY, BASE, LOCATION, BUSY, CP, RESOURCE, STALE, MAXIMUM, STORAGE }
data class BudgetView(val base:Int,val power:Int,val national:Long,val local:Long,val remittance:Int,val quote:BudgetQuote?)
data class BudgetResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:BudgetRejection?=null,
    val changed:Boolean=false,val view:BudgetView?=null,val recovery:NavigationResult?=null)
data class BudgetChange(val stocks:PlanetStocks,val bases:Set<Int>,val defaultRate:Int)

/** Manual W73/native constmsg: MCP320, immediate, aid is a budget percentage.
 * Finance-only authority, capital presence, per-base rates and durable quote/confirmation
 * are explicit restoration policy. No private account or military supply is money. Author: Peppone Choi. */
object BudgetActions {
    val cards=setOf(8,136)
    const val QUOTE_SECONDS=86400L
    fun percentage(amount:Long,rate:Int):Long {require(amount>=0 && rate in 0..100);return amount/100*rate+amount%100*rate/100}
    fun rate(stocks:PlanetStocks,base:Int,default:Int)=stocks.budget.remittanceRates[base] ?: default
    fun evaluate(g:Galaxy,r:BudgetRequest,session:SessionState,p:Navigator?,s:PlanetStocks,defaultRate:Int=50):BudgetResult {
        val i=r.identity
        fun reject(reason:BudgetRejection,view:BudgetView?=null)=BudgetResult(p,s,reason,view=view)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation ||
            member==null || !member.online || member.connection!=i.connection)return reject(BudgetRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(BudgetRejection.IDENTITY)
        if(i.command !in setOf(16,18) || r.phase !in 0..3 || r.base !in 0..65535 || r.rate !in 0..100 || r.quote<0 ||
            (r.phase in 0..1 && r.quote!=0) || (r.phase in 2..3 && r.quote==0) ||
            (r.phase==0 && r.rate!=0) || (r.phase!=0 && r.base==0))return reject(BudgetRejection.COMMAND)
        val power=if(p.power==Power.EMPIRE)2 else 3
        if(i.card!=if(power==2)8 else 136)return reject(BudgetRejection.CARD)
        val role=OrganizationOffices.all.getValue(i.card);val office=OrganizationalOfficePolicy(g)
        val rank=s.strategic.people[i.characterId]?.rank ?: i.rank
        val holding=office.holders(s,i.card).singleOrNull {it.character==i.characterId}
        if(holding==null || rank !in role.ranks)return reject(BudgetRejection.AUTHORITY)
        val baseId=if(r.base>0)r.base else p.baseId.takeIf {s.bases[it]?.let {b->b.power==power && b.camp==0}==true} ?: holding.seat
        val b=s.bases[baseId] ?: return reject(BudgetRejection.BASE)
        if(b.power!=power || b.camp!=0)return reject(BudgetRejection.BASE)
        val national=s.nationalTreasuries[power] ?: 0L;val currentRate=rate(s,baseId,defaultRate)
        val old=s.budget.quotes[i.characterId]?.takeIf {it.command==i.command && it.base==baseId}
        val now=session.gameMillis/1000
        val visible=old?.takeIf {it.phase!=BudgetPhase.QUOTED || now<it.expiresAt && it.national==national &&
            it.local==b.treasury && it.beforeRate==currentRate}
        val view=BudgetView(baseId,power,national,b.treasury,currentRate,visible)
        if(r.phase==0)return BudgetResult(p,s,view=view)
        if(r.phase in 2..3) {
            if(old==null || old.id!=r.quote || old.card!=i.card || old.rate!=r.rate)return reject(BudgetRejection.STALE,view)
            // A committed receipt is replayable after reconnect/restart without paying again.
            if(old.phase!=BudgetPhase.QUOTED)return BudgetResult(p,s,view=view)
            if(r.phase==3) {
                val cancelled=old.copy(phase=BudgetPhase.CANCELLED)
                return BudgetResult(p,s.copy(budget=s.budget.copy(quotes=s.budget.quotes+(i.characterId to cancelled))),
                    changed=true,view=view.copy(quote=cancelled))
            }
        }
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null)return reject(BudgetRejection.BUSY,view)
        if(p.baseId!=holding.seat || p.mode !in 1..4 || g.bases.single {it.id==holding.seat}.grid!=p.grid)
            return reject(BudgetRejection.LOCATION,view)
        if(r.phase==1) {
            val amount=if(i.command==18)percentage(national,r.rate) else 0L
            if(i.command==18 && (r.rate==0 || amount==0L))return reject(BudgetRejection.RESOURCE,view)
            if(i.command==18 && b.treasury>Long.MAX_VALUE-amount)return reject(BudgetRejection.MAXIMUM,view)
            if(old?.phase==BudgetPhase.QUOTED && now<old.expiresAt && old.rate==r.rate && old.national==national &&
                old.local==b.treasury && old.beforeRate==currentRate)return BudgetResult(p,s,view=view)
            if(s.budget.nextId==Int.MAX_VALUE || now>0xffffffffL-QUOTE_SECONDS)return reject(BudgetRejection.MAXIMUM,view)
            val quotes=s.budget.quotes.toMutableMap()
            if(i.characterId !in quotes && quotes.size==256) {
                val removable=quotes.values.filter {it.phase!=BudgetPhase.QUOTED || it.expiresAt<=now}.minByOrNull {it.id}
                    ?: return reject(BudgetRejection.MAXIMUM,view)
                quotes.remove(removable.character)
            }
            val q=BudgetQuote(s.budget.nextId,i.characterId,i.command,i.card,power,baseId,r.rate,currentRate,
                national,b.treasury,amount,now,now+QUOTE_SECONDS)
            quotes[i.characterId]=q
            return BudgetResult(p,s.copy(budget=s.budget.copy(nextId=q.id+1,quotes=quotes)),changed=true,view=view.copy(quote=q))
        }
        val q=checkNotNull(old)
        if(now>=q.expiresAt || q.national!=national || q.local!=b.treasury || q.beforeRate!=currentRate)
            return reject(BudgetRejection.STALE,view)
        val noChange=i.command==16 && r.rate==currentRate
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(noChange)0L else 320L,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(BudgetRejection.CP,view)
        val completed=q.copy(phase=BudgetPhase.COMPLETED)
        var next=s.copy(budget=s.budget.copy(quotes=s.budget.quotes+(i.characterId to completed)))
        if(i.command==16)next=next.copy(budget=next.budget.copy(remittanceRates=next.budget.remittanceRates+(baseId to r.rate)))
        else next=next.copy(nationalTreasuries=next.nationalTreasuries+(power to national-q.amount),
            bases=next.bases+(baseId to b.copy(treasury=b.treasury+q.amount)))
        return BudgetResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,changed=true,
            view=BudgetView(baseId,power,next.nationalTreasuries[power] ?: 0,next.bases.getValue(baseId).treasury,
                rate(next,baseId,defaultRate),completed))
    }
}
