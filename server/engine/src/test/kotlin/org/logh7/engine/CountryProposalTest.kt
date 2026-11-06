package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 실제 국가·관직의 명령/제안, 후임 이관, 자동 승인과 원래 CP 환급. 작성: Peppone Choi. */
class CountryProposalTest {
    private val country=CountryId(42)
    private val foreign=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1002).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("a",country,false,"a");it.join("b",country,false,"b");it.join("c",country,false,"c");it.join("d",foreign,false,"d")}.exportState()
    private val now=session.gameMillis/1000
    private val a=Navigator(1,10000,country,2487,1000,1000,1000,baseId=1000,mode=1,spotId=10000001,shipStyle=ShipStyleId("alliance"))
    private val b=a.copy(characterId=10001)
    private val c=a.copy(characterId=10002)
    private val d=a.copy(characterId=10003,country=foreign)
    private val players=mapOf("a" to a,"b" to b,"c" to c,"d" to d)
    private fun catalog(system:String="empire",ranks:String="empire")=CountryCatalogLoader.load().let {old->
        CountryCatalog(old.calendars.values.toList(),old.families.values.toList()+old.families.getValue("empire").copy(
            key="custom",offices=system,ranks=ranks),old.countries.values.toList()+listOf(42,43).map {
            old.countries.getValue(2).copy(id=it,key="country_$it",family="custom",nativePower=if(system=="empire")3 else 2)})}
    private fun stocks(system:String="empire"):PlanetStocks {
        val minister=if(system=="empire")17 else 159;val personnel=if(system=="empire")19 else 160
        return PlanetStocks(1,0,(1000..1002).associate {id->id to PlanetStock(id,
            Allegiance.Country(if(id==1002)foreign else country),0,2000,200,300)},deploymentInitialized=true,
            strategic=StrategicState(capitals=mapOf(country to 1000,foreign to 1002),people=mapOf(
                10000 to StrategicPerson(rank=2,influence=10),10001 to StrategicPerson(rank=9,influence=10),
                10002 to StrategicPerson(rank=9,influence=10),10003 to StrategicPerson(rank=9,influence=10)),
                offices=listOf(OfficeHolding(minister,1000,10000,country),OfficeHolding(personnel,1000,10001,country),
                    OfficeHolding(personnel,1002,10003,foreign))))
    }
    private fun open(system:String="empire",sender:String="a",command:Int=ProposalActions.RESIGNATION,target:Int=10001,
        base:Int=0,amount:Long=0)=SocialRequest(StrategyActionRequest(sender,sender,if(sender=="a")10000 else 10001,country,1,
        ProposalActions.OPEN,if(sender=="a"){if(system=="empire")17 else 159}else{if(system=="empire")19 else 160},9,
        target=target,value=command,argument=if(sender=="a"){if(system=="empire")19 else 160}else{if(system=="empire")17 else 159},
        base=base,amount=amount))
    private fun reply(id:Int=1,account:String="b",value:Int=Proposals.APPROVE)=SocialRequest(StrategyActionRequest(account,account,
        players.getValue(account).characterId,players.getValue(account).country,1,ProposalActions.REPLY,0,9,target=id,value=value))
    private fun act(r:SocialRequest,s:PlanetStocks=stocks(),accounts:Map<String,Navigator> = players,definitions:CountryCatalog=catalog())=
        SocialActions.evaluate(galaxy,r,session,accounts.getValue(r.identity.account),s,accounts.values,accounts,definitions)
    private fun unchanged(r:SocialResult,s:PlanetStocks,p:Navigator,reason:SocialRejection) {
        assertEquals(reason,r.rejection);assertEquals(s,r.stocks);assertEquals(p,r.player);assertFalse(r.changed);assertTrue(r.navigators.isEmpty())
    }

    @Test fun bothOfficeSystemsSplitTheCostAndResignOnlyTheirOwnCountrysSeat() {
        for(system in listOf("empire","alliance")) {
            val definitions=catalog(system);val opened=act(open(system),stocks(system),definitions=definitions)
            assertNull(opened.rejection);assertEquals(960,opened.player?.mcp)
            val s=assertNotNull(opened.stocks);val mail=s.social.proposals.mails.single()
            assertEquals(country,mail.country);assertTrue(mail.order);assertEquals(40,mail.mcp)
            val approved=act(reply(),s,players+("a" to assertNotNull(opened.player)),definitions)
            assertNull(approved.rejection);assertEquals(960,approved.player?.mcp)
            val saved=assertNotNull(approved.stocks);val card=if(system=="empire")19 else 160
            val offices=OrganizationalOfficePolicy(galaxy,definitions)
            assertTrue(offices.holders(saved,card,country).isEmpty());assertEquals(listOf(10003),offices.holders(saved,card,foreign).map {it.character})
            unchanged(act(reply(),saved,players+("b" to assertNotNull(approved.player)),definitions),saved,assertNotNull(approved.player),SocialRejection.CARD)
            unchanged(act(open(system,target=10003),stocks(system),definitions=definitions),stocks(system),a,SocialRejection.LOCATION)
        }
    }

    @Test fun aPendingRowFollowsOnlyItsOwnCountriesSuccessorAndCannotBeAnsweredAfterDefection() {
        val opened=assertNotNull(act(open()).stocks);val policy=OrganizationalOfficePolicy(galaxy,catalog())
        val vacant=policy.set(opened,OfficeHolding(19,1000,10001,country),remove=true)
        val pending=ProposalActions.settle(galaxy,session,players.toMutableMap(),vacant,catalog())
        assertEquals(10001,pending.social.proposals.mails.single().recipient,"foreign 10003 cannot inherit country 42 mail")
        val handed=policy.set(vacant,OfficeHolding(19,1000,10002,country))
        val moved=ProposalActions.settle(galaxy,session,players.toMutableMap(),handed,catalog())
        assertEquals(10002,moved.social.proposals.mails.single().recipient)
        assertNull(act(reply(account="c"),moved).rejection)
        val defected=players+("b" to b.copy(country=foreign))
        val foreignSession=session.copy(participants=session.participants+("b" to session.participants.getValue("b").copy(country=foreign)))
        val request=reply().let {it.copy(identity=it.identity.copy(country=foreign))}
        val result=SocialActions.evaluate(galaxy,request,foreignSession,defected.getValue("b"),opened,defected.values,defected,catalog())
        unchanged(result,opened,defected.getValue("b"),SocialRejection.IDENTITY)
    }

    @Test fun offlineApprovalWaitsAnHourAndRechecksTheSenderRecipientAndPost() {
        val s=assertNotNull(act(open()).stocks)
        val absent=session.copy(gameMillis=session.gameMillis+ProposalActions.ABSENT_ORDER_SECONDS*1000,
            participants=session.participants+("b" to session.participants.getValue("b").copy(online=false)))
        fun settle(accounts:Map<String,Navigator>,stock:PlanetStocks=s):Pair<PlanetStocks,Map<String,Navigator>> {
            val people=accounts.toMutableMap();return ProposalActions.settle(galaxy,absent,people,stock,catalog()) to people
        }
        val paidPlayers=players+("a" to a.copy(mcp=960))
        val (approved,paid)=settle(paidPlayers);assertFalse(approved.social.proposals.mails.single().pending);assertEquals(960,paid.getValue("b").mcp)
        for(accounts in listOf(paidPlayers+("a" to a.copy(country=foreign)),paidPlayers+("b" to b.copy(country=foreign)),
            paidPlayers+("b" to b.copy(cpPaused=true)))) {
            val (waiting,unchanged)=settle(accounts);assertTrue(waiting.social.proposals.mails.single().pending);assertEquals(accounts,unchanged)
        }
        val noPost=OrganizationalOfficePolicy(galaxy,catalog()).set(s,OfficeHolding(19,1000,10001,country),remove=true)
        val (waiting,unchanged)=settle(paidPlayers,noPost);assertTrue(waiting.social.proposals.mails.single().pending);assertEquals(paidPlayers,unchanged)
    }

    @Test fun appointmentAndDismissalExecuteWithinTheStoredCountryAndRecheckCurrentTargets() {
        val policy=OrganizationalOfficePolicy(galaxy,catalog())
        val s=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10001 to StrategicPerson(rank=4,influence=10))))}
        val (card,seat)=ProposalActions.vacancies(galaxy,s,10000,42,2,10001,4,catalog()).first()
        val opened=act(open(sender="b",command=ProposalActions.APPOINTMENT,target=10000,base=seat,amount=card.toLong()),s)
        assertNull(opened.rejection);val mail=assertNotNull(opened.stocks)
        val approved=act(reply(account="a"),mail);assertNull(approved.rejection)
        assertTrue(policy.held(assertNotNull(approved.stocks),10001).any {it.country==country && it.card==card && it.seat==seat})
        val lost=players+("b" to b.copy(country=foreign))
        unchanged(act(reply(account="a"),mail,lost),mail,a,SocialRejection.CARD)
        val dismissal=act(open(sender="b",command=ProposalActions.DISMISSAL,target=10000,base=10001,amount=19),stocks())
        assertNull(dismissal.rejection)
        val removed=act(reply(account="a"),assertNotNull(dismissal.stocks));assertNull(removed.rejection)
        assertTrue(policy.holders(assertNotNull(removed.stocks),19,country).isEmpty())
        assertEquals(listOf(10003),policy.holders(assertNotNull(removed.stocks),19,foreign).map {it.character})
    }

    @Test fun expiredSubstitutedPaymentsReturnPcpAndApprovalStillChargesOnlyHalfTheNominalMcp() {
        val poor=a.copy(pcp=1000,mcp=0);val accounts=players+("a" to poor)
        val opened=act(open(),accounts=accounts);assertNull(opened.rejection);assertEquals(920,opened.player?.pcp);assertEquals(0,opened.player?.mcp)
        val s=assertNotNull(opened.stocks);assertEquals(80,s.social.proposals.mails.single().pcp);assertEquals(0,s.social.proposals.mails.single().mcp)
        val approved=act(reply(),s,accounts+("a" to assertNotNull(opened.player)))
        assertNull(approved.rejection);assertEquals(960,approved.player?.mcp,"the recipient owes 40 MCP, independent of the sender's substitution")
        val late=session.copy(gameMillis=(now+Proposals.LIFETIME)*1000)
        val after=accounts.toMutableMap().also {it["a"]=assertNotNull(opened.player)}
        val expired=ProposalActions.settle(galaxy,late,after,s,catalog())
        val refunded=poor.copy(pcpUsed=80)
        assertTrue(expired.social.proposals.mails.isEmpty());assertEquals(refunded,after.getValue("a"))
        assertEquals(expired,ProposalActions.settle(galaxy,late,after,expired,catalog()));assertEquals(refunded,after.getValue("a"))
    }

    @Test fun configuredRanksDetermineOrderOrProposalAndLegacyMailCannotBorrowANewCountrysCard() {
        for(ranks in listOf("empire","alliance")) {
            val s=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+
                (10000 to StrategicPerson(rank=1,militaryRank=10,influence=10))))}
            val mail=assertNotNull(act(open(),s,definitions=catalog(ranks=ranks)).stocks).social.proposals.mails.single()
            assertEquals(ranks=="alliance",mail.order)
        }
        val s=assertNotNull(act(open()).stocks).let {it.copy(social=it.social.copy(proposals=it.social.proposals.copy(
            mails=it.social.proposals.mails.map {it.copy(country=null)})))}
        unchanged(act(reply(),s),s,b,SocialRejection.IDENTITY)
    }
}
