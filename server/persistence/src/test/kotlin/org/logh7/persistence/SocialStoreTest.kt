package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** 저장35·34 호환과 CP/영향력/지지/재실행 제한의 원자 저장. 작성: Peppone Choi. */
class SocialStoreTest {
    @Test fun rankChangeRepliesPreserveTheirNativeRanksAlongsideOlderProposalRows() {
        val pending=ProposalMail(1,10000,10001,17,19,ProposalActions.DEMOTION,true,0,86400,
            mcp=160,target=10002,country=CountryId(2))
        val legacyApproved=pending.copy(id=2,reply=Proposals.APPROVE)
        val approved=legacyApproved.copy(id=3,rankChangeMailRank=9)
        val countryInferred=approved.copy(id=4,country=null)
        val promoted=approved.copy(id=5,command=ProposalActions.SPECIAL_PROMOTION,rankChangeMailRank=10)
        val state=SocialState(nextId=6,proposals=ProposalState(listOf(pending,legacyApproved,approved,countryInferred,promoted)))
        val restored=SocialStateCodec.decode(SocialStateCodec.encode(state))
        assertEquals(state,restored)
        assertEquals(listOf(null,null,9,9,10),restored.proposals.mails.map {it.rankChangeMailRank})
    }

    @Test fun assignmentMailPreservesSignedRowsAndReplayIdentityAlongsideOldRows() {
        val ships = mutableListOf(ReorganizationShip(32, 2, 0), ReorganizationShip(40, -1, 0))
        val troops = mutableListOf(ReorganizationTroop(1003, 0, -9), ReorganizationTroop(1003, 1, 5))
        val assignment = StockAssignmentProposal(50, 1049, 2, 0, ships, troops, -1000)
        ships.clear()
        troops.clear()
        assertEquals(listOf(2, -1), assignment.ships.map { it.units })
        assertEquals(listOf(-9, 5), assignment.troops.map { it.units })
        val old = ProposalMail(1, 10000, 10001, 17, 19, ProposalActions.RESIGNATION, true, 100, 86500, mcp = 40)
        val mail = ProposalMail(2, 10000, 10002, 1, 24, ProposalActions.ASSIGNMENT, true, 100, 86500,
            mcp = 80, country = CountryId(2), assignment = assignment, assignmentOriginalRecipient = 10001)
        val expired = mail.copy(id = 3, sender = 10003, assignmentExpired = true)
        val expected = SocialState(nextId = 4, proposals = ProposalState(listOf(old, mail, expired)))
        val restored = SocialStateCodec.decode(SocialStateCodec.encode(expected))
        assertEquals(expected, restored)
        assertTrue(restored.proposals.mails[1].pending)
        assertFalse(restored.proposals.mails[2].pending)
    }

    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val receipt=SpeechReceipt(1,10000,CountryId(2),1049,10490404,now,60,10,11,70,72,now+86400)
    private val social=SocialState(2,mapOf(10000 to receipt),mapOf(10000 to receipt.nextAt),mapOf(1049 to receipt.nextAt))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,PlanetEconomy(1000,600,72,80),treasury=200)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,influence=11,money=789))),
        budget=BudgetState(mapOf(1049 to 75)),social=social)
    @Test fun speechCountriesAndPendingEducationAndInvitationsSurviveAWorldReopenWithTheOldReceiptGrammar() {
        for(country in listOf(2,3,42,43)) {
            val receipt=this.receipt.copy(country=CountryId(country))
            val value=social.copy(speeches=mapOf(10000 to receipt),
                academy=AcademyState(lectures=mapOf(10001 to Lecture(10001,1049,10494017,2,now,now+7200))),
                gatherings=GatheringState(invites=mapOf(10000 to GatheringInvite(10000,10001,13,1049,10493414,now+1440))))
            val text=SocialStateCodec.encode(value)
            assertTrue(text.split('|')[3].startsWith("1:10000:$country:1049:"))
            assertEquals(value,SocialStateCodec.decode(text))
            assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(text.replace("1:10000:$country:1049:","1:10000:0:1049:"))}
            val saved=stocks.copy(bases=stocks.bases.mapValues {(_,b)->b.copy(owner=Allegiance.Country(CountryId(country)))},social=value)
            val player=Navigator(1,10000,CountryId(country),2487,1000,1000,680,shipStyle=ShipStyleId("alliance"))
            val path=directory.resolve("country-$country")
            FileWorldStateStore(path).use {it.saveWorld(mapOf("actor" to player),saved)}
            FileWorldStateStore(path).use {assertEquals(saved,it.loadStocks());assertEquals(player,it.loadNavigation()["actor"])}
        }
        val old="2|10000:${now+86400}|1049:${now+86400}|1:10000:2:1049:10490404:$now:60:10:11:70:72:${now+86400}"
        assertEquals(social,SocialStateCodec.decode(old));assertEquals(old,SocialStateCodec.encode(social))
    }
    @Test fun format35PreservesBudgetAndSAndExplicitSpeakerAndSquareLimitsWhileLoading34() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks);assertEquals(stocks,it.load())}
        val lines=Files.readAllLines(path);assertTrue(lines.first().startsWith("LOGH7-STOCK-35\t"))
        assertEquals(19,lines.first().split('\t').size)
        for(section in listOf("S","C","M","F","V","B","X"))assertEquals(1,lines.count {it.startsWith("$section\t")})
        val old=stocks.copy(social=SocialState());FilePlanetStockStore(path).use {it.save(old);assertEquals(old,it.load())}
        assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-34\t"))
        val only=stocks.copy(budget=BudgetState());FilePlanetStockStore(path).use {it.save(only);assertEquals(only,it.load())}
    }
    @Test fun academyExperienceLecturesAttendanceAndWargamesRoundTripAsAnOptionalFifthField() {
        val academy=AcademyState(mapOf(10000 to listOf(0,1,2,3,4,5,6,99)),mapOf(10001 to Lecture(10001,1049,10494017,2,now,now+7200)),
            mapOf(10000 to now+86400),mapOf(10000 to (now/86400 to 3)))
        val value=social.copy(academy=academy);val text=SocialStateCodec.encode(value);assertEquals(value,SocialStateCodec.decode(text))
        assertEquals(5,text.split('|').size);assertEquals(4,SocialStateCodec.encode(social).split('|').size)
        for(bad in listOf(text.replace(".99",".100"),text.replace(":10494017:2:",":10494017:4:"),text.replace(":3","" ),text+"/x"))
            assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(bad)}
        val path=directory.resolve("academy.tsv");val saved=stocks.copy(social=value)
        FilePlanetStockStore(path).use {it.save(saved);assertEquals(saved,it.load())}
    }
    @Test fun meetingFriendshipAndPairLimitsRoundTripAsAnOptionalSixthField() {
        val meeting=MeetingState(mapOf(MeetingActions.pair(10001,10000) to 53),mapOf(MeetingActions.pair(10000,10002) to now+86400))
        val value=social.copy(meeting=meeting);val text=SocialStateCodec.encode(value);assertEquals(value,SocialStateCodec.decode(text))
        assertEquals(6,text.split('|').size);assertTrue(text.endsWith("|-/-/-/-|10000:10001:53/10000:10002:${now+86400}"))
        for(bad in listOf(text.replace("10000:10001:53","10001:10000:53"),text.replace(":53/",":101/"),text.replace("10000:10001:53","10000:10000:53")))
            assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(bad)}
        val path=directory.resolve("meeting.tsv");val saved=stocks.copy(social=value)
        FilePlanetStockStore(path).use {it.save(saved);assertEquals(saved,it.load())}
    }
    @Test fun gatheringInvitesAndLimitsRoundTripAsAnOptionalSeventhField() {
        val gatherings=GatheringState(mapOf(10000 to GatheringInvite(10000,10001,13,1049,10493414,now+1440)),
            mapOf(10002 to now+86400),mapOf(MeetingActions.pair(10002,10003) to now+86400))
        val value=social.copy(gatherings=gatherings);val text=SocialStateCodec.encode(value);assertEquals(value,SocialStateCodec.decode(text))
        assertEquals(7,text.split('|').size)
        assertTrue(text.endsWith("|10000:10001:13:1049:10493414:${now+1440}/10002:${now+86400}/10002:10003:${now+86400}"))
        assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(text.replace(":13:1049:",":14:1049:"))}
        val path=directory.resolve("gathering.tsv");val saved=stocks.copy(social=value)
        FilePlanetStockStore(path).use {it.save(saved);assertEquals(saved,it.load())}
    }
    @Test fun outfitDisciplineIsAStock36Section() {
        val path=directory.resolve("discipline.tsv")
        val saved=stocks.copy(discipline=mapOf(7 to OutfitDiscipline(80,now),3 to OutfitDiscipline(50,now+1)))
        FilePlanetStockStore(path).use {it.save(saved);assertEquals(saved,it.load())}
        val lines=Files.readAllLines(path);assertTrue(lines.first().startsWith("LOGH7-STOCK-36\t"))
        assertEquals("L\t3:50:${now+1},7:80:$now",lines.single {it.startsWith("L\t")})
        FilePlanetStockStore(path).use {it.save(stocks);assertEquals(stocks,it.load())}
        assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-35\t"))
    }
    @Test fun blackHoleTidalHourIsAStock37SectionEvenWithoutDiscipline() {
        val path=directory.resolve("tidal.tsv")
        val saved=stocks.copy(tidalHour=123)
        FilePlanetStockStore(path).use {it.save(saved);assertEquals(saved,it.load())}
        val lines=Files.readAllLines(path);assertTrue(lines.first().startsWith("LOGH7-STOCK-37\t"))
        assertEquals("L\t-",lines.single {it.startsWith("L\t")});assertEquals("H\t123",lines.single {it.startsWith("H\t")})
        Files.write(path,lines.filterNot {it.startsWith("H\t")})
        FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
    }
    @Test fun airTrainingIsAStock38SectionKeepingAZeroTidalHour() {
        val path=directory.resolve("air.tsv")
        val saved=stocks.copy(airTraining=mapOf(9 to 2,3 to 1))
        FilePlanetStockStore(path).use {it.save(saved);assertEquals(saved,it.load())}
        val lines=Files.readAllLines(path);assertTrue(lines.first().startsWith("LOGH7-STOCK-38\t"))
        assertEquals("K\t3:1,9:2",lines.single {it.startsWith("K\t")});assertEquals("H\t0",lines.single {it.startsWith("H\t")})
        val tidal=saved.copy(tidalHour=55)
        FilePlanetStockStore(path).use {it.save(tidal);assertEquals(tidal,it.load())}
    }
    @Test fun malformedReceiptsMissingLimitsDuplicateSectionsAndReusedIdsAreRejected() {
        val text=SocialStateCodec.encode(social);assertEquals(social,SocialStateCodec.decode(text))
        for(bad in listOf(text.replaceFirst("2|","1|"),text+"|trailing",text.replace("10000:${receipt.nextAt}","10000:0"),
            text.replace("1049:${receipt.nextAt}","1049:0"),text.replace(":10:11:70:72:",":10:99:70:72:")))
            assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(bad)}
        val path=directory.resolve("bad.tsv");FilePlanetStockStore(path).use {it.save(stocks)};val lines=Files.readAllLines(path)
        for(bad in listOf(lines.filterNot {it.startsWith("X\t")},lines+lines.single {it.startsWith("X\t")})) {
            Files.write(path,bad);FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
        }
    }
    @Test fun pointerFailureRestoresAllPreviousValuesAndSuccessfulReopenKeepsReceiptAndLimitsImmutable() {
        val path=directory.resolve("world");val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val before=stocks.copy(social=SocialState(),strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,influence=10,money=789))),
            bases=stocks.bases.mapValues {(_,b)->b.copy(economy=b.economy!!.copy(approval=70))})
        FileWorldStateStore(path).use {store ->
            store.saveWorld(mapOf("actor" to player),before);store.beforeSwitch={throw IOException("injected speech pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(mapOf("actor" to player.copy(mcp=680)),stocks)}
        }
        FileWorldStateStore(path).use {store ->
            assertEquals(before,store.loadStocks());assertEquals(player,store.loadNavigation()["actor"])
            store.saveWorld(mapOf("actor" to player.copy(mcp=680)),stocks)
        }
        FileWorldStateStore(path).use {store ->
            val saved=assertNotNull(store.loadStocks());assertEquals(stocks,saved);assertEquals(680,store.loadNavigation()["actor"]?.mcp)
            assertFailsWith<UnsupportedOperationException>{(saved.social.speeches as MutableMap<Int,SpeechReceipt>).clear()}
            assertFailsWith<UnsupportedOperationException>{(saved.social.speakers as MutableMap<Int,Long>).clear()}
            assertFailsWith<UnsupportedOperationException>{(saved.social.squares as MutableMap<Int,Long>).clear()}
        }
    }
    @Test fun proposalMailsAndRefusalBlocksRoundTripAsAnOptionalEighthField() {
        val proposals=ProposalState(listOf(ProposalMail(1,10002,10004,25,1,0x0709,true,100,86500,reply=1,mcp=80)),
            mapOf(Triple(10002,10004,0x0709) to 86600L))
        val state=SocialState(proposals=proposals)
        val encoded=SocialStateCodec.encode(state)
        assertEquals(8,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
        assertEquals(4,SocialStateCodec.encode(SocialState()).split('|').size,"no proposals keep the old form")
    }
    @Test fun proposalCountriesAndActualPaymentPoolsSurviveAWorldReopenWhileLegacyRowsKeepTheirGrammar() {
        val original=listOf(
            ProposalMail(1,10000,10001,17,19,ProposalActions.RESIGNATION,true,now,now+86400,pcp=80),
            ProposalMail(2,10001,10000,19,17,ProposalActions.DISMISSAL,false,now,now+86400,mcp=80,target=10002,targetCard=19),
            ProposalMail(3,10001,10000,19,17,ProposalActions.APPOINTMENT,false,now,now+86400,mcp=80,target=10001,targetCard=18,seat=1049))
        val old=SocialState(nextId=4,proposals=ProposalState(original))
        val oldText=SocialStateCodec.encode(old)
        assertEquals(listOf(12,14,15),oldText.split('|')[7].substringBefore('/').split(',').map {it.split(':').size})
        assertEquals(old,SocialStateCodec.decode(oldText));assertEquals(oldText,SocialStateCodec.encode(SocialStateCodec.decode(oldText)))
        assertTrue(SocialStateCodec.decode(oldText).proposals.mails.all {it.originCountry==CountryId(2) && it.country==null})
        val boundResignation=ProposalMail(4,10000,10001,1,39,ProposalActions.RESIGNATION,true,now,now+86400,
            mcp=40,seat=200,country=CountryId(2))
        val actual=old.copy(nextId=5,proposals=ProposalState(original.mapIndexed {i,m->
            m.copy(country=CountryId(if(i==2)43 else 42),seat=if(i==1)1049 else m.seat)
        }+boundResignation))
        val encoded=SocialStateCodec.encode(actual)
        assertTrue(encoded.split('|')[7].substringBefore('/').split(',').all {it.split(':').size==16})
        assertEquals(actual,SocialStateCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(encoded.replace(":0:0:0:42,",":0:0:0:0,"))}
        val saved=stocks.copy(social=actual)
        val player=Navigator(1,10000,CountryId(42),2487,920,0,1000,shipStyle=ShipStyleId("alliance"),pcpUsed=80)
        val path=directory.resolve("proposals-country")
        FileWorldStateStore(path).use {it.saveWorld(mapOf("actor" to player),saved)}
        FileWorldStateStore(path).use {assertEquals(saved,it.loadStocks());assertEquals(player,it.loadNavigation()["actor"])}
    }
    @Test fun homeIntelligenceReportsSurviveWorldReopenWithoutChangingTheLegacyGrammar() {
        val a=CountryId(42);val b=CountryId(43)
        val first=HomeIntelligenceReport(a,1049,now,3_000_000_000,75,90)
        val second=first.copy(country=b,treasury=20)
        val legacy=JusticeState(disguises=mapOf(10000 to Disguise(10000,2,b,1,1000,a)))
        val legacySocial=SocialState(justice=legacy)
        val legacyText=SocialStateCodec.encode(legacySocial)
        val justice=legacy.copy(homeReports=mapOf((b to 1049) to second,(a to 1049) to first))
        val social=SocialState(justice=justice)
        val text=SocialStateCodec.encode(social)
        assertEquals(legacySocial,SocialStateCodec.decode(legacyText))
        assertEquals(legacyText,text.substringBeforeLast('/'))
        assertEquals(social,SocialStateCodec.decode(text))
        assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(text+","+text.substringAfterLast('/').substringBefore(','))}
        val saved=stocks.copy(social=social)
        val path=directory.resolve("home-intelligence-reports")
        FileWorldStateStore(path).use {it.saveWorld(emptyMap(),saved)}
        FileWorldStateStore(path).use {assertEquals(saved,it.loadStocks())}
    }

    @Test fun stock53MatchesReportPresenceAndKeepsTacticalEntryAuthorityOptional() {
        val country=CountryId(2)
        val report=HomeIntelligenceReport(country,1049,now,200,75,90)
        val withoutReports=stocks.copy(social=SocialState())
        val withReports=withoutReports.copy(social=SocialState(justice=JusticeState(homeReports=mapOf((country to 1049) to report))))
        val commands=TacticalCommandState(entryAuthority=mapOf(1 to 10000))
        val withBoth=withReports.copy(tacticalCommands=commands)
        val path=directory.resolve("home-reports-format.tsv")
        FilePlanetStockStore(path).use {store->
            store.save(withReports)
            assertEquals(withReports,store.load())
            assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-53\t"))
            store.save(withBoth)
            assertEquals(withBoth,store.load())
            val lines=Files.readAllLines(path)
            assertTrue(lines.first().startsWith("LOGH7-STOCK-53\t"))
            Files.write(path,listOf(lines.first().replaceFirst("LOGH7-STOCK-53","LOGH7-STOCK-52"))+lines.drop(1))
            assertContains(assertFailsWith<IllegalArgumentException>{store.load()}.message.orEmpty(),"stock format 53")

            val onlyEntry=withoutReports.copy(tacticalCommands=commands)
            store.save(onlyEntry)
            assertEquals(onlyEntry,store.load())
            val earlier=Files.readAllLines(path)
            assertTrue(earlier.first().startsWith("LOGH7-STOCK-52\t"))
            Files.write(path,listOf(earlier.first().replaceFirst("LOGH7-STOCK-52","LOGH7-STOCK-53"))+earlier.drop(1))
            assertContains(assertFailsWith<IllegalArgumentException>{store.load()}.message.orEmpty(),"stock format 53")
        }
    }

    @Test fun justiceCountriesCustodyAndSpyOriginsRoundTripWithLegacyRowsUnchanged() {
        val a=CountryId(42);val b=CountryId(43)
        val state=SocialState(justice=JusticeState(
            permits=mapOf(10001 to ArrestPermit(10001,10000,a,3,1000)),
            rights=mapOf(10002 to ArrestRight(10002,10001,10000,1000,a)),
            detentions=mapOf(10003 to Detention(10003,b,1,1000,base=1049)),
            watches=mapOf(10004 to Watch(10004,10005,1,1000,a)),
            infiltrations=mapOf(10006 to Infiltration(10006,1049,10490003,1000,a,b)),
            disguises=mapOf(10007 to Disguise(10007,2,b,1,1000,a))))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(state,SocialStateCodec.decode(encoded),"watch and infiltration coexist")
        for(bad in listOf(encoded.replace("10001:10000:42:","10001:10000:0:"),
            encoded.replace("10002:10001:10000:1000:42","10002:10001:10000:1000:0"),
            encoded.replace("10003:43:1:1000:0:1049","10003:43:1:1000:0:0")))
            assertFailsWith<IllegalArgumentException> {SocialStateCodec.decode(bad)}
        val saved=stocks.copy(social=state)
        val path=directory.resolve("justice-countries")
        FileWorldStateStore(path).use {it.saveWorld(emptyMap(),saved)}
        FileWorldStateStore(path).use {assertEquals(saved,it.loadStocks())}
        val legacy=SocialState(justice=JusticeState(
            permits=mapOf(1 to ArrestPermit(1,2,CountryId(2),3,1000)),rights=mapOf(3 to ArrestRight(3,1,2,1000)),
            detentions=mapOf(4 to Detention(4,CountryId(3),1,1000)),watches=mapOf(5 to Watch(5,6,1,1000)),
            infiltrations=mapOf(7 to Infiltration(7,1049,10490003,1000)),
            disguises=mapOf(8 to Disguise(8,2,CountryId(3),1,1000))))
        val old=SocialStateCodec.encode(legacy)
        assertEquals(legacy,SocialStateCodec.decode(old));assertEquals(old,SocialStateCodec.encode(SocialStateCodec.decode(old)))
    }
    @Test fun coupPlotsAndLoyaltyRoundTripAsAnOptionalTenthField() {
        val coup=CoupState(mapOf(10000 to Plot(10000,CountryId(2),100,setOf(10003),listOf(10002,10001),500,setOf(1049))),mapOf(7 to 20))
        val state=SocialState(coup=coup)
        val encoded=SocialStateCodec.encode(state)
        assertEquals(10,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
        assertEquals(listOf(10002,10001),SocialStateCodec.decode(encoded).coup.plots.getValue(10000).members,"joining order")
    }
    @Test fun coupCountriesAndCapitalTimersPersistWithoutChangingLegacyRows() {
        val old=SocialState(coup=CoupState(mapOf(10000 to Plot(10000,CountryId(2),100,revolt=200,
            bases=setOf(1049),capitalSince=300))))
        val legacy=SocialStateCodec.encode(old)
        assertEquals(8,legacy.split('|')[9].substringBefore('/').split(':').size)
        assertEquals(legacy,SocialStateCodec.encode(SocialStateCodec.decode(legacy)))
        val actual=old.copy(coup=CoupState(mapOf(10000 to old.coup.plots.getValue(10000).copy(country=CountryId(42),capitalBase=1049),
            10001 to Plot(10001,CountryId(43),100))))
        val text=SocialStateCodec.encode(actual)
        assertEquals(actual,SocialStateCodec.decode(text))
        assertFailsWith<IllegalArgumentException> {SocialStateCodec.decode(text.replace("10000:42:","10000:0:"))}
        assertFailsWith<IllegalArgumentException> {SocialStateCodec.decode(text.replace(":300:1049",":300:0"))}
        val saved=stocks.copy(social=actual)
        val path=directory.resolve("coup-countries")
        FileWorldStateStore(path).use {it.saveWorld(emptyMap(),saved)}
        FileWorldStateStore(path).use {assertEquals(saved,it.loadStocks())}
    }
    @Test fun fundBoxesRoundTripAsAnOptionalEleventhField() {
        val state=SocialState(funds=FundState(mapOf(2 to (100L to 5L),42 to (100L to 50L),43 to (7L to 8L)),mapOf(10001 to 300L)))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(11,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
        val value=stocks.copy(social=state)
        val path=directory.resolve("country-funds")
        FilePlanetStockStore(path).use {it.save(value)}
        FilePlanetStockStore(path).use {assertEquals(value,it.load())}
        assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(encoded.replace("42:100:50","0:100:50"))}
    }
    @Test fun goalsRoundTripAsAnOptionalTwelfthField() {
        val state=SocialState(goals=GoalState(mapOf(2 to NationalGoal(1050,10000,7)),mapOf(1049 to 2)))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(12,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
    }
    @Test fun actualCountriesKeepSeparateNationalGoalsAfterReopeningTheWorld() {
        val goals=GoalState(mapOf(42 to NationalGoal(1050,10000,7),43 to NationalGoal(1049,10001,8)),mapOf(1049 to 2))
        val value=PlanetStocks(1,0,mapOf(
            1049 to stocks.bases.getValue(1049).copy(owner=Allegiance.Country(CountryId(42))),
            1050 to stocks.bases.getValue(1049).copy(baseId=1050,owner=Allegiance.Country(CountryId(43)))),
            social=SocialState(goals=goals))
        val encoded=SocialStateCodec.encode(value.social)
        assertEquals(value.social,SocialStateCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(encoded.replace("42:1050:","0:1050:"))}
        val path=directory.resolve("country-goals.tsv")
        FilePlanetStockStore(path).use {it.save(value)}
        FilePlanetStockStore(path).use {assertEquals(value,it.load())}
    }
    @Test fun tradeRoundTripsAsAnOptionalThirteenthField() {
        val state=SocialState(trade=TradeState(mapOf(10000 to TradeRoute(10000,1049,1050,900,100)),mapOf(3 to 20),mapOf(7 to 50L),mapOf(2 to 60L),mapOf(3 to 70L),mapOf(2 to 80L),mapOf(2 to 3)))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(13,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
    }
    @Test fun piratesRoundTripAsAnOptionalFourteenthField() {
        val state=SocialState(pirates=PirateState(mapOf(1 to PirateFleet(1,2490,7,listOf(PirateUnit(900003,300),PirateUnit(900004,0)))),2,99))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(14,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
    }
    @Test fun productionRoundTripsAsAnOptionalFifteenthField() {
        val state=SocialState(production=ProductionState(mapOf(1049 to 9L),setOf(1050),mapOf(1049 to ShipOrder(3,77)),setOf(1051)))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(15,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
    }
}
