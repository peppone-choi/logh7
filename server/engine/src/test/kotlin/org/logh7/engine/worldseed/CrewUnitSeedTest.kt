package org.logh7.engine.worldseed

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class CrewUnitSeedTest {
    private val header = "faction,unit,training,ground_attack,ground_defense"
    private val row = "empire,Unit,60,10,10"
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    private fun read(body: String = row) = CrewUnitSeedLoader.read(stream("$header\n$body\n"))
    private fun rejects(text: String, record: Int, reason: String) {
        val error = assertFailsWith<IllegalArgumentException>(text) { CrewUnitSeedLoader.read(stream(text)) }
        assertEquals("crew-units.csv: record $record: $reason", error.message)
    }

    @Test fun packagedSnapshotPreservesOrderValuesAndProvenance() {
        val units = CrewUnitSeedLoader.load().units
        assertEquals(11, units.size)
        assertEquals(mapOf(SeedFaction.EMPIRE to 6, SeedFaction.ALLIANCE to 5),
            units.groupingBy { it.faction }.eachCount())
        assertEquals(listOf("軽装陸戦兵", "近衛兵", "装甲擲弾兵", "擲弾兵教導", "装甲兵", "艦隊乗組員",
            "軽装陸戦兵", "装甲擲弾兵", "薔薇の騎士", "装甲兵", "艦隊乗組員"), units.map { it.unit })
        fun definition(faction: SeedFaction, name: String, training: Int, ground: Int?, record: Int) =
            CrewUnitDefinition(faction, name, training, ground, ground, ManualSource("crew-units.csv", 100, record))
        assertEquals(definition(SeedFaction.EMPIRE, "軽装陸戦兵", 60, 10, 2), units.first())
        assertEquals(definition(SeedFaction.EMPIRE, "擲弾兵教導", 900, 30, 5), units[3])
        assertEquals(definition(SeedFaction.EMPIRE, "艦隊乗組員", 120, null, 7), units[5])
        assertEquals(definition(SeedFaction.ALLIANCE, "薔薇の騎士", 900, 30, 10), units[8])
        assertEquals(definition(SeedFaction.ALLIANCE, "艦隊乗組員", 120, null, 12), units.last())
        assertEquals(listOf(7, 12), units.filter { it.groundAttack == null }.map { it.source.record })
        assertEquals(listOf(7, 12), units.filter { it.groundDefense == null }.map { it.source.record })
        assertEquals((2..12).map { ManualSource("crew-units.csv", 100, it) }, units.map { it.source })
    }

    @Test fun bomCrLfQuotedUnitAndIndependentNullsAreAccepted() {
        val input = "\uFEFF$header\r\nempire,\" Unit, one \",60,-,0\r\n"
        val unit = CrewUnitSeedLoader.read(stream(input)).units.single()
        assertEquals(" Unit, one ", unit.unit)
        assertNull(unit.groundAttack)
        assertEquals(0, unit.groundDefense)
        val other = read("alliance,Unit,1,0,-").units.single()
        assertEquals(0, other.groundAttack)
        assertNull(other.groundDefense)
        assertEquals(1, read().units.size) // No BOM.
        assertEquals(Int.MAX_VALUE, read("empire,Unit,2147483647,2147483647,0").units.single().training)
    }

    @Test fun malformedUtf8FailsAndCallerStreamsStayOpen() {
        fun tracked(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val bad = tracked("$header\n$row\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28))
        val error = assertFailsWith<IllegalArgumentException> { CrewUnitSeedLoader.read(bad) }
        assertEquals("crew-units.csv: record 1: invalid UTF-8", error.message)
        assertFalse(bad.closed)
        val valid = tracked("$header\n$row".toByteArray())
        CrewUnitSeedLoader.read(valid)
        assertFalse(valid.closed)
    }

    @Test fun headerShapeAndEmptyInputsFail() {
        rejects("", 1, "unexpected header")
        rejects(header, 1, "empty crew unit seed")
        rejects("${header.replace("training", "trainings")}\n$row", 1, "unexpected header")
        rejects("${header.replace("faction,unit", "unit,faction")}\n$row", 1, "unexpected header")
        rejects("\uFEFF\uFEFF$header\n$row", 1, "unexpected header")
        rejects("$header\n${row.substringBeforeLast(',')}", 2, "unexpected column count")
        rejects("$header\n$row,extra", 2, "unexpected column count")
    }

    @Test fun malformedQuotesAndBareCarriageReturnsFailThroughSharedParser() {
        for (bad in listOf("a,b\"c", "a,\"b", "a,\"b\"c", "a,\"b\" \n", "a,b\rc", "a,b\r")) {
            rejects("$header\n$bad", 2, "malformed CSV")
        }
    }

    @Test fun factionAndUnitAreValidatedWithoutNormalizingNames() {
        rejects("$header\n${row.replace("empire", "neutral")}", 2, "unknown faction")
        for (unit in listOf("", " ", "\t")) {
            rejects("$header\n${row.replace("Unit", unit)}", 2, "missing unit")
        }
        assertEquals(" Unit ", read(row.replace("Unit", " Unit ")).units.single().unit)
    }

    @Test fun trainingRequiresCanonicalPositiveInt() {
        for (value in listOf("0", "-1", "01", "-", "", "99999999999", " 1", "+1", "1.0")) {
            rejects("$header\nempire,Unit,$value,10,10", 2, "invalid training")
        }
    }

    @Test fun bothGroundColumnsRequireIndependentCanonicalNonnegativeIntOrDash() {
        for (column in listOf("ground_attack", "ground_defense")) {
            for (value in listOf("", "-1", "01", "99999999999", " -", "0 ", "+0", "1.0")) {
                val bad = if (column == "ground_attack") "empire,Unit,60,$value,10"
                    else "empire,Unit,60,10,$value"
                rejects("$header\n$bad", 2, "invalid $column")
            }
        }
    }

    @Test fun contextualDuplicateFailsAfterValidRowButDifferentFactionIsAllowed() {
        rejects("$header\n$row\n$row", 3, "duplicate contextual crew unit")
        assertEquals(2, read("$row\n${row.replace("empire", "alliance")}").units.size)
    }

    @Test fun snapshotIsDefensivelyCopiedAndImmutableThroughJvmCast() {
        val units = read().units
        assertFailsWith<UnsupportedOperationException> { (units as MutableList<CrewUnitDefinition>).add(units.first()) }
        val mutable = units.toMutableList()
        val seed = CrewUnitSeed(mutable)
        mutable.clear()
        assertEquals(units, seed.units)
    }
}
