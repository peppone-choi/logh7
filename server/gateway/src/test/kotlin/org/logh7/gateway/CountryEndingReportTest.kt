package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** 032f mode 14: public per-country results by actual country ID. Author: Peppone Choi. */
class CountryEndingReportTest {
    // 42 and 43 both display as 3 next to the original 2/3.
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"other_country","Other","alliance",3,0,"alliance")))}
    private val appearances=listOf(CountryAppearance(42,"신제국","new_empire",CountryColors(primary=0x123456),1),
        CountryAppearance(44,"사라진 나라","alliance",CountryColors(primary=0x654321),1))
    private val request=CountryEndingMessages.Request(OutfitPartyMessages.Query(1,7*512,CountryEndingMessages.MODE))
    private fun person(id:Long,country:Int)=CharacterProfile(CharacterMessages.Generate(4,id,3,3,0,"P$id","Q",18,1,1,1000001,
        List(8){50},0,0,0,20,3,0,"S$id",1),CountryId(country),ShipStyleId("empire"))
    private val profiles=mapOf("a" to person(10000,42),"b" to person(10001,43),"c" to person(10002,43),"d" to person(10003,2))
    private val end=SessionEnd(EndReason.CAPITAL_CAPTURED,emptySet(),500,affectedCountries=setOf(CountryId(44)),
        remainingCountries=setOf(CountryId(2),CountryId(42),CountryId(43)))
    private fun stats(n:Int)=SessionStatistics(n,n+1,10_000_000_000L+n,1000L+n,100L+n)
    private val result=SessionResult(end,stats(0),mapOf(
        CountryId(2) to SessionCountryResult(stats(2),SessionGrade.LIMITED,true,false,null),
        CountryId(42) to SessionCountryResult(stats(42),SessionGrade.DECISIVE,true,true,null),
        CountryId(43) to SessionCountryResult(stats(43),SessionGrade.LOCAL,false,true,null),
        CountryId(44) to SessionCountryResult(stats(44),SessionGrade.DEFEAT,false,false,null)),VictoryConditions())
    private fun session(ended:SessionEnd?=end,countries:Set<Int> = setOf(2,42,43,44),generation:Long=5)=
        SessionSnapshot(generation,600,0,0,emptyMap(),4,2,ended,countries.map(::CountryId).toSet(),
            if(ended==null)emptyMap() else mapOf(CountryId(44) to CountryDefeat(EndReason.CAPITAL_CAPTURED,450,CountryId(42))))
    private fun reply(snapshot:WorldSnapshot?)=CountryEndingReport.reply(request,snapshot,profiles,catalog,appearances)
    private fun expected(generation:Long,seconds:Long,status:Int,rows:List<CountryEndingMessages.Row>)=
        CountryEndingMessages.reply(request,generation,seconds,status,rows).map {LoginMessages.sessionMessage(0x032f,it)}
    private fun row(id:Int,name:String,primary:Int?,people:Int,grade:Int,p:Int,f:Int,out:Boolean,s:SessionStatistics)=
        CountryEndingMessages.Row(id,name,primary,people,grade,p,f,out,s.planets,s.fortresses,s.population,s.shipUnits,s.groundUnits)
    private fun same(a:List<ByteArray>,b:List<ByteArray>) {assertEquals(b.size,a.size);a.zip(b).forEach {(x,y)->assertContentEquals(y,x)}}

    @Test fun sameDisplayCountriesKeepSeparateResultsAndARemovedCountryIsPreserved() {
        // "d" defected to 42 in this generation; an old-generation navigator for "a" must not relabel it.
        val navigators=mapOf("d" to Navigator(5,10003,CountryId(42),10,0,0,0,shipStyle=ShipStyleId("empire")),
            "a" to Navigator(4,10000,CountryId(43),10,0,0,0,shipStyle=ShipStyleId("empire")))
        val snapshot=WorldSnapshot(1,0,session(),navigators=navigators,result=result)
        val name2=catalog.countries.getValue(2).name
        same(reply(snapshot),expected(5,500,3,listOf(
            row(2,name2,null,0,0,1,0,false,stats(2)),
            row(42,"신제국",0x123456,2,2,1,1,false,stats(42)),
            row(43,"Other",null,2,1,0,1,false,stats(43)),
            // Removed from the catalogue: explicit name, no stale appearance, statistics kept, eliminated flag.
            row(44,"국가 #44",null,0,3,0,0,true,stats(44)))))
    }

    @Test fun aRunningSessionKeepsItsActualRosterIncludingRemovedCountries() {
        val snapshot=WorldSnapshot(1,0,session(ended=null),planetStocks=PlanetStocks(5,0,emptyMap()))
        val rows=listOf(2,42,43,44).map {id->
            val name=when(id) {42->"신제국";44->"국가 #44";else->catalog.countries.getValue(id).name}
            val people=profiles.values.count {it.country==CountryId(id)}
            row(id,name,if(id==42)0x123456 else null,people,255,255,255,false,SessionStatistics())
        }
        same(reply(snapshot),expected(5,600,1,rows))
    }

    @Test fun theViewerCountryIsPassedThroughAndAnEmptyReplyCarriesNone() {
        val snapshot=WorldSnapshot(1,0,session(),result=result)
        val name2=catalog.countries.getValue(2).name
        val rows=listOf(row(2,name2,null,1,0,1,0,false,stats(2)),row(42,"신제국",0x123456,1,2,1,1,false,stats(42)),
            row(43,"Other",null,2,1,0,1,false,stats(43)),row(44,"국가 #44",null,0,3,0,0,true,stats(44)))
        same(CountryEndingReport.reply(request,snapshot,profiles,catalog,appearances,43),
            CountryEndingMessages.reply(request,5,500,3,rows,43).map {LoginMessages.sessionMessage(0x032f,it)})
        same(CountryEndingReport.reply(request,null,profiles,catalog,appearances,43),
            CountryEndingMessages.empty(request).map {LoginMessages.sessionMessage(0x032f,it)})
    }

    @Test fun noSessionIsAnEmptyReplyAndStaleDataIsRefused() {
        same(reply(null),CountryEndingMessages.empty(request).map {LoginMessages.sessionMessage(0x032f,it)})
        same(reply(WorldSnapshot(1,0)),CountryEndingMessages.empty(request).map {LoginMessages.sessionMessage(0x032f,it)})
        assertFailsWith<IllegalArgumentException>("stale stocks") {
            reply(WorldSnapshot(1,0,session(),planetStocks=PlanetStocks(4,0,emptyMap()),result=result))}
        val other=end.copy(gameSeconds=499)
        assertFailsWith<IllegalArgumentException>("result of another ending") {reply(WorldSnapshot(1,0,session(ended=other),result=result))}
    }

    @Test fun threeHundredFiftyCountriesPageToTheEnd() {
        val many=(1..350).associate {CountryId(it) to SessionCountryResult(stats(it % 7),SessionGrade.UNRESOLVED,false,false,null)}
        val big=WorldSnapshot(1,0,session(),result=result.copy(countries=many))
        fun page(offset:Int)=CountryEndingReport.reply(CountryEndingMessages.Request(
            OutfitPartyMessages.Query(1,7*512+offset,CountryEndingMessages.MODE)),big,profiles,catalog,appearances)
        assertEquals(1+2*CountryEndingMessages.PAGE,page(0).size)
        assertEquals(1+2*2,page(348).size,"the last two rows")
        assertEquals(1,page(350).size,"past the end: header only")
    }

    @Test fun anEliminatedViewerSeesOnlyItsOwnFinalForcesWhileOthersStayUndecided() {
        // Running session (no result): 42 already lost its capital to 43; 2 and 43 keep playing.
        val galaxy=Galaxy(listOf(GalaxySystem(3,10,4,"Empire",2),GalaxySystem(4,20,4,"Alliance",3),GalaxySystem(5,30,4,"Third",3)),
            listOf(GalaxyBase(1049,10,80,0,"Odin",0,20f,86400,0,0f,2f),GalaxyBase(2000,30,80,0,"Lost",0,20f,86400,0,0f,2f),
                GalaxyBase(2001,30,80,1,"Fort",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 10,Power.ALLIANCE to 20))
        val stocks=PlanetStocks(5,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0),
            2000 to PlanetStock(2000,Allegiance.Country(CountryId(42)),0,0,0,0),
            2001 to PlanetStock(2001,Allegiance.Country(CountryId(43)),0,0,0,0)))
        val running=SessionSnapshot(5,600,0,0,emptyMap(),4,2,null,setOf(2,42,43).map(::CountryId).toSet(),
            mapOf(CountryId(42) to CountryDefeat(EndReason.CAPITAL_CAPTURED,450,CountryId(43))))
        val snapshot=WorldSnapshot(1,0,running,planetStocks=stocks)
        val own=SessionResults.statistics(galaxy,snapshot).countries.getValue(CountryId(42))
        assertEquals(1,own.planets);assertEquals(0,own.fortresses);assertTrue(own.population>0,"real forces, not zero")
        fun names(id:Int)=when(id) {42->"신제국";else->catalog.countries.getValue(id).name}
        fun people(id:Int)=profiles.values.count {it.country==CountryId(id)}
        val rows=listOf(2,42,43).map {id->when(id) {
            // Own current statistics, DEFEAT, eliminated; never the other countries' figures.
            42->row(42,names(42),0x123456,people(42),3,255,255,true,own)
            else->row(id,names(id),null,people(id),255,255,255,false,SessionStatistics())
        }}
        same(CountryEndingReport.reply(request,snapshot,profiles,catalog,appearances,42,galaxy),
            CountryEndingMessages.reply(request,5,600,1,rows,42).map {LoginMessages.sessionMessage(0x032f,it)})
        // 43 is still playing: its own (and every other) running statistic stays hidden; 42 is still marked defeated.
        val hidden=rows.map {if(it.country==42)it.copy(planets=0,fortresses=0,population=0,ships=0,troops=0) else it}
        same(CountryEndingReport.reply(request,snapshot,profiles,catalog,appearances,43,galaxy),
            CountryEndingMessages.reply(request,5,600,1,hidden,43).map {LoginMessages.sessionMessage(0x032f,it)})
        // Without the world map no statistics are derived, even for the defeated viewer.
        same(CountryEndingReport.reply(request,snapshot,profiles,catalog,appearances,42),
            CountryEndingMessages.reply(request,5,600,1,hidden,42).map {LoginMessages.sessionMessage(0x032f,it)})
    }
    @Test fun replacingDefinitionsCannotOverflowOrDropTheSessionRoster() {
        val original=CountryCatalogLoader.load()
        val definitions=original.countries.values.toList()+(1..350-original.countries.size).map {n->
            CountryDefinition(5000+n,"replacement_$n","New $n","alliance",3,0,"alliance")}
        val updated=CountryCatalog(original.calendars.values.toList(),original.families.values.toList(),definitions)
        val snapshot=WorldSnapshot(1,0,session(ended=null,countries=(1..350).toSet()))
        assertEquals(350,CountryEndingReport.count(snapshot,updated))
        val last=CountryEndingMessages.Request(OutfitPartyMessages.Query(1,7*512+348,14))
        val rows=(1..350).map {row(it,"국가 #$it",null,0,255,255,255,false,SessionStatistics())}
        same(CountryEndingReport.reply(last,snapshot,emptyMap(),updated,emptyList()),
            CountryEndingMessages.reply(last,5,600,1,rows).map {LoginMessages.sessionMessage(0x032f,it)})
    }
}
