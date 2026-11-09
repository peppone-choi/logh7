package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.engine.strategy.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

class OccupationPresenceTest {
    @Test fun ownershipAndLostCardsReachAuthenticatedPeersAndExpelledCharacterEntersTactics()=occupation(2,3)
    @Test fun additionalCountriesWithTheSameDisplayCompleteTheOccupationRefresh()=occupation(42,43)

    @Test fun sameCountryRebelOccupationSendsTheNewCamp()=occupation(2,2,1)

    @Test fun movingFortressOccupationRefreshCarriesCurrentFortressAndFlagshipCoordinates()=occupation(2,3,moving=true)

    private fun occupation(winnerCountry:Int,loserCountry:Int,camp:Int=0,moving:Boolean=false) {
        val catalog=CountryCatalogLoader.load().let {old -> CountryCatalog(old.calendars.values.toList(),
            old.families.values.toList(),old.countries.values.toList()+listOf(winnerCountry,loserCountry).filter {it !in old.countries}.map {id ->
                old.countries.getValue(2).copy(id=id,key="country_$id",name="Country $id")})}
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3)),
            listOf(GalaxyBase(1049,2487,80,if(moving)1 else 0,"Here",0,20f,86400,0,0f,2f,mobile=moving)),Power.entries.associateWith {2487})
        val people=(0..2).map {i->CharacterMessages.Generate(0,10000L+i,if(i!=0)3 else 2,if(i!=0)89 else 0,
            0,"Pilot","Name",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)}
        val winner=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true).copy(country=CountryId(winnerCountry))
        val loser=Navigator(1,10001,Power.ALLIANCE,2487,700,500,300,baseId=1049,mode=4,spotId=10490001,returnBase=1049).copy(country=CountryId(loserCountry))
        val far=Navigator(1,10002,Power.ALLIANCE,2488,1000,1000,1000,returnBase=1049).copy(country=CountryId(loserCountry))
        val fortressMotion=TacticalMotion(2487,200,TacticalPose(TacticalPoint(30f,40f),0f),TacticalFortressMotion.VELOCITY,0f,
            listOf(TacticalPoint(60f,40f)),waitSeconds=0)
        val expelledMotion=TacticalMotion(2487,250,TacticalPose(TacticalPoint(39f,40f),0f),0f,0f,
            listOf(TacticalPoint(39f,40f)))
        val expelled=loser.copy(baseId=0,mode=5,spotId=0,returnBase=0,cpPaused=true,
            posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1049),motion=expelledMotion.takeIf {moving})
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(CountryId(winnerCountry)),camp,1000,1000,1000)),
            tacticalCommands=TacticalCommandState(fortresses=
                if(moving)mapOf(1049 to TacticalFortressMotion(2487,fortressMotion)) else emptyMap()))
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),
            navigators=mapOf("winner" to winner,"loser" to expelled,"far" to far.copy(returnBase=0)),planetStocks=stocks)
        val packets=List(3){mutableListOf<List<ByteArray>>()};val presence=GamePresence()
        listOf(winner,loser,far).forEachIndexed {i,p->presence.update(GamePresence.Member("c$i",people[i],p,packets[i]::add,
            if(i==1)listOf(BootstrapMessages.OfficeCard(190,1049)) else emptyList()))}
        packets.forEach {it.clear()}
        val event=BaseOccupation(1,2487,1049,Allegiance.Country(CountryId(loserCountry)),CountryId(winnerCountry),250,emptyMap(),mapOf("loser" to expelled),
            (0..2).associate {10000+it to "c$it"},camp)
        fun deliver(e:BaseOccupation=event,s:WorldSnapshot=snapshot)=presence.occupation(e,s,WorldBootstrap(galaxy,catalog),
            {id->people.singleOrNull {it.id==id.toLong()}},{_,_,_->emptyList()})
        fun messages(i:Int,opcode:Int)=packets[i].flatten().filter {ByteBuffer.wrap(it).getShort(4).toInt()==opcode}
        fun notice(packet:ByteArray)=ChatMessages.spot(packet.copyOfRange(4,packet.size)).text
        val winnerName=catalog.countries.getValue(winnerCountry).name.take(10)
        val occupationLine="[점령] Here · $winnerName"+(if(camp==1)" 반란군" else "")
        deliver()
        assertContentEquals(TacticalGroundMessages.occupationFinished(1049,2,camp),messages(0,0x0442).single())
        if(moving) {
            val base=ByteBuffer.wrap(messages(0,0x0345).single())
            assertEquals(1049,base.getInt(7))
            assertEquals(35f,base.getFloat(11))
            assertEquals(40f,base.getFloat(15))
            val states=ByteBuffer.wrap(messages(0,0x033b).single())
            val records=List(states.getShort(6).toInt()) {8+47*it}
            val flagship=records.single {states.getInt(it)==expelled.unitId}
            assertEquals(39f,states.getFloat(flagship+10))
            assertEquals(40f,states.getFloat(flagship+14))
        }
        assertTrue(messages(1,0x0442).isEmpty());assertTrue(messages(2,0x0442).isEmpty())
        assertContentEquals(TacticsMessages.notify(2487,true),messages(1,0x0f1f).single())
        assertContentEquals(PlaceMessages.dockNotification(10001,0,5,250),messages(1,0x0b0b).single())
        assertTrue(messages(1,0x0356).isNotEmpty())
        assertContentEquals(BootstrapMessages.changedCharacter(people[2],far.pcp,far.mcp,far.spotId,far.spotOwner,0),
            messages(2,0x0356).single())
        assertTrue(messages(2,0x0b0b).isEmpty())
        assertEquals(occupationLine,notice(messages(0,0x0f1d).single()))
        assertTrue(messages(1,0x0f1d).isEmpty());assertTrue(messages(2,0x0f1d).isEmpty())
        assertNull(presence.takeGroundResult("c1",expelled))
        presence.groundResultGridReady("c1",expelled)
        assertEquals(occupationLine,notice(checkNotNull(presence.takeGroundResult("c1",expelled))))
        assertNull(presence.takeGroundResult("c1",expelled))
        for(i in 0..2) {
            assertEquals(2,messages(i,0x031f).single()[11].toInt())
            assertEquals(1,messages(i,0x0321).size)
        }
        val preserved=BootstrapMessages.OfficeCard(39,1)
        val initialized=TacticalBootstrap(snapshot,galaxy,winner,catalog){id->people.singleOrNull {it.id==id.toLong()}}
            .initialize {c,_->if(c.power==2)listOf(preserved) else emptyList()}
        assertContentEquals(BootstrapMessages.character(people[0],winner.pcp,winner.mcp,
            winner.spotId,winner.spotOwner,winner.returnBase,listOf(preserved)),initialized.first())
        packets.forEach {it.clear()};deliver(event.copy(recipients=mapOf(10000 to "obsolete")))
        assertTrue(packets.all {it.isEmpty()})
        // The occupation update refreshes presence first, so it must deliver the final tactical exit itself.
        val returning=winner.copy(cpPaused=false)
        val returnedSnapshot=snapshot.copy(navigators=snapshot.navigators+("winner" to returning))
        presence.groundBattle(GroundBattleResult(1,2487,1049,250,listOf(1),listOf(1),mapOf(10000 to "c0"),
            GroundBattleConclusion(CountryId(winnerCountry))),returnedSnapshot,galaxy){null}
        deliver(s=returnedSnapshot)
        assertContentEquals(TacticsMessages.notify(2487,false),messages(0,0x0f1f).single())
        assertTrue(messages(0,0x0f1d).isEmpty())
        presence.groundResultGridReady("c0",returning)
        assertEquals(occupationLine,notice(checkNotNull(presence.takeGroundResult("c0",returning))))
        assertNull(presence.takeGroundResult("c0",returning),"occupation replaces the queued result for this base")
        assertTrue(listOf(0x0442,0x0325,0x0345).all {messages(0,it).isEmpty()})
        presence.update(GamePresence.Member("c1",people[1],loser,packets[1]::add))
        packets.forEach {it.clear()}
        val relocated=returning.copy(characterId=loser.characterId,country=loser.country,grid=2488)
        deliver(s=snapshot.copy(navigators=snapshot.navigators+("loser" to relocated)))
        assertTrue(listOf(0x0b0b,0x0f1f,0x0442).all {messages(1,it).isEmpty()})
        assertEquals(1,messages(1,0x031f).size)
        presence.update(GamePresence.Member("c0",people[0],winner,packets[0]::add))
        packets.forEach {it.clear()}
        val laterStocks=stocks.copy(bases=stocks.bases.mapValues {(_,base)->base.copy(camp=1-camp)})
        deliver(s=snapshot.copy(planetStocks=laterStocks))
        assertTrue(listOf(0x0442,0x0f1d).all {messages(0,it).isEmpty()})
        assertEquals(1,messages(0,0x031f).size)

        val waiting=loser.copy(baseId=0,mode=5,spotId=0,returnBase=0)
        val job=StrategyJob(0,"operation/星系内航行",200_000,29_000_000,29_000_000,
            PaymentResult.Paid(CpBalance(700,500),CpPool.MCP,160,160,false),JobState.WAITING)
        val reserved=waiting.copy(dockTarget=1049,strategy=StrategyCheckpoint(1,200_000,listOf(job)))
        presence.update(GamePresence.Member("c1",people[1],reserved,packets[1]::add))
        packets.forEach {it.clear()}
        val cancelled=snapshot.copy(navigators=snapshot.navigators+("loser" to waiting))
        deliver(s=cancelled)
        assertContentEquals(PlaceMessages.dockNotification(10001,0,5,250,0,10001),messages(1,0x0b0b).single())
        assertTrue(messages(1,0x0f1d).map(::notice).any {it.startsWith("[기항]")})
        assertTrue(messages(0,0x0b0b).isEmpty())
        packets.forEach {it.clear()}
        deliver(s=cancelled)
        assertTrue(messages(1,0x0b0b).isEmpty())
    }
}
