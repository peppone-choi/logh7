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
        val player: Navigator, val send: (List<ByteArray>) -> Unit,
        val offices: List<BootstrapMessages.OfficeCard> = emptyList(), val outfit: Int = 0)
    data class Update(val peers: List<Member>, val refresh: List<ByteArray>)
    private val members = linkedMapOf<String, Member>()
    @Synchronized fun movement(event:org.logh7.engine.TacticalMovement) {
        val packet=if(event.destination!=null)org.logh7.protocol.TacticalRetreatMessages.warped(event.seconds,checkNotNull(event.destination),listOf(event.unit))
            else checkNotNull(TacticalMotionMessages.npc(event.unit,checkNotNull(event.motion),event.seconds))
        members.values.filter {it.player.generation==event.generation && event.recipients[it.character.id.toInt()]==it.connection}
            .forEach {send(it,listOf(packet))}
    }
    /** Every committed shot is queued separately, including while an exchange awaits another command. */
    @Synchronized fun combat(impact: org.logh7.engine.TacticalImpact) {
        val packet = org.logh7.protocol.TacticalAttackMessages.hit(impact.hit.seconds,impact.source,
            impact.weapon.wire,impact.hit.target,impact.hit.destroyed,
            impact.hit.damaged,impact.hit.shieldFace,impact.hit.shieldDamage)
        members.values.filter { viewer -> viewer.player.generation == impact.generation &&
            impact.recipients[viewer.character.id.toInt()] == viewer.connection }.forEach { viewer ->
                if (impact.departed?.characterId==viewer.character.id.toInt()) {
                    // 004c96c0 requires a living own flagship while rendering any tactical unit.
                    // Leave the victim's view before destroying that native anchor; bootstrap returns the replacement.
                    send(viewer,listOf(org.logh7.protocol.TacticsMessages.notify(impact.hit.grid,false)))
                } else {
                    val leave=impact.departed?.let {
                        listOf(org.logh7.protocol.TacticalRetreatMessages.warped(impact.hit.seconds,it.grid,listOf(it.unitId)))
                    }.orEmpty()
                    send(viewer,listOf(packet)+leave)
                }
            }
    }
    private fun send(viewer: Member, messages: List<ByteArray>) {
        try { viewer.send(messages) } catch (_: Exception) { /* A closed peer cannot fail another player's command. */ }
    }
    private fun visible(viewer: Member, other: Member) = viewer.connection != other.connection &&
        viewer.player.generation == other.player.generation && viewer.player.grid == other.player.grid &&
        (viewer.player.power == other.player.power ||
            (viewer.player.baseId != 0 && viewer.player.baseId == other.player.baseId && viewer.player.spotId != 0 &&
                viewer.player.spotId == other.player.spotId && viewer.player.spotOwner == other.player.spotOwner))
    private fun peers(viewer: Member) = members.values.filter { visible(viewer, it) }.sortedBy { it.character.id }
    /** Public profiles follow the same live visibility boundary as the strategy character cache. */
    @Synchronized fun detail(connection: String, id: Int): Member? {
        val viewer = members[connection] ?: return null
        return members.values.singleOrNull { it.character.id == id.toLong() &&
            it.player.generation == viewer.player.generation && (it.connection == connection || visible(viewer, it)) }
    }
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
    @Synchronized fun gridChat(connection: String, message: ChatMessages.Grid, seconds: Long): ByteArray {
        val sender = checkNotNull(members[connection]) { "Chat requires published world membership" }
        require(message.characterId.toLong() == sender.character.id)
        val packet = ChatMessages.grid(message, seconds)
        members.values.filter { other ->
            other.connection != connection && other.player.generation == sender.player.generation &&
                other.player.grid == sender.player.grid && when (message.cast) {
                    0 -> true
                    1 -> other.player.power == sender.player.power
                    2 -> sender.outfit > 0 && other.outfit == sender.outfit && other.player.power == sender.player.power
                    else -> false
                }
        }.forEach { send(it, listOf(packet)) }
        return packet
    }
    fun character(member: Member) = BootstrapMessages.character(member.character, member.player.pcp,
        member.player.mcp, member.player.spotId, member.player.spotOwner, member.player.returnBase, member.offices)
    fun unit(member: Member) = BootstrapMessages.unit(member.character.copy(shipKind=member.player.hull?.kind ?: member.character.shipKind),member.player.grid,member.player.cruise,
        member.player.baseId,member.player.mode,member.outfit,member.player.supplies,if(member.player.hull?.hp?.let {it<100}==true)1 else 0)
    private fun location(previous: Member?, member: Member, seconds: Long) {
        if (previous == null) return
        val old = previous.player; val next = member.player
        if (old.motion != next.motion) TacticalMotionMessages.reply(next,seconds)?.let { packet ->
            members.values.filter {it.connection!=member.connection && it.player.generation==next.generation &&
                it.player.grid==next.grid && it.player.baseId==0 && it.player.cpPaused}.forEach {send(it,listOf(packet))}
        }
        val changedBase = old.baseId != next.baseId || old.mode != next.mode
        if (!changedBase && old.grid == next.grid && old.cruise == next.cruise && previous.outfit == member.outfit) return
        val messages = listOf(BootstrapMessages.unit(member.character.copy(shipKind=member.player.hull?.kind ?: member.character.shipKind), next.grid, next.cruise, next.baseId, next.mode, member.outfit,next.supplies,if(next.hull?.hp?.let {it<100}==true)1 else 0)) +
            if (changedBase) listOf(org.logh7.protocol.PlaceMessages.dockNotification(next.characterId,
                next.baseId, next.mode, seconds, next.spotId, if (next.spotId == 0) next.characterId else next.spotOwner)) else emptyList()
        members.values.filter { visible(it, member) }.forEach { send(it, messages) }
    }
    private fun refresh(before: List<Member>, after: List<Member>): List<ByteArray> {
        val remaining = after.map { it.player.unitId }.toSet()
        val leaving = before.map { it.player.unitId }.filter { it !in remaining }
        // 004bece0 removes characters by unit ID; the CD reader accepts at most 70 IDs.
        val removed = leaving.chunked(70).map { ids -> LoginMessages.sessionMessage(0x0b08,
            ByteBuffer.allocate(1 + ids.size * 4).put(ids.size.toByte()).also { out -> ids.forEach(out::putInt) }.array()) }
        // 0356 only updates existing slots. Enter-grid transactions import newly seen characters.
        // Do not resend the viewer's own character: a queued notification must not roll back its CP.
        return removed + LoginMessages.sessionMessage(0x0b09, byteArrayOf(0)) +
            after.flatMap { listOf(character(it),unit(it)) } + LoginMessages.sessionMessage(0x0b0a, byteArrayOf(0))
    }
    @Synchronized fun update(member: Member, seconds: Long = 0): Update {
        val previous = members[member.connection]
        if (previous != null && previous.player.generation == member.player.generation &&
            previous.player.power == member.player.power && previous.player.grid == member.player.grid &&
            members.values.filter { it.connection != member.connection }.all { visible(previous, it) == visible(member, it) }) {
            members[member.connection] = member
            val before = character(previous)
            val after = character(member)
            if (!before.contentEquals(after)) {
                val changed = BootstrapMessages.changedCharacter(member.character, member.player.pcp,
                    member.player.mcp, member.player.spotId, member.player.spotOwner, member.player.returnBase, member.offices)
                members.values.filter { visible(it, member) }.forEach { send(it, listOf(changed)) }
            }
            location(previous, member, seconds)
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
        location(previous, member, seconds)
        return Update(newPeers, if (previous != null && (previous.player.grid != member.player.grid ||
            oldPeers.map { it.character.id } != newPeers.map { it.character.id }))
            refresh(oldPeers, newPeers) else emptyList())
    }
    @Synchronized fun refreshOffices(characterId: Int,generation: Long,offices: List<BootstrapMessages.OfficeCard>,seconds: Long,outfit: Int?=null) {
        val previous=members.values.singleOrNull { it.character.id.toInt()==characterId && it.player.generation==generation } ?: return
        val next=previous.copy(offices=offices,outfit=outfit ?: previous.outfit)
        val player=next.player
        val updated=update(next,seconds)
        send(next,listOf(BootstrapMessages.changedCharacter(next.character,player.pcp,player.mcp,player.spotId,
            player.spotOwner,player.returnBase,offices))+
            (if(next.outfit!=previous.outfit) listOf(BootstrapMessages.unit(next.character.copy(shipKind=player.hull?.kind ?: next.character.shipKind),player.grid,player.cruise,
                player.baseId,player.mode,next.outfit,player.supplies,if(player.hull?.hp?.let {it<100}==true)1 else 0)) else emptyList())+updated.refresh)
    }
    @Synchronized fun remove(connection: String) {
        val leaving = members[connection] ?: return
        val affected = members.values.filter { visible(it, leaving) }.associateWith(::peers)
        members.remove(connection)
        affected.forEach { (viewer, before) -> send(viewer, refresh(before, peers(viewer))) }
    }
}

internal data class PresenceNotification(val messages: List<ByteArray>)
