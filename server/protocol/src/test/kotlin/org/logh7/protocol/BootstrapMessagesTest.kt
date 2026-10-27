package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class BootstrapMessagesTest {
    @Test fun assignedLogisticsOfficesCanInitializeAndRefreshTheCharacterWithHeadquartersTargets() {
        for ((kind,power,target) in listOf(Triple(24,2,104938),Triple(25,2,104938),Triple(150,3,117737))) {
            val actor=character.copy(power=power)
            val offices=listOf(BootstrapMessages.OfficeCard(kind,target))
            val initial=BootstrapMessages.character(actor,1000,1000,offices=offices)
            val tail=ByteBuffer.wrap(initial,initial.size-7,7)
            assertEquals(kind,tail.short.toInt());assertEquals(target,tail.int)
            val changed=BootstrapMessages.changedCharacter(actor,840,840,offices=offices)
            assertEquals(0x0356,ByteBuffer.wrap(changed).getShort(4).toInt())
            assertFails { BootstrapMessages.character(actor.copy(power=if(power==2) 3 else 2),offices=offices) }
        }
    }
    private val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Alpha", "Pilot", 18, 1, 1,
        1_000_001, listOf(60) + List(7) { 50 }, 0, 0, 0, 20, 3, 0, "AlphaShip", 1)
    @Test fun initialCardTableAndCharacterUseMatchingRoleIds() {
        val cards = ByteBuffer.wrap(BootstrapMessages.response(byteArrayOf(3, 4))!!)
        cards.position(6)
        assertEquals(3, cards.short.toInt())
        val ids = List(3) { cards.short.toInt().also { cards.position(cards.position() + 17) } }
        assertEquals(listOf(0, 59, 195), ids); assertFalse(cards.hasRemaining())
        val body = BootstrapMessages.character(character)
        // Native faction selector is power * 2 + camp (004d34d0); camp 0 is regular.
        assertEquals(2, body[10].toInt()); assertEquals(0, body[11].toInt())
        val tail = ByteBuffer.wrap(body, body.size - 14, 14)
        assertEquals(2, tail.get().toInt()); assertEquals(0, tail.short.toInt()); assertEquals(0, tail.int)
        assertEquals(59, tail.short.toInt()); assertEquals(1_010_000, tail.int); assertEquals(0, tail.get().toInt())
        val alliance = BootstrapMessages.character(character.copy(power = 3))
        assertEquals(195, ByteBuffer.wrap(alliance, alliance.size - 7, 2).short.toInt())
    }
    @Test fun fixedCatalogueLengthsFollowWireWidthsAndRejectPayloads() {
        assertEquals(438, BootstrapMessages.response(byteArrayOf(3, 0x10))!!.size)
        assertEquals(1376, BootstrapMessages.response(byteArrayOf(3, 8))!!.size)
        assertEquals(10, BootstrapMessages.response(byteArrayOf(3, 0x14))!!.size)
        assertEquals(6, BootstrapMessages.response(byteArrayOf(15, 2))!!.size)
        assertNull(BootstrapMessages.response(byteArrayOf(0x7f, 0)))
        assertFails { BootstrapMessages.response(byteArrayOf(3, 4, 0)) }
    }
    @Test fun governorCardsKeepTheirBaseInInitialAndChangedRecords() {
        val offices = listOf(BootstrapMessages.OfficeCard(54,1049))
        val query = BootstrapMessages.character(character,1000,840,offices=offices)
        val tail = ByteBuffer.wrap(query,query.size-20,20)
        assertEquals(3,tail.get().toInt())
        assertEquals(0,tail.short.toInt()); assertEquals(0,tail.int)
        assertEquals(59,tail.short.toInt()); assertEquals(1_010_000,tail.int)
        assertEquals(54,tail.short.toInt()); assertEquals(1049,tail.int); assertEquals(0,tail.get().toInt())
        val changed = BootstrapMessages.changedCharacter(character,1000,840,offices=offices)
        assertContentEquals(query.copyOfRange(6,query.size),changed.copyOfRange(7,changed.size))
        val alliance = BootstrapMessages.character(character.copy(power=3),offices=listOf(BootstrapMessages.OfficeCard(190,1049)))
        assertEquals(190,ByteBuffer.wrap(alliance,alliance.size-7,2).short.toInt())
        assertFails { BootstrapMessages.character(character,offices=listOf(BootstrapMessages.OfficeCard(190,1049))) }
        assertFails { BootstrapMessages.character(character,offices=offices+offices) }
        assertFails { BootstrapMessages.character(character,offices=List(15) { BootstrapMessages.OfficeCard(54,1000+it) }) }
    }
    @Test fun commanderCardKindsPreserveOutfitTargetsAndTheirFaction() {
        for (power in 2..3) for (empire in listOf(39,44,47,50)) {
            val kind=empire + if (power == 3) 136 else 0
            val officer=character.copy(power=power)
            val offices=listOf(BootstrapMessages.OfficeCard(kind,70000))
            val initial=BootstrapMessages.character(officer,offices=offices)
            val tail=ByteBuffer.wrap(initial,initial.size-7,7)
            assertEquals(kind,tail.short.toInt()); assertEquals(70000,tail.int)
            val changed=BootstrapMessages.changedCharacter(officer,1000,1000,offices=offices)
            // Character defaults are 100 CP; compare a query with the same server balances.
            assertContentEquals(BootstrapMessages.character(officer,1000,1000,offices=offices).copyOfRange(6,initial.size),
                changed.copyOfRange(7,changed.size))
            assertFails { BootstrapMessages.character(officer.copy(power=if (power==2) 3 else 2),offices=offices) }
        }
        // Base IDs and outfit IDs are different namespaces even when their numeric target matches.
        BootstrapMessages.character(character,offices=listOf(BootstrapMessages.OfficeCard(54,1049),BootstrapMessages.OfficeCard(39,1049)))
        assertFails { BootstrapMessages.OfficeCard(54,70000) }
    }
    @Test fun flagshipAffiliationOccupiesTheNativeOutfitFieldWhenStationedOrInSpace() {
        val packet=ByteBuffer.wrap(BootstrapMessages.unit(character,2487,1000,1049,4,70000))
        assertEquals(70000,packet.getInt(19))
        assertEquals(0,ByteBuffer.wrap(BootstrapMessages.unit(character)).getInt(19))
        val space=ByteBuffer.wrap(BootstrapMessages.unit(character,2488,900,0,5,70000))
        assertEquals(70000,space.getInt(19));assertEquals(5,space.get(14).toInt())
        assertEquals(2488,space.getInt(15));assertEquals(0,space.getInt(28))
        assertFails { BootstrapMessages.unit(character,2487,1000,0,4,70000) }
        assertFails { BootstrapMessages.unit(character,2487,1000,1049,2,70000) }
    }
    @Test fun clockResponseCarriesUnsignedGameSecondsAndRejectsOverflow() {
        val body = BootstrapMessages.response(byteArrayOf(3, 0), 0xfedcba98L)!!
        assertEquals(0xfedcba98L, ByteBuffer.wrap(body, 6, 4).int.toLong() and 0xffffffffL)
        assertFails { BootstrapMessages.response(byteArrayOf(3, 0), -1) }
        assertFails { BootstrapMessages.response(byteArrayOf(3, 0), 0x1_0000_0000L) }
    }
    @Test fun liveCharacterChangeUsesNotificationInsteadOfQueryAndKeepsLocation() {
        val query = BootstrapMessages.character(character, 990, 840, 10_490_001, 0)
        val changed = BootstrapMessages.changedCharacter(character, 990, 840, 10_490_001, 0)
        assertEquals(0x0356, ByteBuffer.wrap(changed, 4, 2).short.toInt())
        assertEquals(0, changed[6].toInt())
        assertContentEquals(query.copyOfRange(6, query.size), changed.copyOfRange(7, changed.size))
    }
}
