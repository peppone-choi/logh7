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
    @Test fun detentionOfEitherParticipantBlocksInvitingAndAcceptingWithoutPreventingDecline() {
        val prison = galaxy.facilitiesAt(1049).single { it.kind == 0 }.spots.single { it.kind == 18 }.id
        val initial = stocks()
        val invite = request("host", 10000, 10001, 0, initial.social.nextId)
        val opened = act(invite, initial)
        assertNull(opened.rejection)
        val pending = assertNotNull(opened.stocks)
        val accept = request("guest", 10001, 10000, 1, pending.social.nextId)
        val offline = session.copy(participants = session.participants - "guest")
        assertEquals(680, act(invite, initial, clock = offline).player?.mcp, "eligible offline guest accepts automatically")

        for (detainee in listOf(host, guest)) {
            val detainedHost = if (detainee == host) host.copy(spotId = prison, spotOwner = 0) else host
            val detainedGuest = if (detainee == guest) guest.copy(spotId = prison) else guest
            val detention = Detention(detainee.characterId, CountryId(2), now, now + 86400)
            fun detained(stocks: PlanetStocks) = stocks.copy(social = stocks.social.copy(
                justice = stocks.social.justice.copy(detentions = mapOf(detainee.characterId to detention))))
            fun unchanged(result: SocialResult, player: Navigator, stocks: PlanetStocks) {
                assertEquals(SocialRejection.BUSY, result.rejection)
                assertEquals(player, result.player)
                assertEquals(stocks, result.stocks)
                assertFalse(result.changed)
                assertTrue(result.navigators.isEmpty())
            }

            val held = detained(initial)
            for (clock in listOf(session, offline))
                unchanged(act(invite, held, h = detainedHost, g = detainedGuest, clock = clock), detainedHost, held)
            val heldPending = detained(pending)
            unchanged(act(accept, heldPending, h = detainedHost, g = detainedGuest), detainedGuest, heldPending)
            val declined = act(request("guest", 10001, 10000, 2, pending.social.nextId), heldPending, h = detainedHost, g = detainedGuest)
            assertNull(declined.rejection)
            assertTrue(assertNotNull(declined.stocks).social.gatherings.invites.isEmpty())
            assertEquals(detainedGuest, declined.player)
            assertTrue(declined.navigators.isEmpty())
        }
    }

    @Test fun huntingNeedsTheOwnFiefForestAndAPartyTheCapitalBallroomOfANoble() {
        val forest=1049*10000+GatheringActions.MANSION*200+GatheringActions.FOREST+1
        val ballroom=1049*10000+GatheringActions.MANSION*200+GatheringActions.BALLROOM+1
        val hunter=host.copy(spotId=forest,spotOwner=0)
        val fief=stocks().let {it.copy(strategic=it.strategic.copy(fiefs=mapOf(1049 to 10000)))}
        assertEquals(SocialRejection.LOCATION,act(request("host",10000,10001,0,1,GatheringActions.HUNT),stocks(),h=hunter).rejection,"no fief")
        val hunt=act(request("host",10000,10001,0,1,GatheringActions.HUNT),fief,h=hunter);assertNull(hunt.rejection)
        val s=assertNotNull(hunt.stocks)
        val done=act(request("guest",10001,10000,1,s.social.nextId,GatheringActions.HUNT),s,h=hunter)
        assertNull(done.rejection);assertTrue(assertNotNull(done.gathering).friendshipDelta!=0,"hunting changes friendship")
        val party=host.copy(spotId=ballroom,spotOwner=0)
        assertEquals(SocialRejection.LOCATION,act(request("host",10000,10001,0,1,GatheringActions.PARTY),stocks(),h=party).rejection,"no peerage")
        val noble=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10000 to it.strategic.people.getValue(10000).copy(peerage=Peerage.BARON))))}
        val invited=assertNotNull(act(request("host",10000,10001,0,1,GatheringActions.PARTY),noble,h=party).stocks)
        val view=assertNotNull(act(request("guest",10001,10000,1,invited.social.nextId,GatheringActions.PARTY),invited,h=party).gathering)
        assertTrue(view.hostInfluence in setOf(5,2),"夜会 influence is 1.5x");assertEquals(0,view.friendshipDelta)
        assertTrue(Places.forBase(galaxy.bases.single(),2,estate=true).any {it.kind==GatheringActions.MANSION})
    }
    @Test fun theOwnFiefForestIsAnOwnerZeroSpotOnlyItsHolderEnters() {
        val store=object:NavigationStore {var saved=emptyMap<String,Navigator>()
            override fun load()=saved;override fun save(players:Map<String,Navigator>){saved=players}}
        store.saved=mapOf("host" to host.copy(spotId=1049*10000+GatheringActions.MANSION*200+1,spotOwner=0))
        val navigation=Navigation(galaxy,store,NavigationPolicy())
        val one=SessionSimulation(rules).also {it.join("host",Power.EMPIRE,false,"h")}.exportState()
        val fief=stocks().let {it.copy(strategic=it.strategic.copy(fiefs=mapOf(1049 to 10000)))}
        val forest=1049*10000+GatheringActions.MANSION*200+GatheringActions.FOREST+1
        val request=NavigationRequest("host",10000,Power.EMPIRE,"h",1,unitId=1010000,place=PlaceMove.Spot(forest,0))
        val result=navigation.apply(request,one,fief)
        val moved=assertNotNull(result.player,"rejected: ${result.rejection}")
        assertEquals(forest,moved.spotId);assertEquals(0,moved.spotOwner)
        assertEquals(NavigationRejection.SPOT,navigation.apply(request,one,stocks()).rejection,"no fief, no mansion")
    }
    @Test fun outcomeBandsAndAmountsFollowTheApprovedDesign() {
        assertEquals(GatheringGrade.GREAT,GatheringActions.grade(100));assertEquals(GatheringGrade.SUCCESS,GatheringActions.grade(70))
        assertEquals(GatheringGrade.FAILURE,GatheringActions.grade(69))
        assertEquals(listOf(3,1,-1),GatheringGrade.entries.map(GatheringActions::hostInfluence))
        assertEquals(listOf(10,5,-3),GatheringGrade.entries.map(GatheringActions::friendshipDelta))
        assertTrue((0..200).all {GatheringActions.score(50,10000,it.toLong()) in 50..99})
    }
    @Test fun aGuestWhoIsNotConnectedAnswersAtOnceByFriendship() {
        // Only the host is in the session: the guest is an NPC or an offline player.
        val alone=SessionSimulation(rules).also {it.join("host",Power.EMPIRE,false,"h")}.exportState()
        fun invite(s:PlanetStocks)=SocialActions.evaluate(galaxy,request("host",10000,10001,0,1),alone,host,s,listOf(host,guest),
            mapOf("host" to host,"guest" to guest))
        val accepted=invite(stocks());assertNull(accepted.rejection)
        assertEquals(680,accepted.player?.mcp,"the host pays at once");val view=assertNotNull(accepted.gathering)
        assertNotNull(view.grade);assertNull(view.invite)
        val after=assertNotNull(accepted.stocks);assertTrue(after.social.gatherings.invites.isEmpty())
        assertEquals(50+view.friendshipDelta,after.social.meeting.friendship(10000,10001))
        val cold=stocks().let {it.copy(social=it.social.copy(meeting=it.social.meeting.copy(
            friendship=mapOf(MeetingActions.pair(10000,10001) to 40))))}
        val declined=invite(cold);assertNull(declined.rejection);assertEquals(1000,declined.player?.mcp,"no CP on a refusal")
        assertNull(assertNotNull(declined.gathering).grade);assertTrue(assertNotNull(declined.stocks).social.gatherings.invites.isEmpty())
    }

    @Test fun deceasedGuestsCannotAutoAcceptAndPendingInvitesCannotRewardEitherDeceasedParticipant() {
        val initial=stocks()
        val invite=request("host",10000,10001,0,initial.social.nextId)
        val offline=session.copy(participants=session.participants-"guest")
        val deadGuest=BattleDeath.apply(initial,listOf(guest.characterId),now+1) {_,_->true}
        fun unchanged(result:SocialResult,player:Navigator,before:PlanetStocks,reason:SocialRejection) {
            assertEquals(reason,result.rejection)
            assertEquals(player,result.player)
            assertEquals(before,result.stocks)
            assertFalse(result.changed)
            assertTrue(result.navigators.isEmpty())
        }
        val later=session.copy(gameMillis=(now+1)*1000)
        unchanged(act(invite,deadGuest,clock=later.copy(participants=offline.participants)),host,deadGuest,SocialRejection.TARGET)
        unchanged(act(invite,deadGuest,clock=later),host,deadGuest,SocialRejection.TARGET)
        assertEquals(680,act(invite,initial,clock=offline).player?.mcp)

        val pending=assertNotNull(act(invite,initial).stocks)
        val accept=request("guest",10001,10000,1,pending.social.nextId)
        val deadHostPending=BattleDeath.apply(pending,listOf(host.characterId),now+1) {_,_->true}
        val deadGuestPending=BattleDeath.apply(pending,listOf(guest.characterId),now+1) {_,_->true}
        unchanged(act(accept.copy(ticket=initial.social.nextId),deadHostPending,clock=later),guest,deadHostPending,SocialRejection.STALE)
        unchanged(act(accept,deadHostPending,clock=later),guest,deadHostPending,SocialRejection.TARGET)
        unchanged(act(accept,deadGuestPending,clock=later),guest,deadGuestPending,SocialRejection.IDENTITY)
        val expired=session.copy(gameMillis=(now+GatheringActions.INVITE_SECONDS)*1000)
        unchanged(act(accept,deadHostPending,clock=expired),guest,deadHostPending,SocialRejection.STALE)

        val decline=request("guest",10001,10000,2,pending.social.nextId)
        val declined=act(decline,deadHostPending,clock=later)
        assertNull(declined.rejection)
        assertEquals(guest,declined.player)
        assertTrue(declined.navigators.isEmpty())
        val afterDecline=assertNotNull(declined.stocks)
        assertEquals(pending.social.nextId+1,afterDecline.social.nextId)
        assertTrue(afterDecline.social.gatherings.invites.isEmpty())
    }
}
