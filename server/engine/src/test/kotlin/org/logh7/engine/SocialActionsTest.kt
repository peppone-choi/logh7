package org.logh7.engine

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 광장 연설 한 명령의 실제 효과·실패·제한·원자성. 작성: Peppone Choi. 실행은 리드 담당. */
class SocialActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,110,1,"Fort",0,30f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val session=SessionSimulation(rules).also {it.join("actor",Power.EMPIRE,false,"c");it.join("peer",Power.EMPIRE,false,"d")}.exportState()
    private val now=session.gameMillis/1000
    private fun square(base:Int)=galaxy.facilities.getValue(base).single {it.kind==2}.spots.single {it.kind==3}.id
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=square(1049),cpRecoveryAt=now)
    private fun person(politics:Int=60)=StrategicPerson(rank=1,groundTactics=2,peerage=Peerage.DUKE,merit=123,influence=10,
        evaluation=456,money=789,abilities=listOf(50,politics,50,50,50,50,50,50),decorations=setOf(1))
    private fun stocks()=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1177)3 else 2,0,
        2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)},deploymentInitialized=true,
        nationalTreasuries=mapOf(2 to 5001L,3 to 1000L),strategic=StrategicState(people=mapOf(10000 to person())))
    private fun request(phase:Int=1,ticket:Int=1)=SocialRequest(StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,14,0,1),phase,ticket)
    private fun act(r:SocialRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player,clock:SessionState=session)=
        SocialActions.evaluate(galaxy,r,clock,p,s)
    @Test fun successImmediatelyChangesOnlyInfluenceAndBaseApprovalAndChargesMcpOnce() {
        val s=stocks();val r=act();assertNull(r.rejection);assertTrue(r.changed);val next=assertNotNull(r.stocks)
        assertEquals(680,r.player?.mcp);assertEquals(1000,r.player?.pcp)
        assertEquals(11,next.strategic.people.getValue(10000).influence);assertEquals(72,next.bases.getValue(1049).economy?.approval)
        assertEquals(80,next.bases.getValue(1049).economy?.peace)
        assertEquals(person().copy(influence=11),next.strategic.people[10000]);assertEquals(s.nationalTreasuries,next.nationalTreasuries)
        assertEquals(s.bases.getValue(1049).copy(economy=s.bases.getValue(1049).economy!!.copy(approval=72)),next.bases[1049])
        assertEquals(now+86400,next.social.speakers[10000]);assertEquals(now+86400,next.social.squares[1049])
        val receipt=next.social.speeches.getValue(10000);assertEquals(1,receipt.influenceDelta);assertEquals(2,receipt.approvalDelta)
        val replay=act(s=next,p=assertNotNull(r.player));assertFalse(replay.changed);assertEquals(next,replay.stocks);assertEquals(r.player,replay.player)
    }
    @Test fun poorPoliticsHasARealNegativeEffectAndStillPaysWhileAllCapsAvoidMeaninglessCharges() {
        val s=stocks().let {it.copy(strategic=it.strategic.copy(people=mapOf(10000 to person(49))))}
        val failed=act(s=s);assertNull(failed.rejection);assertEquals(680,failed.player?.mcp)
        assertEquals(9,failed.stocks?.strategic?.people?.get(10000)?.influence)
        assertEquals(68,failed.stocks?.bases?.get(1049)?.economy?.approval)
        assertEquals(-1,failed.view?.last?.influenceDelta);assertEquals(-2,failed.view?.last?.approvalDelta)
        val full=stocks().let {it.copy(strategic=it.strategic.copy(people=mapOf(10000 to person().copy(influence=100))),
            bases=it.bases+(1049 to it.bases.getValue(1049).copy(economy=PlanetEconomy(1000,600,100,80))))}
        val capped=act(s=full);assertEquals(SocialRejection.MAXIMUM,capped.rejection);assertEquals(full,capped.stocks);assertEquals(player,capped.player)
        val clipped=act(s=stocks().let {it.copy(strategic=it.strategic.copy(people=mapOf(10000 to person().copy(influence=100))))})
        assertEquals(0,clipped.view?.last?.influenceDelta);assertEquals(2,clipped.view?.last?.approvalDelta)
        val low=s.copy(strategic=s.strategic.copy(people=mapOf(10000 to person(10).copy(influence=0))),
            bases=s.bases+(1049 to s.bases.getValue(1049).copy(economy=PlanetEconomy(1000,600,0,80))))
        assertEquals(SocialRejection.MAXIMUM,act(s=low).rejection)
    }
    @Test fun speakerAndBaseHaveSeparateDurableLimitsAndAReplayCannotReroll() {
        val first=act();val s=assertNotNull(first.stocks);val q=request(ticket=2)
        val again=act(q,s,assertNotNull(first.player));assertEquals(SocialRejection.COOLDOWN,again.rejection);assertEquals(s,again.stocks)
        val moved=player.copy(baseId=1050,spotId=square(1050))
        assertEquals(SocialRejection.COOLDOWN,act(q,s,moved).rejection)
        val other=q.copy(identity=q.identity.copy(account="peer",connection="d",characterId=10001))
        val peer=player.copy(characterId=10001)
        assertEquals(SocialRejection.COOLDOWN,act(other,s,peer).rejection)
        val oldConnection=session.copy(participants=session.participants.mapValues {(account,m)->if(account=="actor")m.copy(connection="new") else m})
        val duplicate=request().let {it.copy(identity=it.identity.copy(connection="new"))}
        assertEquals(s,act(duplicate,s,assertNotNull(first.player),oldConnection).stocks)
        val changed=s.copy(strategic=s.strategic.copy(people=s.strategic.people+(10000 to person(1).copy(influence=11))))
        assertFalse(act(s=changed,p=assertNotNull(first.player)).changed)
        val nextClock=session.copy(gameMillis=(now+86400)*1000)
        val next=act(q,s,assertNotNull(first.player),nextClock);assertNull(next.rejection);assertEquals(360,next.player?.mcp)
        assertEquals(12,next.view?.influence);assertEquals(74,next.view?.approval)
    }
    @Test fun privateRoomsOtherFactionsMilitaryRanksWrongCardsForgedFieldsAndCpFailurePreserveState() {
        val s=stocks()
        val hotel=galaxy.facilities.getValue(1049).single {it.kind==17}.spots.first {it.kind==13}.id
        for(p in listOf(player.copy(spotId=hotel,spotOwner=10000),player.copy(spotOwner=10001),player.copy(spotId=0),
            player.copy(baseId=1177,grid=2015,spotId=square(1177)))) {
            val result=act(s=s,p=p);assertEquals(SocialRejection.LOCATION,result.rejection);assertEquals(s,result.stocks);assertEquals(p,result.player)
        }
        for(q in listOf(request().let {it.copy(identity=it.identity.copy(card=59))},request().let {it.copy(identity=it.identity.copy(command=10))},
            request().let {it.copy(identity=it.identity.copy(target=10001))},request(ticket=7),
            request().let {it.copy(identity=it.identity.copy(connection="old"))})) {
            val result=act(q);assertNotNull(result.rejection);assertEquals(s,result.stocks);assertEquals(player,result.player)
        }
        val military=s.copy(strategic=s.strategic.copy(people=mapOf(10000 to person().copy(rank=5))))
        assertEquals(SocialRejection.RANK,act(s=military).rejection)
        assertEquals(SocialRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        val poor=player.copy(mcp=319,pcp=639);assertEquals(SocialRejection.CP,act(p=poor).rejection)
        val substitute=act(p=poor.copy(pcp=640));assertEquals(0,substitute.player?.pcp);assertEquals(319,substitute.player?.mcp)
        val query=act(request(0,0),s,player.copy(baseId=0,spotId=0,mode=5));assertNull(query.rejection);assertFalse(query.changed)
    }
    @Test fun fortressHasAnAddressablePublicSquareAndAllianceUsesItsOwnGovernmentApproval() {
        assertEquals(3,galaxy.facilities.getValue(1050).single {it.kind==2}.spots.single().kind)
        assertNull(act(p=player.copy(baseId=1050,spotId=square(1050))).rejection)
        val aSession=SessionSimulation(rules).also {it.join("actor",Power.ALLIANCE,false,"c")}.exportState()
        val q=request().let {it.copy(identity=it.identity.copy(power=Power.ALLIANCE))}
        val result=act(q,p=player.copy(power=Power.ALLIANCE,grid=2015,baseId=1177,spotId=square(1177)),clock=aSession)
        assertNull(result.rejection);assertEquals(72,result.stocks?.bases?.get(1177)?.economy?.approval)
        assertEquals(70,result.stocks?.bases?.get(1049)?.economy?.approval)
    }
    @Test fun combinedSaveFailurePublishesNoInfluenceApprovalCpOrCooldown()=runBlocking {
        var stored=stocks();var players=mapOf("actor" to player);var fail=false
        val inventory=object:PlanetStockStore {override fun load()=stored;override fun save(value:PlanetStocks){stored=value}}
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=inventory,
            worldStateCommitter=WorldStateCommitter {p,s->if(fail)throw IOException("injected speech commit failure");players=p;stored=checkNotNull(s)},
            npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val join=CompletableDeferred<Admission>();engine.submit(WorldCommand.Join("actor",Power.EMPIRE,false,join,"c",1));withTimeout(5000){join.await()}
            val stock=CompletableDeferred<PlanetStocks>();engine.submit(WorldCommand.Stock(1,stock));withTimeout(5000){stock.await()}
            val before=engine.snapshot();val durable=stored;val people=players;fail=true
            val reply=CompletableDeferred<SocialResult>();engine.submit(WorldCommand.Social(request(),reply))
            assertFailsWith<IOException>{withTimeout(5000){reply.await()}}
            assertEquals(before.planetStocks,engine.snapshot().planetStocks);assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(durable,stored);assertEquals(people,players);assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
        } finally {withTimeout(5000){engine.drainAndClose()};scope.cancel()}
    }
}
