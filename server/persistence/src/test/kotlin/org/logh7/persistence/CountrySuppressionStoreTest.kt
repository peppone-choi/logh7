package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Policing order countries cannot be disguised under legacy stock headers. Author: Peppone Choi. */
class CountrySuppressionStoreTest {
    @TempDir lateinit var directory:Path
    private val country=CountryId(42)
    private val order=SuppressionOrder(10000,200,1000,country,4_000_000)
    private fun stock(strategic:StrategicState)=PlanetStocks(7,0,mapOf(1000 to PlanetStock(1000,Allegiance.Country(country),0,100,200,300)),
        strategic=strategic)

    @Test fun countryOrdersSelect47AndPreserveAllThreeStrategicShapesAndOlderSections() {
        val path=directory.resolve("stocks")
        val minimal=StrategicState(suppressions=listOf(order))
        val transport=minimal.copy(transports=listOf(TransportPackage(1,country,1000,1001,100)))
        val offices=transport.copy(capitals=mapOf(country to 1000),offices=listOf(OfficeHolding(136,1000,10000,country)))
        for(state in listOf(minimal,transport,offices)) {
            val source=stock(state).copy(social=SocialState(trade=TradeState(tariffs=mapOf(42 to 30),pacts=mapOf(42 to 1000)),
                succession=SuccessionState(countries=mapOf(country to ThroneState(heir=10001)))))
            FilePlanetStockStore(path).use {it.save(source)}
            val text=Files.readString(path);assertTrue(text.startsWith("LOGH7-STOCK-47\t"))
            FilePlanetStockStore(path).use {assertEquals(source,it.load())}
            for(header in listOf(46,45,44,43)) {
                val disguised=text.replaceFirst("LOGH7-STOCK-47","LOGH7-STOCK-$header")
                Files.writeString(path,disguised)
                FilePlanetStockStore(path).use {assertFails {it.load()}}
                assertEquals(disguised,Files.readString(path))
            }
        }
    }

    @Test fun theOldTwoCountryOrderGrammarAndHeaderRemainStable() {
        val state=StrategicState(suppressions=listOf(order.copy(country=CountryId(2))))
        val path=directory.resolve("old")
        val source=PlanetStocks(7,0,mapOf(1000 to PlanetStock(1000,2,0,100,200,300)),strategic=state)
        FilePlanetStockStore(path).use {it.save(source)}
        val text=Files.readString(path);assertTrue(text.startsWith("LOGH7-STOCK-29\t"))
        assertEquals("10000:200:1000:2:4000000",StrategicStateCodec.encode(state).split('|')[1])
        FilePlanetStockStore(path).use {assertEquals(source,it.load());it.save(source)}
        assertEquals(text,Files.readString(path))
        assertEquals(state,StrategicStateCodec.decode(StrategicStateCodec.encode(state)))
    }

    @Test fun invalidUnknownFlagAndNoncanonicalCountryIdsAreRejected() {
        val state=StrategicState(suppressions=listOf(order));val text=StrategicStateCodec.encode(state)
        assertEquals(state,StrategicStateCodec.decode(text,countrySuppression=true))
        assertFails {StrategicStateCodec.decode(text)}
        for(id in listOf("0","-1","042","4294967296"))assertFails {
            StrategicStateCodec.decode(text.replace(":42:",":$id:"),countrySuppression=true)
        }
        assertFails {StrategicStateCodec.decode(text.replace(text.split('|')[1],text.split('|')[1]+";"+text.split('|')[1]),countrySuppression=true)}
    }

    @Test fun reservationCountryAndPayerCpReopenAsOneWorldCheckpoint() {
        val source=stock(StrategicState(suppressions=listOf(order)))
        val pilot=Navigator(7,10000,country,2487,1000,840,1000,baseId=1000,mode=4,shipStyle=ShipStyleId("alliance"))
        val root=directory.resolve("world")
        FileWorldStateStore(root).use {it.saveWorld(mapOf("actor" to pilot),source)}
        FileWorldStateStore(root).use {assertEquals(source,it.loadStocks());assertEquals(mapOf("actor" to pilot),it.loadNavigation())}
    }
}
