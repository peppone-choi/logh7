package org.logh7.gateway

import org.logh7.engine.Navigator
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.LoginMessages
import java.nio.ByteBuffer

/** Friendly grid presence plus people physically sharing a non-ship room. */
class GamePresence {
    data class Member(val connection: String, val character: CharacterMessages.Generate,
        val player: Navigator, val send: (List<ByteArray>) -> Unit)
    data class Update(val peers: List<Member>, val refresh: List<ByteArray>)
    private val members = linkedMapOf<String, Member>()
    private fun send(viewer: Member, messages: List<ByteArray>) {
        try { viewer.send(messages) } catch (_: Exception) { /* A closed peer cannot fail another player's command. */ }
    }
    private fun visible(viewer: Member, other: Member) = viewer.connection != other.connection &&
        viewer.player.generation == other.player.generation && viewer.player.grid == other.player.grid &&
        (viewer.player.power == other.player.power ||
            (viewer.player.baseId != 0 && viewer.player.baseId == other.player.baseId && viewer.player.spotId != 0 &&
                viewer.player.spotId == other.player.spotId && viewer.player.spotOwner == other.player.spotOwner))
    private fun peers(viewer: Member) = members.values.filter { visible(viewer, it) }.sortedBy { it.character.id }
    /** Spot zero means one's own ship, not a public room shared by every undocked player. */
    @Synchronized fun spotChat(connection: String, message: ChatMessages.Spot, seconds: Long): ByteArray {
        val sender = checkNotNull(members[connection]) { "Chat requires published world membership" }
        require(message.characterId.toLong() == sender.character.id)
        val packet = ChatMessages.spot(message, seconds)
        val location = sender.player
        if (location.baseId != 0 && location.spotId != 0) members.values.filter {
            it.connection != connection && it.player.generation == location.generation &&
                it.player.grid == location.grid && it.player.baseId == location.baseId &&
                it.player.spotId == location.spotId && it.player.spotOwner == location.spotOwner
        }.forEach { send(it, listOf(packet)) }
        return packet // The sender's echo uses its own exchange/cipher queue exactly once.
    }
    fun character(member: Member) = BootstrapMessages.character(member.character, member.player.pcp,
        member.player.mcp, member.player.spotId, member.player.spotOwner)
    private fun refresh(before: List<Member>, after: List<Member>): List<ByteArray> {
        val remaining = after.map { it.player.unitId }.toSet()
        val leaving = before.map { it.player.unitId }.filter { it !in remaining }
        // 004bece0 removes characters by unit ID; the CD reader accepts at most 70 IDs.
        val removed = leaving.chunked(70).map { ids -> LoginMessages.sessionMessage(0x0b08,
            ByteBuffer.allocate(1 + ids.size * 4).put(ids.size.toByte()).also { out -> ids.forEach(out::putInt) }.array()) }
        // 0356 only updates existing slots. Enter-grid transactions import newly seen characters.
        // Do not resend the viewer's own character: a queued notification must not roll back its CP.
        return removed + LoginMessages.sessionMessage(0x0b09, byteArrayOf(0)) +
            after.map(::character) + LoginMessages.sessionMessage(0x0b0a, byteArrayOf(0))
    }
    @Synchronized fun update(member: Member): Update {
        val previous = members[member.connection]
        if (previous != null && previous.player.generation == member.player.generation &&
            previous.player.power == member.player.power && previous.player.grid == member.player.grid &&
            members.values.filter { it.connection != member.connection }.all { visible(previous, it) == visible(member, it) }) {
            members[member.connection] = member
            val before = character(previous)
            val after = character(member)
            if (!before.contentEquals(after)) {
                val changed = BootstrapMessages.changedCharacter(member.character, member.player.pcp,
                    member.player.mcp, member.player.spotId, member.player.spotOwner)
                members.values.filter { visible(it, member) }.forEach { send(it, listOf(changed)) }
            }
            return Update(peers(member), emptyList())
        }
        val before = members.values.associate { it.connection to peers(it) }
        val oldPeers = previous?.let(::peers).orEmpty()
        members[member.connection] = member
        val newPeers = peers(member)
        if (newPeers.size >= 600) {
            if (previous == null) members.remove(member.connection) else members[member.connection] = previous
            error("Strategy character cache capacity exceeded")
        }
        members.values.filter { it.connection != member.connection }.forEach { viewer ->
            val old = before[viewer.connection].orEmpty()
            val next = peers(viewer)
            if (old.map { it.character to it.player } != next.map { it.character to it.player })
                send(viewer, refresh(old, next))
        }
        return Update(newPeers, if (previous != null && (previous.player.grid != member.player.grid ||
            oldPeers.map { it.character.id } != newPeers.map { it.character.id }))
            refresh(oldPeers, newPeers) else emptyList())
    }
    @Synchronized fun remove(connection: String) {
        val leaving = members[connection] ?: return
        val affected = members.values.filter { visible(it, leaving) }.associateWith(::peers)
        members.remove(connection)
        affected.forEach { (viewer, before) -> send(viewer, refresh(before, peers(viewer))) }
    }
}

internal data class PresenceNotification(val messages: List<ByteArray>)
