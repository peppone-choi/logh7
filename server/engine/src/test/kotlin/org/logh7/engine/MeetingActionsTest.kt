package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 회견의 같은 스폿·쌍별 하루 제한·우호도 상한·CP. 작성: Peppone Choi. */
class MeetingActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val session=SessionSimulation(rules).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val spot=galaxy.facilities.getValue(1049).single {it.kind==2}.spots.first().id
    private val me=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=spot,cpRecoveryAt=now)
    private val peer=me.copy(characterId=10001)
    private fun stocks()=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1177)3 else 2,0,
        2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)},deploymentInitialized=true)
    private fun request(target:Int=10001,phase:Int=1,ticket:Int=1)=
        SocialRequest(StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,87,0,8,target=target),phase,ticket)
    private fun act(r:SocialRequest=request(),s:PlanetStocks=stocks(),p:Navigator=me,others:List<Navigator> = listOf(peer),clock:SessionState=session)=
        SocialActions.evaluate(galaxy,r,clock,p,s,others+p)

    @Test fun aMeetingInTheSameSpotRaisesFriendshipOncePerPairPerDay() {
        val preview=act(request(phase=0,ticket=0));assertNull(preview.rejection);assertEquals(50,preview.meeting?.friendship)
        val r=act();assertNull(r.rejection);assertTrue(r.changed);assertEquals(990,r.player?.mcp)
        val s=assertNotNull(r.stocks);assertEquals(53,s.social.meeting.friendship(10001,10000));assertEquals(3,r.meeting?.gained)
        assertEquals(now+86400,s.social.meeting.nextAt[MeetingActions.pair(10000,10001)])
        assertEquals(SocialRejection.COOLDOWN,act(request(ticket=2),s,assertNotNull(r.player)).rejection)
        val third=peer.copy(characterId=10002)
        assertNull(act(request(10002,ticket=2),s,assertNotNull(r.player),listOf(peer,third)).rejection)
        val tomorrow=session.copy(gameMillis=(now+86400)*1000)
        assertEquals(56,act(request(ticket=2),s,assertNotNull(r.player),clock=tomorrow).meeting?.friendship)
    }
    @Test fun otherSpotsSelfMissingPartnersCapAndCpRejectWithoutChangingState() {
        val s=stocks()
        assertEquals(SocialRejection.LOCATION,act(others=listOf(peer.copy(spotId=spot+1))).rejection)
        assertEquals(SocialRejection.LOCATION,act(others=listOf(peer.copy(spotOwner=10001))).rejection)
        assertEquals(SocialRejection.LOCATION,act(others=emptyList()).rejection)
        assertEquals(SocialRejection.LOCATION,act(p=me.copy(spotId=0),others=listOf(peer.copy(spotId=0))).rejection)
        assertEquals(SocialRejection.COMMAND,act(request(10000)).rejection)
        assertEquals(SocialRejection.CP,act(p=me.copy(mcp=9,pcp=19)).rejection)
        val full=s.copy(social=s.social.copy(meeting=MeetingState(mapOf(MeetingActions.pair(10000,10001) to 100))))
        val capped=act(s=full);assertEquals(SocialRejection.MAXIMUM,capped.rejection);assertEquals(full,capped.stocks);assertEquals(me,capped.player)
        assertEquals(SocialRejection.STALE,act(request(ticket=4)).rejection)
        val near=s.copy(social=s.social.copy(meeting=MeetingState(mapOf(MeetingActions.pair(10000,10001) to 99))))
        assertEquals(100,act(s=near).stocks?.social?.meeting?.friendship(10000,10001))
    }
}
