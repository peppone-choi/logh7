package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 命令/提案: 보낸 사람 절반 결제·영향력, 승인 시 받는 사람 절반 결제와 사임 실행, 거절 차단. 작성: Peppone Choi. */
class ProposalActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a");it.join("b",Power.EMPIRE,false,"b")}.exportState()
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=10490001)
    private val b=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=10490001)
    private fun stocks():PlanetStocks {
        val initial=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=2,influence=10),10001 to StrategicPerson(rank=9,influence=10))))
        return policy.set(policy.set(initial,OfficeHolding(17,1049,10000)),OfficeHolding(19,1049,10001))
    }
    private fun act(account:String,command:Int,card:Int,target:Int,value:Int,argument:Int,s:PlanetStocks):SocialResult {
        val me=if(account=="a")a else b
        val r=SocialRequest(StrategyActionRequest(account,account,me.characterId,Power.EMPIRE,1,command,card,9,target=target,value=value,argument=argument))
        return SocialActions.evaluate(galaxy,r,session,me,s,listOf(a,b),mapOf("a" to a,"b" to b))
    }
    @Test fun anOrderSplitsTheCostAndApprovalResignsTheRecipientsCard() {
        val opened=act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,stocks())
        assertNull(opened.rejection);assertEquals(960,opened.player?.mcp)
        val s=assertNotNull(opened.stocks);val mail=s.social.proposals.mails.single()
        assertTrue(mail.order,"a minister orders a rank-9 officer");assertEquals(9,s.strategic.people.getValue(10000).influence)
        assertEquals(ProposalRejection.NOT_RECIPIENT,Proposals.reply(s.social.proposals,mail.id,10000,3,1).second)
        val approved=act("b",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,s)
        assertNull(approved.rejection);assertEquals(960,approved.player?.mcp,"the recipient pays the other half")
        assertTrue(policy.held(assertNotNull(approved.stocks),10001).none {it.card==19},"card 19 was resigned")
    }
    @Test fun ordersFromADeceasedSenderCannotExecuteOnlineOrInAbsentSettlement() {
        val opened=act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,stocks())
        val state=BattleDeath.apply(assertNotNull(opened.stocks),listOf(a.characterId),session.gameMillis/1000) {_,_->true}
        val mail=state.social.proposals.mails.single()
        val replied=act("b",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,state)
        assertEquals(SocialRejection.CARD,replied.rejection)
        assertEquals(b,replied.player)
        assertEquals(state,replied.stocks)
        val absent=session.copy(participants=session.participants+("b" to session.participants.getValue("b").copy(online=false)),
            gameMillis=session.gameMillis+ProposalActions.ABSENT_ORDER_SECONDS*1000)
        val players=mutableMapOf("a" to assertNotNull(opened.player),"b" to b)
        assertEquals(state,ProposalActions.settle(galaxy,absent,players,state))
        assertEquals(b,players.getValue("b"))
        assertTrue(policy.held(state,b.characterId).any {it.card==19})
    }
    @Test fun approvingAnAbdicationProposalPassesTheThroneToTheNextCandidate() {
        val initial=policy.set(policy.set(stocks(),OfficeHolding(1,1049,a.characterId)),OfficeHolding(2,1049,b.characterId))
        val opened=act("b",ProposalActions.OPEN,2,a.characterId,ProposalActions.RESIGNATION,1,initial)
        assertNull(opened.rejection)
        val pending=assertNotNull(opened.stocks)
        val mail=pending.social.proposals.mails.single()
        val approved=act("a",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,pending)
        assertNull(approved.rejection)
        assertEquals(960,approved.player?.mcp)
        val resigned=assertNotNull(approved.stocks)
        assertTrue(policy.held(resigned,a.characterId).none {it.card==1})
        val candidates=listOf(a,b).map {StrategicIdentity(it.characterId,2,2,2,List(8){50})}
        val settled=SuccessionActions.accede(galaxy,resigned,candidates,session.gameMillis/1000)
        assertEquals(b.characterId,policy.holders(settled,1,CountryId(2)).single().character)
    }
    @Test fun aProposalGoesUpAndARefusalBlocksTheSameRequestForADay() {
        val opened=act("b",ProposalActions.OPEN,19,10000,ProposalActions.RESIGNATION,17,stocks())
        assertNull(opened.rejection);val s=assertNotNull(opened.stocks)
        assertFalse(s.social.proposals.mails.single().order,"rank 9 proposes to rank 2")
        val refused=assertNotNull(act("a",ProposalActions.REPLY,0,s.social.proposals.mails.single().id,Proposals.REFUSE,0,s).stocks)
        assertTrue(policy.held(refused,10000).any {it.card==17},"nothing executed")
        assertEquals(SocialRejection.COOLDOWN,act("b",ProposalActions.OPEN,19,10000,ProposalActions.RESIGNATION,17,refused).rejection)
    }
    @Test fun cardsRangeAndInfluenceAreChecked() {
        assertEquals(SocialRejection.CARD,act("a",ProposalActions.OPEN,19,10001,ProposalActions.RESIGNATION,19,stocks()).rejection,"not my card")
        assertEquals(SocialRejection.CARD,act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,25,stocks()).rejection,"not their card")
        val poor=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10000 to StrategicPerson(rank=2))))}
        assertEquals(SocialRejection.RANK,act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,poor).rejection,"no influence")
        assertEquals(SocialRejection.COMMAND,act("a",ProposalActions.OPEN,17,10001,0x0c0b,19,stocks()).rejection)
    }
    @Test fun anExpiredRowGivesTheSendersHalfBackInTheNextCommit() {
        val opened=act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,stocks())
        val s=assertNotNull(opened.stocks)
        val late=session.copy(gameMillis=(session.gameMillis/1000+Proposals.LIFETIME+1)*1000)
        val r=SocialRequest(StrategyActionRequest("b","b",10001,Power.EMPIRE,1,ProposalActions.REPLY,0,9,target=s.social.proposals.mails.single().id,value=0))
        val result=SocialActions.evaluate(galaxy,r,late,b,s,listOf(a,b),mapOf("a" to a.copy(mcp=960),"b" to b))
        assertEquals(SocialRejection.STALE,result.rejection,"the row is gone")
        val other=SocialRequest(StrategyActionRequest("b","b",10001,Power.EMPIRE,1,ProposalActions.OPEN,19,9,
            target=10000,value=ProposalActions.RESIGNATION,argument=17))
        val refunded=SocialActions.evaluate(galaxy,other,late,b,s,listOf(a,b),mapOf("a" to a.copy(mcp=960),"b" to b))
        assertNull(refunded.rejection);assertEquals(1000,refunded.navigators.getValue("a").mcp,"40 MCP back to the sender")
        assertEquals(1,assertNotNull(refunded.stocks).social.proposals.mails.size,"only the new row remains")
    }
    @Test fun anOrderToAnAbsentRecipientIsApprovedAfterAnHourButAProposalWaits() {
        val base=policy.set(stocks(),OfficeHolding(18,1049,10001))
        val order=assertNotNull(act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,base).stocks)
        val absent=session.copy(participants=session.participants+("b" to session.participants.getValue("b").copy(online=false)),
            gameMillis=session.gameMillis+ProposalActions.ABSENT_ORDER_SECONDS*1000)
        // Any later proposal action settles it: a's own (rejected-as-blocked-free) open to b again.
        val r=SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,ProposalActions.REPLY,0,9,target=999,value=0))
        val settled=SocialActions.evaluate(galaxy,r,absent,a,order,listOf(a,b),mapOf("a" to a,"b" to b))
        assertEquals(SocialRejection.STALE,settled.rejection,"the trigger itself fails, so nothing is committed")
        val proposal=assertNotNull(act("b",ProposalActions.OPEN,19,10000,ProposalActions.RESIGNATION,17,stocks()).stocks)
        val waiting=SocialActions.evaluate(galaxy,r.copy(identity=r.identity.copy(account="b",characterId=10001)),
            session.copy(participants=session.participants+("a" to session.participants.getValue("a").copy(online=false)),
                gameMillis=session.gameMillis+ProposalActions.ABSENT_ORDER_SECONDS*1000),b,proposal,listOf(a,b),mapOf("a" to a,"b" to b))
        assertTrue(waiting.navigators.isEmpty(),"a proposal is never approved for the absent superior")
        val good=SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,ProposalActions.OPEN,17,9,
            target=10001,value=ProposalActions.RESIGNATION,argument=18))
        val approved=SocialActions.evaluate(galaxy,good,absent,a,order,listOf(a,b),mapOf("a" to a,"b" to b))
        assertNull(approved.rejection,"the triggering order (card 18) succeeds");assertEquals(960,approved.navigators["b"]?.mcp,"the absent recipient paid its half")
        assertTrue(policy.held(assertNotNull(approved.stocks),10001).none {it.card==19},"and resigned")
    }
    @Test fun aQuietWorldTickProcessesAnAbsentOrderAndRefundsAnExpiredProposal() {
        for(isOrder in listOf(true,false)) {
            val opened=if(isOrder)act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,stocks())
                else act("b",ProposalActions.OPEN,19,10000,ProposalActions.RESIGNATION,17,stocks())
            var saved=assertNotNull(opened.stocks)
            val mail=saved.social.proposals.mails.single()
            var players=if(isOrder)mapOf("a" to assertNotNull(opened.player),"b" to b)
                else mapOf("a" to a,"b" to assertNotNull(opened.player))
            val navigation=Navigation(galaxy,object:NavigationStore {
                override fun load()=players
                override fun save(navigators:Map<String,Navigator>) {players=navigators}
            },NavigationPolicy())
            val production=PlanetProduction(galaxy,object:PlanetStockStore {
                override fun load()=saved
                override fun save(stocks:PlanetStocks) {saved=stocks}
            },rules.startSeconds,PlanetProductionPolicy())
            production.settle(session.generation,session.gameMillis/1000)
            val deadline=if(isOrder)mail.createdAt+ProposalActions.ABSENT_ORDER_SECONDS else mail.expiresAt
            val offline=session.copy(participants=session.participants.mapValues {(_,p)->p.copy(online=false,connection=null)})
            fun tick(at:Long)=TacticalCombat.settle(offline.copy(gameMillis=at*1000),galaxy,navigation,production,
                WorldStateCommitter {p,s->players=p;saved=checkNotNull(s)},npcPolicy=NpcTacticalPolicy(enabled=false))
            tick(deadline-1)
            assertTrue(saved.social.proposals.mails.single().pending)
            tick(deadline)
            if(isOrder) {
                assertEquals(Proposals.APPROVE,saved.social.proposals.mails.single().reply)
                assertEquals(960,players.getValue("b").mcp)
            } else {
                assertTrue(saved.social.proposals.mails.isEmpty())
                assertEquals(1000,players.getValue("b").mcp)
            }
        }
    }

    @Test fun anAbsentOrderWaitsForTheRecipientToBeReleasedFromDetention() {
        val order=assertNotNull(act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,stocks()).stocks)
        val due=session.copy(participants=session.participants+("b" to session.participants.getValue("b").copy(online=false)),
            gameMillis=session.gameMillis+ProposalActions.ABSENT_ORDER_SECONDS*1000)
        val players=mutableMapOf("a" to a,"b" to b)
        val now=due.gameMillis/1000
        val jail=JusticeState(detentions=mapOf(10001 to Detention(10001,CountryId(2),now,now+60)))
        val detained=order.copy(social=order.social.copy(justice=jail))
        assertEquals(detained,ProposalActions.settle(galaxy,due,players,detained))
        assertEquals(b,players.getValue("b"))
        val released=ProposalActions.settle(galaxy,due.copy(gameMillis=(now+60)*1000),players,order)
        assertEquals(Proposals.APPROVE,released.social.proposals.mails.single().reply)
        assertEquals(960,players.getValue("b").mcp)
        assertTrue(policy.held(released,10001).none {it.card==19})
    }

    @Test fun aDismissalNamesAThirdPersonTheRecipientMayDismissAndRunsOnApproval() {
        // 10001 proposes that the minister 10000 dismiss a seat 10000 may dismiss.
        val s=stocks();val seats=OfficeDismissalPolicy(galaxy).available(s,10000,2,2)
        assertTrue(seats.isNotEmpty(),"the minister can dismiss someone: $seats");val seat=seats.first()
        val r=SocialRequest(StrategyActionRequest("b","b",10001,Power.EMPIRE,1,ProposalActions.OPEN,19,9,target=10000,
            value=ProposalActions.DISMISSAL,argument=17,base=seat.target,amount=seat.card.toLong()))
        val opened=SocialActions.evaluate(galaxy,r,session,b,s,listOf(a,b),mapOf("a" to a,"b" to b))
        assertNull(opened.rejection);val mail=assertNotNull(opened.stocks).social.proposals.mails.single()
        assertEquals(seat.target,mail.target);assertFalse(mail.order,"rank 9 proposes to rank 2");assertEquals(920,opened.player?.mcp,"half of 160")
        val approved=act("a",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,assertNotNull(opened.stocks))
        assertNull(approved.rejection);assertEquals(920,approved.player?.mcp)
        assertTrue(OrganizationalOfficePolicy(galaxy).held(assertNotNull(approved.stocks),seat.target).none {it.card==seat.card})
    }
    @Test fun aDismissalOfASeatTheRecipientCannotDismissIsRejected() {
        val r=SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,ProposalActions.OPEN,17,9,target=10001,
            value=ProposalActions.DISMISSAL,argument=19,base=10000,amount=17))
        assertEquals(SocialRejection.TARGET,SocialActions.evaluate(galaxy,r,session,a,stocks(),listOf(a,b),mapOf("a" to a,"b" to b)).rejection)
    }
    @Test fun anAppointmentAsksTheRecipientToAppointTheSenderToAVacancy() {
        // 10001 (a rank-4 general here) asks the minister 10000 for a vacancy 10000 may fill with 10001.
        val s=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10001 to StrategicPerson(rank=4,influence=10))))}
        val vacancies=ProposalActions.vacancies(galaxy,s,10000,2,2,10001,4)
        assertTrue(vacancies.isNotEmpty(),"the minister has a vacancy for 10001");val (card,seat)=vacancies.first()
        val r=SocialRequest(StrategyActionRequest("b","b",10001,Power.EMPIRE,1,ProposalActions.OPEN,19,9,target=10000,
            value=ProposalActions.APPOINTMENT,argument=17,base=seat,amount=card.toLong()))
        val opened=SocialActions.evaluate(galaxy,r,session,b,s,listOf(a,b),mapOf("a" to a,"b" to b))
        assertNull(opened.rejection);val mail=assertNotNull(opened.stocks).social.proposals.mails.single()
        assertEquals(10001,mail.target);assertEquals(seat,mail.seat);assertEquals(920,opened.player?.mcp,"half of 160")
        val approved=act("a",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,assertNotNull(opened.stocks))
        assertNull(approved.rejection)
        assertTrue(OrganizationalOfficePolicy(galaxy).held(assertNotNull(approved.stocks),10001).any {it.card==card && it.seat==seat})
    }
    @Test fun anAppointmentToASeatTheRecipientCannotFillIsRejected() {
        val r=SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,ProposalActions.OPEN,17,9,target=10001,
            value=ProposalActions.APPOINTMENT,argument=19,base=1049,amount=17))
        assertEquals(SocialRejection.TARGET,SocialActions.evaluate(galaxy,r,session,a,stocks(),listOf(a,b),mapOf("a" to a,"b" to b)).rejection)
    }
    @Test fun aPendingRowFollowsItsPostToTheNextHolder() {
        // 10000 orders 10001 (card 19); before answering, card 19 passes to 10003.
        val opened=act("a",ProposalActions.OPEN,17,10001,ProposalActions.RESIGNATION,19,stocks())
        assertNull(opened.rejection)
        val handed=policy.set(policy.set(assertNotNull(opened.stocks),OfficeHolding(19,1049,10001),remove=true),OfficeHolding(19,1049,10003))
        val players=linkedMapOf("a" to a,"b" to b)
        val settled=ProposalActions.settle(galaxy,session,players,handed)
        assertEquals(10003,settled.social.proposals.mails.single().recipient,"the successor answers the row")
        val vacant=policy.set(assertNotNull(opened.stocks),OfficeHolding(19,1049,10001),remove=true)
        assertEquals(10001,ProposalActions.settle(galaxy,session,players,vacant).social.proposals.mails.single().recipient,
            "a vacant post keeps the row")
    }
}
