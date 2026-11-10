package org.logh7.gateway

import org.logh7.engine.Navigator
import org.logh7.engine.ProposalActions
import org.logh7.engine.TacticalWeapon
import org.logh7.engine.NpcFleetDialogue
import org.logh7.engine.PublishedNpcFleetDialogue
import org.logh7.engine.positionIn
import org.logh7.engine.members
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.LoginMessages
import java.nio.ByteBuffer

internal fun politicianCardForWire(character:CharacterMessages.Generate,player:Navigator):Int? =
    player.country?.takeIf {it.value==character.power}?.let {ProposalActions.politicianCard(it,character.rank)}

/** Friendly grid presence plus people physically sharing a non-ship room. */
class GamePresence(private val npcDialogue:NpcCombatDialogue?=null,
    private val npcProfiles:PublishedNpcFleetDialogue=PublishedNpcFleetDialogue()) {
    data class Member(val connection: String, val character: CharacterMessages.Generate,
        val player: Navigator, val send: (List<ByteArray>) -> Unit,
        val offices: List<BootstrapMessages.OfficeCard> = emptyList(), val outfit: Int = 0,
        val pendingTacticalEnd: Boolean = false,
        val sendTactical: ((Int, Int, List<ByteArray>) -> Unit)? = null)
    data class Update(val peers: List<Member>, val refresh: List<ByteArray>)
    private val members = linkedMapOf<String, Member>()
    private class CombatHail(val player:Navigator, val outfit:Int, val revision:Long?, val seconds:Long,
        val at:Long, var delivered:Boolean=false)
    private val combatHails = mutableMapOf<String,CombatHail>()
    @Synchronized internal fun communicationMember(connection:String):Member? = members[connection]
    @Synchronized internal fun communicationMembers(generation:Long):List<Member> =
        members.values.filter {it.player.generation==generation}.toList()
    @Synchronized internal fun communicationMember(generation:Long,character:Int):Member? =
        members.values.singleOrNull {it.player.generation==generation && it.character.id.toInt()==character}
    @Synchronized internal fun communicationSend(generation:Long,character:Int,messages:List<ByteArray>,except:String) {
        members.values.filter {it.connection!=except && it.player.generation==generation && it.character.id.toInt()==character}
            .forEach {send(it,messages)}
    }

    /** Ownership is public on the strategic map, including outside the viewer's current system. */
    @Synchronized internal fun refreshBaseOwnership(generation:Long,except:String?,packets:List<ByteArray>) {
        for(viewer in members.values.filter {it.connection!=except && it.player.generation==generation})
            send(viewer,packets)
    }

    /** Government approval is public base state; CP/influence peers use ordinary character publication. */
    @Synchronized fun refreshSocialBase(world:WorldBootstrap,stocks:org.logh7.engine.PlanetStocks,base:Int) {
        val grid=world.galaxy.bases.single {it.id==base}.grid
        for(viewer in members.values.filter {it.player.generation==stocks.generation && it.player.grid==grid})
            send(viewer,listOf(world.conditions(listOf(base),stocks)))
    }
    /** Money is private to the actual finance office; public 031f has no treasury/rate field. */
    @Synchronized fun budget(event:org.logh7.engine.BudgetChange,world:WorldBootstrap) {
        val s=event.stocks;val offices=org.logh7.engine.OrganizationalOfficePolicy(world.galaxy,world.lobbyCountryDefinitions().first)
        for(viewer in members.values.filter {it.player.generation==s.generation}) {
            val base=s.bases[viewer.player.baseId] ?: continue;val country=viewer.player.country ?: continue
            if(!base.belongsTo(country) || base.camp!=0)continue
            val card=listOf(8,136).firstOrNull {offices.role(it,country)!=null} ?: continue
            val role=checkNotNull(offices.role(card,country))
            if((s.strategic.people[viewer.character.id.toInt()]?.rank ?: viewer.character.rank) !in role.ranks ||
                offices.holders(s,card,country).none {it.character==viewer.character.id.toInt()})continue
            send(viewer,org.logh7.protocol.BudgetMessages.update(viewer.character.id.toInt(),card,
                org.logh7.protocol.BudgetMessages.View(base.baseId,s.nationalTreasuries[country] ?: 0,base.treasury,
                    org.logh7.engine.BudgetActions.rate(s,base.baseId,event.defaultRate))))
        }
    }
    /** Head-of-state changes (accession, succession war, chairman election/dismissal): one public chat line to everyone. */
    @Synchronized fun succession(event:org.logh7.engine.SuccessionNotice,characterById:(Int)->CharacterMessages.Generate?,
        countryName:(org.logh7.engine.CountryId)->String?={null}) {
        val name=characterById(event.character)?.let {if(it.generated)it else KoreanDisplayNames.original(it)}
            ?.let {(it.surname+" "+it.givenName).trim()}?.takeIf {it.isNotEmpty()} ?: "ID ${event.character}"
        val nation=countryName(event.country)?.filterNot {it.isSurrogate() || it.isISOControl()}?.take(12)?.takeIf {it.isNotBlank()}
            ?: "국가 ${event.country.value}"
        val line=("[$nation] "+when(event.kind) {
            org.logh7.engine.SuccessionNotice.Kind.ACCESSION -> "[황위] ${name}이(가) 황제로 즉위했습니다."
            org.logh7.engine.SuccessionNotice.Kind.CONTEST -> "[황위] ${name}이(가) 계승을 거부하고 반란을 일으켰습니다."
            org.logh7.engine.SuccessionNotice.Kind.CHAIRMAN_ELECTED -> "[의장] ${name}이(가) 최고평의회 의장으로 선출됐습니다."
            org.logh7.engine.SuccessionNotice.Kind.CHAIRMAN_DISMISSED -> "[의장] 불신임으로 ${name}이(가) 의장직에서 해임됐습니다."
        }).take(65)
        val packet=ChatMessages.spot(ChatMessages.Spot(event.character,line),event.seconds.coerceIn(0,0xffffffffL))
        members.values.filter {it.player.generation==event.generation}.forEach {send(it,listOf(packet))}
    }
    /**
     * Operator notice (maintenance flow, checklist §14): a "[운영]" spot chat line to every online member, sent as
     * the recipient's own line like the other server notices (the client has no system-message opcode in use).
     */
    @Synchronized fun operatorNotice(text:String,seconds:Long):Int {
        val line=("[운영] "+text.trim()).take(65)
        val recipients=members.values.toList()
        for(member in recipients) send(member,listOf(ChatMessages.spot(ChatMessages.Spot(member.character.id.toInt(),line),seconds.coerceIn(0,0xffffffffL))))
        return recipients.size
    }
    /** Black-hole tidal loss (design-blackhole-tactical-terrain.md §2.4): a spot chat line to each online member of the outfit. */
    @Synchronized fun tides(event:org.logh7.engine.TidalLoss) {
        for(character in event.characters) {
            val line=(if(event.pulled)"[중력] 워프 오차로 블랙홀 중력권에 끌려 들어가 함정 ${event.boats}척을 잃었습니다."
                else "[중력] 블랙홀 중력으로 부대 함정 ${event.boats}척을 잃었습니다.").take(65)
            toCharacter(character,event.generation,listOf(ChatMessages.spot(ChatMessages.Spot(character,line),event.seconds.coerceIn(0,0xffffffffL))))
        }
    }
    /** Durable local duties update existing public conditions and authorized stock/party views. */
    @Synchronized fun civic(event:org.logh7.engine.CivicChange,world:WorldBootstrap) {
        for(viewer in members.values.filter {it.player.generation==event.generation}) {
            val visible=event.bases.filter {base->world.galaxy.bases.single {it.id==base}.grid==viewer.player.grid}
            if(visible.isEmpty())continue
            val packets=visible.chunked(4).map {world.conditions(it,event.stocks)}.toMutableList()
            for(base in visible.filter {it==viewer.player.baseId && event.stocks.bases.getValue(it).let {b->viewer.player.country?.let(b::belongsTo)==true && b.camp==0}})
                packets+=world.warehouse(base,event.stocks)
            for(outfit in event.stocks.outfits.values.filter {it.baseId in visible && viewer.character.id.toInt() in it.members() &&
                it.country==viewer.player.country}) {
                val characters=members.values.associateBy {it.character.id.toInt()}
                // Offline member names require the ordinary query path; don't synthesize them.
                if(outfit.members().all {it in characters})packets+=world.outfitParty(
                    org.logh7.protocol.OutfitPartyMessages.Query(outfit.id,0,0),checkNotNull(viewer.player.country).value,event.stocks,
                    characterById={characters[it]?.character})
            }
            send(viewer,packets)
        }
    }
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
        val countries=world.lobbyCountryDefinitions().first
        val occupationLine=if(stocks.bases[event.base]?.let {it.country==event.country && it.camp==event.camp}==true) {
            val base=groundNoticeBase(world.galaxy,event.base)
            val nation=groundNoticeCountry(countries.countries[event.country.value]?.name,event.country)
            "[점령] $base · $nation"+(if(event.camp==1)" 반란군" else "")
        } else null
        // Office cards belong to the actual country of the current snapshot, never the display byte (2/3 shared by
        // added countries); no membership or a removed country holds no national office.
        fun cards(player:Navigator)=player.country?.takeIf {it.value in countries.countries}
            ?.let {offices(player.characterId,it.value,event.generation)}.orEmpty()
        // Snapshot the audience before updating membership; room departures mutate visibility.
        val audience=members.values.filter {it.player.generation==event.generation &&
            event.recipients[it.character.id.toInt()]==it.connection}.toList()
        for(previous in audience) {
            val player=snapshot.navigators.values.singleOrNull {it.characterId==previous.character.id.toInt() &&
                it.generation==event.generation} ?: continue
            val cards=cards(player)
            val member=previous.copy(player=player,offices=cards)
            val updated=update(member,event.seconds)
            val packets=mutableListOf(world.conditions(listOf(event.base),stocks),world.places(listOf(event.base),stocks))
            if(cards!=previous.offices || player.returnBase!=previous.player.returnBase || player.baseId!=previous.player.baseId) {
                packets+=BootstrapMessages.changedCharacter(member.character,player.pcp,player.mcp,player.spotId,
                    player.spotOwner,player.returnBase,cards,politicianCardForWire(member.character,player))
            }
            val expelled=previous.player.baseId==event.base && player.grid==event.grid &&
                player.cpPaused && player.baseId==0 && event.expelled.values.any {it.characterId==player.characterId}
            if(occupationLine!=null && !expelled && previous.player.dockTarget==event.base && player.dockTarget==null &&
                player.grid==previous.player.grid && player.baseId==previous.player.baseId) {
                packets+=org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,player.baseId,player.mode,
                    event.seconds,player.spotId,if(player.spotId==0)player.characterId else player.spotOwner)
                packets+=ChatMessages.spot(ChatMessages.Spot(player.characterId,"[기항] 거점 점령·취소"),event.seconds)
            }
            if(expelled) {
                packets+=unit(member)
                packets+=org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,0,5,event.seconds)
                packets+=org.logh7.protocol.TacticsMessages.notify(player.grid,player.cpPaused)
            } else if(occupationLine!=null && player.grid==event.grid &&
                previous.player.grid==event.grid && previous.player.cpPaused && player.cpPaused && player.baseId==0) {
                val tactical=TacticalBootstrap(snapshot,world.galaxy,player,countries,characterById)
                val display=countries.nativePower(org.logh7.engine.Allegiance.Country(event.country))
                packets+=org.logh7.protocol.TacticalGroundMessages.occupationFinished(event.base,display,event.camp)
                // Imported definitions and states retain real zero-boat identities; no fake weapon hit.
                packets+=event.expelled.mapNotNull {(account,displaced)->
                    val current=snapshot.navigators[account]?.takeIf {it.characterId==displaced.characterId &&
                        it.generation==event.generation && it.grid==event.grid && it.cpPaused && it.baseId==0}
                        ?: return@mapNotNull null
                    characterById(current.characterId)?.let {c->BootstrapMessages.character(
                        c,current.pcp,current.mcp,current.spotId,current.spotOwner,current.returnBase,cards(current),
                        politicianCardForWire(c,current))}
                }
                packets+=org.logh7.protocol.TacticsMessages.definitions(event.grid,tactical.units,tactical.groundDefinitions)
                packets+=org.logh7.protocol.TacticsMessages.corps(tactical.corps)
                packets+=org.logh7.protocol.TacticsMessages.states(tactical.units)
                packets+=org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation)
            }
            if(previous.player.cpPaused && !player.cpPaused && !expelled)
                packets+=org.logh7.protocol.TacticsMessages.notify(player.grid,false)
            GridForceReport.message(snapshot,world.galaxy,player,event.seconds,countries)?.let {packets+=it}
            if(occupationLine!=null && player.grid==event.grid) {
                val notice=ChatMessages.spot(ChatMessages.Spot(player.characterId,occupationLine),event.seconds.coerceIn(0,0xffffffffL))
                if(expelled || previous.player.cpPaused!=player.cpPaused)
                    queueGroundResult(member,event.base,notice)
                else packets+=notice
            }
            send(member,packets+updated.refresh)
        }
    }
    private fun groundNoticeBase(galaxy:org.logh7.engine.Galaxy,base:Int)=KoreanDisplayNames.base(galaxy.bases.single {it.id==base}.name)
        .filterNot {it.isSurrogate() || it.isISOControl()}.take(10).ifBlank {"거점 $base"}

    private fun groundNoticeCountry(name:String?,country:org.logh7.engine.CountryId)=name?.filterNot {it.isSurrogate() || it.isISOControl()}
        ?.take(10)?.takeIf {it.isNotBlank()} ?: "국가 ${country.value}"

    private data class GroundResultNotice(val generation:Long,val grid:Int,val tactical:Boolean,val base:Int,val packet:ByteArray,
        var gridReady:Boolean=false)
    private val groundResultNotices=mutableMapOf<String,ArrayDeque<GroundResultNotice>>()

    private fun queueGroundResult(member:Member,base:Int,packet:ByteArray) {
        val player=member.player
        val notices=groundResultNotices.getOrPut(member.connection){ArrayDeque()}
        // The captured base is the final result of this same fight, so it replaces a queued victory line.
        notices.removeAll {it.generation==player.generation && it.grid==player.grid && it.base==base}
        notices.addLast(GroundResultNotice(player.generation,player.grid,player.cpPaused,base,packet))
    }

    @Synchronized fun groundResultGridReady(connection:String,player:Navigator) {
        groundResultNotices[connection]?.forEach {
            if(it.generation==player.generation && it.grid==player.grid && it.tactical==player.cpPaused)it.gridReady=true
        }
    }

    @Synchronized fun takeGroundResult(connection:String,player:Navigator):ByteArray? {
        val notices=groundResultNotices[connection] ?: return null
        // A reconnect or a new battle must not display an old result.
        notices.removeAll {it.generation!=player.generation || it.grid!=player.grid || it.tactical!=player.cpPaused}
        val next=notices.firstOrNull()?.takeIf {it.gridReady}?.let {notices.removeFirst()}
        if(notices.isEmpty())groundResultNotices.remove(connection)
        return next?.packet
    }

    @Synchronized fun groundBattle(event:org.logh7.engine.GroundBattleResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,countryName:(org.logh7.engine.CountryId)->String?={null},
        characterById:(Int)->CharacterMessages.Generate?) {
        members.values.filter {it.player.generation==event.generation && it.player.grid==event.grid && it.player.cpPaused && it.player.baseId==0 &&
            event.recipients[it.character.id.toInt()]==it.connection}.forEach {viewer->
            val current=snapshot.navigators.values.singleOrNull {
                it.characterId==viewer.player.characterId && it.generation==event.generation && it.grid==event.grid
            } ?: return@forEach
            val packets=mutableListOf<ByteArray>()
            if(current.cpPaused && current.baseId==0) {
                val tactical=TacticalBootstrap(snapshot,galaxy,current,characterById)
                val base=tactical.baseInformation.single {it.position.id==event.base}
                packets+=listOf(org.logh7.protocol.TacticsMessages.definitions(event.grid,tactical.units,tactical.groundDefinitions),
                    org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation),
                    org.logh7.protocol.TacticalGroundMessages.baseDefenseChanged(event.seconds,event.base,base.stamina))
            }
            event.conclusion?.let {result->
                val name=groundNoticeBase(galaxy,event.base)
                val winner=result.survivingCountry
                val outcome=when {
                    result.withdrawal->"철수로 종료"
                    winner==null->"생존 병력 없음"
                    winner==current.country && result.insurgent==org.logh7.engine.CoupActions.rebel(checkNotNull(snapshot.planetStocks),current.characterId,current.country)->"아군 승리"
                    else->groundNoticeCountry(countryName(winner),winner)+(if(result.insurgent)" 반란군" else "")+" 승리"
                }
                val line="[지상전] $name · $outcome"
                val notice=ChatMessages.spot(ChatMessages.Spot(viewer.character.id.toInt(),line),event.seconds.coerceIn(0,0xffffffffL))
                if(!current.cpPaused)queueGroundResult(viewer.copy(player=current),event.base,notice)
                else if(current.baseId==0)packets+=notice
            }
            if(packets.isNotEmpty())send(viewer,packets,event.grid)
        }
    }
    /** U-31: an orbital strike changes a garrison outside any ground battle; refresh every tactical viewer of the grid. */
    @Synchronized fun groundRefresh(generation:Long,grid:Int,base:Int,seconds:Long,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        members.values.filter {it.player.generation==generation && it.player.grid==grid && it.player.cpPaused && it.player.baseId==0}.forEach {viewer->
            val current=tacticalViewer(snapshot,viewer,grid) ?: return@forEach
            val tactical=TacticalBootstrap(snapshot,galaxy,current,characterById)
            val stamina=tactical.baseInformation.singleOrNull {it.position.id==base}?.stamina ?: return@forEach
            send(viewer,listOf(org.logh7.protocol.TacticsMessages.definitions(grid,tactical.units,tactical.groundDefinitions),
                org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation),
                org.logh7.protocol.TacticalGroundMessages.baseDefenseChanged(seconds,base,stamina)),grid)
        }
    }
    @Synchronized fun ground(event:org.logh7.engine.TacticalGroundResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        val order=event.order
        val deploy=order.action==org.logh7.engine.TacticalGroundAction.DEPLOY
        members.values.filter {it.player.generation==event.generation && it.player.grid==order.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}.forEach {viewer ->
            val current=tacticalViewer(snapshot,viewer,order.grid) ?: return@forEach
            val packet=if(event.cancelled)listOf(org.logh7.protocol.TacticalGroundMessages.reply(
                org.logh7.protocol.TacticalGroundMessages.Command(
                    if(deploy)org.logh7.protocol.TacticalGroundMessages.Action.DEPLOY
                    else org.logh7.protocol.TacticalGroundMessages.Action.WITHDRAW,order.character,listOf(order.carrier)),event.seconds,0,false))
            else {
                val tactical=TacticalBootstrap(snapshot,galaxy,current,characterById)
                listOf(org.logh7.protocol.TacticsMessages.definitions(order.grid,tactical.units,tactical.groundDefinitions),
                    org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation),
                    org.logh7.protocol.TacticalGroundMessages.baseDefenseChanged(event.seconds,order.base,
                        tactical.baseInformation.single {it.position.id==order.base}.stamina))+
                    tactical.groundDefinitions.filter {troop->troop.id in event.troops &&
                        if(deploy)troop.base==order.base else troop.carrier==order.carrier
                    }.map {troop->org.logh7.protocol.TacticalGroundMessages.movedTroop(event.seconds,
                        !deploy,order.carrier,troop.id,order.base)}
            }
            val notice=if(viewer.character.id.toInt()==order.character)listOf(org.logh7.protocol.ChatMessages.spot(
                org.logh7.protocol.ChatMessages.Spot(order.character,TacticalGroundNotices.finished(deploy,event.cancelled,event.troops.size)),
                event.seconds)) else emptyList()
            send(viewer,packet+notice,order.grid)
        }
    }
    @Synchronized fun posture(event:org.logh7.engine.TacticalPostureResult,snapshot:org.logh7.engine.WorldSnapshot) {
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
                val enteredBase=!event.cancelled && event.mode==org.logh7.engine.TacticalShipMode.STATIONED &&
                    event.character==viewer.character.id.toInt() && snapshot.navigators.values.any {
                        it.characterId==event.character && it.generation==event.generation && it.grid==event.grid &&
                            it.baseId==event.base && it.unitId in event.units &&
                            it.posture?.let {posture->posture.mode==event.mode && posture.base==event.base}==true
                    }
                if(!enteredBase && tacticalViewer(snapshot,viewer,event.grid)==null)return@forEach
                val frozen=if(event.cancelled)emptyList() else event.units.map {(id,pose)->
                    TacticalMotionMessages.npc(id,org.logh7.engine.TacticalMotion(event.grid,event.seconds,pose,0f,pose.direction,
                        listOf(pose.position)),event.seconds,event.character)}
                send(viewer,frozen+packet,event.grid.takeUnless {enteredBase})
            }
    }
    /** 0421 mission kinds in the CD's submenu order (constmsg group 118 notices 14..19). */
    private val missionNames=listOf("유격","방위","점령","요격","정찰","철수")
    @Synchronized fun tacticalCommand(event:org.logh7.engine.TacticalCommandResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        members.values.filter {it.player.generation==event.generation && it.player.grid==event.order.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}.forEach {viewer->
            val current=snapshot.navigators.values.singleOrNull {
                it.characterId==viewer.player.characterId && it.generation==event.generation && it.grid==event.order.grid &&
                    it.baseId==viewer.player.baseId && (it.cpPaused || it.baseId>0)
            } ?: return@forEach
            val tactical=TacticalBootstrap(snapshot,galaxy,current,characterById)
            val packets=mutableListOf<ByteArray>()
            if(event.cancelled)TacticalCommandWire.command(event.order)?.let {
                packets+=org.logh7.protocol.TacticalCommandMessages.reply(it,event.seconds,0,false)
            }
            // A failed shoot ACK leaves the CD's charge counters running. Publish the
            // committed stop to every current observer, including automatic cancellation.
            if((event.cancelled && event.order.command.opcode==0x419) ||
                (!event.cancelled && !event.stateOnly && event.order.command.opcode==0x41e)) {
                packets+=org.logh7.protocol.TacticalBaseMessages.stopped(event.seconds,event.order.character,event.order.command.base)
            }
            if(!event.cancelled && !event.stateOnly && event.order.command.opcode in setOf(0x408,0x421)) {
                val proposed=snapshot.planetStocks!!.tacticalCommands.proposals[event.order.character]?.let {
                    it.assignedAt==event.seconds && it.mission==event.order.command.kind}==true
                val assigned=event.order.command.opcode==0x421 && current.characterId!=event.order.character &&
                    snapshot.planetStocks!!.tacticalCommands.missions[current.characterId]?.let {
                        it.assignedAt==event.seconds && it.mission==event.order.command.kind}==true
                if(assigned) TacticalCommandWire.command(event.order)?.let {
                    // CD 004ba2b0 applies a 0421 result only when its character is the receiver.
                    // A supreme-commander mission (update04) is addressed to every recipient.
                    val payload=it.payload.toMutableList()
                    java.nio.ByteBuffer.allocate(4).putInt(current.characterId).array().forEachIndexed {i,b->payload[8+i]=b.toInt() and 255}
                    packets+=org.logh7.protocol.TacticalCommandMessages.reply(it.copy(character=current.characterId,payload=payload),event.seconds,0)
                    // The CD shows no window for that result, so the subordinate also gets a same-side tactical chat line.
                    missionNames.getOrNull(event.order.command.kind)?.let {name->
                        packets+=ChatMessages.grid(ChatMessages.Grid(event.order.character,1,"[임무] $name 임무를 지시합니다."),event.seconds)}
                } else if(!proposed || tactical.corps.any {it.character==current.characterId && it.chief}) {
                    TacticalCommandWire.command(event.order)?.let {
                        packets+=org.logh7.protocol.TacticalCommandMessages.reply(it,event.seconds,0)}
                    // The CD ignores a 0421 result for another character, so the chief reads a 具申 as chat.
                    if(proposed && current.characterId!=event.order.character)missionNames.getOrNull(event.order.command.kind)?.let {name->
                        packets+=ChatMessages.grid(ChatMessages.Grid(event.order.character,1,"[구신] $name 임무를 구신합니다."),event.seconds)}
                }
            }
            if(!event.cancelled && event.order.command.opcode==0x419) {
                tactical.bases.singleOrNull {it.id==event.order.command.base}?.let {b->
                    packets+=org.logh7.protocol.TacticalCommandMessages.fortressMoved(event.seconds,b.id,
                        org.logh7.protocol.TacticalCommandMessages.Point(b.x,b.y,b.z))}
                packets+=org.logh7.protocol.TacticalBaseMessages.fired(event.seconds,event.order.character,
                    event.order.command.base,event.order.command.direction)
                // The CD's 0436 loop overwrites its own index while rendering a kill.
                // One actual destroyed target per packet also avoids that native skip.
                packets+=event.destroyedUnits.map {id->org.logh7.protocol.TacticalBaseMessages.destroyed(
                    event.seconds,event.order.command.base,listOf(id))}
            }
            if(!event.cancelled && event.order.command.opcode in setOf(0x41b,0x41c)) {
                val command=event.order.command
                val available=checkNotNull(snapshot.planetStocks).bases.getValue(command.base).supplies
                for(id in command.units) {
                    val target=tactical.units.singleOrNull {it.id==id} ?: continue
                    packets+=if(command.opcode==0x41b)org.logh7.protocol.TacticalBaseMessages.repaired(
                        command.base,id,available,target.damaged)
                    else org.logh7.protocol.TacticalBaseMessages.supplied(command.base,id,available,target.supplies)
                }
            }
            if(!event.cancelled && event.order.command.opcode in setOf(0x422,0x41c)) {
                val stocks=checkNotNull(snapshot.planetStocks)
                val base=event.order.command.base
                val supply=tactical.emergencySupply(base)
                if(supply!=null) {
                    val targets=if(event.order.command.opcode==0x422)listOf(0) else event.order.command.units
                    for(id in targets) {
                        val balance=if(id==0)0 else snapshot.navigators.values.singleOrNull {it.unitId==id}?.supplies ?:
                            stocks.outfits.values.singleOrNull {o->o.activeShips?.values?.any {it.id==id}==true}
                                ?.let {org.logh7.engine.OutfitSupplies.distribution(it)[id]} ?: 0
                        packets+=org.logh7.protocol.TacticalBaseMessages.emergency(base,id,supply.supplies,balance)
                    }
                }
            }
            // A finished supreme-commander mission is told only to its holder, as a tactical chat line.
            event.finishedMissions[current.characterId]?.let {(kind,reward)->missionNames.getOrNull(kind)?.let {name->
                packets+=ChatMessages.grid(ChatMessages.Grid(current.characterId,1,
                    "[임무] $name 임무 "+(if(reward>0)"완료(공적 +$reward)." else "종료.")),event.seconds)}}
            for((id,merit) in event.merits) {
                val person=snapshot.navigators.values.singleOrNull {it.characterId==id} ?: continue
                val character=characterById(id)?.let {WorldCharacters.view(it,snapshot)}?.copy(merit=merit) ?: continue
                val cards=members.values.singleOrNull {it.character.id==id.toLong()}?.offices.orEmpty()
                packets+=BootstrapMessages.changedCharacter(character,person.pcp,person.mcp,person.spotId,
                    person.spotOwner,person.returnBase,cards,politicianCardForWire(character,person))
            }
            // Completion publishes committed definitions/state, never an unconditional success ACK.
            if(!event.cancelled)for((id,value) in event.morale) {
                packets+=org.logh7.protocol.TacticalStatusMessages.morale(event.seconds,id,value)
                packets+=org.logh7.protocol.TacticalStatusMessages.confusion(event.seconds,id,value<=20)
            }
            packets+=org.logh7.protocol.TacticsMessages.definitions(event.order.grid,tactical.units,tactical.groundDefinitions)
            packets+=org.logh7.protocol.TacticsMessages.states(tactical.units)
            packets+=org.logh7.protocol.TacticsMessages.corps(tactical.corps)
            packets+=org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation)
            packets+=org.logh7.protocol.TacticsMessages.basePositions(tactical.bases)
            if(event.order.command.base>0 && event.order.command.opcode in setOf(0x41e,0x41f)) {
                tactical.bases.singleOrNull {it.id==event.order.command.base}?.let {b->
                    packets+=org.logh7.protocol.TacticalCommandMessages.fortressMoved(event.seconds,b.id,
                        org.logh7.protocol.TacticalCommandMessages.Point(b.x,b.y,b.z))}
            }
            packets+=org.logh7.protocol.TacticsMessages.positions(tactical.units)
            val motions=if(event.stateOnly && event.order.command.opcode==0x40e)
                event.motions.filter {(id,motion)->tactical.committedMotion(id)==motion}
            else event.motions
            packets+=motions.map {(id,motion)->TacticalMotionMessages.npc(id,motion,event.seconds,event.order.character)}
            send(viewer,packets,event.order.grid,current.baseId)
        }
    }
    @Synchronized fun support(event:org.logh7.engine.TacticalSupportResult,snapshot:org.logh7.engine.WorldSnapshot,
        galaxy:org.logh7.engine.Galaxy,characterById:(Int)->CharacterMessages.Generate?) {
        val packet=if(event.cancelled)org.logh7.protocol.TacticalStopMessages.supportCancelled(
            event.character,event.unit,snapshot.session?.gameSeconds ?: 0)
            else if(event.action==org.logh7.engine.TacticalSupportAction.REPAIR)
            org.logh7.protocol.TacticalSupportMessages.repaired(event.unit,event.target,event.supplies,event.damaged)
            else org.logh7.protocol.TacticalSupportMessages.supplied(event.unit,event.target,event.supplies,event.targetSupplies)
        members.values.filter {it.player.generation==event.generation && it.player.grid==event.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}
            .forEach { viewer ->
                val current=tacticalViewer(snapshot,viewer,event.grid) ?: return@forEach
                // 0325 replaces the complete native definition cache, including unrelated combatants.
                val tactical=TacticalBootstrap(snapshot,galaxy,current,characterById)
                send(viewer,if(event.cancelled)listOf(packet) else listOf(packet,org.logh7.protocol.TacticsMessages.definitions(event.grid,tactical.units,tactical.groundDefinitions)),event.grid)
            }
    }
    @Synchronized fun strategicMovement(event:org.logh7.engine.NpcStrategicMovement,
        snapshot:org.logh7.engine.WorldSnapshot,world:WorldBootstrap,characterById:(Int)->CharacterMessages.Generate?) {
        val audience=members.values.filter {it.player.generation==event.generation &&
            event.recipients[it.character.id.toInt()]==it.connection}.toList()
        for(previous in audience) {
            val player=snapshot.navigators.values.singleOrNull {it.generation==event.generation &&
                it.characterId==previous.character.id.toInt()} ?: continue
            val pendingEnd=previous.player.grid==player.grid && !player.cpPaused &&
                (previous.player.cpPaused || previous.pendingTacticalEnd && previous.player==player)
            val member=previous.copy(player=player,pendingTacticalEnd=pendingEnd)
            val updated=update(member,event.seconds)
            val packets=mutableListOf<ByteArray>()
            GridForceReport.message(snapshot,world.galaxy,player,event.seconds)?.let {packets+=it}
            if(player.grid==event.source && player.baseId==0 && player.cpPaused &&
                previous.player.grid==event.source && previous.player.cpPaused)event.units.chunked(255).forEach {
                packets+=org.logh7.protocol.TacticalRetreatMessages.warped(event.seconds,event.destination,it)
            }
            if(player.grid==event.destination && player.cpPaused) {
                // Definitions/states replace caches but do not import a new native model.
                // Re-enter through 0f02 to rebuild the complete committed battlefield.
                packets+=org.logh7.protocol.TacticsMessages.notify(player.grid,true)
            }
            send(member,packets+updated.refresh)
        }
    }

    private fun tacticalViewer(snapshot:org.logh7.engine.WorldSnapshot,viewer:Member,grid:Int):Navigator? =
        snapshot.navigators.values.singleOrNull {
            it.characterId==viewer.character.id.toInt() && it.generation==viewer.player.generation &&
                it.grid==grid && it.baseId==0 && it.cpPaused
        }

    /** The current 033b table carries the effective commander for existing ship identities. */
    internal fun refreshTacticalOffice(generation:Long,outfitId:Int,world:WorldBootstrap,
        latest:()->org.logh7.engine.WorldSnapshot?,characterById:(Int)->CharacterMessages.Generate?) =
        refreshTacticalOffices(generation, setOf(outfitId), world, latest, characterById)

    @Synchronized internal fun refreshTacticalOffices(generation: Long, outfitIds: Set<Int>, world: WorldBootstrap,
        latest: () -> org.logh7.engine.WorldSnapshot?, characterById: (Int) -> CharacterMessages.Generate?) {
        val snapshot=latest() ?: return
        val stocks=snapshot.planetStocks?.takeIf {it.generation==generation} ?: return
        val grids = outfitIds.mapNotNull { id ->
            stocks.outfits[id]?.positionIn(world.galaxy)?.takeIf { it.baseId == 0 }?.grid
        }.toSet()
        if (grids.isEmpty()) return
        val countries=world.lobbyCountryDefinitions().first
        members.values.filter {it.player.generation==generation && it.player.grid in grids && it.player.cpPaused}
            .forEach {viewer->
                val grid = viewer.player.grid
                val player=tacticalViewer(snapshot,viewer,grid) ?: return@forEach
                val tactical=TacticalBootstrap(snapshot,world.galaxy,player,countries,characterById)
                send(viewer,listOf(org.logh7.protocol.TacticsMessages.states(tactical.units)),grid)
            }
    }

    @Synchronized fun movement(event:org.logh7.engine.TacticalMovement,
        snapshot:org.logh7.engine.WorldSnapshot?=null) {
        // A committed audience binding can still be live after its viewer changes battlefield.
        members.values.filter {it.player.generation==event.generation && it.player.grid==event.grid &&
            event.recipients[it.character.id.toInt()]==it.connection}
            .forEach { viewer ->
                if(snapshot!=null && tacticalViewer(snapshot,viewer,event.grid)==null) {
                    // The last hostile warp can end tactics in the same committed tick.
                    val finalWarp=event.destination!=null && viewer.player.cpPaused && snapshot.navigators.values.any {
                        it.characterId==viewer.character.id.toInt() && it.generation==event.generation &&
                            it.grid==event.grid && it.baseId==0 && !it.cpPaused && it.cpRecoveryAt==event.seconds &&
                            (it.hull?.woundedUntil ?: 0)<=event.seconds
                    }
                    if(!finalWarp)return@forEach
                }
                if(event.formation.isNotEmpty()) {
                    send(viewer,TacticalMotionMessages.formation(event.formation,event.seconds,event.character),event.grid)
                    return@forEach
                }
                val packet=if(event.reverseTarget!=null)org.logh7.protocol.TacticalTurnMessages.turned(event.seconds,event.unit,checkNotNull(event.reverseTarget))
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
                // The final warp can share a tick with the transition out of tactics.
                send(viewer,(if(event.stopped)listOf(org.logh7.protocol.TacticalStopMessages.reply(
                    org.logh7.protocol.TacticalStopMessages.Stop(event.character,listOf(event.unit)),event.seconds)) else emptyList())+packet,
                    event.grid.takeIf {event.destination==null})
            }
    }

    @Synchronized fun tacticalEnd(event:org.logh7.engine.TacticalEnd,snapshot:org.logh7.engine.WorldSnapshot) {
        val viewer=members[event.connection] ?: return
        if(viewer.character.id!=event.character.toLong() || viewer.player.generation!=event.generation ||
            viewer.player.grid!=event.grid)return
        val current=snapshot.navigators.values.singleOrNull {
            it.characterId==event.character && it.generation==event.generation
        } ?: return
        // A strategic movement report can already have published this same committed exit.
        if(current.grid!=event.grid || current.cpPaused ||
            (!viewer.player.cpPaused && (!viewer.pendingTacticalEnd || viewer.player!=current)))return
        if(viewer.pendingTacticalEnd)members[event.connection]=viewer.copy(pendingTacticalEnd=false)
        send(viewer,listOf(org.logh7.protocol.TacticsMessages.notify(event.grid,false)))
    }

    /** Every committed shot is queued separately, including while an exchange awaits another command.
     * Gateway supplies a current snapshot to reject stale exits after reentry. Without one, the legacy
     * event/presence contract cannot distinguish a later battle in the same grid. */
    @Synchronized fun combat(impact: org.logh7.engine.TacticalImpact,
        snapshot:org.logh7.engine.WorldSnapshot?=null,galaxy:org.logh7.engine.Galaxy?=null,
        characterById:((Int)->CharacterMessages.Generate?)?=null) {
        val npcOutfit=impact.npcOutfit
        val packet = if(impact.boarding)org.logh7.protocol.TacticalCommandMessages.fought(impact.hit.seconds,impact.source,impact.hit.target)
            else if(impact.airBattle)org.logh7.protocol.TacticalCommandMessages.airBattle(impact.hit.seconds,impact.source,impact.hit.target,
                impact.hit.damaged,impact.hit.destroyed,impact.hit.shieldFace,impact.hit.shieldDamage,
                snapshot?.planetStocks?.tacticalCommands?.morale?.get(impact.hit.target) ?: 100)
            else org.logh7.protocol.TacticalAttackMessages.hit(impact.hit.seconds,impact.source,
            impact.weapon.wire,impact.hit.target,impact.hit.destroyed,
            impact.hit.damaged,impact.hit.shieldFace,impact.hit.shieldDamage,sourceBase=impact.fortress,
                morale=snapshot?.planetStocks?.tacticalCommands?.morale?.get(impact.hit.target) ?: 100)
        members.values.filter { viewer -> viewer.player.generation == impact.generation &&
            impact.recipients[viewer.character.id.toInt()] == viewer.connection }.forEach { viewer ->
                val character=viewer.character.id.toInt()
                val current=snapshot?.navigators?.values?.singleOrNull {
                    it.characterId==character && it.generation==impact.generation}
                val departed=impact.departed?.takeIf {it.characterId==character}
                if (departed!=null) {
                    // Strategic movement can publish this same transaction's return before its impact.
                    // A newer return/hull or reentered battle must never receive the old exit.
                    val awaitingReturn=viewer.player.grid==impact.hit.grid && viewer.player.cpPaused
                    if(departed.generation!=impact.generation || departed.cpPaused ||
                        (snapshot!=null && current!=departed) ||
                        (viewer.player!=departed && !awaitingReturn))return@forEach
                    // 004c96c0 requires a living own flagship while rendering any tactical unit.
                    // Leave the victim's view before destroying that native anchor; bootstrap returns the replacement.
                    // U-24: the sinking also lowered ability experience/values; refresh the victim's status panel.
                    val changed=snapshot?.let {s->WorldCharacters.view(viewer.character,s)}?.let {c->
                        BootstrapMessages.changedCharacter(c,departed.pcp,departed.mcp,departed.spotId,departed.spotOwner,
                            departed.returnBase,viewer.offices,politicianCardForWire(c,departed))}
                    send(viewer,listOfNotNull(org.logh7.protocol.TacticsMessages.notify(impact.hit.grid,false),changed))
                } else {
                    val location=if(snapshot==null)viewer.player else current ?: return@forEach
                    if(viewer.player.grid!=impact.hit.grid || location.grid!=impact.hit.grid ||
                        (!impact.boarding && (location.baseId!=0 ||
                            !location.cpPaused && (location.hull?.woundedUntil ?: 0)>impact.hit.seconds)))return@forEach
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
                    val morale=snapshot?.planetStocks?.tacticalCommands?.morale?.get(impact.hit.target)
                    val status=if(morale==null)emptyList() else listOf(
                        org.logh7.protocol.TacticalStatusMessages.morale(impact.hit.seconds,impact.hit.target,morale),
                        org.logh7.protocol.TacticalStatusMessages.confusion(impact.hit.seconds,impact.hit.target,morale<=20))
                    // Boarding can hit a docked flagship, and the last impact can accompany battle exit.
                    send(viewer,listOf(packet)+status+leave+cargo,
                        impact.hit.grid.takeIf {!impact.boarding && location.cpPaused})
                    if(npcOutfit!=null && viewer.player.unitId==impact.hit.target && viewer.player.cpPaused &&
                        viewer.player.baseId==0)combatHail(viewer,npcOutfit,impact.weapon,impact.hit.seconds)
                }
            }
    }
    private fun combatHail(viewer:Member, outfit:Int, weapon:TacticalWeapon, seconds:Long) {
        val now=System.nanoTime()
        val last=combatHails[viewer.connection]
        if(last!=null && sameBattlefield(last.player,viewer.player) && now-last.at<120_000_000_000L)return
        val profile=npcProfiles.current(viewer.player.generation,outfit)
        val hail=CombatHail(viewer.player,outfit,profile?.revision,seconds,now)
        combatHails[viewer.connection]=hail
        val generator=npcDialogue
        if(generator==null)deliverCombatHail(viewer.connection,hail,null)
        else try {generator.request(weapon,profile?.dialogue ?: NpcFleetDialogue()) {line->deliverCombatHail(viewer.connection,hail,line)}}
        catch(_:Exception){deliverCombatHail(viewer.connection,hail,null)}
    }
    private fun sameBattlefield(before:Navigator, after:Navigator) = before.generation==after.generation &&
        before.characterId==after.characterId && before.grid==after.grid && before.baseId==after.baseId && before.cpPaused==after.cpPaused

    @Synchronized private fun deliverCombatHail(connection:String, hail:CombatHail, generated:String?) {
        val current=members[connection] ?: return
        if(combatHails[connection]!==hail || hail.delivered || !sameBattlefield(hail.player,current.player))return
        hail.delivered=true
        val profile=npcProfiles.current(current.player.generation,hail.outfit)
        val line=generated?.trim()?.takeIf { profile?.revision==hail.revision && NpcFleetDialogue.validLine(it) }
            ?: profile?.dialogue?.fallback?.takeIf {it.isNotEmpty()} ?: NpcFleetDialogue.DEFAULT_LINE
        send(current,listOf(ChatMessages.grid(ChatMessages.Grid(current.player.characterId,1,"[NPC 교신] $line"),hail.seconds)))
    }
    private fun send(viewer: Member, messages: List<ByteArray>, tacticalGrid: Int? = null, tacticalBase: Int = 0) {
        try {
            val sendTactical=viewer.sendTactical
            if(tacticalGrid!=null && sendTactical!=null)sendTactical(tacticalGrid,tacticalBase,messages) else viewer.send(messages)
            observeMotions(viewer.connection,messages)
        } catch (_: Exception) { /* A closed peer cannot fail another player's command. */ }
    }
    private fun visible(viewer: Member, other: Member) = viewer.connection != other.connection &&
        viewer.player.generation == other.player.generation && viewer.player.grid == other.player.grid &&
        (viewer.player.country!=null && viewer.player.country == other.player.country ||
            (viewer.player.baseId != 0 && viewer.player.baseId == other.player.baseId && viewer.player.spotId != 0 &&
                viewer.player.spotId == other.player.spotId && viewer.player.spotOwner == other.player.spotOwner))
    private fun peers(viewer: Member) = members.values.filter { visible(viewer, it) }.sortedBy { it.character.id }
    /** Public profiles follow the same live visibility boundary as the strategy character cache. */
    /** Delivers to every live connection of one character (e.g. a 会談/談話 invitation). */
    @Synchronized fun toCharacter(characterId: Int, generation: Long, messages: List<ByteArray>): Boolean {
        val targets = members.values.filter { it.character.id == characterId.toLong() && it.player.generation == generation }
        targets.forEach { send(it, messages) };return targets.isNotEmpty()
    }
    @Synchronized fun detail(connection: String, id: Int): Member? {
        val viewer = members[connection] ?: return null
        return members.values.singleOrNull { it.character.id == id.toLong() &&
            it.player.generation == viewer.player.generation && (it.connection == connection || visible(viewer, it)) }
    }
    @Synchronized fun spotPortrait(connection:String,id:Int): CharacterMessages.Generate? {
        val viewer=members[connection] ?: return null
        val room=viewer.player
        if(room.baseId==0 || room.spotId==0)return null
        return members.values.singleOrNull { member ->
            val peer=member.player
            member.connection!=connection && member.character.id==id.toLong() && !member.character.generated &&
                room.generation==peer.generation && room.grid==peer.grid && room.baseId==peer.baseId &&
                room.spotId==peer.spotId && room.spotOwner==peer.spotOwner
        }?.character
    }
    /** Spot zero means one's own ship, not a public room shared by every undocked player. */
    @Synchronized fun spotChat(connection: String, message: ChatMessages.Spot, seconds: Long): ByteArray {
        val sender = checkNotNull(members[connection]) { "Chat requires published world membership" }
        require(message.characterId.toLong() == sender.character.id)
        ChatChannel.parse(message.text)?.let {(channel,text) -> return channelChat(sender,channel,text,seconds)}
        val packet = ChatMessages.spot(message, seconds)
        val location = sender.player
        if (location.baseId != 0 && location.spotId != 0) members.values.filter {
            it.connection != connection && it.player.generation == location.generation &&
                it.player.grid == location.grid && it.player.baseId == location.baseId &&
                it.player.spotId == location.spotId && it.player.spotOwner == location.spotOwner
        }.forEach { send(it, listOf(packet)) }
        return packet // The sender's echo uses its own exchange/cipher queue exactly once.
    }
    /** U-06: same-side channel members anywhere in the session; the sender must belong to the channel. */
    private fun channelChat(sender:Member,channel:ChatChannel,text:String,seconds:Long):ByteArray {
        fun cards(member:Member)=member.offices.map {it.kind}
        val allowed=sender.player.country!=null && channel.admits(cards(sender),sender.outfit)
        val line=("[${channel.label}] "+(if(!allowed)"이 채널을 쓸 수 있는 직무·소속이 아닙니다." else text)).take(65)
        val packet=ChatMessages.spot(ChatMessages.Spot(sender.character.id.toInt(),line),seconds)
        if(allowed && text.isNotBlank())members.values.filter {
            it.connection!=sender.connection && it.player.generation==sender.player.generation &&
                it.player.country==sender.player.country && channel.admits(cards(it),it.outfit) &&
                (channel!=ChatChannel.OWN_OUTFIT || it.outfit==sender.outfit)
        }.forEach {send(it,listOf(packet))}
        return packet
    }
    @Synchronized fun gridChat(connection: String, message: ChatMessages.Grid, seconds: Long): ByteArray {
        val sender = checkNotNull(members[connection]) { "Chat requires published world membership" }
        require(message.characterId.toLong() == sender.character.id)
        val packet = ChatMessages.grid(message, seconds)
        // Manual W p.25: tactical chat reaches only the same battle's participants, and a battle is not heard outside.
        fun inTactics(member: Member) = member.player.cpPaused && member.player.baseId == 0
        members.values.filter { other ->
            other.connection != connection && other.player.generation == sender.player.generation &&
                other.player.grid == sender.player.grid && inTactics(other) == inTactics(sender) && when (message.cast) {
                    0 -> true
                    1 -> sender.player.country!=null && other.player.country == sender.player.country
                    2 -> sender.player.country!=null && sender.outfit > 0 && other.outfit == sender.outfit && other.player.country == sender.player.country
                    else -> false
                }
        }.forEach { send(it, listOf(packet)) }
        return packet
    }
    /** U-17 侵入: a forged identity (alias, side) by character id, shown to everyone else. */
    @Volatile var disguise: (Int) -> Pair<String, Int>? = { null }
    private fun shown(member: Member): CharacterMessages.Generate = disguise(member.character.id.toInt())?.let { (alias, power) ->
        member.character.copy(surname = alias, givenName = "", power = power,
            shipKind = org.logh7.engine.Flagships.forType(power, member.character.shipType).kind) } ?: member.character
    fun character(member: Member) = shown(member).let { c -> BootstrapMessages.character(c, member.player.pcp,
        member.player.mcp, member.player.spotId, member.player.spotOwner, member.player.returnBase,
        if (c === member.character) member.offices else emptyList(),
        if (c === member.character) politicianCardForWire(c,member.player) else null) }
    fun unit(member: Member) = BootstrapMessages.unit(shown(member).let { c -> if (c === member.character) c.copy(shipKind=member.player.hull?.kind ?: c.shipKind) else c },member.player.grid,member.player.cruise,
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
        val flagshipChanged=old.hull?.kind!=next.hull?.kind || (old.hull?.hp?.let {it<100}==true)!=(next.hull?.hp?.let {it<100}==true) ||
            old.supplies!=next.supplies
        if (!changedBase && old.grid == next.grid && old.cruise == next.cruise && previous.outfit == member.outfit && !flagshipChanged) return
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
            previous.player.characterId == member.player.characterId && previous.player.country == member.player.country &&
            previous.player.grid == member.player.grid &&
            members.values.filter { it.connection != member.connection }.all { visible(previous, it) == visible(member, it) }) {
            members[member.connection] = member
            if(!sameBattlefield(previous.player,member.player))combatHails.remove(member.connection)
            val before = character(previous)
            val after = character(member)
            if (!before.contentEquals(after)) {
                val shownCharacter = shown(member)
                val changed = BootstrapMessages.changedCharacter(shownCharacter, member.player.pcp,
                    member.player.mcp, member.player.spotId, member.player.spotOwner, member.player.returnBase,
                    if (shownCharacter === member.character) member.offices else emptyList(),
                    if (shownCharacter === member.character) politicianCardForWire(shownCharacter,member.player) else null)
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
        if(previous!=null && !sameBattlefield(previous.player,member.player))combatHails.remove(member.connection)
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
    /**
     * A flagship sunk inside a breached fortress (FortressArmor): the docked owner gets no combat impact, so move the
     * client to its replacement like a combat departure. Leave a tactical view first; a stale (already newer) state is ignored.
     */
    @Synchronized fun fortressSunk(departed:org.logh7.engine.Navigator,character:CharacterMessages.Generate,
        offices:List<BootstrapMessages.OfficeCard>,seconds:Long) {
        val previous=members.values.singleOrNull {it.character.id.toInt()==departed.characterId &&
            it.player.generation==departed.generation} ?: return
        val member=previous.copy(character=character,player=departed,offices=offices,pendingTacticalEnd=false)
        val updated=update(member,seconds)
        // A cross-grid sinking also needs the native scene transition when the owner was docked in strategy.
        val changesGrid = previous.player.grid != departed.grid
        val exit = if (changesGrid || previous.player.cpPaused || previous.pendingTacticalEnd && previous.player == departed)
            listOf(org.logh7.protocol.TacticsMessages.notify(previous.player.grid,false)) else emptyList()
        send(member,exit+listOf(BootstrapMessages.changedCharacter(character,departed.pcp,departed.mcp,departed.spotId,
            departed.spotOwner,departed.returnBase,offices,
            politicianCardForWire(character,departed)),unit(member)))
        // During tactical exit the exchange suppresses strategy cache rebuilds, not the return itself.
        if(updated.refresh.isNotEmpty())send(member,updated.refresh)
    }
    @Synchronized fun refreshCharacter(character:CharacterMessages.Generate,player:Navigator,
        offices:List<BootstrapMessages.OfficeCard>,seconds:Long,outfit:Int=0) {
        val previous=members.values.singleOrNull {it.character.id==character.id && it.player.generation==player.generation} ?: return
        val member=previous.copy(character=character,player=player,offices=offices,outfit=outfit)
        val updated=update(member,seconds)
        send(member,listOf(BootstrapMessages.changedCharacter(character,player.pcp,player.mcp,player.spotId,
            player.spotOwner,player.returnBase,offices,
            politicianCardForWire(character,player)),unit(member))+updated.refresh)
    }
    @Synchronized fun refreshOffices(characterId: Int,generation: Long,offices: List<BootstrapMessages.OfficeCard>,seconds: Long,outfit: Int?=null) {
        val previous=members.values.singleOrNull { it.character.id.toInt()==characterId && it.player.generation==generation } ?: return
        val next=previous.copy(offices=offices,outfit=outfit ?: previous.outfit)
        val player=next.player
        val updated=update(next,seconds)
        send(next,listOf(BootstrapMessages.changedCharacter(next.character,player.pcp,player.mcp,player.spotId,
            player.spotOwner,player.returnBase,offices,
            politicianCardForWire(next.character,player)))+
            (if(next.outfit!=previous.outfit) listOf(BootstrapMessages.unit(next.character.copy(shipKind=player.hull?.kind ?: next.character.shipKind),player.grid,player.cruise,
                player.baseId,player.mode,next.outfit,player.supplies,if(player.hull?.hp?.let {it<100}==true)1 else 0)) else emptyList())+updated.refresh)
    }
    /** Existing native directory refresh; no guessed original create/delete notification. */
    @Synchronized fun refreshOutfits(world:WorldBootstrap,stocks:org.logh7.engine.PlanetStocks,grid:Int,except:String) {
        for(viewer in members.values.filter {it.connection!=except && it.player.generation==stocks.generation && it.player.grid==grid}) {
            val country=viewer.player.country ?: continue
            val ids=stocks.outfits.values.filter {it.country==country && it.positionIn(world.galaxy).grid==grid}.map {it.id}
            send(viewer,ids.chunked(100).map {world.outfits(it,stocks)}+
                world.gridOutfits(org.logh7.protocol.OutfitMessages.GridQuery(grid,viewer.character.power,0),stocks,country)+refresh(peers(viewer),peers(viewer)))
        }
    }
    /** Native 032b only fills the detail slot; 0b0a's 004c2a80(1) merges the last 032b into the
     * 100-row outfit list. Re-sending the current peers keeps its character import unchanged. */
    @Synchronized fun outfitListRefresh(connection: String): List<ByteArray> =
        members[connection]?.let { refresh(peers(it), peers(it)) }.orEmpty()
    @Synchronized fun remove(connection: String) {
        motionPaths.keys.removeIf { it.first==connection }
        groundResultNotices.remove(connection)
        combatHails.remove(connection)
        val leaving = members[connection] ?: return
        val affected = members.values.filter { visible(it, leaving) }.associateWith(::peers)
        members.remove(connection)
        affected.forEach { (viewer, before) -> send(viewer, refresh(before, peers(viewer))) }
    }
}

internal data class PresenceNotification(val messages: List<ByteArray>, val tacticalGrid: Int? = null, val tacticalBase: Int = 0)
