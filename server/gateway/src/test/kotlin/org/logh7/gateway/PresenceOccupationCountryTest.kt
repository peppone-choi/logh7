package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** Occupation refresh looks office cards up by the actual country, not the display byte. Author: Peppone Choi. */
class PresenceOccupationCountryTest {
    // 42: empire family displayed as 3. 43: alliance family displayed as 2. The power byte is the display code, so it differs from the family.
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3)),
        listOf(GalaxyBase(1049,2487,80,0,"Here",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val people=listOf(3,2).mapIndexed {i,power->CharacterMessages.Generate(0,10000L+i,power,if(power==2)0 else 89,
        0,"Pilot","Name",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)}
    private val winner=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true).copy(country=CountryId(42))
    private val loser=Navigator(1,10001,Power.ALLIANCE,2487,700,500,300,baseId=1049,mode=4,spotId=10490001,returnBase=1049)
        .copy(country=CountryId(43))
    private val held=BootstrapMessages.OfficeCard(39,1049)
    /** Distinct fake card per actual country; its kind matches the display family the wire requires. */
    private fun card(country:Int)=BootstrapMessages.OfficeCard(if(country==42)190 else 39,country)

    private class Run(val calls:List<Pair<Int,Int>>,val winner:List<ByteArray>,val loser:List<ByteArray>)
    /** [expelledCountry] is the loser's country in the current snapshot; [eventCopy] is the stale expelled record. */
    private fun run(expelledCountry:CountryId?,eventCopy:CountryId?=CountryId(43),
        update:(Navigator)->Navigator={it}):Run {
        val expelled=loser.copy(baseId=0,mode=5,spotId=0,returnBase=0,cpPaused=true,
            posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1049))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(CountryId(42)),0,1000,1000,1000)))
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),
            navigators=mapOf("winner" to winner,"loser" to update(expelled.copy(country=expelledCountry))),planetStocks=stocks)
        val packets=List(2){mutableListOf<List<ByteArray>>()};val presence=GamePresence()
        listOf(winner,loser).forEachIndexed {i,p->presence.update(GamePresence.Member("c$i",people[i],p,packets[i]::add,
            if(i==1)listOf(held) else emptyList()))}
        packets.forEach {it.clear()}
        val calls=mutableListOf<Pair<Int,Int>>()
        val event=BaseOccupation(1,2487,1049,Allegiance.Country(CountryId(43)),CountryId(42),250,emptyMap(),
            mapOf("loser" to expelled.copy(country=eventCopy)),mapOf(10000 to "c0",10001 to "c1"))
        presence.occupation(event,snapshot,WorldBootstrap(galaxy,catalog),{id->people.singleOrNull {it.id==id.toLong()}},
            {id,country,_->calls+=id to country;listOf(card(country))})
        return Run(calls,packets[0].flatten(),packets[1].flatten())
    }
    private fun List<ByteArray>.has(packet:ByteArray)=any {it.contentEquals(packet)}
    private fun expelledRecord(cards:List<BootstrapMessages.OfficeCard>)=BootstrapMessages.character(people[1],
        loser.pcp,loser.mcp,0,0,0,cards)
    private fun loserChange(cards:List<BootstrapMessages.OfficeCard>)=BootstrapMessages.changedCharacter(people[1],
        loser.pcp,loser.mcp,0,0,0,cards)

    @Test fun cardsAreLookedUpByTheActualCountryOfEachSide() {
        val r=run(CountryId(43))
        assertEquals(setOf(10000 to 42,10001 to 43),r.calls.toSet(),"never the display codes 3/2")
        assertTrue(r.winner.has(BootstrapMessages.changedCharacter(people[0],winner.pcp,winner.mcp,winner.spotId,
            winner.spotOwner,winner.returnBase,listOf(card(42)))),"winner's own 42 cards")
        assertTrue(r.winner.has(expelledRecord(listOf(card(43)))),"expelled 43 record in the 42 audience")
        assertTrue(r.loser.has(loserChange(listOf(card(43)))))
    }

    @Test fun anUnaffiliatedCountryHoldsNoNationalOfficeAndTheCurrentSnapshotWins() {
        val r=run(null)
        assertEquals(listOf(10000 to 42),r.calls,"no lookup without membership, not even the stale event copy 43")
        assertTrue(r.winner.has(expelledRecord(emptyList())))
        assertTrue(r.loser.has(loserChange(emptyList())),"the held card is cleared")
        // A removed country (44) is not covered here: the same refresh's GridForceReport/TacticalBootstrap already
        // require a known country, outside this change. The card lookup itself skips it by the same catalogue check.
        // A stale event copy without membership does not hide the current snapshot's country.
        assertEquals(setOf(10000 to 42,10001 to 43),run(CountryId(43),eventCopy=null).calls.toSet())
    }
    @Test fun viewersReceiveOnlyCurrentExpelledCharactersAndTheirSavedValues() {
        val updated=run(CountryId(43)) {it.copy(pcp=600,mcp=400,returnBase=1050)}
        assertTrue(updated.winner.has(BootstrapMessages.character(people[1],600,400,0,0,1050,listOf(card(43)))))
        assertFalse(updated.winner.has(expelledRecord(listOf(card(43)))))
        val departed=run(CountryId(43)) {it.copy(grid=2488,cpPaused=false,posture=null)}
        assertTrue(departed.winner.none {java.nio.ByteBuffer.wrap(it).getShort(4).toInt()==0x0323})
    }

}
