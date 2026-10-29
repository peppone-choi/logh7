package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.*

/** Revival presentation codes: atlas order for confirmed grades; 255 means not yet decided.
 * Population is projected in ten-thousands, while the engine retains exact people and ratios. */
internal object SessionEndingReport {
    private fun statistics(value:SessionStatistics)=SessionEndingMessages.Statistics(value.planets,value.fortresses,
        value.population/10_000,value.shipUnits,value.groundUnits)
    private fun flag(value:Boolean?)=when(value){true->1;false->0;null->255}
    fun message(world:WorldSnapshot,character:CharacterMessages.Generate,player:Navigator,
        registered:List<CharacterMessages.Generate>,offices:List<BootstrapMessages.OfficeCard>):ByteArray {
        val session=checkNotNull(world.session)
        require(player.generation==session.generation && character.id==player.characterId.toLong())
        require(character.power in 2..3 && player.power==if(character.power==2)Power.EMPIRE else Power.ALLIANCE)
        // No award is granted here. The compatibility result panel labels evaluation/fame as pending.
        return SessionEndingMessages.ended(character,SessionEndingMessages.Evaluation(0,0,0,0,0),session(world,registered),
            player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices)
    }
    fun session(world:WorldSnapshot,registered:List<CharacterMessages.Generate>):SessionEndingMessages.Session {
        val result=checkNotNull(world.result);require(result.end==checkNotNull(world.session).ended)
        val powers=Power.entries.map {side->
            val power=if(side==Power.EMPIRE)2 else 3
            val report=result.powers.getValue(side)
            val grade=when(report.grade){SessionGrade.LIMITED->0;SessionGrade.LOCAL->1;SessionGrade.DECISIVE->2;
                SessionGrade.DEFEAT->3;SessionGrade.UNRESOLVED->255}
            SessionEndingMessages.PowerResult(power,0,registered.count {it.power==power}.toLong(),
                (report.statistics.planets+report.statistics.fortresses).toLong(),
                SessionEndingMessages.PowerEnding("",0,grade,flag(report.populationDominance),flag(report.fleetDominance),
                    flag(result.conditions.noCoupAtEnd),flag(if(side==Power.ALLIANCE)true else result.conditions.imperialLeaderAtEnemyCapital),
                    statistics(report.statistics)))
        }
        val status=when(result.end.reason){EndReason.CAPITAL_CAPTURED->3;EndReason.SYSTEMS_REDUCED->4;EndReason.TIME_LIMIT->5}
        return SessionEndingMessages.Session(LobbyMessages.LOCAL_SESSION_ID,status,LobbyMessages.LOCAL_SESSION_NAME,
            "",result.end.gameSeconds,powers,statistics(result.total))
    }
}
