package org.logh7.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** World ownership is data, not the original system appearance. Author: Peppone Choi. */
class GalaxyInitialOwnersTest {
    @TempDir lateinit var directory:Path
    private fun world(schema:Int=3,extra:String=""):Galaxy {
        val path=directory.resolve("world.properties")
        Files.writeString(path,"""schema=$schema
unmapped.cells=local-space-default
terrain=none
systems=1
system.0.kind=3
system.0.grid=2487
system.0.star=4
system.0.name=Home
system.0.power=2
bases=2
base.0.id=1000
base.0.grid=2487
base.0.model=80
base.0.kind=0
base.0.name=First
base.0.class=0
base.0.radius=20
base.0.cycle=86400
base.0.direction=0
base.0.angle=0
base.0.diameter=2
base.1.id=1001
base.1.grid=2487
base.1.model=80
base.1.kind=0
base.1.name=Second
base.1.class=0
base.1.radius=20
base.1.cycle=86400
base.1.direction=0
base.1.angle=0
base.1.diameter=2
start.empire=2487
start.alliance=2487
$extra
""")
        return Galaxy.load(path)
    }

    @Test fun oldSchemasKeepTheirOwnersAndSchemaThreeSeparatesCountryIdsFromNonstates() {
        for(schema in 1..2)assertTrue(world(schema).initialOwners.values.all {it.country==CountryId(2)})
        for((text,owner) in listOf("C:42" to Allegiance.Country(CountryId(42)),"C:1" to Allegiance.Country(CountryId(1)),
            "U" to Allegiance.Unspecified,"N" to Allegiance.Neutral,"P" to Allegiance.Pirates)) {
            val g=world(extra="base.0.owner=$text\nstart.country.42=2487")
            assertEquals(owner,g.initialOwners.getValue(1000));assertEquals(CountryId(2),g.initialOwners.getValue(1001).country)
            assertEquals(2,g.conditions.getValue(1000).power,"original display data is not the country identity")
            assertEquals(2487,g.countryStarts[CountryId(42)])
        }
    }

    @Test fun invalidOwnersUnknownBasesAndUnversionedExtensionsAreRejected() {
        for(schema in 1..2)assertFailsWith<IllegalArgumentException> {world(schema,"base.0.owner=C:42")}
        for(text in listOf("", "42","C:0","C:-1","C:042","C:+42","C:2147483648","C:4294967296","unknown"))
            assertFailsWith<IllegalArgumentException>(text) {world(extra="base.0.owner=$text")}
        for(index in listOf("-1","2","00","+0","oops"))assertFailsWith<IllegalArgumentException> {
            world(extra="base.$index.owner=C:42")
        }
        val g=world()
        assertFailsWith<IllegalArgumentException> {Galaxy(g.systems,g.bases,g.starts,initialOwners=mapOf(999 to Allegiance.Neutral))}
    }

    @Test fun ownerMapsCannotChangeAfterConstruction() {
        val g=world();val input=mutableMapOf(1000 to Allegiance.Country(CountryId(42)))
        val seeded=Galaxy(g.systems,g.bases,g.starts,initialOwners=input)
        input.clear()
        assertEquals(CountryId(42),seeded.initialOwners.getValue(1000).country)
        assertFailsWith<UnsupportedOperationException> {(seeded.initialOwners as MutableMap).clear()}
    }

    @Test fun onlyAFreshGenerationUsesTheSeedOwnersAndSavedOccupationWinsOnReopening() {
        val g=world(extra="base.0.owner=C:42\nbase.1.owner=N")
        var saved:PlanetStocks?=null
        val store=object:PlanetStockStore {override fun load()=saved;override fun save(stocks:PlanetStocks){saved=stocks}}
        val source=PlanetProduction(g,store,0,PlanetProductionPolicy()).settle(1,0)
        assertEquals(CountryId(42),source.bases.getValue(1000).country)
        assertEquals(Allegiance.Neutral,source.bases.getValue(1001).owner)
        saved=source.copy(bases=source.bases+(1000 to source.bases.getValue(1000).copy(owner=Allegiance.Country(CountryId(43)))))
        val reopened=PlanetProduction(g,store,0,PlanetProductionPolicy())
        assertEquals(CountryId(43),reopened.settle(1,0).bases.getValue(1000).country)
        assertEquals(CountryId(42),reopened.settle(2,0).bases.getValue(1000).country)
    }

    @Test fun forcesAndLegacyCapitalDefaultsFollowTheHistoricalOwner() {
        val original=world()
        val bases=original.bases.map {if(it.id==1000)it.copy(name=Capital.EMPIRE.baseName) else it}
        val g=Galaxy(original.systems,bases,original.starts,initialOwners=mapOf(1000 to Allegiance.Country(CountryId(42)),1001 to Allegiance.Neutral))
        val forces=GridForces.collect(g,1,emptyList(),null).getValue(2487)
        assertEquals(setOf(CountryId(42)),forces.map {it.camp.country}.toSet())
        val stocks=PlanetProduction(g,null,0,PlanetProductionPolicy()).settle(1,0)
        assertNull(WorldTerritory(g).ending(stocks,setOf(CountryId(2),CountryId(3),CountryId(42))),
            "a new seed owner has not captured the old scenario's capital")
    }
}
