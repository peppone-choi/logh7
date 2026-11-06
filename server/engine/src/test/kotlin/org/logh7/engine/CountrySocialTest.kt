package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 실제 국가의 교육·연설·초대와 설정 변경 뒤 권한·장소 재검증. 작성: Peppone Choi. */
class CountrySocialTest {
    private val country=CountryId(42)
    private val foreign=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1002).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("host",country,false,"h");it.join("guest",country,false,"g")}.exportState()
    private val now=session.gameMillis/1000
    private fun catalog(offices:String="empire",peerage:String="empire",native:Int=3)=CountryCatalogLoader.load().let {old->
        CountryCatalog(old.calendars.values.toList(),old.families.values.toList()+old.families.getValue("empire").copy(
            key="custom",offices=offices,peerage=peerage),old.countries.values.toList()+listOf(42,43).map {
            old.countries.getValue(2).copy(id=it,key="country_$it",family="custom",nativePower=native)})}
    private fun stocks(card:Int=38)=PlanetStocks(1,0,(1000..1002).associate {id->id to PlanetStock(id,
        Allegiance.Country(if(id==1002)foreign else country),0,2000,200,300,PlanetEconomy(1000,600,70,80))},
        deploymentInitialized=true,strategic=StrategicState(capitals=mapOf(country to 1000,foreign to 1002),
            fiefs=mapOf(1001 to 10000),offices=listOf(OfficeHolding(card,1000,10001,country)),
            people=mapOf(10000 to StrategicPerson(rank=1,influence=10,abilities=List(8){90}),
                10001 to StrategicPerson(rank=8,abilities=List(8){50}))))
    private fun spot(base:Int,facility:Int,kind:Int)=base*10000+facility*200+kind+1
    private fun pilot(id:Int=10000,base:Int=1000,facility:Int=20,kind:Int=16,owner:Int=0)=
        Navigator(1,id,country,2487,1000,1000,1000,baseId=base,mode=1,spotId=spot(base,facility,kind),spotOwner=owner,
            cpRecoveryAt=now,shipStyle=ShipStyleId("alliance"))
    private fun request(command:Int,p:Navigator,target:Int=0,value:Int=0,argument:Int=0,card:Int=0,ticket:Int=1)=
        SocialRequest(StrategyActionRequest(if(p.characterId==10000)"host" else "guest",if(p.characterId==10000)"h" else "g",
            p.characterId,p.country,1,command,card,if(p.characterId==10000)1 else 8,target=target,value=value,argument=argument),1,ticket)
    private fun act(r:SocialRequest,s:PlanetStocks=stocks(),p:Navigator=pilot(),others:Map<String,Navigator> = emptyMap(),
        definitions:CountryCatalog=catalog()):SocialResult {
        val accounts=others+(r.identity.account to p)
        return SocialActions.evaluate(galaxy,r,session,p,s,accounts.values,accounts,definitions)
    }
    private fun unchanged(result:SocialResult,before:PlanetStocks,p:Navigator,reason:SocialRejection) {
        assertEquals(reason,result.rejection);assertEquals(before,result.stocks);assertEquals(p,result.player)
        assertTrue(result.navigators.isEmpty());assertFalse(result.changed)
    }

    @Test fun educationUsesTheActualCountryAndItsOfficeSystemInsteadOfDisplayOrShipStyle() {
        for(system in listOf("empire","alliance")) {
            val card=if(system=="empire")38 else 174
            val definitions=catalog(system,native=if(system=="empire")3 else 2)
            val s=stocks(card);val lecturer=pilot(10001)
            val opened=act(request(27,lecturer,argument=1,card=card),s,lecturer,definitions=definitions)
            assertNull(opened.rejection);assertEquals(840,opened.player?.mcp)
            val taught=assertNotNull(opened.stocks)
            val heard=act(request(93,pilot(),ticket=taught.social.nextId),taught,others=mapOf("guest" to lecturer),definitions=definitions)
            assertNull(heard.rejection);assertEquals(100,heard.academy?.gained)
            assertEquals(listOf(0,0,0,0,0,0,50,50),heard.stocks?.social?.academy?.experience?.get(10000))
            val simulator=pilot(kind=17)
            val game=act(request(94,simulator,argument=4),s,simulator,definitions=definitions)
            assertNull(game.rejection);assertEquals(990,game.player?.mcp);assertEquals(5,game.academy?.gained)
            unchanged(act(request(27,lecturer,card=if(card==38)174 else 38),s,lecturer,definitions=definitions),s,lecturer,SocialRejection.CARD)
            val foreignOffice=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(card,1002,10001,foreign))))
            unchanged(act(request(27,lecturer,card=card),foreignOffice,lecturer,definitions=definitions),foreignOffice,lecturer,SocialRejection.CARD)
            val staleOffice=s.copy(strategic=s.strategic.copy(capitals=s.strategic.capitals+(country to 1001)))
            unchanged(act(request(27,lecturer,card=card),staleOffice,lecturer,definitions=definitions),staleOffice,lecturer,SocialRejection.CARD)
            val abroad=pilot(base=1002)
            unchanged(act(request(93,abroad),s,abroad,definitions=definitions),s,abroad,SocialRejection.LOCATION)
        }
    }

    @Test fun aSavedLectureStopsTeachingForeignMovedOrOldGenerationLecturers() {
        val lecturer=pilot(10001);val opened=act(request(27,lecturer,argument=1,card=38),p=lecturer)
        val s=assertNotNull(opened.stocks)
        for(gone in listOf(lecturer.copy(country=foreign),lecturer.copy(generation=2),lecturer.copy(spotOwner=10001),
            lecturer.copy(mode=5),lecturer.copy(grid=2488))) {
            val heard=act(request(93,pilot(),argument=2,ticket=s.social.nextId),s,others=mapOf("guest" to gone))
            assertNull(heard.rejection);assertEquals(20,heard.academy?.gained);assertEquals(-1,heard.academy?.content)
            assertEquals(20,heard.stocks?.social?.academy?.experience?.get(10000)?.get(2))
        }
    }

    @Test fun speechSavesItsCountryAndPreservesEveryOtherSocialSubsystemAndReplayCharge() {
        val p=pilot(facility=2,kind=3)
        val s=stocks().let {it.copy(social=it.social.copy(
            gatherings=GatheringState(hostNextAt=mapOf(10001 to now+100)),
            proposals=ProposalState(blocked=mapOf(Triple(10001,10000,1) to now+100)),
            funds=FundState(confidence=mapOf(42 to (50L to 3L))),goals=GoalState(focus=mapOf(1001 to 2)),
            trade=TradeState(pacts=mapOf(2 to now+100)),production=ProductionState(paused=setOf(1001)),
            pirates=PirateState(nextSpawnAt=now+100),succession=SuccessionState(heir=10001)))}
        val result=act(request(14,p),s,p);assertNull(result.rejection);assertEquals(680,result.player?.mcp)
        val saved=assertNotNull(result.stocks);val receipt=saved.social.speeches.getValue(10000)
        assertEquals(country,receipt.country);assertEquals(72,saved.bases.getValue(1000).economy?.approval)
        assertEquals(s.social,saved.social.copy(nextId=s.social.nextId,speeches=s.social.speeches,
            speakers=s.social.speakers,squares=s.social.squares))
        val repeated=act(request(14,p),saved,assertNotNull(result.player))
        assertNull(repeated.rejection);assertFalse(repeated.changed);assertEquals(result.player,repeated.player);assertEquals(saved,repeated.stocks)
        val abroad=pilot(base=1002,facility=2,kind=3)
        unchanged(act(request(14,abroad),s,abroad),s,abroad,SocialRejection.LOCATION)
    }

    @Test fun aHotelInvitationRechecksCountryAndBaseOwnershipBeforeChargingTheHost() {
        val host=pilot(facility=17,kind=13,owner=10000);val guest=pilot(10001,facility=17,kind=1)
        val opened=act(request(13,host,target=10001),p=host,others=mapOf("guest" to guest))
        assertNull(opened.rejection);assertEquals(1000,opened.player?.mcp)
        val s=assertNotNull(opened.stocks);val accept=request(13,guest,target=10000,value=1,ticket=s.social.nextId)
        for(other in listOf(host.copy(country=foreign),host.copy(cpPaused=true),host.copy(grid=2488)))
            unchanged(act(accept,s,guest,mapOf("host" to other)),s,guest,SocialRejection.LOCATION)
        val captured=s.copy(bases=s.bases+(1000 to s.bases.getValue(1000).copy(owner=Allegiance.Country(foreign))))
        unchanged(act(accept,captured,guest,mapOf("host" to host)),captured,guest,SocialRejection.LOCATION)
        val accepted=act(accept,s,guest,mapOf("host" to host));assertNull(accepted.rejection)
        assertEquals(680,accepted.navigators.getValue("host").mcp);assertTrue(assertNotNull(accepted.stocks).social.gatherings.invites.isEmpty())
        unchanged(act(accept,assertNotNull(accepted.stocks),guest,mapOf("host" to host)),assertNotNull(accepted.stocks),guest,SocialRejection.STALE)
        val foreignGuest=guest.copy(country=foreign)
        unchanged(act(request(13,host,target=10001),stocks(),host,mapOf("guest" to foreignGuest)),stocks(),host,SocialRejection.LOCATION)
    }

    @Test fun partiesUseTheConfiguredPeerageAndCurrentCapitalAndHuntsUseAnActiveOwnFief() {
        for(system in listOf("empire","alliance"))for(peerage in listOf("empire","none")) {
            val definitions=catalog(system,peerage);val host=pilot(facility=18,kind=14);val guest=pilot(10001)
            val s=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+
                (10000 to it.strategic.people.getValue(10000).copy(peerage=if(peerage=="empire")Peerage.BARON else null))))}
            val party=act(request(10,host,target=10001),s,host,mapOf("guest" to guest),definitions)
            assertNull(party.rejection,"$system/$peerage")
            val privateRoom=host.copy(spotOwner=10000)
            unchanged(act(request(10,privateRoom,target=10001),s,privateRoom,mapOf("guest" to guest),definitions),s,privateRoom,SocialRejection.LOCATION)
            val invited=assertNotNull(party.stocks)
            val done=act(request(10,guest,target=10000,value=1,ticket=invited.social.nextId),invited,guest,mapOf("host" to host),definitions)
            assertNull(done.rejection);assertEquals(680,done.navigators.getValue("host").mcp)
            val moved=s.copy(strategic=s.strategic.copy(capitals=s.strategic.capitals+(country to 1001)))
            unchanged(act(request(10,host,target=10001),moved,host,mapOf("guest" to guest),definitions),moved,host,SocialRejection.LOCATION)
        }
        val hunter=pilot(base=1001,facility=18,kind=15);val guest=pilot(10001,base=1001)
        assertNull(act(request(11,hunter,target=10001),p=hunter,others=mapOf("guest" to guest)).rejection)
        val captured=stocks().let {it.copy(bases=it.bases+(1001 to it.bases.getValue(1001).copy(owner=Allegiance.Country(foreign))))}
        unchanged(act(request(11,hunter,target=10001),captured,hunter,mapOf("guest" to guest)),captured,hunter,SocialRejection.LOCATION)
    }

    @Test fun acceptingAPartyUsesTheHostsCurrentIdentityRatherThanBorrowingTheGuestsRank() {
        val definitions=catalog(peerage="none");val host=pilot(facility=18,kind=14);val guest=pilot(10001)
        val before=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people-10000))}
        val s=assertNotNull(act(request(10,host,target=10001),before,host,mapOf("guest" to guest),definitions).stocks)
        val accept=request(10,guest,target=10000,value=1,ticket=s.social.nextId)
        fun identity(rank:Int)=listOf(StrategicIdentity(10000,country,rank,0,List(8){50}))
        val forgedRank=accept.copy(identity=accept.identity.copy(rank=1,identities=identity(8)))
        unchanged(act(forgedRank,s,guest,mapOf("host" to host),definitions),s,guest,SocialRejection.LOCATION)
        val valid=accept.copy(identity=accept.identity.copy(identities=identity(7)))
        val result=act(valid,s,guest,mapOf("host" to host),definitions)
        assertNull(result.rejection);assertEquals(680,result.navigators.getValue("host").mcp)
    }
}
