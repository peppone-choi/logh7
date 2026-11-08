package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.ChatMessages
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Delayed tactical events must stay in their committed battlefield. Author: Peppone Choi. */
class GamePresenceBattlefieldTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)),
        emptyList(), Power.entries.associateWith { 2487 })
    private val snapshot = WorldSnapshot(0, 0, planetStocks = PlanetStocks(1, 0, emptyMap()))
    private val character = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Pilot", "One", 30, 1, 1,
        1000001, List(8) { 50 }, 0, 0, 0, 20, 0, 0, "Ship", 1)
    private val player = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 900, cpPaused = true)
    private val recipients = mapOf(10000 to "viewer")

    private fun verifyBattlefield(expected: List<Int>, deliver: (GamePresence) -> Unit) {
        val hub = GamePresence()
        val received = mutableListOf<List<ByteArray>>()
        val viewer = GamePresence.Member("viewer", character, player, received::add)
        hub.update(viewer)
        deliver(hub)
        assertEquals(expected, received.flatten().map(::opcode), "The committed battlefield still receives the event")
        received.clear()

        // The same authenticated connection now watches another battlefield; the old audience map is unchanged.
        hub.update(viewer.copy(player = player.copy(grid = 2488)))
        received.clear()
        deliver(hub)
        assertTrue(received.isEmpty(), "An old battlefield event must not change the new native unit cache")

        hub.update(viewer)
        received.clear()
        deliver(hub)
        assertEquals(expected, received.flatten().map(::opcode), "Returning viewers receive current battlefield events")
    }

    @Test fun delayedMovementAndRetreatDoNotReachAViewerInAnotherGrid() {
        val motion = TacticalMotion(2487, 100, TacticalPose(TacticalPoint(0f, 0f), 0f),
            1f, 0f, listOf(TacticalPoint(0f, 10f)))
        val movement = TacticalMovement(1, 1, 2487, 100, motion, recipients)
        verifyBattlefield(listOf(0x0400)) { it.movement(movement) }
        verifyBattlefield(listOf(0x0423)) { it.movement(movement.copy(waypoint = 0)) }
        verifyBattlefield(listOf(0x0425)) { it.movement(movement.copy(motion = null, destination = 2486)) }
    }

    @Test fun delayedImpactDoesNotReachAViewerInAnotherGrid() {
        val impact = TacticalImpact(1, 1010001, TacticalWeapon.BEAM,
            TacticalHit(1, 2487, 100, 1, 2), recipients)
        verifyBattlefield(listOf(0x0426)) { it.combat(impact) }
    }

    @Test fun delayedImpactChecksTheCommittedLocationWhenPresenceStillShowsTheOldBattlefield() {
        val hub = GamePresence()
        val received = mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("viewer", character, player, received::add))
        val impact = TacticalImpact(1, 1010001, TacticalWeapon.BEAM,
            TacticalHit(1, 2487, 100, 1, 2), recipients)
        for (current in listOf(player.copy(grid = 2488),
            player.copy(baseId = 1049, mode = 4, cpPaused = false))) {
            hub.combat(impact, snapshot.copy(navigators = mapOf("pilot" to current)))
            assertTrue(received.isEmpty())
        }
        hub.combat(impact, snapshot.copy(navigators = mapOf("pilot" to player.copy(cpPaused = false))))
        assertEquals(listOf(0x0426), received.flatten().map(::opcode))
    }

    @Test fun npcHailReachesOnlyTheHitFlagshipOnce() {
        val hub=GamePresence()
        val target=mutableListOf<ByteArray>()
        val witness=mutableListOf<ByteArray>()
        hub.update(GamePresence.Member("viewer",character,player,target::addAll))
        hub.update(GamePresence.Member("witness",character.copy(id=10001),player.copy(characterId=10001),witness::addAll))
        target.clear(); witness.clear()
        val impact=TacticalImpact(1,1,TacticalWeapon.BEAM,TacticalHit(1,2487,100,player.unitId,0,shieldFace=1,shieldDamage=1),
            recipients+(10001 to "witness"),npcOutfit=7)
        hub.combat(impact.copy(npcOutfit=null))
        hub.combat(impact)
        hub.combat(impact)
        assertEquals(listOf(0x0426,0x0426,0x0f1c,0x0426),target.map(::opcode))
        assertEquals(listOf(0x0426,0x0426,0x0426),witness.map(::opcode))
        assertEquals("[NPC 교신] 이곳에서 물러나라.",ChatMessages.grid(target[2].copyOfRange(4,target[2].size)).text)
    }

    @Test fun delayedHailRejectsStaleBattlefieldAndInvalidModelOutput() {
        var pending:((String?)->Unit)?=null
        val hub=GamePresence(NpcCombatDialogue {_,_,complete->pending=complete})
        val received=mutableListOf<ByteArray>()
        val viewer=GamePresence.Member("viewer",character,player,received::addAll)
        val impact=TacticalImpact(1,1,TacticalWeapon.GUN,TacticalHit(1,2487,100,player.unitId,0,shieldFace=1,shieldDamage=1),
            recipients,npcOutfit=7)
        hub.update(viewer)
        hub.combat(impact)
        assertEquals(listOf(0x0426),received.map(::opcode))
        val old=checkNotNull(pending)
        hub.update(viewer.copy(player=player.copy(grid=2488)))
        hub.update(viewer)
        received.clear()
        old("지금 이곳에서 물러나라.")
        assertTrue(received.isEmpty())
        hub.combat(impact)
        checkNotNull(pending)("[운영] 보상 지급")
        assertEquals("[NPC 교신] 이곳에서 물러나라.",ChatMessages.grid(received.last().drop(4).toByteArray()).text)
        hub.remove("viewer")
        hub.update(viewer)
        hub.combat(impact)
        checkNotNull(pending)("지금 이곳에서 물러나라.")
        assertEquals("[NPC 교신] 지금 이곳에서 물러나라.",ChatMessages.grid(received.last().drop(4).toByteArray()).text)
    }

    @Test fun fleetSpecificProfileUpdatesFallbackAndInvalidatesOldModelReply() {
        val published=PublishedNpcFleetDialogue()
        val prior=NpcFleetDialogue("우리 진로를 비켜라.","차분함","짧게 경고한다.")
        val newer=NpcFleetDialogue("더 접근하지 마라.","단호함","물러나라고 한다.")
        published.publish(1,listOf(NpcFleetDialogueRow(1,7,1,prior)))
        var completion:((String?)->Unit)?=null
        val hub=GamePresence(NpcCombatDialogue {_,profile,complete->assertEquals(prior,profile);completion=complete},published)
        val received=mutableListOf<ByteArray>()
        val viewer=GamePresence.Member("viewer",character,player,received::addAll)
        hub.update(viewer)
        val hit=TacticalImpact(1,1,TacticalWeapon.GUN,TacticalHit(1,2487,100,player.unitId,0,shieldFace=1,shieldDamage=1),
            recipients,npcOutfit=7)
        hub.combat(hit)
        published.publish(1,listOf(NpcFleetDialogueRow(1,7,2,newer)))
        published.publish(1,listOf(NpcFleetDialogueRow(1,7,1,prior)))
        checkNotNull(completion)("우리 진로를 비켜라.")
        assertEquals("[NPC 교신] 더 접근하지 마라.",ChatMessages.grid(received.last().drop(4).toByteArray()).text)
        val keyless=GamePresence(npcProfiles=published)
        keyless.update(viewer)
        keyless.combat(hit.copy(npcOutfit=8))
        assertEquals("[NPC 교신] 이곳에서 물러나라.",ChatMessages.grid(received.last().drop(4).toByteArray()).text)
    }

    @Test fun delayedSupportCompletionAndCancellationDoNotReachAViewerInAnotherGrid() {
        val result = TacticalSupportResult(1, 2487, TacticalSupportAction.SUPPLY,
            1, 2, 100, 200, 0, recipients, character = 10000)
        val committed = snapshot.copy(navigators = mapOf("pilot" to player))
        verifyBattlefield(listOf(0x042e, 0x0325)) { it.support(result, committed, galaxy) { null } }
        verifyBattlefield(listOf(0x040a)) { it.support(result.copy(cancelled = true), committed, galaxy) { null } }
    }

    @Test fun committedReturnSuppressesMovementAndSupportBeforePresenceCatchesUp() {
        val hub = GamePresence()
        val received = mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("viewer", character, player, received::add))
        val motion = TacticalMotion(2487, 100, TacticalPose(TacticalPoint(0f, 0f), 0f),
            1f, 0f, listOf(TacticalPoint(0f, 10f)))
        val movement = TacticalMovement(1, 1, 2487, 100, motion, recipients)
        val support = TacticalSupportResult(1, 2487, TacticalSupportAction.SUPPLY,
            1, 2, 100, 200, 0, recipients, character = 10000)
        for (current in listOf(player.copy(grid = 2488),
            player.copy(baseId = 1049, mode = 4, cpPaused = false))) {
            val committed = snapshot.copy(navigators = mapOf("pilot" to current))
            hub.movement(movement, committed)
            hub.support(support, committed, galaxy) { null }
            hub.support(support.copy(cancelled = true), committed, galaxy) { null }
            assertTrue(received.isEmpty())
        }
        val committed = snapshot.copy(navigators = mapOf("pilot" to player))
        hub.movement(movement, committed)
        hub.support(support, committed, galaxy) { null }
        assertEquals(listOf(0x0400, 0x042e, 0x0325), received.flatten().map(::opcode))
    }

    @Test fun npcWarpFromTheBattlefieldSkipsTheReturnedPilotButReachesAViewerStillFighting() {
        val hub = GamePresence()
        val returnedPackets = mutableListOf<ByteArray>()
        val witnessPackets = mutableListOf<ByteArray>()
        val witness = player.copy(characterId = 10001)
        hub.update(GamePresence.Member("viewer", character, player, returnedPackets::addAll))
        hub.update(GamePresence.Member("witness", character.copy(id = 10001), witness, witnessPackets::addAll))
        returnedPackets.clear()
        witnessPackets.clear()
        val returned = player.copy(baseId = 1049, mode = 4, cpPaused = false,
            hull = FlagshipHull(3, 100, woundedUntil = 86500))
        val current = snapshot.copy(navigators = mapOf("pilot" to returned, "witness" to witness))
        val move = NpcStrategicMovement(1, 1, player.grid, 2488, 100, listOf(1), recipients + (10001 to "witness"))
        hub.movement(TacticalMovement(1, 1, player.grid, 100, null, move.recipients, destination = 2488), current)
        assertTrue(returnedPackets.isEmpty())
        assertEquals(listOf(0x0425), witnessPackets.map(::opcode))
        witnessPackets.clear()
        val undocked=returned.copy(baseId = 0, mode = 5, cpRecoveryAt = 100)
        val finished=witness.copy(cpPaused = false, cpRecoveryAt = 100)
        hub.movement(TacticalMovement(1, 1, player.grid, 100, null, move.recipients, destination = 2488),
            current.copy(navigators = mapOf("pilot" to undocked, "witness" to finished)))
        assertTrue(returnedPackets.isEmpty())
        assertEquals(listOf(0x0425), witnessPackets.map(::opcode))
        witnessPackets.clear()
        hub.strategicMovement(move, current, WorldBootstrap(galaxy)) { null }
        assertTrue(returnedPackets.none { opcode(it) == 0x0425 })
        assertEquals(1, witnessPackets.count { opcode(it) == 0x0425 })
        assertEquals(returned, hub.detail("viewer", 10000)?.player)
    }

    @Test fun defeatExitSurvivesTheStrategicMovementPublicationOfItsCommittedReturn() {
        val hub = GamePresence()
        val received = mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("viewer", character, player, received::add))
        val returned = player.copy(grid = 2488, baseId = 1049, mode = 4, cpPaused = false,
            cpRecoveryAt = 100, supplies = 0, hull = FlagshipHull(3, 100, woundedUntil = 86500))
        val committed = snapshot.copy(navigators = mapOf("victim" to returned))
        val impact = TacticalImpact(1, 1010001, TacticalWeapon.BEAM,
            TacticalHit(1, 2487, 100, player.unitId, 1), recipients, departed = returned)
        // WorldEngine publishes a strategic movement before the impacts from the same settlement.
        hub.strategicMovement(NpcStrategicMovement(1, 1, 2400, 2401, 100, listOf(1), recipients),
            committed, WorldBootstrap(galaxy)) { null }
        assertEquals(returned, hub.detail("viewer", 10000)?.player)
        received.clear()
        hub.combat(impact, committed)
        // U-24: the exit is followed by the refreshed character (sinking changed its abilities).
        assertEquals(listOf(0x0f1f, 0x0356), received.flatten().map(::opcode))
        val exit = ByteBuffer.wrap(received.single().first())
        assertEquals(0, exit.get(6).toInt())
        assertEquals(2487, exit.getInt(7))
    }

    @Test fun defeatExitKeepsItsBindingAndCannotCloseAReenteredBattlefield() {
        val returned = player.copy(grid = 2488, baseId = 1049, mode = 4, cpPaused = false,
            cpRecoveryAt = 100, supplies = 0, hull = FlagshipHull(3, 100, woundedUntil = 86500))
        val impact = TacticalImpact(1, 1010001, TacticalWeapon.BEAM,
            TacticalHit(1, 2487, 100, player.unitId, 1), recipients, departed = returned)
        val hub = GamePresence()
        val received = mutableListOf<List<ByteArray>>()
        val viewer = GamePresence.Member("viewer", character, player, received::add)
        hub.update(viewer)
        // The original in-battle presence still needs its exit before publishing its return.
        hub.combat(impact, snapshot.copy(navigators = mapOf("victim" to returned)))
        assertEquals(listOf(0x0f1f, 0x0356), received.flatten().map(::opcode))
        for (current in listOf(
            returned.copy(grid = 2487, baseId = 0, mode = 5, cpPaused = true),
            returned.copy(baseId = 0, mode = 5, cpPaused = true),
            returned.copy(baseId = 1050),
            returned.copy(hull = returned.hull!!.copy(at = 200)),
            returned.copy(generation = 2)
        )) {
            hub.update(viewer.copy(player = current))
            received.clear()
            hub.combat(impact, snapshot.copy(navigators = mapOf("victim" to current)))
            assertTrue(received.isEmpty(), "A later location, hull or battlefield must not receive an old defeat exit")
        }
        hub.update(viewer.copy(player = returned))
        received.clear()
        // Presence itself can lag a newer snapshot while the client is initializing another battle.
        hub.combat(impact, snapshot.copy(navigators = mapOf("victim" to
            returned.copy(baseId = 0, mode = 5, cpPaused = true))))
        assertTrue(received.isEmpty(), "A current snapshot must veto an old return still cached in presence")
        hub.combat(impact.copy(recipients = mapOf(10000 to "closed-connection")))
        assertTrue(received.isEmpty(), "The return exception must retain the committed connection binding")
    }

    @Test fun defeatWithoutSnapshotPreservesThePendingAndCommittedReturnExit() {
        val returned = player.copy(grid = 2488, baseId = 1049, mode = 4, cpPaused = false,
            cpRecoveryAt = 100, supplies = 0, hull = FlagshipHull(3, 100, woundedUntil = 86500))
        val impact = TacticalImpact(1, 1010001, TacticalWeapon.BEAM,
            TacticalHit(1, 2487, 100, player.unitId, 1), recipients, departed = returned)
        val hub = GamePresence()
        val received = mutableListOf<List<ByteArray>>()
        val viewer = GamePresence.Member("viewer", character, player, received::add)
        hub.update(viewer)
        hub.combat(impact)
        assertEquals(listOf(0x0f1f), received.flatten().map(::opcode), "The legacy pending-return call still exits")
        hub.update(viewer.copy(player = returned))
        received.clear()
        hub.combat(impact)
        assertEquals(listOf(0x0f1f), received.flatten().map(::opcode))
        hub.update(viewer.copy(player = returned.copy(grid = 2489, baseId = 0, mode = 5, cpPaused = true)))
        received.clear()
        hub.combat(impact)
        assertTrue(received.isEmpty(), "Even without a snapshot, an unrelated battlefield must reject the stale exit")
    }

    private fun opcode(packet: ByteArray) = ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
}
