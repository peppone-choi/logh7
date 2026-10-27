package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class SupplementTest {
    private val ship=UnitStack(32,2)
    private val crew=UnitStack(3)
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4)
    private val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()
    private fun request(ids: List<Int> = listOf(1),supplies: Int=100,mode: Int=1)=SupplementRequest(
        "pilot","c",10000,Power.EMPIRE,1,1049,1,0,mode,if(ids.isEmpty())emptyMap() else mapOf(32 to ids),supplies)
    private fun stocks(boats: Int=290,warehouseCrew: Int=100,warehouseShips: Boolean=true): PlanetStocks {
        val units=UnitInventory(mapOf(ship to 1))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,units,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,ship,boats))))),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
                UnitInventory(if(warehouseShips)mapOf(UnitStack(32) to 1) else emptyMap(),
                    if(warehouseCrew>0)mapOf(crew to warehouseCrew) else emptyMap()),supplies=1000)),
            deploymentInitialized=true,nextShipId=2)
    }
    private fun total(stocks: PlanetStocks)=stocks.outfits.getValue(1).activeShips!!.values.sumOf {it.boats}+
        stocks.warehouses.getValue(101).let {it.units.ships.values.sum()*300+it.spareShips.values.values.sum()}

    @Test fun exactShipReplacementCrewAndCargoConserveBothSidesAndKeepUnitIdentity() {
        val initial=stocks();val result=Supplement.evaluate(request(),session,player,initial)
        assertNull(result.rejection);assertTrue(result.changed);assertEquals(840,result.player!!.mcp)
        val after=result.stocks!!;val unit=after.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(ActiveShipUnit(1,ship,300),unit);assertEquals(total(initial),total(after))
        val warehouse=after.warehouses.getValue(101)
        assertTrue(warehouse.units.ships.isEmpty());assertEquals(290,warehouse.spareShips.values[UnitStack(32)])
        assertEquals(100,after.outfits.getValue(1).supplies);assertEquals(900,warehouse.supplies)
        assertEquals(100,warehouse.units.troops.values.sum()+after.outfits.getValue(1).units.troops.values.sum())
        val again=Supplement.evaluate(request(supplies=0),session,result.player,after)
        assertNull(again.rejection);assertFalse(again.changed);assertEquals(result.player,again.player)
        val damaged=after.copy(outfits=after.outfits+(1 to after.outfits.getValue(1).copy(
            activeShips=ActiveShipUnits(listOf(unit.copy(boats=280))))))
        val reused=Supplement.evaluate(request(supplies=0),session,result.player,damaged)
        assertNull(reused.rejection);assertEquals(total(damaged),total(reused.stocks!!))
        assertEquals(270,reused.stocks!!.warehouses.getValue(101).spareShips.values[UnitStack(32)])
    }
    @Test fun previewNeverChangesCpAndSupplyReturnIsBoundedAndConserved() {
        val initial=stocks();val preview=Supplement.evaluate(request(mode=0),session,player,initial)
        assertNull(preview.rejection);assertFalse(preview.changed);assertEquals(player,preview.player)
        assertEquals(290,initial.outfits.getValue(1).activeShips!!.values.single().boats)
        val commit=Supplement.evaluate(request(),session,player,initial)
        val returned=Supplement.evaluate(request(emptyList(),-50),session,commit.player,commit.stocks!!)
        assertNull(returned.rejection);assertEquals(50,returned.stocks!!.outfits.getValue(1).supplies)
        assertEquals(950,returned.stocks!!.warehouses.getValue(101).supplies)
        assertEquals(LogisticsRejection.STOCK,Supplement.evaluate(request(emptyList(),-101),session,
            commit.player,commit.stocks!!).rejection)
    }
    @Test fun invalidUnitsStockCrewCpSessionAndAuthorityLeaveStateUntouched() {
        val initial=stocks()
        for(q in listOf(request(listOf(2)),request(listOf(1,1)),request(supplies=1001),
            SupplementRequest("pilot","c",10000,Power.EMPIRE,1,1049,1,0,1,mapOf(48 to listOf(1))),
            SupplementRequest("pilot","c",10000,Power.EMPIRE,1,1049,1,0,1,mapOf(32 to listOf(1),48 to listOf(2))))) {
            val result=Supplement.evaluate(q,session,player,initial)
            assertEquals(LogisticsRejection.STOCK,result.rejection);assertEquals(initial,result.stocks);assertEquals(player,result.player)
        }
        assertEquals(LogisticsRejection.STOCK,Supplement.evaluate(request(),session,player,stocks(warehouseShips=false)).rejection)
        assertEquals(LogisticsRejection.CREW,Supplement.evaluate(request(),session,player,stocks(warehouseCrew=0)).rejection)
        assertEquals(LogisticsRejection.CP,Supplement.evaluate(request(),session,player.copy(pcp=0,mcp=159),initial).rejection)
        assertEquals(LogisticsRejection.SESSION,Supplement.evaluate(request(),session.copy(generation=2),player,initial).rejection)
        assertEquals(LogisticsRejection.LOCATION,Supplement.evaluate(request(),session,player.copy(baseId=1050),initial).rejection)
        assertEquals(LogisticsRejection.ACCESS,Supplement.evaluate(request(),session,player,
            initial.copy(outfits=initial.outfits+(1 to initial.outfits.getValue(1).copy(commanderId=10001)))).rejection)
    }
    @Test fun returningAnUnderstrengthUnitCannotManufactureNewBoats() {
        val returned=ReplacementBoats.reorganize(stocks(boats=123,warehouseShips=false),1,
            UnitTransfer(mapOf(ship to -1)))
        val corrected=returned.warehouses.getValue(101)
        assertTrue(corrected.units.ships.isEmpty());assertEquals(123,corrected.spareShips.values[ship])
        val depleted=ReplacementBoats.take(corrected,32,100)
        assertEquals(23,depleted.spareShips.values[ship]);assertFails {ReplacementBoats.take(depleted,32,24)}
    }
}
