package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 命令/提案 편지의 생성·회신 1회·거절 하루 차단·만료. 작성: Peppone Choi. */
class ProposalsTest {
    @Test fun aRowIsAnsweredOnceByItsRecipientAndRefusalBlocksTheSameCommandForADay() {
        val (opened,none)=Proposals.open(ProposalState(),1,10002,10004,25,1,0x0709,true,100,0,80);assertNull(none)
        assertTrue(opened.mails.single().pending);assertEquals(100+Proposals.LIFETIME,opened.mails.single().expiresAt)
        assertEquals(ProposalRejection.NOT_RECIPIENT,Proposals.reply(opened,1,10002,3,101).second)
        assertEquals(ProposalRejection.VALUE,Proposals.reply(opened,1,10004,4,101).second)
        val (refused,ok)=Proposals.reply(opened,1,10004,Proposals.REFUSE,101);assertNull(ok)
        assertEquals(1,refused.mails.single().reply);assertEquals(ProposalRejection.REPLIED,Proposals.reply(refused,1,10004,3,102).second)
        assertEquals(ProposalRejection.BLOCKED,Proposals.open(refused,2,10002,10004,25,1,0x0709,true,200,0,0).second)
        assertNull(Proposals.open(refused,2,10002,10004,25,1,0x0709,true,101+Proposals.LIFETIME,0,0).second,"the block ends after a day")
    }
    @Test fun pendingRowsExpireAndApprovalIsValueThree() {
        val (opened,_)=Proposals.open(ProposalState(),1,10002,10004,25,1,0x0709,false,0,10,20)
        assertEquals(listOf(1),Proposals.expired(opened,Proposals.LIFETIME).map {it.id})
        assertTrue(Proposals.withoutExpired(opened,Proposals.LIFETIME).mails.isEmpty())
        assertEquals(ProposalRejection.STALE,Proposals.reply(opened,1,10004,Proposals.APPROVE,Proposals.LIFETIME).second)
        assertEquals(3,Proposals.reply(opened,1,10004,Proposals.APPROVE,5).first.mails.single().reply)
    }
}
