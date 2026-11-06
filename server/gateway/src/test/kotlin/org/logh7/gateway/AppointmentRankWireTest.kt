package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.Properties
import kotlin.test.*

/** The directory candidates (0x1200 search 4) and the appointment (0x0707) judge the target by the same
 * stored country rank, with the registered profile rank as fallback. Author: Peppone Choi. */
class AppointmentRankWireTest {
    @TempDir lateinit var directory: Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State(galaxy:Galaxy,storedTargetRank:Int,militaryRank:Int):WorldStateCommitter {
        var players=(0..1).associate { "load-%03d".format(it) to Navigator(1,10000+it,Power.EMPIRE,2487,1000,1000,1000,
            cpRecoveryAt=SessionRules.load().startSeconds) }
        var stocks=PlanetStocks(1,0,galaxy.conditions.mapValues { (_,c)->PlanetStock(c.baseId,c.power,c.camp,
            100,200,300,PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace),governorId=10000) },deploymentInitialized=true,
            strategic=StrategicState(people=mapOf(10001 to StrategicPerson(rank=storedTargetRank,militaryRank=militaryRank))))
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?) { players=navigation.toMap();stocks=checkNotNull(inventory) }
        val navigationStore=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) { this@State.players=players.toMap() }
        }
        val stockStore=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) { this@State.stocks=stocks }
        }
    }
    private fun appoint()=ByteBuffer.allocate(40).putShort(0x0707).putInt(-1).putInt(10000)
        .putInt(Int.MAX_VALUE).putInt(Int.MAX_VALUE).putInt(10001).putInt(55).putShort(0).putInt(0).putInt(0).putInt(1049).array()
    private fun candidates()=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(4).putShort(80).putShort(55)
        .also { it.putInt(25,0) }.array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) { val value=client.receive();if(ByteBuffer.wrap(value).getShort(4).toInt()==opcode)return value }
        error("Missing $opcode")
    }

    // Garrison commander (card 55): the governor (rank 7) may appoint ranks 8..? within the manual range;
    // AppointmentWireTest shows profile rank 10 accepted and 20 rejected.
    @Test fun aStoredIneligibleRankHidesTheCandidateAndRejectsTheAppointmentDespiteAnEligibleProfile()=exercise(10,20,false)
    @Test fun aStoredEligibleRankListsAndAppointsTheCandidateDespiteAnIneligibleProfile()=exercise(20,10,true)
    // An empire politician (rank 1) keeps a military ladder rank (manual p.37). The character view still
    // reports 1, so only the country rank (ProposalActions.rank) used by both paths makes them agree.
    @Test fun anEmpirePoliticianIsJudgedByTheMilitaryLadderInTheDirectoryAndTheAppointment()=exercise(10,1,true,militaryRank=10)

    private fun exercise(profileTargetRank:Int,storedTargetRank:Int,accepted:Boolean,militaryRank:Int=0) {
        val properties=Properties()
        for(i in 0..1) {
            val person=CharacterMessages.Generate(4,10000L+i,2,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},
                0,0,0,if(i==0)7 else profileTargetRank,0,0,"Ship$i",0)
            val reply=CharacterMessages.generateReply(person)
            properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(reply.copyOfRange(4,reply.size)))
        }
        Files.newBufferedWriter(directory.resolve("characters.properties")).use { properties.store(it,null) }
        val state=State(galaxy,storedTargetRank,militaryRank)
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=state.stockStore,
            navigationStore=state.navigationStore,worldStateCommitter=state).use { fixture->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val target=fixture.join(fixture.account(1),false,false).client
            target.send(SyntheticClient.opcode(0x0f00));next(target,0x0f01)
            actor.send(candidates());next(actor,0x1200)
            val people=next(actor,0x1202)
            assertEquals(if(accepted)1 else 0,people[6].toInt(),"directory candidates")
            if(accepted)assertEquals(10001,ByteBuffer.wrap(people).getInt(7))
            next(actor,0x1201)
            actor.send(appoint())
            val ack=ByteBuffer.wrap(next(actor,0x0707))
            assertEquals(if(accepted)0 else 1,ack.int,"appointment result")
            assertEquals(if(accepted)10001 else 0,state.stocks.bases.getValue(1049).garrisonCommanderId)
            assertEquals(if(accepted)840 else 1000,state.players.getValue("load-000").mcp)
        }
    }
}
