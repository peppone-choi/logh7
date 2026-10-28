package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class GroundTransferTest {
    private val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4)
    private fun request(action:GroundTransferAction=GroundTransferAction.LOAD)=GroundTransferRequest("pilot","c",10000,Power.EMPIRE,1,1,action)
    private fun stocks(troops:List<ActiveGroundUnit> = listOf(ActiveGroundUnit(2,UnitStack(0,2),base=1049),
        ActiveGroundUnit(3,UnitStack(1)),ActiveGroundUnit(4,UnitStack(0),base=1050))) :PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1050 to PlanetStock(1050,2,0,0,0,0)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,UnitInventory(ships.quantities(),ActiveGroundUnits(troops).quantities()),
                activeShips=ships,activeGround=ActiveGroundUnits(troops))),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1)),deploymentInitialized=true,nextShipId=100)
    }
    @Test fun wholeOutfitRoundTripPreservesIdsGradesInventoryAndRemoteGarrisonAndDoesNotRechargeRetry() {
        val before=stocks();val loaded=GroundTransfer.evaluate(request(),session,pilot,before)
        assertNull(loaded.rejection);assertTrue(loaded.changed);assertEquals(920,loaded.player!!.mcp)
        assertEquals(listOf(2,3),loaded.moved)
        val roster=loaded.stocks!!.outfits.getValue(1).activeGround!!.values
        assertTrue(roster.take(2).all {it.carrier==1 && it.base==0})
        assertEquals(before.outfits.getValue(1).activeGround!!.values.last(),roster.last())
        val retry=GroundTransfer.evaluate(request(),session,loaded.player,loaded.stocks!!)
        assertFalse(retry.changed);assertEquals(loaded.player,retry.player)
        val unloaded=GroundTransfer.evaluate(request(GroundTransferAction.UNLOAD),session,loaded.player,loaded.stocks!!)
        assertEquals(840,unloaded.player!!.mcp);assertTrue(unloaded.stocks!!.outfits.getValue(1).activeGround!!.values.take(2).all {it.base==1049 && it.carrier==0})
        assertEquals(before.outfits.getValue(1).units,unloaded.stocks!!.outfits.getValue(1).units)
        assertEquals(before.warehouses,unloaded.stocks!!.warehouses);assertEquals(before.nextShipId,unloaded.stocks!!.nextShipId)
        assertEquals(before.outfits.getValue(1).activeGround!!.values.map {it.id to it.stack},unloaded.stocks!!.outfits.getValue(1).activeGround!!.values.map {it.id to it.stack})
        assertFalse(GroundTransfer.evaluate(request(GroundTransferAction.UNLOAD),session,unloaded.player,unloaded.stocks!!).changed)
    }
    @Test fun slotOverflowIsAtomicAndExactCapacityIsAllowed() {
        val eleven=stocks((2..12).map {ActiveGroundUnit(it,UnitStack(0),base=1049)})
        val refused=GroundTransfer.evaluate(request(),session,pilot,eleven)
        assertEquals(LogisticsRejection.STOCK,refused.rejection);assertEquals(eleven,refused.stocks);assertEquals(pilot,refused.player)
        val ten=GroundTransfer.evaluate(request(),session,pilot,stocks((2..11).map {ActiveGroundUnit(it,UnitStack(0),base=1049)}))
        assertNull(ten.rejection);assertEquals(10,ten.moved.size)
    }
    @Test fun staleConnectionIdentityCpOwnerAndLocationCannotMutate() {
        val before=stocks()
        assertEquals(LogisticsRejection.SESSION,GroundTransfer.evaluate(request().copy(connection="old"),session,pilot,before).rejection)
        assertEquals(LogisticsRejection.IDENTITY,GroundTransfer.evaluate(request().copy(characterId=10001),session,pilot,before).rejection)
        assertEquals(LogisticsRejection.CP,GroundTransfer.evaluate(request(),session,pilot.copy(mcp=79),before).rejection)
        assertEquals(LogisticsRejection.LOCATION,GroundTransfer.evaluate(request(),session,pilot.copy(baseId=1050),before).rejection)
        assertEquals(LogisticsRejection.ACCESS,GroundTransfer.evaluate(request(),session,pilot,before.copy(bases=before.bases+(1049 to before.bases.getValue(1049).copy(power=3)))).rejection)
        assertEquals(LogisticsRejection.ACCESS,GroundTransfer.evaluate(request(),session,pilot,before.copy(outfits=before.outfits+(1 to before.outfits.getValue(1).copy(commanderId=10001)))).rejection)
    }
    @Test fun currentStationedBaseRatherThanWarehouseHomeDeterminesTransferLocation() {
        val initial=stocks();val moved=initial.copy(outfits=initial.outfits+(1 to initial.outfits.getValue(1).copy(position=OutfitPosition(2487,1050))))
        val result=GroundTransfer.evaluate(request(),session,pilot.copy(baseId=1050),moved)
        assertNull(result.rejection);assertEquals(listOf(3,4),result.moved)
        assertEquals(1049,result.stocks!!.outfits.getValue(1).activeGround!!.values.first().base)
        val unloaded=GroundTransfer.evaluate(request(GroundTransferAction.UNLOAD),session,result.player,result.stocks!!)
        assertEquals(1050,unloaded.stocks!!.outfits.getValue(1).activeGround!!.values.single {it.id==4}.base)
        val afloat=moved.copy(outfits=moved.outfits+(1 to moved.outfits.getValue(1).copy(position=OutfitPosition(2487))))
        assertEquals(LogisticsRejection.LOCATION,GroundTransfer.evaluate(request(),session,pilot,afloat).rejection)
    }
    @Test fun pendingTacticalOrderBlocksWholeOutfitAndSavedOriginIsPreferred() {
        val ordered=stocks(listOf(ActiveGroundUnit(2,UnitStack(0),carrier=1,order=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,1,1049,0,25))))
        assertEquals(LogisticsRejection.BUSY,GroundTransfer.evaluate(request(),session,pilot,ordered).rejection)
        val before=stocks(listOf(ActiveGroundUnit(2,UnitStack(0),base=1049,origin=5)))
        val outfit=before.outfits.getValue(1)
        val ships=ActiveShipUnits(outfit.activeShips!!.values+ActiveShipUnit(5,UnitStack(32)))
        val result=GroundTransfer.evaluate(request(),session,pilot,before.copy(outfits=before.outfits+(1 to outfit.copy(activeShips=ships,units=UnitInventory(ships.quantities(),outfit.units.troops)))))
        assertNull(result.rejection);assertEquals(5,result.stocks!!.outfits.getValue(1).activeGround!!.values.single().carrier)
    }
}
