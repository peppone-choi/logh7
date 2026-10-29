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
    private val motionPaths = java.util.concurrent.ConcurrentHashMap<Pair<String,Int>,Int>()
    /** Track the path actually queued for each native client, including truncated reconnect paths. */
    internal fun observeMotions(connection:String,messages:List<ByteArray>) {
        messages.forEach { packet ->
            if(packet.size<48)return@forEach
            val input=ByteBuffer.wrap(packet)
            if(input.getInt(0)!=0 || (input.getShort(4).toInt() and 65535) !in listOf(0x0400,0x0402))return@forEach
            val units=input.get(18).toInt() and 255
            val countOffset=27+units*20
            if(units !in 1..32 || countOffset>=packet.size)return@forEach
            val points=input.get(countOffset).toInt() and 255
            if(points !in 1..32 || packet.size!=countOffset+1+points*12)return@forEach
            repeat(units) { motionPaths[connection to input.getInt(19+it*20)]=points }
        }
    }
    @Synchronized fun occupation(event:org.logh7.engine.BaseOccupation,snapshot:org.logh7.engine.WorldSnapshot,
        world:WorldBootstrap,characterById:(Int)->CharacterMessages.Generate?,
        offices:(Int,Int,Long)->List<BootstrapMessages.OfficeCard>) {
        val stocks=snapshot.planetStocks ?: return
        if(stocks.generation!=event.generation)return
        // Snapshot the audience before updating membership; room departures mutate visibility.
        val audience=members.values.filter {it.player.generation==event.generation &&
            event.recipients[it.character.id.toInt()]==it.connection}.toList()
        for(previous in audience) {
            val player=snapshot.navigators.values.singleOrNull {it.characterId==previous.character.id.toInt() &&
                it.generation==event.generation} ?: continue
            val cards=offices(player.characterId,previous.character.power,event.generation)
            val member=previous.copy(player=player,offices=cards)
            val updated=update(member,event.seconds)
            val packets=mutableListOf(world.conditions(listOf(event.base),stocks),world.places(listOf(event.base),stocks))
            if(cards!=previous.offices || player.returnBase!=previous.player.returnBase || player.baseId!=previous.player.baseId) {
                packets+=BootstrapMessages.changedCharacter(member.character,player.pcp,player.mcp,player.spotId,
                    player.spotOwner,player.returnBase,cards)
            }
            val expelled=event.expelled.values.any {it.characterId==player.characterId}
            if(expelled) {
                packets+=unit(member)
                packets+=org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,0,5,event.seconds)
                packets+=org.logh7.protocol.TacticsMessages.notify(player.grid,player.cpPaused)
            } else if(player.grid==event.grid && previous.player.cpPaused) {
                val tactical=TacticalBootstrap(snapshot,world.galaxy,player,characterById)
                packets+=org.logh7.protocol.TacticalGroundMessages.occupationFinished(event.base,event.power,0)
                // Imported definitions and states retain real zero-boat identities; no fake weapon hit.
                packets+=event.expelled.values.mapNotNull {p->characterById(p.characterId)?.let {c->
                    BootstrapMessages.character(c,p.pcp,p.mcp,p.spotId,p.spotOwner,p.returnBase,
                        offices(p.characterId,c.power,event.generation))}}
                packets+=org.logh7.protocol.TacticsMessages.definitions(event.grid,tactical.units,tactical.groundDefinitions)
                packets+=org.logh7.protocol.TacticsMessages.corps(tactical.corps)
                packets+=org.logh7.protocol.TacticsMessages.states(tactical.units)
                packets+=org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation)
            }
            if(previous.player.cpPaused && !player.cpPaused && !expelled)
                packets+=org.logh7.protocol.TacticsMessages.notify(player.grid,false)
            GridForceReport.message(snapshot,world.galaxy,player,event.seconds)?.let {packets+=it}
            send(member,packets+updated.refresh)
        }
    }
    @Synchronized fun groundBattle(event:org.logh7.engine.GroundBattleResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        members.values.filter {it.player.generation==event.generation && it.player.grid==event.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}.forEach {viewer->
            val tactical=TacticalBootstrap(snapshot,galaxy,viewer.player,characterById)
            send(viewer,listOf(org.logh7.protocol.TacticsMessages.definitions(event.grid,tactical.units,tactical.groundDefinitions),
                org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation),
                org.logh7.protocol.TacticalGroundMessages.baseDefenseChanged(event.seconds,event.base,
                    tactical.baseInformation.single {it.position.id==event.base}.stamina)))
        }
    }
    @Synchronized fun ground(event:org.logh7.engine.TacticalGroundResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        val order=event.order
        members.values.filter {it.player.generation==event.generation && it.player.grid==order.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}.forEach {viewer ->
            val packet=if(event.cancelled)listOf(org.logh7.protocol.TacticalGroundMessages.reply(
                org.logh7.protocol.TacticalGroundMessages.Command(
                    if(order.action==org.logh7.engine.TacticalGroundAction.DEPLOY)org.logh7.protocol.TacticalGroundMessages.Action.DEPLOY
                    else org.logh7.protocol.TacticalGroundMessages.Action.WITHDRAW,order.character,listOf(order.carrier)),event.seconds,0,false))
            else {
                val tactical=TacticalBootstrap(snapshot,galaxy,viewer.player,characterById)
                listOf(org.logh7.protocol.TacticsMessages.definitions(order.grid,tactical.units,tactical.groundDefinitions),
                    org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation),
                    org.logh7.protocol.TacticalGroundMessages.baseDefenseChanged(event.seconds,order.base,
                        tactical.baseInformation.single {it.position.id==order.base}.stamina))+
                    event.troops.map {troop->org.logh7.protocol.TacticalGroundMessages.movedTroop(event.seconds,
                        order.action==org.logh7.engine.TacticalGroundAction.WITHDRAW,order.carrier,troop,order.base)}
            }
            send(viewer,packet)
        }
    }
    @Synchronized fun posture(event:org.logh7.engine.TacticalPostureResult) {
        val ids=event.units.keys.toList()
        val packet=if(event.cancelled && event.sortie)org.logh7.protocol.TacticalGroundMessages.reply(
            org.logh7.protocol.TacticalGroundMessages.Command(org.logh7.protocol.TacticalGroundMessages.Action.SORTIE,event.character,ids),event.seconds,0,false)
        else if(event.cancelled)org.logh7.protocol.TacticalGroundMessages.reply(
            org.logh7.protocol.TacticalGroundMessages.Posture(event.character,ids,event.mode.wire,event.base),event.seconds,0,false)
        else if(event.sortie)org.logh7.protocol.TacticalGroundMessages.sortieCompleted(event.seconds,event.base,
            event.units.entries.single().let {(id,pose)->org.logh7.protocol.TacticalGroundMessages.PostureUnit(id,pose.direction,pose.position.x,pose.position.y,0f)})
        else org.logh7.protocol.TacticalGroundMessages.postureChanged(event.seconds,event.mode.wire,event.base,
            event.units.map {(id,pose)->org.logh7.protocol.TacticalGroundMessages.PostureUnit(id,pose.direction,
                pose.position.x,pose.position.y,0f)},event.spot,event.spotOwner)
        members.values.filter {it.player.generation==event.generation && it.player.grid==event.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}.forEach {viewer->
                val frozen=if(event.cancelled)emptyList() else event.units.map {(id,pose)->
                    TacticalMotionMessages.npc(id,org.logh7.engine.TacticalMotion(event.grid,event.seconds,pose,0f,pose.direction,
                        listOf(pose.position)),event.seconds,event.character)}
                send(viewer,frozen+packet)
            }
    }
    @Synchronized fun support(event:org.logh7.engine.TacticalSupportResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        val packet=if(event.cancelled)org.logh7.protocol.TacticalStopMessages.reply(
            org.logh7.protocol.TacticalStopMessages.Stop(event.character,listOf(event.unit)),snapshot.session?.gameSeconds ?: 0)
            else if(event.action==org.logh7.engine.TacticalSupportAction.REPAIR)
            org.logh7.protocol.TacticalSupportMessages.repaired(event.unit,event.target,event.supplies,event.damaged)
            else org.logh7.protocol.TacticalSupportMessages.supplied(event.unit,event.target,event.supplies,event.targetSupplies)
        members.values.filter {it.player.generation==event.generation && event.recipients[it.character.id.toInt()]==it.connection}
            .forEach { viewer ->
                // 0325 replaces the complete native definition cache, including unrelated combatants.
                val tactical=TacticalBootstrap(snapshot,galaxy,viewer.player,characterById)
                send(viewer,if(event.cancelled)listOf(packet) else listOf(packet,org.logh7.protocol.TacticsMessages.definitions(event.grid,tactical.units,tactical.groundDefinitions)))
            }
    }
    @Synchronized fun strategicMovement(event:org.logh7.engine.NpcStrategicMovement,
        snapshot:org.logh7.engine.WorldSnapshot,world:WorldBootstrap,characterById:(Int)->CharacterMessages.Generate?) {
        val audience=members.values.filter {it.player.generation==event.generation &&
            event.recipients[it.character.id.toInt()]==it.connection}.toList()
        for(previous in audience) {
            val player=snapshot.navigators.values.singleOrNull {it.generation==event.generation &&
                it.characterId==previous.character.id.toInt()} ?: continue
            val member=previous.copy(player=player)
            val updated=update(member,event.seconds)
            val packets=mutableListOf<ByteArray>()
            GridForceReport.message(snapshot,world.galaxy,player,event.seconds)?.let {packets+=it}
            if(player.grid==event.source && previous.player.cpPaused)event.units.chunked(255).forEach {
                packets+=org.logh7.protocol.TacticalRetreatMessages.warped(event.seconds,event.destination,it)
            }
            if(player.grid==event.destination && player.cpPaused) {
                if(!previous.player.cpPaused)packets+=org.logh7.protocol.TacticsMessages.notify(player.grid,true)
                else {
                    val tactical=TacticalBootstrap(snapshot,world.galaxy,player,characterById)
                    packets+=org.logh7.protocol.TacticsMessages.definitions(player.grid,tactical.units,tactical.groundDefinitions)
                    packets+=org.logh7.protocol.TacticsMessages.corps(tactical.corps)
                    packets+=org.logh7.protocol.TacticsMessages.states(tactical.units)
                    packets+=org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation)
                }
            }
            send(member,packets+updated.refresh)
        }
    }

    @Synchronized fun movement(event:org.logh7.engine.TacticalMovement) {
        members.values.filter {it.player.generation==event.generation && event.recipients[it.character.id.toInt()]==it.connection}
            .forEach { viewer ->
                if(event.formation.isNotEmpty()) {
                    send(viewer,TacticalMotionMessages.formation(event.formation,event.seconds,event.character))
                    return@forEach
                }
                val packet=if(event.turned)org.logh7.protocol.TacticalTurnMessages.turned(event.seconds,event.unit,checkNotNull(event.motion).direction)
                else if(event.destination!=null)
                    org.logh7.protocol.TacticalRetreatMessages.warped(event.seconds,checkNotNull(event.destination),listOf(event.unit))
                else if(event.waypoint!=null)checkNotNull(event.motion).let {motion ->
                    val pose=motion.pose(event.seconds)
                    val original=checkNotNull(event.waypoint)
                    val count=motionPaths[viewer.connection to event.unit] ?: motion.waypoints.size
                    val index=if(original<0)original else original-(motion.waypoints.size-count)
                    // A reconnect snapshot can already be ahead of this queued progress notification.
                    if(index<0 && original>=0)return@forEach
                    org.logh7.protocol.TacticalMoveMessages.moved(event.seconds,event.unit,pose.direction,
                        org.logh7.protocol.TacticalMoveMessages.Point(pose.position.x,pose.position.y,0f),index)
                } else TacticalMotionMessages.npc(event.unit,checkNotNull(event.motion),event.seconds,event.character)
                send(viewer,(if(event.stopped)listOf(org.logh7.protocol.TacticalStopMessages.reply(
                    org.logh7.protocol.TacticalStopMessages.Stop(event.character,listOf(event.unit)),event.seconds)) else emptyList())+packet)
            }
    }

    @Synchronized fun tacticalEnd(event:org.logh7.engine.TacticalEnd) {
        val viewer=members[event.connection] ?: return
        if(viewer.character.id!=event.character.toLong() || viewer.player.generation!=event.generation ||
            viewer.player.grid!=event.grid || !viewer.player.cpPaused)return
        send(viewer,listOf(org.logh7.protocol.TacticsMessages.notify(event.grid,false)))
    }

    /** Every committed shot is queued separately, including while an exchange awaits another command. */
    @Synchronized fun combat(impact: org.logh7.engine.TacticalImpact,
        snapshot:org.logh7.engine.WorldSnapshot?=null,galaxy:org.logh7.engine.Galaxy?=null,
        characterById:((Int)->CharacterMessages.Generate?)?=null) {
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
                    val cargo=if((impact.weapon!=org.logh7.engine.TacticalWeapon.BEAM || impact.hit.target in 1..999999) &&
                        snapshot!=null && galaxy!=null && characterById!=null &&
                        snapshot.navigators.values.any {it.characterId==viewer.character.id.toInt() && it.cpPaused}) {
                        val tactical=TacticalBootstrap(snapshot,galaxy,viewer.player,characterById)
                        listOf(org.logh7.protocol.TacticsMessages.definitions(impact.hit.grid,tactical.units,tactical.groundDefinitions),
                            org.logh7.protocol.TacticsMessages.corps(tactical.corps))
                    } else emptyList()
                    send(viewer,listOf(packet)+leave+cargo)
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
        // Persisted progress is announced by 0423; it must not restart the peer's native path.
        val changedMotion = old.motion?.copy(reportedWaypoints=0) != next.motion?.copy(reportedWaypoints=0)
        if (changedMotion && next.motion?.rotation==null) TacticalMotionMessages.reply(next,seconds)?.let { packet ->
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
        motionPaths.keys.removeIf { it.first==connection }
        val leaving = members[connection] ?: return
        val affected = members.values.filter { visible(it, leaving) }.associateWith(::peers)
        members.remove(connection)
        affected.forEach { (viewer, before) -> send(viewer, refresh(before, peers(viewer))) }
    }
}

internal data class PresenceNotification(val messages: List<ByteArray>)
