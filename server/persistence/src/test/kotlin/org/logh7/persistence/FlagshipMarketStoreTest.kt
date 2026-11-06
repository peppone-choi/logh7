package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** S/C 보존·M 제안 복원·양측 기함/CP 저장 실패의 원자성. 작성: Peppone Choi. */
class FlagshipMarketStoreTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val offer=FlagshipExchangeOffer(10000,10001,0,2,1049,10494622,now+86400)
    private val market=FlagshipMarketState(listOf(offer))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=1000))),
        tacticalCommands=TacticalCommandState(morale=mapOf(1 to 75)),flagshipMarket=market)
    private val giver=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,hull=FlagshipHull(0,now))
    private val receiver=giver.copy(characterId=10001,hull=FlagshipHull(2,now))
    @Test fun addedCountryCapitalMixedHullOfferAndGuardCargoRecoverTogether() {
        val country=CountryId(42);val style=ShipStyleId("alliance");val option=GuardShips.options[13];val unit=UnitStack(option.baseKind)
        val guard=OutfitStock(1,country,OutfitKind.PATROL,1050,1,10000,units=UnitInventory(ships=mapOf(unit to 1)),number=201,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(101,unit))),guard=mapOf(101 to option.index),shipStyle=style)
        val pending=stocks.copy(bases=mapOf(1050 to PlanetStock(1050,Allegiance.Country(country),0,100,200,300)),
            outfits=mapOf(1 to guard),warehouses=mapOf(1 to OutfitWarehouse(1,country,OutfitKind.PATROL,1050,shipStyle=style)),nextShipId=102,
            strategic=stocks.strategic.copy(capitals=mapOf(country to 1050)),
            flagshipMarket=FlagshipMarketState(listOf(offer.copy(base=1050,spot=10504622,giverKind=89))))
        val players=mapOf("giver" to giver.copy(country=country,shipStyle=style,baseId=1050,mode=4,spotId=10504622,
            hull=FlagshipHull(89,now),pcp=920),"receiver" to receiver.copy(country=country,baseId=1050,mode=4,spotId=10504622))
        val root=directory.resolve("country-world")
        FileWorldStateStore(root).use {it.saveWorld(players,pending)}
        FileWorldStateStore(root).use {
            assertEquals(players,it.loadNavigation());assertEquals(pending,it.loadStocks())
            val recovered=it.loadStocks()!!
            assertEquals(country,recovered.outfits.getValue(1).country);assertEquals(style,recovered.outfits.getValue(1).shipStyle)
            assertEquals(1050,recovered.strategic.capitals[country]);assertEquals(89,recovered.flagshipMarket.offers.single().giverKind)
        }
    }
    @Test fun format31KeepsSAndCAndStrictMRecordsAndOlderFormatsRemainReadable() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val rows=Files.readAllLines(path);val header=rows.first().split('\t')
        assertEquals("LOGH7-STOCK-31",header[0]);assertEquals(15,header.size)
        assertEquals(listOf("1","1","1"),header.takeLast(3))
        assertEquals(1,rows.count {it.startsWith("S\t")});assertEquals(1,rows.count {it.startsWith("C\t")});assertEquals(1,rows.count {it.startsWith("M\t")})
        FilePlanetStockStore(path).use {assertEquals(stocks,it.load())}
        val lower=stocks.copy(flagshipMarket=FlagshipMarketState())
        FilePlanetStockStore(path).use {it.save(lower);assertEquals(lower,it.load())}
        assertEquals("LOGH7-STOCK-30",Files.readAllLines(path).first().substringBefore('\t'))
        val oldest=lower.copy(tacticalCommands=TacticalCommandState())
        FilePlanetStockStore(path).use {it.save(oldest);assertEquals(oldest,it.load())}
        assertEquals("LOGH7-STOCK-29",Files.readAllLines(path).first().substringBefore('\t'))
        assertEquals(market,FlagshipMarketStateCodec.decode(FlagshipMarketStateCodec.encode(market)))
        assertFailsWith<IllegalArgumentException>{FlagshipMarketStateCodec.decode("0"+FlagshipMarketStateCodec.encode(market))}
        assertFailsWith<IllegalArgumentException>{FlagshipMarketStateCodec.decode(FlagshipMarketStateCodec.encode(market)+";"+FlagshipMarketStateCodec.encode(market))}
    }
    @Test fun aFailedExchangeNeverPublishesOnlyOnePersonsHullOrConsumesTheOffer() {
        val path=directory.resolve("world");val before=mapOf("giver" to giver,"receiver" to receiver)
        val after=mapOf("giver" to giver.copy(hull=receiver.hull),"receiver" to receiver.copy(hull=giver.hull,pcp=920))
        val accepted=stocks.copy(flagshipMarket=FlagshipMarketState())
        FileWorldStateStore(path).use {store->
            store.saveWorld(before,stocks);store.beforeSwitch={throw IOException("injected exchange pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(after,accepted)}
        }
        FileWorldStateStore(path).use {store->
            assertEquals(before,store.loadNavigation());assertEquals(stocks,store.loadStocks())
            store.saveWorld(after,accepted)
        }
        FileWorldStateStore(path).use {store->
            assertEquals(after,store.loadNavigation());assertEquals(accepted,store.loadStocks())
        }
    }
}
