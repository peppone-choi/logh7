package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 사관학교 수강·병기 연습·강의의 위치·카드·CP·경험치·제한. 작성: Peppone Choi. */
class AcademyActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,110,1,"Fort",0,30f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val session=SessionSimulation(rules).also {it.join("actor",Power.EMPIRE,false,"c");it.join("peer",Power.EMPIRE,false,"d")}.exportState()
    private val now=session.gameMillis/1000
    private fun room(base:Int,kind:Int)=galaxy.facilities.getValue(base).single {it.kind==20}.spots.single {it.kind==kind}.id
    private val classroom=room(1049,AcademyActions.CLASSROOM)
    private val simulator=room(1049,AcademyActions.SIMULATOR)
    private val student=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1,spotId=classroom,cpRecoveryAt=now)
    private val lecturer=student.copy(characterId=10001)
    private fun person(vararg abilities:Int)=StrategicPerson(rank=8,abilities=abilities.toList().ifEmpty {List(8) {50}})
    private fun stocks()=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1177)3 else 2,0,
        2000,200,300,PlanetEconomy(1000,600,70,80),treasury=200)},deploymentInitialized=true,
        strategic=StrategicState(people=mapOf(10000 to person(),10001 to person()),offices=listOf(OfficeHolding(38,1,10001))))
    private fun request(command:Int,argument:Int,phase:Int=1,ticket:Int=1,card:Int=0,account:String="actor",id:Int=10000)=
        SocialRequest(StrategyActionRequest(account,if(account=="actor")"c" else "d",id,Power.EMPIRE,1,command,card,8,argument=argument),phase,ticket)
    private fun act(r:SocialRequest,s:PlanetStocks=stocks(),p:Navigator=student,clock:SessionState=session,others:List<Navigator> = emptyList())=
        SocialActions.evaluate(galaxy,r,clock,p,s,others+p)

    @Test fun attendingWithoutALectureGivesTwentyToTheChosenAbilityOncePerGameDay() {
        val r=act(request(93,6));assertNull(r.rejection);assertTrue(r.changed);val next=assertNotNull(r.stocks)
        assertEquals(840,r.player?.mcp);assertEquals(listOf(0,0,0,0,0,0,20,0),next.social.academy.experience[10000])
        assertEquals(50,next.strategic.people.getValue(10000).abilities?.get(6));assertEquals(now+86400,next.social.academy.attendedUntil[10000])
        assertEquals(6,r.academy?.ability);assertEquals(20,r.academy?.experience);assertEquals(20,r.academy?.gained)
        val again=act(request(93,6,ticket=2),next,assertNotNull(r.player));assertEquals(SocialRejection.COOLDOWN,again.rejection);assertEquals(next,again.stocks)
        val tomorrow=session.copy(gameMillis=(now+86400)*1000)
        assertNull(act(request(93,6,ticket=2),next,assertNotNull(r.player),tomorrow).rejection)
    }
    @Test fun aLectureGivesFiftyToBothContentAbilitiesOnlyWhileTheLecturerStaysInTheClassroom() {
        val opened=act(request(27,1,card=38,account="peer",id=10001),p=lecturer)
        assertNull(opened.rejection);assertEquals(840,opened.player?.mcp);val s=assertNotNull(opened.stocks)
        assertEquals(Lecture(10001,1049,classroom,1,now,now+7200),s.social.academy.lectures[10001])
        assertEquals(1,opened.academy?.content);assertEquals(7200,opened.academy?.lectureRemaining)
        val preview=act(request(93,0,0,0),s,others=listOf(lecturer));assertEquals(1,preview.academy?.content);assertEquals(6,preview.academy?.ability)
        val heard=act(request(93,0,ticket=2),s,others=listOf(lecturer));assertNull(heard.rejection)
        assertEquals(listOf(0,0,0,0,0,0,50,50),heard.stocks?.social?.academy?.experience?.get(10000));assertEquals(100,heard.academy?.gained)
        val left=act(request(93,0,ticket=2),s,others=listOf(lecturer.copy(spotId=simulator)))
        assertEquals(listOf(20,0,0,0,0,0,0,0),left.stocks?.social?.academy?.experience?.get(10000))
        val ended=act(request(93,0,ticket=2),s,clock=session.copy(gameMillis=(now+7200)*1000),others=listOf(lecturer))
        assertEquals(20,ended.academy?.gained)
        assertEquals(SocialRejection.COOLDOWN,act(request(27,2,ticket=2,card=38,account="peer",id=10001),s,lecturer).rejection)
    }
    @Test fun wargamesRunFiveTimesPerDayInTheSimulatorAndExperienceRaisesTheAbility() {
        var s=stocks().let {it.copy(social=it.social.copy(academy=AcademyState(experience=mapOf(10000 to listOf(0,0,0,0,98,0,0,0)))))}
        var p=student.copy(spotId=simulator);var ticket=1
        repeat(5) {
            val r=act(request(94,4,ticket=ticket),s,p);assertNull(r.rejection);s=assertNotNull(r.stocks);p=assertNotNull(r.player);ticket++
            assertEquals(4-it,r.academy?.usesLeft)
        }
        assertEquals(950,p.mcp);assertEquals(51,s.strategic.people.getValue(10000).abilities?.get(4))
        assertEquals(23,s.social.academy.experience.getValue(10000)[4])
        assertEquals(SocialRejection.COOLDOWN,act(request(94,4,ticket=ticket),s,p).rejection)
        assertEquals(SocialRejection.COMMAND,act(request(94,0),p=student.copy(spotId=simulator)).rejection)
        assertEquals(SocialRejection.LOCATION,act(request(94,4)).rejection)
        assertEquals(SocialRejection.LOCATION,act(request(93,4),p=student.copy(spotId=simulator)).rejection)
    }
    @Test fun cardsCapsCpAndForeignAcademiesRejectWithoutChangingState() {
        val s=stocks()
        assertEquals(SocialRejection.CARD,act(request(27,0),s).rejection)
        assertEquals(SocialRejection.CARD,act(request(27,0,card=37),s).rejection)
        assertEquals(SocialRejection.CARD,act(request(93,0,card=38),s).rejection)
        val capped=s.copy(strategic=s.strategic.copy(people=s.strategic.people+(10000 to person(50,50,50,50,50,50,100,50))))
        assertEquals(SocialRejection.MAXIMUM,act(request(93,6),capped).rejection)
        assertEquals(SocialRejection.CP,act(request(93,6),s,student.copy(mcp=159,pcp=319)).rejection)
        val foreign=student.copy(grid=2015,baseId=1177,spotId=room(1177,AcademyActions.CLASSROOM))
        val result=act(request(93,6),s,foreign);assertEquals(SocialRejection.LOCATION,result.rejection);assertEquals(s,result.stocks);assertEquals(foreign,result.player)
        assertEquals(SocialRejection.STALE,act(request(93,6,ticket=5),s).rejection)
    }
}
