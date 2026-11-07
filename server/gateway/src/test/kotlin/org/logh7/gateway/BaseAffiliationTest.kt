package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** 032f mode 12: public base owners by actual country beside the native display projection. Author: Peppone Choi. */
class BaseAffiliationTest {
    // 42 and 43 both display as 3 (alliance code) yet are separate countries.
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"other_country","Other","alliance",3,0,"alliance")))}
    private fun base(id:Int,grid:Int)=GalaxyBase(id,grid,80,0,"B$id",0,20f,86400,0,0f,2f)
    private val systems=listOf(GalaxySystem(3,10,4,"Empire",2),GalaxySystem(4,20,4,"Alliance",3),GalaxySystem(5,30,4,"Free",0))
    private val bases=listOf(base(1177,20),base(1049,10),base(1050,10),base(2000,30),base(2001,30))
    private val galaxy=Galaxy(systems,bases,mapOf(Power.EMPIRE to 10,Power.ALLIANCE to 20),
        initialOwners=mapOf(2000 to Allegiance.Country(CountryId(42)),2001 to Allegiance.Country(CountryId(43))))
    private val world=WorldBootstrap(galaxy,catalog)
    private val query=OutfitPartyMessages.Query(0,77,BaseAffiliationMessages.MODE)
    private fun owner(id:Int,country:Int,power:Int,camp:Int=0)=BaseAffiliationMessages.Owner(id,country,power,camp)
    private fun stocks(vararg owners:Pair<Int,Allegiance>)=PlanetStocks(1,0,bases.associate {b->b.id to
        PlanetStock(b.id,owners.toMap()[b.id] ?: galaxy.initialOwners.getValue(b.id),0,0,0,0)})

    @Test fun initialOwnersWithoutStocksAreSortedAndKeepActualIds() {
        assertEquals(listOf(owner(1049,2,2),owner(1050,2,2),owner(1177,3,3),owner(2000,42,3),owner(2001,43,3)),
            world.baseAffiliationOwners())
        // The display projection is the one 031f already sends for the same bases.
        for(o in world.baseAffiliationOwners())
            assertEquals(o.power,ByteBuffer.wrap(world.conditions(listOf(o.id))).get(11).toInt(),"${o.id}")
    }

    @Test fun currentOwnersFollowOccupationAndReportNoCountryAsZero() {
        val captured=stocks(1049 to Allegiance.Country(CountryId(43)),1177 to Allegiance.Country(CountryId(42)),
            2000 to Allegiance.Unspecified,2001 to Allegiance.Neutral).let {s->
            s.copy(bases=s.bases+(1050 to s.bases.getValue(1050).copy(camp=1)))}
        assertEquals(listOf(owner(1049,43,3),owner(1050,2,2,1),owner(1177,42,3),owner(2000,0,0),owner(2001,0,1)),
            world.baseAffiliationOwners(captured))
        // The native power byte never carries 42/43; only the separate country field does.
        assertTrue(world.baseAffiliationOwners(captured).all {it.power in 0..4})
    }

    @Test fun framesWrapEachReplyBodyIn032fAndValidateTheQuery() {
        val owners=world.baseAffiliationOwners()
        val frames=world.baseAffiliations(query)
        val bodies=BaseAffiliationMessages.replies(query,owners)
        assertEquals(bodies.size,frames.size)
        frames.zip(bodies).forEach {(frame,body)->assertContentEquals(LoginMessages.sessionMessage(0x032f,body),frame)}
        for(bad in listOf(query.copy(outfit=1),query.copy(base=0),query.copy(base=65536),query.copy(mode=4)))
            assertFailsWith<IllegalArgumentException>("$bad") {world.baseAffiliations(bad)}
    }

    @Test fun everyPublicBaseUpToTheLimitAppearsOnceInOrder() {
        val many=(1..350).map {base(65536-it,30)}
        val full=WorldBootstrap(Galaxy(systems,many,mapOf(Power.EMPIRE to 10,Power.ALLIANCE to 20)),catalog)
        val ids=full.baseAffiliationOwners().map {it.id}
        assertEquals(many.map {it.id}.sorted(),ids)
        assertEquals(ids.distinct(),ids)
        assertTrue(full.baseAffiliations(query).isNotEmpty())
    }

    @Test fun anUnregisteredCountryFailsLikeThePublicConditions() {
        // Same policy as 031f: an owner missing from the catalogue is refused, not reported as 0 or a display code.
        val removed=stocks(2000 to Allegiance.Country(CountryId(44)))
        assertFailsWith<IllegalArgumentException> {world.conditions(listOf(2000),removed)}
        assertFailsWith<IllegalArgumentException> {world.baseAffiliationOwners(removed)}
    }
}
