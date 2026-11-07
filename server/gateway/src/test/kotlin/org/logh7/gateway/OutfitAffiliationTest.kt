package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** Mode 13 fleet affiliations: actual country within the viewer's existing visibility. Author: Peppone Choi. */
class OutfitAffiliationTest {
    // 42 and 43 both display as 3; 2 is an original country.
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"other_country","Other","alliance",3,0,"alliance")))}
    private fun base(id:Int,grid:Int)=GalaxyBase(id,grid,80,0,"B$id",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,10,4,"Here",2),GalaxySystem(4,20,4,"There",3),GalaxySystem(5,30,4,"Far",3)),
        listOf(base(100,10),base(200,20),base(300,30)),mapOf(Power.EMPIRE to 10,Power.ALLIANCE to 20))
    private val world=WorldBootstrap(galaxy,catalog)
    private val empire=ShipStyleId("empire")
    private fun fleet(id:Int,country:Int,base:Int,commander:Int=0)=OutfitStock(id,CountryId(country),OutfitKind.FLEET,base,id+1000,
        commanderId=commander,shipStyle=empire)
    private val outfits=listOf(fleet(1,42,100,500),fleet(2,43,100),fleet(3,43,200),fleet(4,42,200),fleet(5,2,100),fleet(6,42,300))
    private fun stocks(list:List<OutfitStock> = outfits,generation:Long=1,social:SocialState=SocialState())=
        PlanetStocks(generation,0,listOf(100,200,300).associateWith {PlanetStock(it,2,0,0,0,0)},
            outfits=list.associateBy {it.id},social=social,warehouses=list.associate {it.warehouseId to
                OutfitWarehouse(it.warehouseId,it.country,it.kind,it.baseId,it.id,shipStyle=it.shipStyle)})
    private fun viewer(country:Int?,grid:Int=10,generation:Long=1)=Navigator(generation,9000,country?.let(::CountryId),grid,1000,1000,1000,shipStyle=empire)
    private fun owner(id:Int,country:Int,power:Int,camp:Int=0)=OutfitAffiliationMessages.Owner(id,country,power,camp)
    private fun ids(s:PlanetStocks,v:Navigator)=world.outfitAffiliationOwners(s,v).map {it.id}.sorted()
    private val query=OutfitPartyMessages.Query(77,0,OutfitAffiliationMessages.MODE)

    @Test fun sameDisplayCountriesStaySeparateAndForeignOffGridOutfitsAreAbsent() {
        val owners=world.outfitAffiliationOwners(stocks(),viewer(42)).sortedBy {it.id}
        // Here: 42, 43 and 2 on the viewer's grid; own 42 off-grid (4, 6); foreign 43 off-grid (3) is not listed.
        assertEquals(listOf(owner(1,42,3),owner(2,43,3),owner(4,42,3),owner(5,2,2),owner(6,42,3)),owners)
        assertTrue(owners.none {it.power>4},"the native power byte never carries 42/43")
    }

    @Test fun movesAndCapturesAreReadAtQueryTime() {
        val moved=outfits.map {if(it.id==3)it.copy(baseId=100) else if(it.id==6)it.copy(country=CountryId(43)) else it}
        assertEquals(listOf(1,2,3,4,5),ids(stocks(moved),viewer(42)),"3 arrived on the grid; 6 changed hands off-grid")
        // The viewer's own move: the old grid's foreign fleets (2, 5) drop out; only own 42 fleets remain visible.
        assertEquals(listOf(1,4,6),ids(stocks(),viewer(42,grid=30)))
    }

    @Test fun rebelCampIsSeparateFromTheActualCountry() {
        val coup=CoupState(mapOf(500 to Plot(500,CountryId(42),1,revolt=10)),mapOf(1 to CoupActions.LOYAL))
        val rebel=world.outfitAffiliationOwners(stocks(social=SocialState(coup=coup)),viewer(42)).single {it.id==1}
        assertEquals(owner(1,42,3,1),rebel)
    }

    @Test fun piratesUseTheirVirtualIdOnTheCurrentGridOnly() {
        val pirates=PirateState(mapOf(1 to PirateFleet(1,10,0,listOf(PirateUnit(900_001,300))),
            2 to PirateFleet(2,20,0,listOf(PirateUnit(900_002,300)))),3)
        val owners=world.outfitAffiliationOwners(stocks(social=SocialState(pirates=pirates)),viewer(42))
        assertEquals(listOf(owner(PirateActions.OUTFIT_BASE+1,0,4)),owners.filter {it.id>PirateActions.OUTFIT_BASE})
    }

    @Test fun anUnaffiliatedViewerSeesOnlyTheCurrentGridIncludingSharedBases() {
        assertEquals(listOf(1,2,5),ids(stocks(),viewer(null)),"three countries share base 100")
        assertEquals(listOf(3,4),ids(stocks(),viewer(null,grid=20)))
    }

    @Test fun framesUseTheViewersGridAndGenerationAndPassTheCursorThrough() {
        val many=(1..130).map {fleet(it,42,200)}
        val s=stocks(many);val v=viewer(42)
        for(cursor in listOf(0,60,120)) {
            val q=query.copy(base=cursor)
            val expected=OutfitAffiliationMessages.replies(q,10,1,world.outfitAffiliationOwners(s,v))
            assertEquals(expected.size,world.outfitAffiliations(q,s,v).size)
            world.outfitAffiliations(q,s,v).zip(expected).forEach {(frame,body)->
                assertContentEquals(LoginMessages.sessionMessage(0x032f,body),frame)}
        }
        assertEquals(130,world.outfitAffiliationOwners(s,v).size,"the full allow-list goes to the protocol pager")
        for(bad in listOf(query.copy(outfit=0),query.copy(outfit=65536),query.copy(base=-1),query.copy(mode=12)))
            assertFailsWith<IllegalArgumentException>("$bad") {world.outfitAffiliations(bad,s,v)}
    }

    @Test fun staleGenerationsAndUnregisteredOwnersAreRefused() {
        assertFailsWith<IllegalArgumentException> {world.outfitAffiliationOwners(stocks(),viewer(42,generation=2))}
        assertFailsWith<IllegalArgumentException> {world.outfitAffiliationOwners(stocks(outfits+fleet(7,44,100)),viewer(42))}
    }
}
