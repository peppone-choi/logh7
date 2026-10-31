package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 회담·담화: 호텔 객실 초대→수락의 이동·주최자 결제·영향력/우호도·하루 제한·거절. 작성: Peppone Choi. */
class GatheringActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(rules).also {it.join("host",Power.EMPIRE,false,"h");it.join("guest",Power.EMPIRE,false,"g")}.exportState()
    private val now=session.gameMillis/1000
    private val roomSpot=1049*10000+GatheringActions.HOTEL*200+GatheringActions.HOTEL_ROOM+1
    private val lobby=1049*10000+GatheringActions.HOTEL*200+1+1
    private val host=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=roomSpot,spotOwner=10000,cpRecoveryAt=now)
    private val guest=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=lobby,cpRecoveryAt=now)
    private fun stocks()=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)),
        deploymentInitialized=true,strategic=StrategicState(people=mapOf(10000 to StrategicPerson(influence=10,abilities=List(8){if(it==1)90 else 50}))))
    private fun request(account:String,character:Int,target:Int,value:Int,ticket:Int,command:Int=GatheringActions.DISCUSSION)=
        SocialRequest(StrategyActionRequest(account,account.first().toString(),character,Power.EMPIRE,1,command,0,8,target=target,value=value),1,ticket)
    private fun act(r:SocialRequest,s:PlanetStocks,h:Navigator=host,g:Navigator=guest,clock:SessionState=session):SocialResult {
        val me=if(r.identity.account=="host")h else g
        return SocialActions.evaluate(galaxy,r,clock,me,s,listOf(h,g),mapOf("host" to h,"guest" to g))
    }
    @Test fun anAcceptedDiscussionMovesTheGuestChargesTheHostAndChangesInfluenceAndFriendship() {
        val invited=act(request("host",10000,10001,0,1),stocks())
        assertNull(invited.rejection);assertTrue(invited.changed);assertEquals(1000,invited.player?.mcp,"no CP on invitation")
        val s=assertNotNull(invited.stocks);val invite=assertNotNull(s.social.gatherings.invites[10000])
        assertEquals(now+GatheringActions.INVITE_SECONDS,invite.expiresAt)
        val incoming=act(request("guest",10001,10000,0,0).copy(phase=0),s).gathering?.invite
        assertEquals(10000,incoming?.host,"the guest's preview sees the received invitation")
        assertEquals(10000,act(request("host",10000,10001,0,0).copy(phase=0),s).gathering?.invite?.host)
        val accepted=act(request("guest",10001,10000,1,s.social.nextId),s)
        assertNull(accepted.rejection);assertEquals(guest,accepted.player,"the guest is not moved")
        assertEquals(680,accepted.navigators.getValue("host").mcp)
        val view=assertNotNull(accepted.gathering);val after=assertNotNull(accepted.stocks)
        assertEquals(10+view.hostInfluence,after.strategic.people.getValue(10000).influence)
        assertEquals(50+view.friendshipDelta,after.social.meeting.friendship(10000,10001))
        assertTrue(view.grade!=GatheringGrade.FAILURE,"politics 90 always reaches 70");assertEquals(1,view.guestInfluence)
        assertTrue(after.social.gatherings.invites.isEmpty());assertEquals(now+86400,after.social.gatherings.hostNextAt[10000])
        assertEquals(SocialRejection.COOLDOWN,act(request("host",10000,10001,0,after.social.nextId),after).rejection)
    }
    @Test fun outsideTheOwnRoomOtherBasesDeclinesAndExpiryNeverCharge() {
        assertEquals(SocialRejection.LOCATION,act(request("host",10000,10001,0,1),stocks(),h=host.copy(spotId=lobby,spotOwner=0)).rejection)
        assertEquals(SocialRejection.LOCATION,act(request("host",10000,10001,0,1),stocks(),g=guest.copy(baseId=0,mode=5,spotId=0)).rejection)
        val s=assertNotNull(act(request("host",10000,10001,0,1),stocks()).stocks)
        val declined=act(request("guest",10001,10000,2,s.social.nextId),s)
        assertNull(declined.rejection);assertTrue(assertNotNull(declined.stocks).social.gatherings.invites.isEmpty());assertTrue(declined.navigators.isEmpty())
        val late=session.copy(gameMillis=(now+GatheringActions.INVITE_SECONDS)*1000)
        assertEquals(SocialRejection.STALE,act(request("guest",10001,10000,1,s.social.nextId),s,clock=late).rejection)
        assertEquals(SocialRejection.STALE,act(request("guest",10001,10000,1,s.social.nextId,GatheringActions.TALK),s).rejection,"command must match")
    }
    @Test fun huntingNeedsTheOwnFiefForestAndAPartyTheCapitalBallroomOfANoble() {
        val forest=1049*10000+GatheringActions.MANSION*200+GatheringActions.FOREST+1
        val ballroom=1049*10000+GatheringActions.MANSION*200+GatheringActions.BALLROOM+1
        val hunter=host.copy(spotId=forest)
        val fief=stocks().let {it.copy(strategic=it.strategic.copy(fiefs=mapOf(1049 to 10000)))}
        assertEquals(SocialRejection.LOCATION,act(request("host",10000,10001,0,1,GatheringActions.HUNT),stocks(),h=hunter).rejection,"no fief")
        val hunt=act(request("host",10000,10001,0,1,GatheringActions.HUNT),fief,h=hunter);assertNull(hunt.rejection)
        val s=assertNotNull(hunt.stocks)
        val done=act(request("guest",10001,10000,1,s.social.nextId,GatheringActions.HUNT),s,h=hunter)
        assertNull(done.rejection);assertTrue(assertNotNull(done.gathering).friendshipDelta!=0,"hunting changes friendship")
        val party=host.copy(spotId=ballroom)
        assertEquals(SocialRejection.LOCATION,act(request("host",10000,10001,0,1,GatheringActions.PARTY),stocks(),h=party).rejection,"no peerage")
        val noble=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10000 to it.strategic.people.getValue(10000).copy(peerage=Peerage.BARON))))}
        val invited=assertNotNull(act(request("host",10000,10001,0,1,GatheringActions.PARTY),noble,h=party).stocks)
        val view=assertNotNull(act(request("guest",10001,10000,1,invited.social.nextId,GatheringActions.PARTY),invited,h=party).gathering)
        assertTrue(view.hostInfluence in setOf(5,2),"夜会 influence is 1.5x");assertEquals(0,view.friendshipDelta)
        assertTrue(Places.forBase(galaxy.bases.single(),2,estate=true).any {it.kind==GatheringActions.MANSION})
    }
    @Test fun outcomeBandsAndAmountsFollowTheApprovedDesign() {
        assertEquals(GatheringGrade.GREAT,GatheringActions.grade(100));assertEquals(GatheringGrade.SUCCESS,GatheringActions.grade(70))
        assertEquals(GatheringGrade.FAILURE,GatheringActions.grade(69))
        assertEquals(listOf(3,1,-1),GatheringGrade.entries.map(GatheringActions::hostInfluence))
        assertEquals(listOf(10,5,-3),GatheringGrade.entries.map(GatheringActions::friendshipDelta))
        assertTrue((0..200).all {GatheringActions.score(50,10000,it.toLong()) in 50..99})
    }
}
