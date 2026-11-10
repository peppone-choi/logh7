package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.LoadFixture
import org.logh7.gateway.load.LoadMetrics
import org.logh7.gateway.load.SyntheticClient

class HomeIntelligenceWireTest {
    @TempDir lateinit var directory: Path

    @Test fun authenticatedReportQueryUsesActualCountryInsteadOfNativeDisplay() {
        val original = CountryCatalogLoader.current()
        val revision = (CountryCatalogLoader.snapshot()?.revision ?: 0) + 1
        val home = CountryId(42)
        val other = CountryId(43)
        val catalog = CountryCatalog(original.calendars.values.toList(), original.families.values.toList(),
            original.countries.values.toList() + listOf(
                CountryDefinition(42, "home_report_a", "Report A", "alliance", 3, 0, "alliance"),
                CountryDefinition(43, "home_report_b", "Report B", "alliance", 3, 0, "alliance")))
        val countries = listOf(CountryId(2), CountryId(3), home, other)
        val grids = listOf(2487, 2015, 2488, 2489)
        val bases = listOf(1049, 1177, 1050, 1051)
        val galaxy = Galaxy(grids.mapIndexed { i, grid -> GalaxySystem(3 + i, grid, 4, "System$i", if (i == 0) 2 else 3) },
            bases.mapIndexed { i, base -> GalaxyBase(base, grids[i], 80, 0, "Base$i", 0, 20f, 86400, 0, 0f, 2f) },
            mapOf(Power.EMPIRE to grids[0], Power.ALLIANCE to grids[1]),
            countryStarts = countries.zip(grids).toMap(),
            initialOwners = bases.zip(countries.map { Allegiance.Country(it) }).toMap())
        val start = SessionRules.load().startSeconds
        val target = bases[1]
        val report = HomeIntelligenceReport(home, target, start, 5_000_000_000, 75, 90)
        var stock = PlanetStocks(1, 0, bases.mapIndexed { i, base ->
            base to PlanetStock(base, Allegiance.Country(countries[i]), 0, 0, 0, 0)
        }.toMap(), deploymentInitialized = true,
            social = SocialState(justice = JusticeState(homeReports = mapOf((home to target) to report))))
        var savedPlayers = (0..1).associate { i ->
            "load-00$i" to Navigator(1, 10000 + i, countries[i + 2], grids[i + 2], 1000, 1000, 1000,
                baseId = bases[i + 2], mode = 1, cpRecoveryAt = start, shipStyle = ShipStyleId("alliance"))
        }
        val inventory = object : PlanetStockStore {
            override fun load() = stock
            override fun save(stocks: PlanetStocks) { stock = stocks }
        }
        val navigation = object : NavigationStore {
            override fun load() = savedPlayers
            override fun save(players: Map<String, Navigator>) { savedPlayers = players }
        }
        CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision, catalog, emptyMap(), emptyMap()))
        try {
            LoadFixture(directory, 2, LoadMetrics(), galaxy, ticking = false, stockStore = inventory,
                navigationStore = navigation, powerByIndex = { 3 }).use { fixture ->
                val clients = (0..1).map { index ->
                    val joined = fixture.join(fixture.account(index), false, false)
                    assertTrue(joined.accepted)
                    joined.client.also { client ->
                        client.send(SyntheticClient.opcode(0x0f00))
                        assertTrue((1..300).any { ByteBuffer.wrap(client.receive()).getShort(4).toInt() == 0x0f01 })
                    }
                }
                clients.forEachIndexed { index, client ->
                    val character = 10000 + index
                    val ticket = 120 + index
                    val request = ByteBuffer.allocate(31).putShort(0, 0x1200).putInt(2, character)
                        .putShort(6, 0x7f14).putShort(8, target.toShort()).putInt(10, ticket).putShort(29, 1)
                    client.send(request.array())
                    val response = client.expect(0x1200)
                    val payload = ByteBuffer.wrap(response)
                    assertEquals(35, response.size)
                    assertEquals(character, payload.getInt(6))
                    assertEquals(0x7f14, payload.getShort(10).toInt())
                    assertEquals(target, payload.getShort(12).toInt())
                    assertEquals(ticket, payload.getInt(14))
                    assertEquals(1, payload.getShort(33).toInt())
                    if (index == 0) {
                        assertEquals(1, payload.get(18).toInt())
                        assertEquals(start, payload.getInt(19).toLong() and 0xffffffffL)
                        assertEquals(report.treasury, payload.getLong(23))
                        assertEquals(report.approval, payload.get(31).toInt())
                        assertEquals(report.peace, payload.get(32).toInt())
                    } else assertContentEquals(ByteArray(15), response.copyOfRange(18, 33))
                    assertEquals(countries[index + 2], fixture.engine.snapshot().navigators.getValue(fixture.account(index)).country)
                }
            }
        } finally {
            CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision + 1, original, emptyMap(), emptyMap()))
        }
    }
}
