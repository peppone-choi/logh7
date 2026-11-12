package org.logh7.gateway

import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.logh7.gateway.load.LoadFixture
import org.logh7.gateway.load.LoadMetrics
import org.logh7.gateway.load.SyntheticClient
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import kotlin.test.*

class CardCharacterWorldTest {
    @TempDir lateinit var directory:Path

    @Test fun freshGovernmentHoldersAreAvailableOverTheNativeOfficeProtocol() {
        val regular = Galaxy(galaxy.systems + GalaxySystem(4, 2015, 4, "Alliance", 3),
            listOf(galaxy.bases[0].copy(name = Capital.EMPIRE.baseName),
                galaxy.bases[1].copy(grid = 2015, name = Capital.ALLIANCE.baseName)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015), initialGovernment = true)
        LoadFixture(directory, 1, LoadMetrics(), regular, ticking = false).use { fixture ->
            val client = fixture.join(fixture.account(0), true, false).client
            fun next(opcode: Int): ByteBuffer {
                repeat(100) {
                    val message = ByteBuffer.wrap(client.receive())
                    if (message.getShort(4).toInt() == opcode) return message
                }
                error("Expected opcode $opcode")
            }
            client.send(SyntheticClient.opcode(0x0f00))
            next(0x0f01)
            for ((card, id) in mapOf(1 to 901, 2 to 901, 5 to 902, 6 to 902, 132 to 903, 139 to 904)) {
                client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())
                val reply = next(0x034f)
                assertEquals(0, reply.getInt(0))
                assertEquals(1, reply.get(8).toInt())
                assertEquals(id, reply.getInt(9))
            }
            client.send(ByteBuffer.allocate(6).putShort(0x0322).putInt(902).array())
            assertEquals(902, next(0x0323).getInt(6))
        }
    }

    @Test fun encryptedPagesReturnTheLastHolderWithoutOverflowingTheNativeResponse() {
        val seats=(1..65).map {galaxy.bases.first().copy(id=it,grid=if(it==1)2487 else 2400+it)}
        val systems=seats.map {GalaxySystem(it.id+2,it.grid,4,"System "+it.id,2)}
        val rebel = seats.first().copy(id = 66, grid = 2466)
        val world=Galaxy(systems + GalaxySystem(68, rebel.grid, 4, "Rebel", 3), seats + rebel, galaxy.starts)
        val roster=stocks.copy(bases=seats.associate {it.id to PlanetStock(it.id,2,0,0,0,0,governorId=19000+it.id)} +
            (rebel.id to PlanetStock(rebel.id, 3, 1, 0, 0, 0)),
            strategic = StrategicState(offices = listOf(OfficeHolding(256, rebel.id, 19066, CountryId(3)))),
            deploymentInitialized=true)
        val characters=CharacterCreation(directory.resolve("characters.properties"))
        for(id in 19001..19065)assertTrue(characters.carry("holder$id",CharacterProfile(character.copy(id=id.toLong()),CountryId(2),ShipStyleId("empire"))))
        assertTrue(characters.carry("holder19066", CharacterProfile(character.copy(id = 19066, power = 3),
            CountryId(3), ShipStyleId("alliance"))))
        val store=object:PlanetStockStore {
            override fun load()=roster
            override fun save(stocks:PlanetStocks) {}
        }
        LoadFixture(directory,1,LoadMetrics(),world,ticking=false,stockStore=store).use {fixture ->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            fun next(opcode:Int):ByteBuffer {
                repeat(100) {
                    val message=ByteBuffer.wrap(client.receive())
                    if(message.getShort(4).toInt()==opcode)return message
                }
                error("Expected opcode $opcode")
            }
            next(0x0f01)
            fun page(value:Int):ByteBuffer {
                client.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(value.toShort()).array())
                return next(0x034f)
            }
            val legacy=page(54)
            assertEquals(1,legacy.getInt(0));assertEquals(0,legacy.get(8).toInt())
            val first=page(0x8036)
            assertEquals(64,first.get(8).toInt());assertEquals(19001,first.getInt(9))
            val second=page(0x8136)
            assertEquals(54,second.getShort(6).toInt());assertEquals(1,second.get(8).toInt())
            assertEquals(19065,second.getInt(9))
            assertEquals(0,page(0x8236).get(8).toInt())
            for (request in listOf(0x0100, 0x8000)) {
                val rebels = page(request)
                assertEquals(0, rebels.getInt(0))
                assertEquals(256, rebels.getShort(6).toInt())
                assertEquals(1, rebels.get(8).toInt())
                assertEquals(19066, rebels.getInt(9))
            }
            val emptyRebelPage = page(0x8100)
            assertEquals(256, emptyRebelPage.getShort(6).toInt())
            assertEquals(0, emptyRebelPage.get(8).toInt())
        }
    }

    @Test fun capitalsExposeOnlyTheirOwnOriginalOfficesAndRegisteredFactionHolders() {
        val capitalWorld=WorldBootstrap(Galaxy(galaxy.systems+GalaxySystem(4,2015,4,"Alliance",3),listOf(
            galaxy.bases[0].copy(name="オーディン"),
            galaxy.bases[1].copy(grid=2015,name="ハイネセン")),galaxy.starts))
        val assigned=stocks.copy(bases=mapOf(
            1048 to PlanetStock(1048,2,0,0,0,0,governorId=10010,garrisonCommanderId=10011,
                capitalDefenseCommanderId=10000,guardInspectorId=10001),
            1049 to PlanetStock(1049,3,0,0,0,0,governorId=10012,garrisonCommanderId=10013,
                capitalAdministratorId=10002,capitalDefenseCommanderId=10003)))
        for((card,id,power) in listOf(Triple(56,10000,2),Triple(57,10001,2),Triple(192,10002,3),Triple(193,10003,3))) {
            val response=capitalWorld.cardCharacters(card,assigned) { character.copy(id=it.toLong(),power=power) }
            assertEquals(1,response[8].toInt());assertEquals(id,ByteBuffer.wrap(response).getInt(9))
            assertEquals(card,ByteBuffer.wrap(response).getShort(response.size-7).toInt())
            assertEquals(if(power==2)1048 else 1049,ByteBuffer.wrap(response).getInt(response.size-5))
        }
        for(card in listOf(54,55,190,191)) assertEquals(0,capitalWorld.cardCharacters(card,assigned) {
            error("Legacy capital assignments must not become ordinary office cards") }[8].toInt())
        assertFails { capitalWorld.cardCharacters(56,assigned) { character.copy(power=3) } }
    }
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        (1048..1049).map { GalaxyBase(it,2487,80,0,"Home",0,20f,86400,0,0f,2f) },
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val world=WorldBootstrap(galaxy)
    private val character=CharacterMessages.Generate(4,10000,2,0,0,"Governor","Pilot",18,1,1,
        1000001,List(8) { 50 },0,0,0,20,3,0,"Ship",1)
    private val stocks=PlanetStocks(1,0,(1048..1049).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10000) })
    @Test fun oneGovernorRetainsEveryAssignedBaseWithoutDuplicatePeople() {
        val response=world.cardCharacters(54,stocks) { character.takeIf { c -> c.id==it.toLong() } }
        assertEquals(1,response[8].toInt());assertEquals(10000,ByteBuffer.wrap(response).getInt(9))
        val tail=ByteBuffer.wrap(response,response.size-26,26)
        assertEquals(4,tail.get().toInt()) // personal, captain, two appointments
        tail.position(tail.position()+12)
        assertEquals(54,tail.short.toInt());assertEquals(1048,tail.int)
        assertEquals(54,tail.short.toInt());assertEquals(1049,tail.int);assertEquals(0,tail.get().toInt())
        val other=world.cardCharacters(190,stocks) { error("Unappointed faction must not resolve a person") }
        assertEquals(0,other[8].toInt())
    }
    @Test fun missingOrWrongFactionIdentityCannotBecomeAnEmptyOffice() {
        assertFails { world.cardCharacters(54,stocks) { null } }
        assertFails { world.cardCharacters(54,stocks) { character.copy(power=3) } }
        assertFails { world.cardCharacters(59,stocks) { character } }
    }
    @Test fun aDeceasedHolderInAnOldSaveDoesNotMaskLivingPlanetOfficers() {
        val saved=stocks.copy(bases=mapOf(
            1048 to stocks.bases.getValue(1048).copy(governorId=10001,garrisonCommanderId=10001),
            1049 to stocks.bases.getValue(1049).copy(garrisonCommanderId=10000)),
            strategic=StrategicState(people=mapOf(10001 to StrategicPerson(deceasedAt=1))))
        for(card in listOf(54,55)) {
            val response=ByteBuffer.wrap(world.cardCharacters(card,saved) { id ->
                character.takeIf {it.id==id.toLong()}
            })
            assertEquals(0,response.getInt(0))
            assertEquals(1,response.get(8).toInt())
            assertEquals(10000,response.getInt(9))
            assertEquals(1049,response.getInt(response.limit()-5))
        }
        val onlyDeceased=saved.copy(bases=saved.bases - 1049)
        val response=ByteBuffer.wrap(world.cardCharacters(54,onlyDeceased) { error("No live holder") })
        assertEquals(0,response.getInt(0))
        assertEquals(0,response.get(8).toInt())
    }
    @Test fun originalLimitsRejectTruncationInsteadOfReportingVacancies() {
        val manyPeople=stocks.copy(bases=(1..65).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10000+it) })
        val capacityWorld=WorldBootstrap(Galaxy(galaxy.systems,(1..65).map { galaxy.bases.first().copy(id=it) },galaxy.starts))
        val rejected=capacityWorld.cardCharacters(54,manyPeople) { error("Overflow must fail before resolving people") }
        assertEquals(1,ByteBuffer.wrap(rejected).int)
        assertEquals(0,rejected[8].toInt())
        assertContentEquals(org.logh7.protocol.CardCharacterMessages.response(54,emptyList(),failed=true),rejected)
        val countryOf:(Int)->CountryId?={CountryId(2)}
        val profile:(Int)->org.logh7.protocol.CharacterMessages.Generate?={character.copy(id=it.toLong())}
        val pages=(0..2).map {capacityWorld.cardCharacters(54,manyPeople,countryOf,profile,it)}
        assertEquals(listOf(64,1,0),pages.map {it[8].toInt()})
        assertEquals(listOf(10001,10065),pages.take(2).map {ByteBuffer.wrap(it).getInt(9)})
        val manyBases=stocks.copy(bases=(1..15).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10000) })
        assertFails { capacityWorld.cardCharacters(54,manyBases) { character } }
    }

    @Test fun planetAndFortressGarrisonsUseDifferentOriginalCardsForBothFactions() {
        val garrisonWorld=WorldBootstrap(Galaxy(galaxy.systems,listOf(
            galaxy.bases[0].copy(kind=0),galaxy.bases[1].copy(kind=1)),galaxy.starts))
        for (power in listOf(2,3)) {
            val offset=if (power==3) 136 else 0
            val assigned=stocks.copy(bases=stocks.bases.mapValues { (_,base) ->
                base.copy(owner=Allegiance.fromLegacy(power),governorId=0,garrisonCommanderId=10000) })
            for ((card,base) in listOf(55+offset to 1048,52+offset to 1049)) {
                val response=garrisonWorld.cardCharacters(card,assigned) { character.copy(power=power) }
                assertEquals(1,response[8].toInt())
                assertEquals(card,ByteBuffer.wrap(response).getShort(response.size-7).toInt())
                assertEquals(base,ByteBuffer.wrap(response).getInt(response.size-5))
            }
            val empty=garrisonWorld.cardCharacters(54+offset,assigned) { error("Garrison is not a governor") }
            assertEquals(0,empty[8].toInt())
        }
    }

}
