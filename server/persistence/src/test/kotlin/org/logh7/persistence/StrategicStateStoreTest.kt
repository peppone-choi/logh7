package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** CP·훈련/계급·예약을 같은 월드 슬롯에 저장. 작성: Peppone Choi. */
class StrategicStateStoreTest {
    @TempDir lateinit var directory:Path
    private val navigator=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val state=StrategicState(mapOf(10000 to StrategicPerson(1,2,3_000_000,Peerage.BARON,100,25,30,40,List(8){55},setOf(0,127))),
        listOf(SuppressionOrder(10001,200,1049,CountryId(2),4_000_000)),listOf(OfficeHolding(6,1049,10000),OfficeHolding(4,1049,10001)),
        mapOf(1049 to 10000),listOf(StrategicOperation(1,10001,24,CountryId(2),2487,StrategicForce.FLEET,StrategicPurpose.DEFEND,1,
            3_000_000,defended=setOf(1049))))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),strategic=state)

    @Test fun checkpointHasStrictCanonicalRoundTripAndRejectsDuplicateIdentities() {
        val encoded=StrategicStateCodec.encode(state)
        assertEquals(state,StrategicStateCodec.decode(encoded))
        val person=encoded.substringBefore('|')
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode("$person;$encoded")}
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode(encoded.replace(":1:2:3000000:",":1:4:3000000:"))}
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode("0$encoded")}
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode("-|-|-|-|-|-")}
    }
    @Test fun airTacticsIsAnOptionalTwelfthPersonFieldThatKeepsOlderRowsCanonical() {
        val trained=state.copy(people=mapOf(10000 to state.people.getValue(10000).copy(airTactics=2)))
        val encoded=StrategicStateCodec.encode(trained);assertEquals(trained,StrategicStateCodec.decode(encoded))
        assertTrue(encoded.substringBefore('|').endsWith(":0,127:2"))
        assertFalse(StrategicStateCodec.encode(state).substringBefore('|').endsWith(":0"))
        for(bad in listOf(encoded.replace(":0,127:2",":0,127:0"),encoded.replace(":0,127:2",":0,127:4")))
            assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(bad)}
    }
    @Test fun deathIsAnOptionalThirteenthPersonFieldAfterAnExplicitAirTactics() {
        val dead=state.copy(people=mapOf(10000 to state.people.getValue(10000).copy(deceasedAt=777)))
        val encoded=StrategicStateCodec.encode(dead);assertEquals(dead,StrategicStateCodec.decode(encoded))
        assertTrue(encoded.substringBefore('|').endsWith(":0,127:0:777"))
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(encoded.replace(":0,127:0:777",":0,127:0:0"))}
    }
    @Test fun format29ReopensAlongsideLegacyEmptyStrategicState() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val header=Files.readAllLines(path).first().split('\t')
        assertEquals("LOGH7-STOCK-29",header[0]);assertEquals(13,header.size);assertEquals("1",header[12])
        repeat(2){FilePlanetStockStore(path).use {assertEquals(stocks,it.load())}}
        FilePlanetStockStore(path).use {it.save(stocks.copy(strategic=StrategicState()))}
        FilePlanetStockStore(path).use {assertEquals(StrategicState(),it.load()?.strategic)}
    }
    @Test fun paymentAndPendingEffectAreCommittedTogetherOrBothPreservedOnFailure() {
        val root=directory.resolve("world");val empty=stocks.copy(strategic=StrategicState())
        FileWorldStateStore(root).use {store->
            store.saveWorld(mapOf("actor" to navigator),empty)
            store.beforeSwitch={throw IOException("injected")}
            assertFailsWith<IOException>{store.saveWorld(mapOf("actor" to navigator.copy(mcp=840)),stocks)}
        }
        FileWorldStateStore(root).use {store->
            assertEquals(navigator,store.loadNavigation()["actor"]);assertEquals(empty,store.loadStocks())
            store.saveWorld(mapOf("actor" to navigator.copy(mcp=840)),stocks)
        }
        FileWorldStateStore(root).use {store->
            assertEquals(840,store.loadNavigation().getValue("actor").mcp);assertEquals(stocks,store.loadStocks())
        }
    }
    @Test fun strategy29MigratesToCombined30WithoutLosingEitherPendingCommandOrTreasury() {
        val path=directory.resolve("combined.tsv")
        val original=stocks.copy(nationalTreasuries=mapOf(CountryId(2) to 123L))
        FilePlanetStockStore(path).use {it.save(original)}
        assertEquals("LOGH7-STOCK-29",Files.readAllLines(path).first().substringBefore('\t'))
        val tactical=TacticalCommandState(orders=listOf(TacticalQueuedCommand(10000,2487,100,105,
            TacticalCommand(0x40d,listOf(1010000),kind=3))),morale=mapOf(1010000 to 80))
        val combined=original.copy(tacticalCommands=tactical)
        FilePlanetStockStore(path).use {assertEquals(original,it.load());it.save(combined)}
        val lines=Files.readAllLines(path);val header=lines.first().split('\t')
        assertEquals("LOGH7-STOCK-30",header[0]);assertEquals(14,header.size)
        assertEquals(listOf("1","1"),header.takeLast(2))
        assertEquals(1,lines.count {it.startsWith("S\t")});assertEquals(1,lines.count {it.startsWith("C\t")})
        repeat(2){FilePlanetStockStore(path).use {assertEquals(combined,it.load())}}
        Files.write(path,lines.filterNot {it.startsWith("S\t")})
        FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
    }
    @Test fun actualCountryOperationsKeepAssignmentsAndProgressAfterReopening() {
        val planned=StrategicOperation(1,10000,24,CountryId(42),2487,StrategicForce.FLEET,StrategicPurpose.DEFEND,1,10,
            StrategicPlanState.STARTED,10,outfits=setOf(200),characters=setOf(10002),defended=setOf(1049),issuer=10001)
        val other=planned.copy(id=2,country=CountryId(43),state=StrategicPlanState.PLANNED,issuedAt=null,issuer=0)
        val value=stocks.copy(strategic=state.copy(operations=listOf(planned,other)))
        val encoded=StrategicStateCodec.encode(value.strategic)
        assertEquals(value.strategic,StrategicStateCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(encoded.replace("1:10000:24:42:","1:10000:24:0:"))}
        val path=directory.resolve("country-operations.tsv")
        FilePlanetStockStore(path).use {it.save(value)}
        FilePlanetStockStore(path).use {assertEquals(value,it.load())}
    }
    @Test fun actualCountryPersonnelTitlesFiefsAndShipStyleSurviveAWorldReopen() {
        val country=CountryId(42)
        val player=Navigator(1,10001,country,2487,1000,1000,1000,shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(93,0))
        val value=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(country),0,100,200,300),
            1050 to PlanetStock(1050,Allegiance.Country(country),0,100,200,300)),strategic=StrategicState(
                people=mapOf(10001 to StrategicPerson(rank=6,merit=100,peerage=Peerage.BARON,decorations=setOf(0))),
                fiefs=mapOf(1050 to 10001),capitals=mapOf(country to 1049)))
        val path=directory.resolve("country-personnel")
        FileWorldStateStore(path).use {it.saveWorld(mapOf("target" to player),value)}
        FileWorldStateStore(path).use {
            assertEquals(value,it.loadStocks());assertEquals(mapOf("target" to player),it.loadNavigation())
        }
    }
    @Test fun transportPackagesAreAnOptionalSixthSection() {
        assertEquals(5,StrategicStateCodec.encode(state).split('|').size)
        val packed=state.copy(transports=listOf(TransportPackage(1,CountryId(2),1049,1050,1000,UnitInventory(mapOf(UnitStack(32,1) to 2),mapOf(UnitStack(3) to 1))),
            TransportPackage(2,CountryId(2),1049,1050,5,carrier=200,carrierBoats=300)))
        val encoded=StrategicStateCodec.encode(packed);assertEquals(packed,StrategicStateCodec.decode(encoded))
        assertEquals("1:2:1049:1050:1000:32.1.2:3.0.1:0:0;2:2:1049:1050:5:-:-:200:300",encoded.substringAfterLast('|'))
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode("$encoded|")}
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(encoded.replace(":200:300",":200:0"))}
    }
    @Test fun transportPackagesKeepActualCountryIdentityAndCargoAcrossReopening() {
        val cargo=UnitInventory(mapOf(UnitStack(32,1) to 2),mapOf(UnitStack(3) to 1))
        val packages=listOf(TransportPackage(1,CountryId(42),1049,1050,1000,cargo),
            TransportPackage(2,CountryId(43),1049,1050,5,carrier=200,carrierBoats=300))
        val value=stocks.copy(strategic=state.copy(transports=packages))
        val encoded=StrategicStateCodec.encode(value.strategic)
        assertEquals(value.strategic,StrategicStateCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(encoded.replace("1:42:1049:","1:0:1049:"))}
        val path=directory.resolve("country-packages.tsv")
        FilePlanetStockStore(path).use {it.save(value)}
        FilePlanetStockStore(path).use {assertEquals(value,it.load())}
    }
    @Test fun anEmpirePoliticiansKeptMilitaryRankIsAnOptionalFourteenthField() {
        val politician=state.copy(people=mapOf(10000 to state.people.getValue(10000).copy(rank=1,militaryRank=6)))
        val encoded=StrategicStateCodec.encode(politician);assertEquals(politician,StrategicStateCodec.decode(encoded))
        assertTrue(encoded.substringBefore('|').endsWith(":0,127:0:0:6"))
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(encoded.replace(":0,127:0:0:6",":0,127:0:0:0"))}
    }
}
