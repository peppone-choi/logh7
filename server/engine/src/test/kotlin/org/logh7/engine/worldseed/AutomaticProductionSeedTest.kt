package org.logh7.engine.worldseed

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class AutomaticProductionSeedTest {
    private val header = "page,table,columns,values,faction,kind"
    private val columns = "星系名|惑星名|艦艇ユニット種別|乗組員ユニット種別|陸戦兵ユニット種別"
    private val row = "76,0,$columns,Star|Planet|A / A|B|C,empire,automatic_production"
    private val deployment = "anything,uninterpreted,ignored,ignored,unknown,deployment"
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    private fun read(body: String = row) = AutomaticProductionSeedLoader.read(stream("$header\n$body\n"))
    private fun rejects(text: String, reason: String, record: Int = 2) {
        val error = assertFailsWith<IllegalArgumentException>(text) {
            AutomaticProductionSeedLoader.read(stream(text))
        }
        assertEquals("initial-deployment.csv: record $record: $reason", error.message)
    }

    @Test fun packagedSnapshotPreservesAllCountsOrderAndProvenance() {
        val entries = AutomaticProductionSeedLoader.load().entries
        assertEquals(216, entries.size)
        assertEquals(mapOf(SeedFaction.EMPIRE to 132, SeedFaction.ALLIANCE to 84),
            entries.groupingBy { it.faction }.eachCount())
        assertEquals(mapOf(
            Triple(SeedFaction.EMPIRE, 76, 0) to 44,
            Triple(SeedFaction.EMPIRE, 77, 0) to 47,
            Triple(SeedFaction.EMPIRE, 78, 0) to 41,
            Triple(SeedFaction.ALLIANCE, 76, 1) to 47,
            Triple(SeedFaction.ALLIANCE, 77, 1) to 37,
        ), entries.groupingBy { Triple(it.faction, it.source.page, it.table) }.eachCount())
        assertEquals((80..295).toList(), entries.map { it.source.record })
        assertEquals(AutomaticProduction(SeedFaction.EMPIRE, 0, "アイゼンヘルツ", "ヒンターヴィルド",
            listOf("巡航艦Ⅵ"), listOf("艦隊乗組員"), listOf("軽装陸戦兵", "軽装陸戦兵", "装甲擲弾兵", "装甲兵"),
            ManualSource("initial-deployment.csv", 76, 80)), entries.first())
        assertEquals(ManualSource("initial-deployment.csv", 78, 295), entries.last().source)
        val emptyGround = entries.single { it.source.record == 218 }
        assertTrue(emptyGround.groundUnits.isEmpty())
        assertEquals(4, emptyGround.shipUnits.size)
        assertEquals(4, emptyGround.crewUnits.size)
        val shifted = entries.single { it.source.record == 109 }
        assertEquals(listOf("高速戦艦Ⅴ", "艦隊乗組員"), shifted.shipUnits)
        assertTrue(shifted.crewUnits.isEmpty())
        val repeated = entries.filter { it.source.record in 164..165 }
        assertEquals(2, repeated.size)
        assertEquals(repeated[0].copy(source = repeated[1].source), repeated[1])
        assertEquals(listOf(200, 236, 439), listOf(entries.sumOf { it.shipUnits.size },
            entries.sumOf { it.crewUnits.size }, entries.sumOf { it.groundUnits.size }))
        assertEquals(listOf(92, 93, 1), listOf(entries.count { it.shipUnits.isEmpty() },
            entries.count { it.crewUnits.isEmpty() }, entries.count { it.groundUnits.isEmpty() }))
        assertEquals(58, entries.map { it.starSystem }.toSet().size)
        assertEquals(213, entries.map { Triple(it.faction, it.starSystem, it.planet) }.toSet().size)
        assertEquals(2, entries.single { it.source.record == 104 }.shipUnits.size)
        assertEquals(5, entries.single { it.source.record == 104 }.crewUnits.size)
    }

    @Test fun diagnosticCrossReferencePinsFiveUnresolvedOriginalTokens() {
        val ships = ShipUnitSeedLoader.load().units.map { it.faction to it.unit }.toSet()
        val crew = CrewUnitSeedLoader.load().units.map { it.faction to it.unit }.toSet()
        data class Mismatch(val record: Int, val page: Int, val faction: SeedFaction,
            val column: String, val token: String)
        val unresolved = mutableListOf<Mismatch>()
        var matched = 0
        for (entry in AutomaticProductionSeedLoader.load().entries) {
            for ((column, tokens, definitions) in listOf(Triple("ship", entry.shipUnits, ships),
                Triple("crew", entry.crewUnits, crew), Triple("ground", entry.groundUnits, crew))) {
                for (token in tokens) {
                    if ((entry.faction to token) in definitions) matched++
                    else unresolved.add(Mismatch(entry.source.record, entry.source.page, entry.faction, column, token))
                }
            }
        }
        assertEquals(870, matched)
        assertEquals(listOf(
            Mismatch(83, 76, SeedFaction.EMPIRE, "ship", "揚陸艇Ⅱ"),
            Mismatch(109, 76, SeedFaction.EMPIRE, "ship", "艦隊乗組員"),
            Mismatch(133, 76, SeedFaction.ALLIANCE, "ship", "揚陸艇Ⅳ"),
            Mismatch(172, 77, SeedFaction.EMPIRE, "ship", "揚陸艇Ⅱ"),
            Mismatch(182, 77, SeedFaction.EMPIRE, "ship", "揚陸艇Ⅱ"),
        ), unresolved)
    }

    @Test fun f8SourceMismatchRowsStayVerbatimAsPrintedInManual() {
        val entries = AutomaticProductionSeedLoader.load().entries.filter {
            it.source.record in setOf(83, 109, 133, 172, 182)
        }
        assertEquals(listOf(
            AutomaticProduction(SeedFaction.EMPIRE, 0, "アイゼンヘルツ", "ゴータ",
                listOf("揚陸艇Ⅱ"), listOf("艦隊乗組員"), listOf("軽装陸戦兵", "軽装陸戦兵"),
                ManualSource("initial-deployment.csv", 76, 83)),
            AutomaticProduction(SeedFaction.EMPIRE, 0, "ヴァナヘイム", "エインヘリヤル",
                listOf("高速戦艦Ⅴ", "艦隊乗組員"), emptyList(), listOf("軽装陸戦兵", "軽装陸戦兵"),
                ManualSource("initial-deployment.csv", 76, 109)),
            AutomaticProduction(SeedFaction.ALLIANCE, 1, "エルゴン", "ブタンディガ",
                listOf("揚陸艇Ⅳ"), listOf("艦隊乗組員"), listOf("軽装陸戦兵", "軽装陸戦兵"),
                ManualSource("initial-deployment.csv", 76, 133)),
            AutomaticProduction(SeedFaction.EMPIRE, 0, "ヴィッテルスバッハ", "ヴィクトアーリエン",
                listOf("揚陸艇Ⅱ"), listOf("艦隊乗組員"), listOf("軽装陸戦兵", "軽装陸戦兵"),
                ManualSource("initial-deployment.csv", 77, 172)),
            AutomaticProduction(SeedFaction.EMPIRE, 0, "ヴェルラ", "シュテファニ",
                listOf("揚陸艇Ⅱ"), listOf("艦隊乗組員"), listOf("軽装陸戦兵", "軽装陸戦兵"),
                ManualSource("initial-deployment.csv", 77, 182)),
        ), entries)
    }

    @Test fun f8ShipDefinitionsPreserveLandingShipNamesByFaction() {
        val units = ShipUnitSeedLoader.load().units
        assertEquals(0, units.count { it.unit.startsWith("揚陸艇") })
        for (faction in listOf(SeedFaction.EMPIRE, SeedFaction.ALLIANCE)) {
            assertEquals(setOf("揚陸艦Ⅰ", "揚陸艦Ⅱ", "揚陸艦Ⅲ", "揚陸艦Ⅳ"),
                units.filter { it.faction == faction && it.unit.startsWith("揚陸艦") }
                    .map { it.unit }.toSet(), faction.name)
        }
    }

    @Test fun snapshotAndEveryNestedListAreUnmodifiableDefensiveCopies() {
        val entries = read().entries
        assertFailsWith<UnsupportedOperationException> { (entries as MutableList<AutomaticProduction>).add(entries.first()) }
        for (items in listOf(entries.first().shipUnits, entries.first().crewUnits, entries.first().groundUnits,
            read(row.replace("A / A|B|C", "||C")).entries.first().shipUnits)) {
            assertFailsWith<UnsupportedOperationException> { (items as MutableList<String>).add("changed") }
        }
        val ship = mutableListOf("A")
        val crew = mutableListOf("B")
        val ground = mutableListOf("C")
        val mutable = mutableListOf(entries.first().copy(shipUnits = ship, crewUnits = crew, groundUnits = ground))
        val snapshot = AutomaticProductionSeed(mutable)
        mutable.clear(); ship.clear(); crew.clear(); ground.clear()
        assertEquals(1, snapshot.entries.size)
        assertEquals(listOf("A"), snapshot.entries.single().shipUnits)
        assertEquals(listOf("B"), snapshot.entries.single().crewUnits)
        assertEquals(listOf("C"), snapshot.entries.single().groundUnits)
    }

    @Test fun acceptsBomDuplicatesIgnoredDeploymentAndSingleNonemptyItemCell() {
        val entries = read("$deployment\n$row\n$row").entries
        assertEquals(listOf(3, 4), entries.map { it.source.record })
        assertEquals(entries[0].copy(source = entries[1].source), entries[1])
        assertEquals(listOf("A", "A"), entries.first().shipUnits)
        for (items in listOf("A||", "|B|", "||C")) {
            assertEquals(1, read(row.replace("A / A|B|C", items)).entries.size)
        }
        assertEquals(read().entries, AutomaticProductionSeedLoader.read(stream("\uFEFF$header\r\n$row\r\n")).entries)
        val quoted = row.replace("Star|Planet", "\" Star, one | Planet").replace("|C,empire", "|C\",empire")
        assertEquals(" Star, one ", read(quoted).entries.single().starSystem)
    }

    @Test fun malformedUtf8AndValidReadsDoNotCloseCallerStreams() {
        for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28), "$header\n$row".toByteArray())) {
            val input = object : ByteArrayInputStream(bytes) {
                var closed = false
                override fun close() { closed = true; super.close() }
            }
            if (bytes[0] == 0xC3.toByte()) {
                val error = assertFailsWith<IllegalArgumentException> { AutomaticProductionSeedLoader.read(input) }
                assertEquals("initial-deployment.csv: record 1: invalid UTF-8", error.message)
            } else AutomaticProductionSeedLoader.read(input)
            assertFalse(input.closed)
        }
    }

    @Test fun rejectsInvalidHeadersShapesAndMalformedCsv() {
        rejects("", "unexpected header", 1)
        rejects(header, "empty seed", 1)
        rejects("${header.replace("page", "pages")}\n$row", "unexpected header", 1)
        rejects("$header\n${row.substringBeforeLast(',')}", "unexpected column count")
        rejects("$header\n$row,extra", "unexpected column count")
        for (bad in listOf("a,b\"c", "a,\"b", "a,\"b\"c", "a,b\rc")) {
            rejects("$header\n$bad", "malformed CSV")
        }
        rejects("$header\n${row.replace("automatic_production", "other")}", "unknown kind")
        rejects("$header\n${row.replace("empire", "neutral")}", "unknown faction")
        rejects("$header\n$deployment", "empty automatic production seed", 1)
    }

    @Test fun rejectsUnverifiedPagesNoncanonicalNumbersAndFactionTableMismatch() {
        for (page in listOf("75", "79")) {
            rejects("$header\n${row.replace("76,", "$page,")}", "page outside verified automatic production table")
        }
        for (value in listOf("01", "99999999999", "-1", "", " 0", "+0", "1.0")) {
            for (bad in listOf(row.replace("76,0,", "$value,0,"), row.replace("76,0,", "76,$value,"))) {
                rejects("$header\n$bad", if (value == "99999999999") "integer overflow" else "invalid nonnegative integer")
            }
        }
        for (bad in listOf(row.replace("empire", "alliance"), row.replace("76,0,", "76,1,"),
            row.replace("76,0,", "76,2,"))) {
            rejects("$header\n$bad", "table faction mismatch")
        }
        assertEquals(SeedFaction.ALLIANCE, read(row.replace("76,0,", "76,1,")
            .replace("empire", "alliance")).entries.single().faction)
    }

    @Test fun rejectsInvalidColumnsPlacesAndItemLists() {
        rejects("$header\n${row.replace(columns, "changed")}", "unexpected columns")
        for (cells in listOf("Star|Planet|A|B", "Star|Planet|A|B|C|D")) {
            rejects("$header\n${row.replace("Star|Planet|A / A|B|C", cells)}", "unexpected value cell count")
        }
        for (place in listOf("|Planet", "Star|", " |Planet", "Star| ")) {
            rejects("$header\n${row.replace("Star|Planet", place)}", "missing place")
        }
        for (bad in listOf("A /  B", "A/B", " A", "A / ", "A/ B", "A /B", "A /  / B", " ")) {
            for (items in listOf("$bad|B|C", "A|$bad|C", "A|B|$bad")) {
                rejects("$header\n${row.replace("A / A|B|C", items)}", "invalid item list")
            }
        }
        rejects("$header\n${row.replace("A / A|B|C", "||")}", "empty production row")
        rejects("$header\n$row\n${row.replace("A / A|B|C", "||")}", "empty production row", 3)
    }
}
