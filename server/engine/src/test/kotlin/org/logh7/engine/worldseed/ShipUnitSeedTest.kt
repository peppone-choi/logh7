package org.logh7.engine.worldseed

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class ShipUnitSeedTest {
    private val headers = listOf(
        "faction", "page", "unit", "建造工期:建造工期", "必要乗組員ユニット数:必要乗組員ユニット数",
        "出力:出力", "索敵範囲（万km）:索敵範囲（万km）", "最高速度（km/1G秒）:最高速度（km/1G秒）",
        "装甲:前", "装甲:側", "装甲:後", "シールド（旗艦のみ）:防護値", "シールド（旗艦のみ）:容量",
        "ビーム兵装破壊力:ビーム兵装破壊力", "ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資",
        "ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資", "対空兵装破壊力:対空兵装破壊力",
        "戦闘艇/雷撃艇搭載数:戦闘艇/雷撃艇搭載数", "物資搭載量:物資搭載量", "修理消費物資(１隻):修理消費物資(１隻)",
        "source_row", "verification_status",
    )
    private val header = headers.joinToString(",")
    private val row = listOf("empire", "79", "Unit") + List(17) { "-" } + listOf("1", "reviewed")
    private fun csv(cells: List<String>) = cells.joinToString(",") { cell ->
        if (cell.any { it in ",\"\r\n" }) "\"${cell.replace("\"", "\"\"")}\"" else cell
    }
    private fun changed(column: Int, value: String) = row.toMutableList().also { it[column] = value }
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    private fun read(vararg rows: List<String>) = ShipUnitSeedLoader.read(stream(
        header + "\n" + rows.joinToString("\n") { csv(it) }))
    private fun rejects(text: String, record: Int, reason: String) {
        val error = assertFailsWith<IllegalArgumentException>(text) { ShipUnitSeedLoader.read(stream(text)) }
        assertEquals("ship-units.csv: record $record: $reason", error.message)
    }
    private fun rejectsCell(column: Int, value: String, reason: String) =
        rejects("$header\n${csv(changed(column, value))}", 2, reason)

    @Test fun packagedSnapshotPreservesCountsOrderAndPageLocalSourceRows() {
        val units = ShipUnitSeedLoader.load().units
        assertEquals(118, units.size)
        assertEquals(mapOf(SeedFaction.EMPIRE to 63, SeedFaction.ALLIANCE to 55),
            units.groupingBy { it.faction }.eachCount())
        assertEquals((2..119).toList(), units.map { it.source.record })
        assertTrue(units.all { it.source.file == "ship-units.csv" })
        units.forEach { assertEquals(headers.subList(3, 20), it.stats.keys.toList()) }
        val expected = linkedMapOf(
            (SeedFaction.EMPIRE to 79) to 9, (SeedFaction.EMPIRE to 80) to 9,
            (SeedFaction.EMPIRE to 81) to 9, (SeedFaction.EMPIRE to 82) to 10,
            (SeedFaction.EMPIRE to 83) to 4, (SeedFaction.EMPIRE to 84) to 4,
            (SeedFaction.EMPIRE to 85) to 4, (SeedFaction.EMPIRE to 86) to 4,
            (SeedFaction.EMPIRE to 87) to 4, (SeedFaction.EMPIRE to 88) to 4,
            (SeedFaction.EMPIRE to 89) to 2, (SeedFaction.ALLIANCE to 90) to 9,
            (SeedFaction.ALLIANCE to 91) to 9, (SeedFaction.ALLIANCE to 92) to 6,
            (SeedFaction.ALLIANCE to 93) to 9, (SeedFaction.ALLIANCE to 94) to 4,
            (SeedFaction.ALLIANCE to 95) to 4, (SeedFaction.ALLIANCE to 96) to 4,
            (SeedFaction.ALLIANCE to 97) to 4, (SeedFaction.ALLIANCE to 98) to 4,
            (SeedFaction.ALLIANCE to 99) to 2,
        )
        val grouped = units.groupBy { it.faction to it.source.page }
        assertEquals(expected.keys.toList(), grouped.keys.toList())
        assertEquals(expected, grouped.mapValues { it.value.size })
        grouped.values.forEach { assertEquals((1..it.size).toList(), it.map { unit -> unit.sourceRow }) }
        assertEquals(mapOf("reviewed" to 116, "name_clipped" to 2),
            units.groupingBy { it.verificationStatus }.eachCount())
        val cells = units.flatMap { it.stats.values }
        assertEquals(623, cells.count { it == "-" })
        assertEquals(264, cells.count { ',' in it })
        assertEquals(89, cells.count { '/' in it })
        assertEquals(1, cells.count { it == "0" })
    }

    @Test fun packagedRepresentativeCellsAndClippedNamesStayVerbatim() {
        val units = ShipUnitSeedLoader.load().units
        fun at(record: Int) = units.single { it.source.record == record }
        val first = at(2)
        assertEquals(SeedFaction.EMPIRE, first.faction)
        assertEquals(ManualSource("ship-units.csv", 79, 2), first.source)
        assertEquals(1, first.sourceRow)
        assertEquals("戦艦（旗艦）", first.unit)
        assertEquals("390", first.stats[headers[5]])
        assertEquals("5,600", first.stats[headers[6]])
        assertEquals("-", first.stats[headers[3]])
        assertEquals("reviewed", first.verificationStatus)
        val second = at(3)
        assertEquals(SeedFaction.EMPIRE, second.faction)
        assertEquals(79, second.source.page)
        assertEquals(2, second.sourceRow)
        assertEquals("戦艦Ⅰ", second.unit)
        assertEquals("100/1", second.stats[headers[14]])
        assertEquals("80/5", second.stats[headers[15]])
        assertEquals("760", second.stats[headers[18]])
        for ((record, power) in listOf(83 to "230", 84 to "300")) {
            val clipped = at(record)
            assertEquals(SeedFaction.ALLIANCE, clipped.faction)
            assertEquals(92, clipped.source.page)
            assertEquals(record - 82, clipped.sourceRow)
            assertEquals("艦）", clipped.unit)
            assertEquals("name_clipped", clipped.verificationStatus)
            assertEquals(power, clipped.stats[headers[5]])
        }
        assertEquals(listOf(83, 84), units.filter { it.verificationStatus == "name_clipped" }.map { it.source.record })
        val zero = at(96)
        assertEquals(SeedFaction.ALLIANCE, zero.faction)
        assertEquals(93, zero.source.page)
        assertEquals(8, zero.sourceRow)
        assertEquals("駆逐艦Ⅶ", zero.unit)
        assertEquals("0", zero.stats[headers[6]])
        val last = at(119)
        assertEquals(SeedFaction.ALLIANCE, last.faction)
        assertEquals(99, last.source.page)
        assertEquals(2, last.sourceRow)
        assertEquals("商船", last.unit)
        assertEquals("4,000", last.stats[headers[18]])
    }

    @Test fun bomCrLfQuotedUnitAndAllStatGrammarsArePreserved() {
        for (bom in listOf("", "\uFEFF")) {
            val input = "$bom$header\r\n${csv(changed(2, " Unit, \"one\" "))}\r\n"
            assertEquals(" Unit, \"one\" ", ShipUnitSeedLoader.read(stream(input)).units.single().unit)
        }
        // Every stat column accepts the same grammar; no numeric conversion or per-column meaning.
        for (column in 3..19) {
            for (value in listOf("-", "0", "5", "5,600", "100/1", "0/0", "99999999999999999999999999")) {
                assertEquals(value, read(changed(column, value)).units.single().stats[headers[column]])
            }
        }
        assertEquals("name_clipped", read(changed(21, "name_clipped")).units.single().verificationStatus)
    }

    @Test fun keyIsFactionPageAndSourceRowNotUnitNameOrRecord() {
        val sameNameOtherSourceRow = changed(20, "3")
        val otherFaction = changed(0, "alliance").also { it[1] = "90" }
        val otherPage = changed(1, "80")
        val units = read(row, sameNameOtherSourceRow, otherFaction, otherPage).units
        assertEquals(4, units.size)
        assertEquals(listOf("Unit", "Unit", "Unit", "Unit"), units.map { it.unit })
        assertEquals(listOf(1, 3, 1, 1), units.map { it.sourceRow })
        assertEquals(listOf(2, 3, 4, 5), units.map { it.source.record })
        assertEquals(listOf(80, 79), read(otherPage, row).units.map { it.source.page })
        rejects("$header\n${csv(row)}\n${csv(changed(2, "Other"))}", 3, "duplicate source row")
    }

    @Test fun headerShapeAndEmptyInputsFail() {
        rejects("", 1, "unexpected header")
        rejects(header, 1, "empty ship unit seed")
        rejects("${header.replace("faction,page", "page,faction")}\n${csv(row)}", 1, "unexpected header")
        rejects("${header.replace("装甲:側", "側:側")}\n${csv(row)}", 1, "unexpected header")
        rejects("\uFEFF\uFEFF$header\n${csv(row)}", 1, "unexpected header")
        rejects("$header\n${csv(row.dropLast(1))}", 2, "unexpected column count")
        rejects("$header\n${csv(row + "extra")}", 2, "unexpected column count")
    }

    @Test fun malformedQuotesAndBareCrFailThroughSharedParser() {
        for (bad in listOf("a,b\"c", "a,\"b", "a,\"b\"c", "a,\"b\" \n", "a,b\rc", "a,b\r")) {
            rejects("$header\n$bad", 2, "malformed CSV")
        }
    }

    @Test fun factionPageUnitAndSourceRowAreValidated() {
        rejectsCell(0, "neutral", "unknown faction")
        for (value in listOf("0", "079", "-", "", "2147483648", " 79", "+79")) {
            rejectsCell(1, value, "invalid page")
        }
        rejectsCell(1, "90", "page outside verified faction table")
        rejectsCell(0, "alliance", "page outside verified faction table") // alliance 79
        val badAlliance = changed(0, "alliance").also { it[1] = "89" }
        rejects("$header\n${csv(badAlliance)}", 2, "page outside verified faction table")
        for (value in listOf("", " ", "\t")) rejectsCell(2, value, "missing unit")
        assertEquals(" Unit ", read(changed(2, " Unit ")).units.single().unit)
        for (value in listOf("0", "01", "", "2147483648", "-", " 1")) {
            rejectsCell(20, value, "invalid source_row")
        }
        assertEquals(Int.MAX_VALUE, read(changed(20, "2147483647")).units.single().sourceRow)
    }

    @Test fun everyStatColumnRejectsNoncanonicalCellSyntax() {
        for (column in 3..19) {
            for (value in listOf("", " 5", "01", "-1", "1,20", ",100", "01,000", "1/", "/1", "1/2/3", "1.5", "--")) {
                rejectsCell(column, value, "invalid ${headers[column]}")
            }
        }
    }

    @Test fun statusRecordNumbersAndValidationOrderAreExact() {
        for (value in listOf("Reviewed", "", "unknown")) rejectsCell(21, value, "unknown verification_status")
        rejects("$header\n${csv(row)}\n${csv(changed(20, "2"))}\n${csv(changed(21, "unknown"))}",
            4, "unknown verification_status")
        val bad = row.toMutableList().also { it[0] = "neutral"; it[1] = "0"; it[3] = "invalid" }
        rejects("$header\n${csv(bad)}", 2, "unknown faction")
        bad[0] = "empire"
        rejects("$header\n${csv(bad)}", 2, "invalid page")
        bad[1] = "79"; bad[4] = "invalid"
        rejects("$header\n${csv(bad)}", 2, "invalid ${headers[3]}")
    }

    @Test fun snapshotsAreDefensivelyCopiedAndImmutableThroughJvmCasts() {
        val units = read(row).units
        assertFailsWith<UnsupportedOperationException> { (units as MutableList<ShipUnitDefinition>).add(units.first()) }
        assertFailsWith<UnsupportedOperationException> { (units.first().stats as MutableMap<String, String>)[headers[3]] = "0" }
        val mutable = units.toMutableList()
        val seed = ShipUnitSeed(mutable)
        mutable.clear()
        assertEquals(units, seed.units)
    }

    @Test fun malformedUtf8FailsAndCallerStreamsStayOpenOnSuccessOrFailure() {
        fun tracked(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val validBytes = "$header\n${csv(row)}".toByteArray(Charsets.UTF_8)
        val bad = tracked(validBytes + byteArrayOf(0xC3.toByte(), 0x28))
        val error = assertFailsWith<IllegalArgumentException> { ShipUnitSeedLoader.read(bad) }
        assertEquals("ship-units.csv: record 1: invalid UTF-8", error.message)
        assertFalse(bad.closed)
        val valid = tracked(validBytes)
        ShipUnitSeedLoader.read(valid)
        assertFalse(valid.closed)
        val invalid = tracked("$header\n${csv(changed(1, "0"))}".toByteArray(Charsets.UTF_8))
        assertFailsWith<IllegalArgumentException> { ShipUnitSeedLoader.read(invalid) }
        assertFalse(invalid.closed)
    }
}
