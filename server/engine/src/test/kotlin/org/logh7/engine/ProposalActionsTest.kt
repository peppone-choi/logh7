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
}
