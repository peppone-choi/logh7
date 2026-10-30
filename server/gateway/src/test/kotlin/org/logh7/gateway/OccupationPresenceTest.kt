package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

class OccupationPresenceTest {
    @Test fun ownershipAndLostCardsReachAuthenticatedPeersAndExpelledCharacterEntersTactics() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3)),
            listOf(GalaxyBase(1049,2487,80,0,"Here",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val people=(0..2).map {i->CharacterMessages.Generate(0,10000L+i,if(i==1)3 else 2,if(i==1)89 else 0,
            0,"Pilot","Name",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)}
        val winner=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true)
        val loser=Navigator(1,10001,Power.ALLIANCE,2487,700,500,300,baseId=1049,mode=4,spotId=10490001,returnBase=1049)
        val far=Navigator(1,10002,Power.EMPIRE,2488,1000,1000,1000)
        val expelled=loser.copy(baseId=0,mode=5,spotId=0,returnBase=0,cpPaused=true,
            posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1049))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,1000,1000)))
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),
            navigators=mapOf("winner" to winner,"loser" to expelled,"far" to far),planetStocks=stocks)
        val packets=List(3){mutableListOf<List<ByteArray>>()};val presence=GamePresence()
        listOf(winner,loser,far).forEachIndexed {i,p->presence.update(GamePresence.Member("c$i",people[i],p,packets[i]::add,
            if(i==1)listOf(BootstrapMessages.OfficeCard(190,1049)) else emptyList()))}
        packets.forEach {it.clear()}
        val event=BaseOccupation(1,2487,1049,3,2,250,emptyMap(),mapOf("loser" to expelled),
            (0..2).associate {10000+it to "c$it"})
        fun deliver(e:BaseOccupation=event,s:WorldSnapshot=snapshot)=presence.occupation(e,s,WorldBootstrap(galaxy),
            {id->people.singleOrNull {it.id==id.toLong()}},{_,_,_->emptyList()})
        fun messages(i:Int,opcode:Int)=packets[i].flatten().filter {ByteBuffer.wrap(it).getShort(4).toInt()==opcode}
        deliver()
        assertContentEquals(TacticalGroundMessages.occupationFinished(1049,2,0),messages(0,0x0442).single())
        assertTrue(messages(1,0x0442).isEmpty());assertTrue(messages(2,0x0442).isEmpty())
        assertContentEquals(TacticsMessages.notify(2487,true),messages(1,0x0f1f).single())
        assertContentEquals(PlaceMessages.dockNotification(10001,0,5,250),messages(1,0x0b0b).single())
        assertTrue(messages(1,0x0356).isNotEmpty())
        for(i in 0..2) {
            assertEquals(2,messages(i,0x031f).single()[11].toInt())
            assertEquals(1,messages(i,0x0321).size)
        }
        val preserved=BootstrapMessages.OfficeCard(39,1)
        val initialized=TacticalBootstrap(snapshot,galaxy,winner){id->people.singleOrNull {it.id==id.toLong()}}
            .initialize {c,_->if(c.power==2)listOf(preserved) else emptyList()}
        assertContentEquals(BootstrapMessages.character(people[0],winner.pcp,winner.mcp,
            winner.spotId,winner.spotOwner,winner.returnBase,listOf(preserved)),initialized.first())
        packets.forEach {it.clear()};deliver(event.copy(recipients=mapOf(10000 to "obsolete")))
        assertTrue(packets.all {it.isEmpty()})
        // The occupation update refreshes presence first, so it must deliver the final tactical exit itself.
        deliver(s=snapshot.copy(navigators=snapshot.navigators+("winner" to winner.copy(cpPaused=false))))
        assertContentEquals(TacticsMessages.notify(2487,false),messages(0,0x0f1f).single())
    }
}
