package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** TacticalBootstrap exposes each tactical unit's actual camp beside the unchanged native units. Author: Peppone Choi. */
class TacticalUnitCampTest {
    // 42 and 43 both display as 3; only the actual country separates them.
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"other_country","Other","alliance",3,0,"alliance")))}
    private val empire=ShipStyleId("empire")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,10,4,"Here",2),GalaxySystem(4,20,4,"Away",3)),
        listOf(GalaxyBase(100,10,80,0,"Here",0,20f,86400,0,0f,2f),GalaxyBase(200,20,80,0,"Away",0,20f,86400,0,0f,2f)),
        Power.entries.associateWith {10})
    private fun pilot(id:Int,country:Int?,grid:Int=10,base:Int=0)=Navigator(1,id,country?.let(::CountryId),grid,1000,1000,1000,
        baseId=base,mode=if(base>0)4 else 5,cpPaused=true,shipStyle=empire)
    private fun person(id:Int)=CharacterMessages.Generate(0,id.toLong(),3,3,0,"P$id","Q",30,1,1,0,List(8){50},0,0,0,20,0,0,"S$id",0)
    private fun fleet(id:Int,country:Int,base:Int,commander:Int,vararg ships:Int)=
        ActiveShipUnits(ships.map {ActiveShipUnit(it,UnitStack(32))}).let {active->OutfitStock(id,CountryId(country),OutfitKind.FLEET,base,
            100+id,commander,UnitInventory(active.quantities()),activeShips=active,shipStyle=empire,
                position=OutfitPosition(galaxy.bases.single {it.id==base}.grid))}
    private val viewer=pilot(10000,42)
    private val navigators=mapOf("viewer" to viewer,"other" to pilot(10001,43),"rebel" to pilot(10003,42),
        "lone1" to pilot(10004,null),"lone2" to pilot(10005,null),
        "away" to pilot(10006,42,grid=20),"docked" to pilot(10007,42,base=100))
    private val outfits=listOf(fleet(1,43,100,10001,1,2),fleet(2,42,100,10003,3),fleet(3,43,200,0,4))
    private val stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,0,0,0,0)},
        outfits.associateBy {it.id},outfits.associate {it.warehouseId to OutfitWarehouse(it.warehouseId,it.country,OutfitKind.FLEET,it.baseId,it.id,shipStyle=empire)},
        nextShipId=5,social=SocialState(pirates=PirateState(mapOf(1 to PirateFleet(1,10,0,listOf(PirateUnit(900_001,300))),
            2 to PirateFleet(2,20,0,listOf(PirateUnit(900_002,300)))),3),
            coup=CoupState(mapOf(10003 to Plot(10003,CountryId(42),1,revolt=10)),mapOf(2 to CoupActions.LOYAL))))
    private val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),navigators=navigators,planetStocks=stocks)
    private fun bootstrap()=TacticalBootstrap(snapshot,galaxy,viewer,catalog) {person(it)}

    @Test fun everyTacticalUnitCarriesItsActualCampUnderTheSameId() {
        val b=bootstrap()
        val camps=b.unitCamps
        assertEquals(b.units.map {it.id}.toSet(),camps.keys,"one camp per wire unit, nothing more")
        assertEquals(b.units.size,camps.size)
        val c42=Camp(CountryId(42));val c43=Camp(CountryId(43))
        assertEquals(mapOf(
            1 to c43,2 to c43,                         // 43's fleet on the grid
            3 to Camp(CountryId(42),true),           // 42 fleet following its rebel commander (loyalty 50)
            900_001 to Camp.PIRATES,
            1_010_000 to c42,1_010_001 to c43,
            1_010_003 to Camp(CountryId(42),true),   // same-country rebel flagship
            1_010_004 to Camp(Camp.Owner.Independent(10004)),1_010_005 to Camp(Camp.Owner.Independent(10005))),camps)
        assertNotEquals(camps.getValue(1_010_000),camps.getValue(1_010_001),"same native 3, different countries")
        assertNotEquals(camps.getValue(1_010_004),camps.getValue(1_010_005),"unaffiliated individuals are separate")
    }

    @Test fun otherGridsDockedFlagshipsAndAwayFleetsStayOutAndUnitsAreUnchanged() {
        val b=bootstrap()
        for(id in listOf(1_010_006,1_010_007,4,900_002))assertFalse(id in b.unitCamps,"$id")
        // Same IDs and order as before; the wire carries no camp field.
        assertEquals(listOf(1,2,3,900_001,1_010_000,1_010_001,1_010_003,1_010_004,1_010_005),b.units.map {it.id})
    }

    @Test fun flagshipDefinitionsKeepOnlyOneCurrentCampOutfitAfterDismissal() {
        val friendly = fleet(4, 42, 100, 0, 5).copy(aideId = viewer.characterId)
        val secondFriendly = fleet(5, 42, 100, 0, 6).copy(aideId = viewer.characterId)
        val rival = stocks.outfits.getValue(1).copy(aideId = viewer.characterId)
        val rebels = stocks.outfits.getValue(2).copy(aideId = viewer.characterId)
        val distant = fleet(6, 42, 200, 0, 7).copy(aideId = viewer.characterId)
        fun warehouse(outfit: OutfitStock) = OutfitWarehouse(outfit.warehouseId, outfit.country, outfit.kind,
            outfit.baseId, outfit.id, shipStyle = empire)
        val prepared = stocks.copy(bases = stocks.bases + (100 to stocks.bases.getValue(100).copy(owner = Allegiance.Country(CountryId(42)))),
            outfits = stocks.outfits + listOf(friendly, rival, rebels, distant).associateBy { it.id },
            warehouses = stocks.warehouses + listOf(friendly, distant).associate { it.warehouseId to warehouse(it) }, nextShipId = 8)
        fun affiliation(state: PlanetStocks, player: Navigator = viewer): Int {
            val b = TacticalBootstrap(snapshot.copy(planetStocks = state), galaxy, player, catalog) { person(it) }
            assertEquals(TacticalAuthority.camp(player, state), b.unitCamps.getValue(player.unitId))
            val flagship = b.units.single { it.id == player.unitId }
            val packet = ByteBuffer.wrap(TacticsMessages.definitions(player.grid, listOf(flagship)))
            assertEquals(0x0325, packet.getShort(4).toInt())
            return packet.getInt(19)
        }

        assertEquals(friendly.id, affiliation(prepared))
        assertEquals(rebels.id, affiliation(prepared, navigators.getValue("rebel")))
        val ambiguous = prepared.copy(outfits = prepared.outfits + (secondFriendly.id to secondFriendly),
            warehouses = prepared.warehouses + (secondFriendly.warehouseId to warehouse(secondFriendly)))
        assertEquals(0, affiliation(ambiguous))

        val office = OrganizationalOfficePolicy(galaxy, catalog)
        val dismissed = office.set(prepared, OfficeHolding(43, friendly.id, viewer.characterId, CountryId(42)), remove = true)
        assertEquals(0, dismissed.outfits.getValue(friendly.id).aideId)
        assertEquals(viewer.characterId, dismissed.outfits.getValue(rebels.id).aideId)
        assertEquals(0, affiliation(dismissed))
        assertEquals(0, affiliation(dismissed.copy(outfits = dismissed.outfits + (rival.id to rival.copy(aideId = 0)))))
        assertEquals(0, affiliation(dismissed.copy(outfits = dismissed.outfits + (rebels.id to rebels.copy(aideId = 0)))))
    }
}
