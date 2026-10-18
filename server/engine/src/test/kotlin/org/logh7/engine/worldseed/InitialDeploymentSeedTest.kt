package org.logh7.engine.worldseed

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class InitialDeploymentSeedTest {
    private val header = "page,table,columns,values,faction,kind"
    private val row = "75,0,艦隊番号|星系|惑星/要塞,Fleet|Star|Planet,empire,deployment"
    private val production = "76,0,uninterpreted,anything,alliance,automatic_production"
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    private fun read(body: String = row) = InitialDeploymentSeedLoader.read(stream("$header\n$body\n"))
    private fun rejects(text: String, reason: String) {
        val error = assertFailsWith<IllegalArgumentException>(text) {
            InitialDeploymentSeedLoader.read(stream(text))
        }
        assertTrue(error.message.orEmpty().matches(Regex("initial-deployment\\.csv: record [0-9]+: .*")))
        assertContains(error.message.orEmpty(), reason)
    }

    @Test fun packagedSnapshotPreservesCountsOrderProvenanceAndRawValues() {
        val entries = InitialDeploymentSeedLoader.load().entries
        assertEquals(78, entries.size)
        assertEquals(mapOf(SeedFaction.EMPIRE to 41, SeedFaction.ALLIANCE to 37),
            entries.groupingBy { it.faction }.eachCount())
        assertEquals(mapOf(DeploymentUnitKind.FLEET to 24, DeploymentUnitKind.PATROL to 35,
            DeploymentUnitKind.GROUND to 19), entries.groupingBy { it.unitKind }.eachCount())
        assertEquals(mapOf(0 to 12, 1 to 12, 2 to 23, 3 to 12, 4 to 13, 5 to 6),
            entries.groupingBy { it.table }.eachCount())
        assertEquals(InitialDeployment(SeedFaction.EMPIRE, DeploymentUnitKind.FLEET, 0,
            "第１艦隊", "ヴァルハラ", "オーディン", ManualSource("initial-deployment.csv", 75, 2)), entries.first())
        assertEquals(InitialDeployment(SeedFaction.ALLIANCE, DeploymentUnitKind.FLEET, 1,
            "第１艦隊", "バーラト", "ハイネセン", ManualSource("initial-deployment.csv", 75, 14)),
            entries.first { it.faction == SeedFaction.ALLIANCE })
        assertEquals(InitialDeployment(SeedFaction.EMPIRE, DeploymentUnitKind.PATROL, 2,
            "第53巡察隊 / ～第60巡察隊", "", "", ManualSource("initial-deployment.csv", 75, 48)),
            entries.single { it.source.record == 48 })
        assertEquals(InitialDeployment(SeedFaction.EMPIRE, DeploymentUnitKind.GROUND, 5,
            "第51地上部隊 / ～第60地上部隊", "イゼルローン", "イゼルローン",
            ManualSource("initial-deployment.csv", 75, 79)), entries.last())
        assertEquals("サンア・アナ", entries.single { it.faction == SeedFaction.ALLIANCE &&
            it.unitKind == DeploymentUnitKind.GROUND && it.unitLabel == "第31地上部隊 / ～第35地上部隊" }.location)
        assertEquals(46, entries.count { " / " in it.unitLabel })
        assertEquals((2..79).toList(), entries.map { it.source.record })
        assertTrue(entries.all { it.source.page == 75 })
    }

    @Test fun bomCrLfQuotedCommaAndRawWhitespaceArePreserved() {
        val text = "\uFEFF$header\r\n${row.replace("Fleet|Star|Planet", "\" Fleet, one | Star | Planet \"")}\r\n"
        val entry = InitialDeploymentSeedLoader.read(stream(text)).entries.single()
        assertEquals(" Fleet, one ", entry.unitLabel)
        assertEquals(" Star ", entry.starSystem)
        assertEquals(" Planet ", entry.location)
        assertEquals("", read(row.replace("Star|Planet", "|")).entries.single().location)
        assertEquals(1, read().entries.size) // No BOM, no production rows required.
    }

    @Test fun productionHasOnlyStructuralValidationAndIsNeverExposed() {
        val entries = read("$production\n$row\n${production.replace("76,0", "78,99")}").entries
        assertEquals(1, entries.size)
        assertEquals(3, entries.single().source.record)
        rejects("$header\n$production", "empty deployment seed")
        for (page in listOf("75", "79")) {
            rejects("$header\n$row\n${production.replace("76,", "$page,")}", "page outside verified automatic production table")
        }
        rejects("$header\n$row\n${production.replace("alliance", "neutral")}", "unknown faction")
        rejects("$header\n$row\n${production.replace("76,0", "76,01")}", "invalid nonnegative integer")
    }

    @Test fun invalidUtf8IsReportedWithoutClosingCallerStream() {
        val input = object : ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val error = assertFailsWith<IllegalArgumentException> { InitialDeploymentSeedLoader.read(input) }
        assertEquals("initial-deployment.csv: record 1: invalid UTF-8", error.message)
        assertFalse(input.closed)
        val valid = object : ByteArrayInputStream("$header\n$row".toByteArray()) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        InitialDeploymentSeedLoader.read(valid)
        assertFalse(valid.closed)
    }

    @Test fun headersColumnCountsAndEmptyInputsFail() {
        rejects("", "unexpected header")
        rejects(header, "empty seed")
        rejects("${header.replace("page", "pages")}\n$row", "unexpected header")
        rejects("$header\n${row.substringBeforeLast(',')}", "unexpected column count")
        rejects("$header\n$row,extra", "unexpected column count")
        rejects("\uFEFF\uFEFF$header\n$row", "unexpected header")
    }

    @Test fun malformedQuotesAndBareCarriageReturnsFailThroughSharedParser() {
        for (bad in listOf("a,b\"c", "a,\"b", "a,\"b\"c", "a,\"b\" \n", "a,b\rc", "a,b\r")) {
            rejects("$header\n$bad", "malformed CSV")
        }
    }

    @Test fun unknownKindsFactionsAndUnverifiedPagesFail() {
        rejects("$header\n${row.replace("deployment", "other")}", "unknown kind")
        rejects("$header\n${row.replace("empire", "neutral")}", "unknown faction")
        for (page in listOf("74", "76")) {
            rejects("$header\n${row.replace("75,", "$page,")}", "page outside verified deployment table")
        }
    }

    @Test fun pageAndTableRequireCanonicalNonnegativeIntegersWithoutOverflow() {
        for (value in listOf("-1", "01", "99999999999", "", " 0", "+0", "1.0")) {
            for (bad in listOf(row.replace("75,0,", "75,$value,"), row.replace("75,0,", "$value,0,"))) {
                rejects("$header\n$bad", if (value == "99999999999") "integer overflow" else "invalid nonnegative integer")
            }
        }
        rejects("$header\n${row.replace("75,0,", "75,6,")}", "unknown deployment table")
    }

    @Test fun tableFactionColumnsAndValueShapeAreValidated() {
        rejects("$header\n${row.replace("empire", "alliance")}", "table faction mismatch")
        rejects("$header\n${row.replace("艦隊番号", "Fleet")}", "unexpected columns")
        for (value in listOf("Fleet|Star", "Fleet|Star|Planet|Extra")) {
            rejects("$header\n${row.replace("Fleet|Star|Planet", value)}", "unexpected value cell count")
        }
        rejects("$header\n${row.replace("Fleet|Star|Planet", " |Star|Planet")}", "missing unit label")
        for (value in listOf("Fleet||Planet", "Fleet|Star|")) {
            rejects("$header\n${row.replace("Fleet|Star|Planet", value)}", "incomplete location")
        }
    }

    @Test fun duplicateKeysFailButDifferentFactionAndKindRemainDistinct() {
        rejects("$header\n$row\n$row", "duplicate contextual deployment")
        val alliance = row.replace("75,0,", "75,1,").replace("empire", "alliance")
        val patrol = row.replace("75,0,", "75,2,")
            .replace("艦隊番号|星系|惑星/要塞", "巡察隊番号|ユニット配置星系|ユニット配置惑星")
        assertEquals(3, read("$row\n$alliance\n$patrol").entries.size)
    }

    @Test fun snapshotIsDefensivelyCopiedAndCannotBeMutatedThroughJvmCast() {
        val entries = read().entries
        assertFailsWith<UnsupportedOperationException> { (entries as MutableList<InitialDeployment>).add(entries.first()) }
        val mutable = entries.toMutableList()
        val seed = InitialDeploymentSeed(mutable)
        mutable.clear()
        assertEquals(entries, seed.entries)
    }
}
