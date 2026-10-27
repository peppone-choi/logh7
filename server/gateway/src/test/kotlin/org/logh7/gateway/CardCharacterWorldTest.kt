package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class CardCharacterWorldTest {
    @Test fun capitalsExposeOnlyTheirOwnOriginalOfficesAndRegisteredFactionHolders() {
        val capitalWorld=WorldBootstrap(Galaxy(galaxy.systems+GalaxySystem(4,2015,4,"Alliance",3),listOf(
            galaxy.bases[0].copy(name="オーディン"),
            galaxy.bases[1].copy(grid=2015,name="ハイネセン")),galaxy.starts))
        val assigned=stocks.copy(bases=mapOf(
            1048 to PlanetStock(1048,2,0,0,0,0,governorId=10010,garrisonCommanderId=10011,
                capitalDefenseCommanderId=10000,guardInspectorId=10001),
            1049 to PlanetStock(1049,3,0,0,0,0,governorId=10012,garrisonCommanderId=10013,
                capitalAdministratorId=10002,capitalDefenseCommanderId=10003)))
        for((card,id,power) in listOf(Triple(56,10000,2),Triple(57,10001,2),Triple(192,10002,3),Triple(193,10003,3))) {
            val response=capitalWorld.cardCharacters(card,assigned) { character.copy(id=it.toLong(),power=power) }
            assertEquals(1,response[8].toInt());assertEquals(id,ByteBuffer.wrap(response).getInt(9))
            assertEquals(card,ByteBuffer.wrap(response).getShort(response.size-7).toInt())
            assertEquals(if(power==2)1048 else 1049,ByteBuffer.wrap(response).getInt(response.size-5))
        }
        for(card in listOf(54,55,190,191)) assertEquals(0,capitalWorld.cardCharacters(card,assigned) {
            error("Legacy capital assignments must not become ordinary office cards") }[8].toInt())
        assertFails { capitalWorld.cardCharacters(56,assigned) { character.copy(power=3) } }
    }
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        (1048..1049).map { GalaxyBase(it,2487,80,0,"Home",0,20f,86400,0,0f,2f) },
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val world=WorldBootstrap(galaxy)
    private val character=CharacterMessages.Generate(4,10000,2,0,0,"Governor","Pilot",18,1,1,
        1000001,List(8) { 50 },0,0,0,20,3,0,"Ship",1)
    private val stocks=PlanetStocks(1,0,(1048..1049).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10000) })
    @Test fun oneGovernorRetainsEveryAssignedBaseWithoutDuplicatePeople() {
        val response=world.cardCharacters(54,stocks) { character.takeIf { c -> c.id==it.toLong() } }
        assertEquals(1,response[8].toInt());assertEquals(10000,ByteBuffer.wrap(response).getInt(9))
        val tail=ByteBuffer.wrap(response,response.size-26,26)
        assertEquals(4,tail.get().toInt()) // personal, captain, two appointments
        tail.position(tail.position()+12)
        assertEquals(54,tail.short.toInt());assertEquals(1048,tail.int)
        assertEquals(54,tail.short.toInt());assertEquals(1049,tail.int);assertEquals(0,tail.get().toInt())
        val other=world.cardCharacters(190,stocks) { error("Unappointed faction must not resolve a person") }
        assertEquals(0,other[8].toInt())
    }
    @Test fun missingOrWrongFactionIdentityCannotBecomeAnEmptyOffice() {
        assertFails { world.cardCharacters(54,stocks) { null } }
        assertFails { world.cardCharacters(54,stocks) { character.copy(power=3) } }
        assertFails { world.cardCharacters(59,stocks) { character } }
    }
    @Test fun originalLimitsRejectTruncationInsteadOfReportingVacancies() {
        val manyPeople=stocks.copy(bases=(1..65).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10000+it) })
        val capacityWorld=WorldBootstrap(Galaxy(galaxy.systems,(1..65).map { galaxy.bases.first().copy(id=it) },galaxy.starts))
        val rejected=capacityWorld.cardCharacters(54,manyPeople) { error("Overflow must fail before resolving people") }
        assertEquals(1,ByteBuffer.wrap(rejected).int)
        assertEquals(0,rejected[8].toInt())
        assertContentEquals(org.logh7.protocol.CardCharacterMessages.response(54,emptyList(),failed=true),rejected)
        val manyBases=stocks.copy(bases=(1..15).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10000) })
        assertFails { capacityWorld.cardCharacters(54,manyBases) { character } }
    }

    @Test fun planetAndFortressGarrisonsUseDifferentOriginalCardsForBothFactions() {
        val garrisonWorld=WorldBootstrap(Galaxy(galaxy.systems,listOf(
            galaxy.bases[0].copy(kind=0),galaxy.bases[1].copy(kind=1)),galaxy.starts))
        for (power in listOf(2,3)) {
            val offset=if (power==3) 136 else 0
            val assigned=stocks.copy(bases=stocks.bases.mapValues { (_,base) ->
                base.copy(power=power,governorId=0,garrisonCommanderId=10000) })
            for ((card,base) in listOf(55+offset to 1048,52+offset to 1049)) {
                val response=garrisonWorld.cardCharacters(card,assigned) { character.copy(power=power) }
                assertEquals(1,response[8].toInt())
                assertEquals(card,ByteBuffer.wrap(response).getShort(response.size-7).toInt())
                assertEquals(base,ByteBuffer.wrap(response).getInt(response.size-5))
            }
            val empty=garrisonWorld.cardCharacters(54+offset,assigned) { error("Garrison is not a governor") }
            assertEquals(0,empty[8].toInt())
        }
    }

}
