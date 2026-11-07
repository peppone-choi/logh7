package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.*

/** Revival presentation codes: atlas order for confirmed grades; 255 means not yet decided.
 * Population is projected in ten-thousands, while the engine retains exact people and ratios. */
internal object SessionEndingReport {
    fun information(world:WorldSnapshot?,registered:List<CharacterMessages.Generate>,
        sessionId:Int=LobbyMessages.LOCAL_SESSION_ID,sessionName:String=LobbyMessages.LOCAL_SESSION_NAME,
        generation:Long=1):SessionEndingMessages.Session {
        if(world?.result!=null && world.session?.generation==generation)
            return session(world,registered).copy(id=sessionId,name=sessionName)
        val powers=listOf(2,3).map {power->SessionEndingMessages.PowerResult(power,0,
            registered.count {it.power==power}.toLong(),
            // Owner by country, so a base of another country never reaches PlanetStock.power (legacy authority).
            world?.planetStocks?.bases?.values?.count {it.belongsTo(CountryId(power)) && it.camp==0}?.toLong() ?: 0,null)}
        return SessionEndingMessages.Session(sessionId,1,sessionName,"",world?.session?.gameSeconds ?: 0,powers,null)
    }
    private fun statistics(value:SessionStatistics)=SessionEndingMessages.Statistics(value.planets,value.fortresses,
        (value.population/10_000).coerceAtMost(0xffffffffL),value.shipUnits.coerceAtMost(0xffffffffL),value.groundUnits.coerceAtMost(0xffffffffL))
    private fun flag(value:Boolean?)=when(value){true->1;false->0;null->255}
    fun message(world:WorldSnapshot,character:CharacterMessages.Generate,player:Navigator,
        registered:List<CharacterMessages.Generate>,offices:List<BootstrapMessages.OfficeCard>,
        evaluation:SessionEndingMessages.Evaluation=SessionEndingMessages.Evaluation(0,0,0,0,0),
        sessionId:Int=LobbyMessages.LOCAL_SESSION_ID,sessionName:String=LobbyMessages.LOCAL_SESSION_NAME,
        countries:CountryCatalog=CountryCatalogLoader.current()):ByteArray {
        val session=checkNotNull(world.session)
        require(player.generation==session.generation && character.id==player.characterId.toLong())
        // The record's native side must be the player's actual country's display code (same rule as the captain
        // card); a removed or unknown country, or a mismatch, is refused rather than read as the empire or alliance.
        require(character.power in 2..3 && TacticalAuthority.captainCard(player,countries)==if(character.power==2)59 else 195) {
            "Session ending record differs from the player's country" }
        // The result observer grants fame; rendering only uses its persisted statement.
        return SessionEndingMessages.ended(character,evaluation,session(world,registered).copy(id=sessionId,name=sessionName),
            player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices)
    }
    fun session(world:WorldSnapshot,registered:List<CharacterMessages.Generate>):SessionEndingMessages.Session {
        val result=checkNotNull(world.result);require(result.end==checkNotNull(world.session).ended)
        val powers=Power.entries.map {side->
            val power=if(side==Power.EMPIRE)2 else 3
            // Native transport has two physical rows. Added-country results travel in mode14, never folded here.
            val report=result.countries[side.country]
            val grade=when(report?.grade){SessionGrade.LIMITED->0;SessionGrade.LOCAL->1;SessionGrade.DECISIVE->2;
                SessionGrade.DEFEAT->3;SessionGrade.UNRESOLVED,null->255}
            SessionEndingMessages.PowerResult(power,0,registered.count {it.power==power}.toLong(),
                report?.let {(it.statistics.planets+it.statistics.fortresses).toLong()} ?: 0,
                report?.let {SessionEndingMessages.PowerEnding("",0,grade,flag(it.populationDominance),flag(it.fleetDominance),
                    flag(result.conditions.noCoup(side)),flag(when(it.leaderRequired) {
                        false->true;true->result.conditions.leader(side.country);null->null}),
                    statistics(it.statistics))})
        }
        val status=when(result.end.reason){EndReason.CAPITAL_CAPTURED->3;EndReason.SYSTEMS_REDUCED->4;EndReason.TIME_LIMIT->5}
        return SessionEndingMessages.Session(LobbyMessages.LOCAL_SESSION_ID,status,LobbyMessages.LOCAL_SESSION_NAME,
            "",result.end.gameSeconds,powers,statistics(result.total))
    }
}
