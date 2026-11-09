package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.persistence.FileWorldStateStore
import org.logh7.protocol.TacticalAttackMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** Tactical damage and the carried package must share the same durable world replacement. Author: Peppone Choi. */
class TransportCombatLossWireTest {
    @TempDir lateinit var directory:Path

    @Test fun tacticalDamageSettlesOnlyTheHitCarriersCargoAndSurvivesReopen()=runBlocking<Unit> {
        for(boats in listOf(300,1)) {
            val root=directory.resolve("world-$boats")
            val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
                listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f),
                    GalaxyBase(1001,2487,80,0,"Destination",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
            val now=SessionRules.load().startSeconds
            fun motion(x:Float,y:Float):TacticalMotion {
                val point=TacticalPoint(x,y)
                return TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point))
            }
            val player=Navigator(1,10000,Power.EMPIRE,2488,1000,1000,900,cpPaused=true,
                supplies=400,hull=FlagshipHull(0,now,hp=100),motion=motion(1f,0.5f))
            val own=UnitStack(32)
            val enemy=UnitStack(155)
            val cargo=TransportPackage(1,CountryId(3),1000,1001,if(boats==1)50 else 2001,UnitInventory(troops=mapOf(UnitStack(4) to 3)),
                carrier=2,carrierBoats=boats)
            val untouched=cargo.copy(id=2,carrier=0,carrierBoats=0,survivingBoats=0)
            val initial=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100),1001 to PlanetStock(1001,3,0,200,100,100)),
                outfits=mapOf(
                    1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                        units=UnitInventory(mapOf(own to 1)),supplies=10000,position=OutfitPosition(2488),
                        activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,own,supplies=10000,
                            tactical=NpcTacticalState(motion(0f,0f),now,now))))),
                    2 to OutfitStock(2,3,OutfitKind.TRANSPORT,1001,20,units=UnitInventory(mapOf(enemy to 1)),
                        activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,enemy,boats=boats,
                            tactical=NpcTacticalState(motion(0f,2f),now,now)))),position=OutfitPosition(2488))),
                warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),
                    20 to OutfitWarehouse(20,3,OutfitKind.TRANSPORT,1001,2)),deploymentInitialized=true,nextShipId=100,
                strategic=StrategicState(transports=listOf(cargo,untouched)))
            val remaining=FileWorldStateStore(root).use {store ->
                store.saveWorld(mapOf("load-000" to player),initial)
                LoadFixture(directory.resolve("client-$boats"),2,LoadMetrics(),galaxy,ticking=false,
                    navigationStore=store.navigationStore,stockStore=store.stockStore,worldStateCommitter=store,
                    powerByIndex={2}).use {fixture ->
                    val client=fixture.join(fixture.account(0),true,false).client
                    fun through(opcode:Int):ByteBuffer {
                        repeat(500) {
                            val packet=ByteBuffer.wrap(client.receive())
                            if(packet.getShort(4).toInt()==opcode)return packet
                        }
                        error("Missing $opcode")
                    }
                    client.send(SyntheticClient.opcode(0x0f00));through(0x0f01)
                    client.send(SyntheticClient.opcode(0x0f02));through(0x0f03)
                    val shot=TacticalAttackMessages.Shoot(10000,listOf(1),2,1,99)
                    val packet=TacticalAttackMessages.reply(shot,0,0)
                    client.send(packet.copyOfRange(4,packet.size))
                    assertEquals(0,through(0x0406).int)
                    fixture.engine.submit(WorldCommand.AdvanceTo(5000))
                    assertEquals(99,through(0x0426).getInt(16))
                    val saved=assertNotNull(store.loadStocks())
                    val alive=saved.outfits.getValue(2).activeShips!!.values.single().boats
                    if(boats==1)assertEquals(0,alive) else assertTrue(alive in 1 until boats)
                    assertEquals(listOf(untouched),saved.strategic.transports.filter {it.carrier==0})
                    assertEquals(if(alive==0)emptyList() else listOf(cargo.copy(survivingBoats=alive)),
                        saved.strategic.transports.filter {it.carrier!=0})
                    assertEquals(initial.bases.mapValues {it.value.supplies},saved.bases.mapValues {it.value.supplies})
                    assertEquals(initial.bases.mapValues {it.value.units},saved.bases.mapValues {it.value.units})
                    alive
                }
            }
            FileWorldStateStore(root).use {reopened ->
                val saved=assertNotNull(reopened.loadStocks())
                assertEquals(remaining,saved.outfits.getValue(2).activeShips!!.values.single().boats)
                val expected=if(remaining==0)listOf(untouched) else listOf(cargo.copy(survivingBoats=remaining),untouched)
                assertEquals(expected,saved.strategic.transports)
                if(remaining>0)assertEquals(2001*remaining/boats,
                    TransportActions.delivered(saved.strategic.transports.first(),boats).first)
            }
        }
    }
}
