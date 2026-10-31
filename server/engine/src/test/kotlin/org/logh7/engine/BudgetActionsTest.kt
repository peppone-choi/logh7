package org.logh7.engine

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 자금 보존·일일 경계·실제 직위·영속 견적과 실패 원자성. 작성: Peppone Choi. 실행은 리드 담당. */
class BudgetActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,1,"Fort",0,30f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val session=SessionSimulation(rules).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,cpRecoveryAt=now)
    private fun stocks()=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1177)3 else 2,0,2000,200,300,
        PlanetEconomy(1000,600,70,80),taxRate=20,treasury=200)},deploymentInitialized=true,
        nationalTreasuries=mapOf(2 to 5001L,3 to 1000L),strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1)),
            offices=listOf(OfficeHolding(8,1049,10000))))
    private fun request(command:Int=18,phase:Int=1,rate:Int=33,base:Int=1050,quote:Int=0)=BudgetRequest(
        StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,command,8,1),phase,base,rate,quote)
    private fun act(r:BudgetRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player)=BudgetActions.evaluate(galaxy,r,session,p,s)
    private fun confirm(prepared:BudgetResult,r:BudgetRequest=request()):BudgetResult=act(r.copy(phase=2,quote=assertNotNull(prepared.view?.quote).id),
        assertNotNull(prepared.stocks),assertNotNull(prepared.player))
    @Test fun aidTransfersAPercentageOfExistingNationalMoneyAndNeverTouchesSuppliesOrPersonalMoney() {
        val s=stocks();val prepared=act();assertNull(prepared.rejection)
        assertEquals(1650L,prepared.view?.quote?.amount);assertEquals(s.bases,prepared.stocks?.bases)
        assertEquals(s.nationalTreasuries,prepared.stocks?.nationalTreasuries);assertEquals(1000,prepared.player?.mcp)
        val committed=confirm(prepared);assertNull(committed.rejection);val after=assertNotNull(committed.stocks)
        assertEquals(3351L,after.nationalTreasuries[2]);assertEquals(1850L,after.bases.getValue(1050).treasury)
        assertEquals(5201L,after.nationalTreasuries.getValue(2)+after.bases.getValue(1050).treasury)
        assertEquals(680,committed.player?.mcp);assertEquals(s.strategic,after.strategic)
        assertEquals(s.bases.getValue(1050).supplies,after.bases.getValue(1050).supplies)
        assertEquals(BudgetPhase.COMPLETED,committed.view?.quote?.phase)
        val replay=act(request(phase=2,quote=prepared.view!!.quote!!.id),after,assertNotNull(committed.player))
        assertFalse(replay.changed);assertEquals(after,replay.stocks);assertEquals(committed.player,replay.player)
        val reconnected=session.copy(participants=session.participants.mapValues {(_,m)->m.copy(connection="new")})
        val retry=request(phase=2,quote=prepared.view!!.quote!!.id).let {it.copy(identity=it.identity.copy(connection="new"))}
        assertEquals(after,BudgetActions.evaluate(galaxy,retry,reconnected,committed.player,after).stocks)
    }
    @Test fun remittanceIsDistinctFromTaxAndSplitsTheExistingAssessmentWithoutCreatingRevenue() {
        val r=request(16,rate=80,base=1049);val prepared=act(r);val result=confirm(prepared,r)
        val next=assertNotNull(result.stocks);assertEquals(80,next.budget.remittanceRates[1049]);assertEquals(20,next.bases.getValue(1049).taxRate)
        assertEquals(stocks().nationalTreasuries,next.nationalTreasuries);assertEquals(stocks().bases,next.bases)
        val policy=PlanetTaxRevenuePolicy(1,50);val b=next.bases.getValue(1049)
        for(rate in listOf(0,1,33,80,100)) {
            val income=PlanetTaxRevenue.assess(b,galaxy.conditions.getValue(1049),policy,rate)
            assertEquals(120L,income.collected);assertEquals(income.collected,income.central+income.local)
            assertEquals(BudgetActions.percentage(120,rate),income.central)
        }
        val same=confirm(act(request(16,rate=50)),request(16,rate=50));assertEquals(1000,same.player?.mcp)
        assertEquals(Long.MAX_VALUE,BudgetActions.percentage(Long.MAX_VALUE,100))
        assertEquals(0L,BudgetActions.percentage(Long.MAX_VALUE,0))
    }
    @Test fun dailySettlementUsesPerBaseRateExactlyOnceAndNeverReplaysThePreviousDayAfterAChange() {
        var stored=stocks().copy(budget=BudgetState(mapOf(1049 to 100,1050 to 0)))
        val store=object:PlanetStockStore {override fun load()=stored;override fun save(value:PlanetStocks){stored=value}}
        val production=PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy())
        val initial=production.settle(1,now);val first=production.settle(1,now+86400)
        assertEquals(5121L,first.nationalTreasuries[2]);assertEquals(200L,first.bases.getValue(1049).treasury)
        // Fortresses are not taxable in the existing economy; setting a rate cannot invent taxable population.
        assertEquals(initial.bases.getValue(1050).treasury,first.bases.getValue(1050).treasury)
        val changed=production.replace(first,first.copy(budget=first.budget.copy(remittanceRates=mapOf(1049 to 0,1050 to 100))))
        assertEquals(changed,production.settle(1,now+86400))
        val second=production.settle(1,now+172800)
        assertEquals(changed.nationalTreasuries[2],second.nationalTreasuries[2])
        assertTrue(second.bases.getValue(1049).treasury>changed.bases.getValue(1049).treasury)
        assertEquals(second,production.settle(1,now+172800))
    }
    @Test fun quoteCancellationAndStaleBalancesCannotPayOrConsumeCpAndIdsNeverRepeat() {
        val first=act();val pending=assertNotNull(first.stocks);val q=assertNotNull(first.view?.quote)
        assertEquals(pending,act(s=pending).stocks);assertFalse(act(s=pending).changed)
        val stale=pending.copy(nationalTreasuries=pending.nationalTreasuries+(2 to 5002L))
        val rejected=act(request(phase=2,quote=q.id),stale);assertEquals(BudgetRejection.STALE,rejected.rejection)
        assertEquals(stale,rejected.stocks);assertEquals(player,rejected.player)
        assertNull(act(request(phase=0,rate=0),stale).view?.quote)
        val new=act(s=stale);assertTrue(new.view!!.quote!!.id>q.id)
        assertEquals(BudgetRejection.STALE,act(request(phase=2,quote=q.id),assertNotNull(new.stocks)).rejection)
        val cancelled=act(request(phase=3,quote=q.id),pending,player.copy(baseId=0,mode=5))
        assertNull(cancelled.rejection);assertEquals(BudgetPhase.CANCELLED,cancelled.view?.quote?.phase)
        assertEquals(1000,cancelled.player?.mcp);assertEquals(pending.nationalTreasuries,cancelled.stocks?.nationalTreasuries)
        val expired=session.copy(gameMillis=(q.expiresAt*1000))
        assertEquals(BudgetRejection.STALE,BudgetActions.evaluate(galaxy,request(phase=2,quote=q.id),expired,player,pending).rejection)
    }
    @Test fun actualFinanceOfficeAndFriendlyTargetAreRequiredAndAllDeniedCasesPreserveMoneyAndCp() {
        val s=stocks();val q=assertNotNull(act().view?.quote)
        for(r in listOf(request().let {it.copy(identity=it.identity.copy(card=1))},request(base=1177),
            request().let {it.copy(identity=it.identity.copy(connection="old"))},request(rate=101),request(phase=2,quote=999))) {
            val no=act(r);assertNotNull(no.rejection);assertEquals(s,no.stocks);assertEquals(player,no.player)
        }
        for(next in listOf(s.copy(strategic=StrategicState()),s.copy(strategic=s.strategic.copy(people=mapOf(10000 to StrategicPerson(rank=5)))),
            s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(camp=1)))))
            assertEquals(BudgetRejection.AUTHORITY,act(s=next).rejection)
        val pending=assertNotNull(act().stocks)
        assertEquals(BudgetRejection.LOCATION,act(request(phase=2,quote=q.id),pending,player.copy(baseId=1050)).rejection)
        val poor=player.copy(mcp=319,pcp=639);val no=act(request(phase=2,quote=q.id),pending,poor)
        assertEquals(BudgetRejection.CP,no.rejection);assertEquals(pending,no.stocks);assertEquals(poor,no.player)
        val substitute=act(request(phase=2,quote=q.id),pending,poor.copy(pcp=640))
        assertEquals(0,substitute.player?.pcp);assertEquals(319,substitute.player?.mcp)
        assertEquals(BudgetRejection.RESOURCE,act(request(rate=0)).rejection)
        assertEquals(BudgetRejection.RESOURCE,act(s=s.copy(nationalTreasuries=emptyMap())).rejection)
        val overflow=s.copy(bases=s.bases+(1050 to s.bases.getValue(1050).copy(treasury=Long.MAX_VALUE)))
        assertEquals(BudgetRejection.MAXIMUM,act(s=overflow).rejection)
        val depleted=pending.copy(bases=pending.bases+(1050 to pending.bases.getValue(1050).copy(treasury=201)))
        assertEquals(BudgetRejection.STALE,act(request(phase=2,quote=q.id),depleted).rejection)
    }
    @Test fun allianceFinanceRoleUsesItsOwnCapitalAndNationalAccount() {
        val s=stocks().copy(strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1)),
            offices=listOf(OfficeHolding(136,1177,10000))))
        val p=player.copy(power=Power.ALLIANCE,baseId=1177,grid=2015)
        val aSession=SessionSimulation(rules).also {it.join("actor",Power.ALLIANCE,false,"c")}.exportState()
        val r=request(base=1177,rate=100).let {it.copy(identity=it.identity.copy(power=Power.ALLIANCE,card=136))}
        val quote=BudgetActions.evaluate(galaxy,r,aSession,p,s);assertNull(quote.rejection)
        val done=BudgetActions.evaluate(galaxy,r.copy(phase=2,quote=quote.view!!.quote!!.id),aSession,p,assertNotNull(quote.stocks))
        assertEquals(0L,done.stocks?.nationalTreasuries?.get(3));assertEquals(1200L,done.stocks?.bases?.get(1177)?.treasury)
        assertEquals(5001L,done.stocks?.nationalTreasuries?.get(2))
    }
    @Test fun failedCombinedCommitPublishesNeitherCpMoneyNorCompletedReceipt()=runBlocking {
        val prepared=act();var stored=assertNotNull(prepared.stocks);var players=mapOf("actor" to player);var fail=false
        val inventory=object:PlanetStockStore {override fun load()=stored;override fun save(value:PlanetStocks){stored=value}}
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=inventory,
            worldStateCommitter=WorldStateCommitter {p,s->if(fail)throw IOException("injected budget commit failure");players=p;stored=checkNotNull(s)},
            npcPolicy=NpcTacticalPolicy(enabled=false))
        val events=mutableListOf<BudgetChange>();val subscription=engine.observeBudget {events+=it}
        try {
            val join=CompletableDeferred<Admission>();engine.submit(WorldCommand.Join("actor",Power.EMPIRE,false,join,"c",1));withTimeout(5000){join.await()}
            val stock=CompletableDeferred<PlanetStocks>();engine.submit(WorldCommand.Stock(1,stock));withTimeout(5000){stock.await()}
            val before=engine.snapshot();val durable=stored;val people=players;events.clear();fail=true
            val reply=CompletableDeferred<BudgetResult>();engine.submit(WorldCommand.Budget(request(phase=2,quote=prepared.view!!.quote!!.id),reply))
            assertFailsWith<IOException>{withTimeout(5000){reply.await()}}
            assertEquals(before.planetStocks,engine.snapshot().planetStocks);assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(durable,stored);assertEquals(people,players);assertTrue(events.isEmpty());assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
        } finally {subscription.close();withTimeout(5000){engine.drainAndClose()};scope.cancel()}
    }
}
