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
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val receipt=SpeechReceipt(1,10000,2,1049,10490404,now,60,10,11,70,72,now+86400)
    private val social=SocialState(2,mapOf(10000 to receipt),mapOf(10000 to receipt.nextAt),mapOf(1049 to receipt.nextAt))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,PlanetEconomy(1000,600,72,80),treasury=200)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,influence=11,money=789))),
        budget=BudgetState(mapOf(1049 to 75)),social=social)
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
    @Test fun coupPlotsAndLoyaltyRoundTripAsAnOptionalTenthField() {
        val coup=CoupState(mapOf(10000 to Plot(10000,2,100,setOf(10003),listOf(10002,10001),500,setOf(1049))),mapOf(7 to 20))
        val state=SocialState(coup=coup)
        val encoded=SocialStateCodec.encode(state)
        assertEquals(10,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
        assertEquals(listOf(10002,10001),SocialStateCodec.decode(encoded).coup.plots.getValue(10000).members,"joining order")
    }
    @Test fun fundBoxesRoundTripAsAnOptionalEleventhField() {
        val state=SocialState(funds=FundState(mapOf(2 to (100L to 5L)),mapOf(10001 to 300L)))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(11,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
    }
    @Test fun goalsRoundTripAsAnOptionalTwelfthField() {
        val state=SocialState(goals=GoalState(mapOf(2 to NationalGoal(1050,10000,7)),mapOf(1049 to 2)))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(12,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
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
        val state=SocialState(production=ProductionState(mapOf(1049 to 9L),setOf(1050),mapOf(1049 to ShipOrder(3,77))))
        val encoded=SocialStateCodec.encode(state)
        assertEquals(15,encoded.split('|').size);assertEquals(state,SocialStateCodec.decode(encoded))
    }
}
