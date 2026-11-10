package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    @Test fun absentDemotionOrdersRecheckTheTargetAndChargeOnlyOnce() = absentRankChange(ProposalActions.DEMOTION)
    @Test fun absentSpecialPromotionUpdatesTheTargetFlagshipAndChargesOnlyOnce() = absentRankChange(ProposalActions.SPECIAL_PROMOTION)

    private fun absentRankChange(command:Int) {
        val promoting=command==ProposalActions.SPECIAL_PROMOTION
        val oldRank=if(promoting)11 else 9
        val half=if(promoting)320 else 160
        val destroyer=Flagships.forType(2,3).kind
        val target=b.copy(characterId=10002,hull=FlagshipHull(destroyer,0))
        val identities=listOf(
            StrategicIdentity(a.characterId,2,2,0,List(8){50}),
            StrategicIdentity(b.characterId,2,oldRank,0,List(8){50}),
            StrategicIdentity(target.characterId,2,oldRank,0,List(8){50}))
        val original=stocks()
        val withRanks=original.copy(strategic=original.strategic.copy(people=original.strategic.people+
            (b.characterId to StrategicPerson(rank=oldRank,merit=100))+(target.characterId to StrategicPerson(rank=oldRank))))
        val mail=ProposalMail(1,a.characterId,b.characterId,17,19,command,true,0,Proposals.LIFETIME,
            mcp=half,target=target.characterId,country=Power.EMPIRE.country)
        val pending=withRanks.copy(social=withRanks.social.copy(proposals=ProposalState(listOf(mail))))
        val due=session.copy(gameMillis=ProposalActions.ABSENT_ORDER_SECONDS*1000,
            participants=session.participants+("b" to session.participants.getValue("b").copy(online=false)))
        val players=mutableMapOf("a" to a.copy(mcp=1000-half),"b" to b,"target" to target)
        val dead=BattleDeath.apply(pending,listOf(target.characterId),1) {_,_->true}
        assertEquals(dead,ProposalActions.settle(galaxy,due,players,dead) {identities})
        assertEquals(b,players.getValue("b"))

        val approved=ProposalActions.settle(galaxy,due,players,pending) {identities}
        assertEquals(Proposals.APPROVE,approved.social.proposals.mails.single().reply)
        assertEquals(10,approved.strategic.people.getValue(target.characterId).rank)
        assertEquals(if(promoting)10 else oldRank,approved.social.proposals.mails.single().rankChangeMailRank)
        assertEquals(if(promoting)Flagships.rankDefault(2,10).kind else destroyer,players.getValue("target").hull?.kind)
        assertEquals(1000-half,players.getValue("b").mcp)
        assertEquals(approved,ProposalActions.settle(galaxy,due,players,approved) {identities})
        assertEquals(1000-half,players.getValue("b").mcp)
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

    @Test fun ordersToADeceasedRecipientCannotExecuteAgainstAnAbsentSuccessor() {
        val opened=act("a",ProposalActions.OPEN,17,b.characterId,ProposalActions.RESIGNATION,19,stocks())
        val dead=BattleDeath.apply(assertNotNull(opened.stocks),listOf(b.characterId),session.gameMillis/1000) {_,_->true}
        val successor=b.copy(characterId=10003)
        val state=policy.set(dead,OfficeHolding(19,1049,successor.characterId))
        val mail=state.social.proposals.mails.single()
        val sender=assertNotNull(opened.player)
        val players=mutableMapOf("a" to sender,"b" to b,"successor" to successor)
        val due=session.copy(gameMillis=(mail.createdAt+ProposalActions.ABSENT_ORDER_SECONDS)*1000)

        assertEquals(state,ProposalActions.settle(galaxy,due,players,state))
        assertEquals(successor,players.getValue("successor"))

        val expiry=due.copy(gameMillis=mail.expiresAt*1000)
        val expired=ProposalActions.settle(galaxy,expiry,players,state)
        assertTrue(expired.social.proposals.mails.isEmpty())
        val refunded=sender.copy(pcp=a.pcp,mcp=a.mcp)
        assertEquals(refunded,players.getValue("a"))
        assertEquals(successor,players.getValue("successor"))
        assertTrue(policy.held(expired,successor.characterId).any {it.card==19})
        assertEquals(expired,ProposalActions.settle(galaxy,expiry,players,expired))
        assertEquals(refunded,players.getValue("a"))
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
    @Test fun proposalsCannotCrossBetweenSpaceAndAFacilityInTheSameGrid() {
        val s=stocks()
        val request=SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,ProposalActions.OPEN,17,9,
            target=10001,value=ProposalActions.RESIGNATION,argument=19))
        val spaceA=a.copy(baseId=0,mode=5,spotId=0)
        val spaceB=b.copy(baseId=0,mode=5,spotId=0)
        for((sender,recipient) in listOf(spaceA to b,a to spaceB)) {
            val result=SocialActions.evaluate(galaxy,request,session,sender,s,listOf(sender,recipient),
                mapOf("a" to sender,"b" to recipient))
            assertEquals(SocialRejection.LOCATION,result.rejection)
            assertEquals(sender,result.player)
            assertEquals(s,result.stocks)
        }
        assertNull(SocialActions.evaluate(galaxy,request,session,spaceA,s,listOf(spaceA,spaceB),
            mapOf("a" to spaceA,"b" to spaceB)).rejection)
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

    @Test fun anEliminatedCountryCannotApproveAnOfficeOrderButCanRefuseOrReceiveTheExpiryRefund() {
        val opened = act("a", ProposalActions.OPEN, 17, b.characterId, ProposalActions.RESIGNATION, 19, stocks())
        val pending = assertNotNull(opened.stocks)
        val sender = assertNotNull(opened.player)
        val mail = pending.social.proposals.mails.single()
        assertTrue(mail.order)
        assertEquals(960, sender.mcp)
        val active = session.copy(countries = setOf(Power.EMPIRE.country, Power.ALLIANCE.country, CountryId(42)))
        val defeated = active.copy(eliminated = mapOf(Power.EMPIRE.country to
            CountryDefeat(EndReason.SYSTEMS_REDUCED, mail.createdAt)))
        val accounts = mapOf("a" to sender, "b" to b)
        fun reply(value: Int): SocialResult {
            val request = SocialRequest(StrategyActionRequest("b", "b", b.characterId, Power.EMPIRE, 1,
                ProposalActions.REPLY, 0, 9, target = mail.id, value = value))
            return ProposalActions.evaluate(galaxy, request, defeated, b, pending, accounts)
        }
        val approval = reply(Proposals.APPROVE)
        assertEquals(SocialRejection.SESSION, approval.rejection)
        assertEquals(b, approval.player)
        assertEquals(pending, approval.stocks)

        val refusal = reply(Proposals.REFUSE)
        assertNull(refusal.rejection)
        assertEquals(b, refusal.player)
        val refused = assertNotNull(refusal.stocks)
        assertEquals(Proposals.REFUSE, refused.social.proposals.mails.single().reply)
        assertTrue(policy.held(refused, b.characterId).any { it.card == 19 })

        val due = active.copy(gameMillis = (mail.createdAt + ProposalActions.ABSENT_ORDER_SECONDS) * 1000,
            participants = active.participants + ("b" to active.participants.getValue("b").copy(online = false, connection = null)))
        val players = accounts.toMutableMap()
        val defeatedDue = due.copy(eliminated = defeated.eliminated)
        assertEquals(pending, ProposalActions.settle(galaxy, defeatedDue, players, pending))
        assertEquals(accounts, players)

        val survivor = accounts.toMutableMap()
        val approved = ProposalActions.settle(galaxy, due, survivor, pending)
        assertEquals(Proposals.APPROVE, approved.social.proposals.mails.single().reply)
        assertEquals(960, survivor.getValue("b").mcp)
        assertTrue(policy.held(approved, b.characterId).none { it.card == 19 })

        val expiry = defeatedDue.copy(gameMillis = mail.expiresAt * 1000)
        val expired = ProposalActions.settle(galaxy, expiry, players, pending)
        assertTrue(expired.social.proposals.mails.isEmpty())
        assertTrue(policy.held(expired, b.characterId).any { it.card == 19 })
        assertEquals(mapOf("a" to sender.copy(mcp = a.mcp), "b" to b), players)
        assertEquals(expired, ProposalActions.settle(galaxy, expiry, players, expired))
        assertEquals(a.mcp, players.getValue("a").mcp)
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
        assertEquals(mapOf("b" to b),approved.navigators)
        assertTrue(OrganizationalOfficePolicy(galaxy).held(assertNotNull(approved.stocks),seat.target).none {it.card==seat.card})
    }
    @Test fun aDismissalOfASeatTheRecipientCannotDismissIsRejected() {
        val r=SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,ProposalActions.OPEN,17,9,target=10001,
            value=ProposalActions.DISMISSAL,argument=19,base=10000,amount=17))
        assertEquals(SocialRejection.TARGET,SocialActions.evaluate(galaxy,r,session,a,stocks(),listOf(a,b),mapOf("a" to a,"b" to b)).rejection)
    }
    @Test fun aPendingDismissalDoesNotFollowACommanderToAnotherFleet() {
        val role = FleetOffice.EMPIRE_COMMANDER
        val target = b.copy(characterId = 10002)
        val initial = policy.set(policy.set(stocks(), OfficeHolding(1, 1049, a.characterId)),
            OfficeHolding(role.authority, 1049, b.characterId))
        val before = initial.copy(
            outfits = mapOf(200 to OutfitStock(200, 2, OutfitKind.FLEET, 1049, 300, commanderId = target.characterId),
                201 to OutfitStock(201, 2, OutfitKind.FLEET, 1049, 301)),
            warehouses = mapOf(300 to OutfitWarehouse(300, 2, OutfitKind.FLEET, 1049, 200),
                301 to OutfitWarehouse(301, 2, OutfitKind.FLEET, 1049, 201)),
            strategic = initial.strategic.copy(people = initial.strategic.people + (b.characterId to StrategicPerson(rank = 2))))
        val players = mutableMapOf("a" to a, "b" to b, "target" to target)
        val open = SocialRequest(StrategyActionRequest("a", "a", a.characterId, Power.EMPIRE, 1,
            ProposalActions.OPEN, 1, 0, target = b.characterId, value = ProposalActions.DISMISSAL,
            argument = role.authority, base = target.characterId, amount = role.card.toLong()))
        val opened = SocialActions.evaluate(galaxy, open, session, a, before, players.values, players)
        assertNull(opened.rejection)
        val sender = assertNotNull(opened.player)
        players["a"] = sender
        val pending = assertNotNull(opened.stocks)
        val mail = pending.social.proposals.mails.single()
        val departed = policy.set(pending, OfficeHolding(role.card, 200, target.characterId), remove = true)
        val transferred = policy.set(departed, OfficeHolding(role.card, 201, target.characterId))
        val reply = SocialRequest(StrategyActionRequest("b", "b", b.characterId, Power.EMPIRE, 1,
            ProposalActions.REPLY, 0, 2, target = mail.id, value = Proposals.APPROVE))
        val rejected = SocialActions.evaluate(galaxy, reply, session, b, transferred, players.values, players)
        assertEquals(SocialRejection.CARD, rejected.rejection)
        assertEquals(b, rejected.player)
        assertEquals(transferred, rejected.stocks)
        assertEquals(200, mail.seat)

        val due = session.copy(gameMillis = (mail.createdAt + ProposalActions.ABSENT_ORDER_SECONDS) * 1000,
            participants = session.participants + ("b" to session.participants.getValue("b").copy(online = false)))
        assertEquals(transferred, ProposalActions.settle(galaxy, due, players, transferred))
        assertEquals(b, players.getValue("b"))
        val expired = ProposalActions.settle(galaxy, due.copy(gameMillis = mail.expiresAt * 1000), players, transferred)
        assertTrue(expired.social.proposals.mails.isEmpty())
        assertEquals(sender.copy(mcp = a.mcp), players.getValue("a"))
        assertEquals(target.characterId, expired.outfits.getValue(201).commanderId)

        val legacy = pending.copy(social = pending.social.copy(proposals = ProposalState(listOf(mail.copy(seat = 0)))))
        val legacyPlayers = players.toMutableMap().also { it["a"] = sender }
        assertEquals(legacy, ProposalActions.settle(galaxy, due, legacyPlayers, legacy))
        assertEquals(b, legacyPlayers.getValue("b"))
        val expiredLegacy = ProposalActions.settle(galaxy, due.copy(gameMillis = mail.expiresAt * 1000), legacyPlayers, legacy)
        assertTrue(expiredLegacy.social.proposals.mails.isEmpty())
        assertEquals(sender.copy(mcp = a.mcp), legacyPlayers.getValue("a"))
        assertEquals(target.characterId, expiredLegacy.outfits.getValue(200).commanderId)

        val approved = SocialActions.evaluate(galaxy, reply, session, b, pending, players.values, players)
        assertNull(approved.rejection)
        assertEquals(920, approved.player?.mcp)
        assertEquals(0, assertNotNull(approved.stocks).outfits.getValue(200).commanderId)
    }
    @Test fun aResignationOrderStaysWithItsOriginalFleetSeat() {
        val role=FleetOffice.EMPIRE_COMMANDER
        val initial=policy.set(stocks(),OfficeHolding(1,1049,a.characterId)).copy(
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=b.characterId),
                201 to OutfitStock(201,2,OutfitKind.FLEET,1049,301)),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200),
                301 to OutfitWarehouse(301,2,OutfitKind.FLEET,1049,201)))
        val opened=act("a",ProposalActions.OPEN,1,b.characterId,ProposalActions.RESIGNATION,role.card,initial)
        assertNull(opened.rejection)
        val pending=assertNotNull(opened.stocks)
        val mail=pending.social.proposals.mails.single()
        val vacant=policy.set(pending,OfficeHolding(role.card,200,b.characterId),remove=true)
        val transferred=policy.set(vacant,OfficeHolding(role.card,201,b.characterId))
        val denied=act("b",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,transferred)
        assertEquals(SocialRejection.CARD,denied.rejection)
        assertEquals(transferred,denied.stocks)
        assertEquals(b,denied.player)
        assertEquals(200,mail.seat)

        val due=session.copy(gameMillis=(mail.createdAt+ProposalActions.ABSENT_ORDER_SECONDS)*1000,
            participants=session.participants+("b" to session.participants.getValue("b").copy(online=false)))
        val successor=b.copy(characterId=10002)
        val sender=assertNotNull(opened.player)
        val players=mutableMapOf("a" to sender,"b" to b,"successor" to successor)
        assertEquals(transferred,ProposalActions.settle(galaxy,due,players,transferred))
        assertEquals(b,players.getValue("b"))

        val unrelated=policy.set(vacant,OfficeHolding(role.card,201,successor.characterId))
        assertEquals(unrelated,ProposalActions.settle(galaxy,due,players,unrelated))
        assertEquals(successor,players.getValue("successor"))

        val replaced=policy.set(transferred,OfficeHolding(role.card,200,successor.characterId))
        val completed=ProposalActions.settle(galaxy,due,players,replaced)
        val reply=completed.social.proposals.mails.single()
        assertEquals(successor.characterId,reply.recipient)
        assertEquals(Proposals.APPROVE,reply.reply)
        assertEquals(0,completed.outfits.getValue(200).commanderId)
        assertEquals(b.characterId,completed.outfits.getValue(201).commanderId)
        assertEquals(960,players.getValue("successor").mcp)
        assertEquals(b,players.getValue("b"))

        val legacy=transferred.copy(social=transferred.social.copy(proposals=ProposalState(listOf(mail.copy(seat=0)))))
        assertEquals(legacy,ProposalActions.settle(galaxy,due,players,legacy))
        val expired=ProposalActions.settle(galaxy,due.copy(gameMillis=mail.expiresAt*1000),players,legacy)
        assertTrue(expired.social.proposals.mails.isEmpty())
        assertEquals(a.mcp,players.getValue("a").mcp)
        assertEquals(b.characterId,expired.outfits.getValue(201).commanderId)
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
        val players = linkedMapOf("a" to a, "b" to assertNotNull(opened.player))
        val now = session.gameMillis / 1000
        val detained = assertNotNull(JusticeActions.detain(galaxy, assertNotNull(opened.stocks), players,
            b.characterId, CountryId(2), now))
        val reply = SocialRequest(StrategyActionRequest("a", "a", a.characterId, Power.EMPIRE, 1,
            ProposalActions.REPLY, 0, 9, target = mail.id, value = Proposals.APPROVE))
        val rejected = SocialActions.evaluate(galaxy, reply, session, a, detained, players.values, players)
        assertEquals(SocialRejection.CARD, rejected.rejection)
        assertEquals(a, rejected.player)
        assertEquals(detained, rejected.stocks)
        assertTrue(rejected.navigators.isEmpty())

        val released = detained.copy(social = detained.social.copy(
            justice = detained.social.justice.copy(detentions = emptyMap())))
        val approved = SocialActions.evaluate(galaxy, reply, session, a, released, players.values, players)
        assertNull(approved.rejection)
        assertEquals(920, approved.player?.mcp)
        assertEquals(mapOf("b" to players.getValue("b")), approved.navigators)
        val appointed=assertNotNull(approved.stocks)
        assertTrue(OrganizationalOfficePolicy(galaxy).held(appointed,10001).any {it.card==card && it.seat==seat})
        val duplicate=act("a",ProposalActions.REPLY,0,mail.id,Proposals.APPROVE,0,appointed)
        assertEquals(SocialRejection.STALE,duplicate.rejection)
        assertTrue(duplicate.navigators.isEmpty())
    }
    @Test fun approvedTransportAppointmentsRefreshOnlyChangedTacticalAuthority() = runBlocking {
        for((automatic,former) in listOf(false to 10002,true to 10002,false to b.characterId)) {
            val initial=policy.set(stocks(),OfficeHolding(18,1049,a.characterId))
            val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(76))))
            val outfit=OutfitStock(200,2,OutfitKind.TRANSPORT,1049,300,
                units=UnitInventory(ships.quantities()),activeShips=ships,position=OutfitPosition(2488))
            val available=initial.copy(outfits=mapOf(200 to outfit),
                warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.TRANSPORT,1049,200)),
                strategic=initial.strategic.copy(people=initial.strategic.people+(b.characterId to StrategicPerson(rank=4,influence=10))),
                tacticalCommands=TacticalCommandState(authority=mapOf(1 to former)),deploymentInitialized=true,nextShipId=2)
            val open=SocialRequest(StrategyActionRequest("b","b",b.characterId,Power.EMPIRE,1,ProposalActions.OPEN,19,4,
                target=a.characterId,value=ProposalActions.APPOINTMENT,argument=18,base=200,amount=44))
            val opened=SocialActions.evaluate(galaxy,open,session,b,available,listOf(a,b),mapOf("a" to a,"b" to b))
            assertNull(opened.rejection)
            var saved=assertNotNull(opened.stocks)
            val mail=saved.social.proposals.mails.single()
            if(automatic) {
                val proposals=saved.social.proposals.copy(mails=listOf(mail.copy(order=true)))
                saved=saved.copy(social=saved.social.copy(proposals=proposals))
            }
            var players=mapOf("a" to a,"b" to assertNotNull(opened.player))
            val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=object:NavigationStore {
                override fun load()=players
                override fun save(navigators:Map<String,Navigator>) {players=navigators}
            },stockStore=object:PlanetStockStore {
                override fun load()=saved
                override fun save(stocks:PlanetStocks) {saved=stocks}
            },worldStateCommitter=WorldStateCommitter {p,s->players=p;saved=checkNotNull(s)},npcPolicy=NpcTacticalPolicy(enabled=false))
            val refreshed=mutableListOf<Set<Int>>()
            try {
                engine.observeTacticalOfficeRefresh {_,ids->refreshed+=ids}.use {
                    for(account in listOf("a","b")) {
                        val joined=CompletableDeferred<Admission>()
                        engine.submit(WorldCommand.Join(account,Power.EMPIRE,false,joined,account,1))
                        assertEquals(Admission.ACCEPTED,withTimeout(5000) {joined.await()})
                    }
                    if(automatic) {
                        engine.submit(WorldCommand.Disconnect("a","a"))
                        val elapsed=(ProposalActions.ABSENT_ORDER_SECONDS*1000+rules.speed-1)/rules.speed
                        engine.submit(WorldCommand.AdvanceTo(elapsed))
                        val flushed=CompletableDeferred<Unit>()
                        engine.submit(WorldCommand.Quiesce({},flushed))
                        withTimeout(5000) {flushed.await()}
                    } else {
                        val reply=SocialRequest(StrategyActionRequest("a","a",a.characterId,Power.EMPIRE,1,
                            ProposalActions.REPLY,0,2,target=mail.id,value=Proposals.APPROVE))
                        val result=CompletableDeferred<SocialResult>()
                        engine.submit(WorldCommand.Social(reply,result))
                        assertNull(withTimeout(5000) {result.await()}.rejection)
                    }
                    val appointed=saved.outfits.getValue(200)
                    assertEquals(b.characterId,TacticalAuthority.commander(appointed,ships.values.single(),saved))
                    assertEquals(if(former==b.characterId)emptyList() else listOf(setOf(200)),refreshed)
                }
            } finally {engine.drainAndClose()}
        }
    }
    @Test fun aDormantFiefCountsAgainstOpeningAndApprovingAnAppointment() {
        val initial = stocks()
        val estates = (1050..1061).associateWith { id ->
            PlanetStock(id, 2, if (id == 1061) 1 else 0, 100, 200, 300)
        }
        val world = Galaxy(galaxy.systems, galaxy.bases + estates.keys.map { id ->
            galaxy.bases.first().copy(id = id, name = "Estate $id")
        }, galaxy.starts)
        val offices = OrganizationalOfficePolicy(world)
        val available = initial.copy(bases = initial.bases + estates, strategic = initial.strategic.copy(
            people = initial.strategic.people + (b.characterId to StrategicPerson(rank = 4, influence = 10, peerage = Peerage.BARON)),
            fiefs = (1050..1060).associateWith { b.characterId }))
        val (card, seat) = ProposalActions.vacancies(world, available, a.characterId, 2, 2, b.characterId, 4).first()
        val open = SocialRequest(StrategyActionRequest("b", "b", b.characterId, Power.EMPIRE, 1,
            ProposalActions.OPEN, 19, 4, target = a.characterId, value = ProposalActions.APPOINTMENT,
            argument = 17, base = seat, amount = card.toLong()))
        val opened = SocialActions.evaluate(world, open, session, b, available, listOf(a, b), mapOf("a" to a, "b" to b))
        assertNull(opened.rejection)
        val pending = assertNotNull(opened.stocks)
        val full = pending.copy(strategic = pending.strategic.copy(fiefs = pending.strategic.fiefs + (1061 to b.characterId)))
        assertEquals(14, offices.cardCount(full, b.characterId))
        assertEquals(11, FundActions.governed(world, full, b.characterId, CountryId(2), card = 66).size)

        val sender = assertNotNull(opened.player)
        val refused = SocialActions.evaluate(world, open, session, sender, full, listOf(a, sender),
            mapOf("a" to a, "b" to sender))
        assertEquals(SocialRejection.TARGET, refused.rejection)
        assertEquals(sender, refused.player)
        assertEquals(full, refused.stocks)

        val reply = SocialRequest(StrategyActionRequest("a", "a", a.characterId, Power.EMPIRE, 1,
            ProposalActions.REPLY, 0, 2, target = pending.social.proposals.mails.single().id, value = Proposals.APPROVE))
        val denied = SocialActions.evaluate(world, reply, session, a, full, listOf(a, sender), mapOf("a" to a, "b" to sender))
        assertEquals(SocialRejection.CARD, denied.rejection)
        assertEquals(a, denied.player)
        assertEquals(full, denied.stocks)
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
