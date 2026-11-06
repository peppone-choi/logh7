package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class SystemOwnershipBootstrapTest {
    @Test fun initialOwnerProjectionAndFacilitiesUseTheInjectedCatalogBeforeStocksExist() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487},
            initialOwners=mapOf(1000 to Allegiance.Country(CountryId(42))))
        val original=CountryCatalogLoader.load()
        val catalog=CountryCatalog(original.calendars.values.toList(),original.families.values.toList(),
            original.countries.values.toList()+original.countries.getValue(3).copy(id=42,key="custom",nativePower=3))
        val world=WorldBootstrap(galaxy,catalog)
        assertEquals(3,world.conditions(listOf(1000))[11].toInt())
        assertEquals(galaxy.facilitiesAt(1000,PlanetStocks(1,0,mapOf(1000 to
            PlanetStock(1000,Allegiance.Country(CountryId(42)),0,1,2,3))),catalog),galaxy.facilitiesAt(1000,countries=catalog))
        val occupied=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1,2,3)))
        assertEquals(2,world.conditions(listOf(1000),occupied)[11].toInt())
    }
    @Test fun countryOwnerUsesCatalogAppearanceWithoutTurningNeutralOrPiratesIntoCountries() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val original=CountryCatalogLoader.load()
        val other=original.countries.getValue(2).copy(id=42,key="other_empire")
        val catalog=CountryCatalog(original.calendars.values.toList(),original.families.values.toList(),
            original.countries.values.toList()+other)
        val world=WorldBootstrap(galaxy,catalog)
        fun stock(owner:Allegiance)=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,owner,1,100,200,300)))
        val country=stock(Allegiance.Country(CountryId(42)))
        assertContentEquals(world.conditions(listOf(1000),stock(Allegiance.Country(CountryId(2)))),
            world.conditions(listOf(1000),country))
        assertEquals(CountryId(42),country.bases.getValue(1000).country)
        for((owner,code) in listOf(Allegiance.Unspecified to 0,Allegiance.Neutral to 1,Allegiance.Pirates to 4)) {
            val message=world.conditions(listOf(1000),stock(owner))
            assertEquals(code,message[11].toInt())
            assertEquals(1,message[12].toInt())
        }
    }
    @Test fun loginPublishesEveryCurrentOwnerInNativeFourRecordBatches() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Other",3)),
            (1..7).map { GalaxyBase(it,if(it<=3)2487 else 2488,80,0,"Base$it",0,20f,86400,0,0f,2f) },
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
        val state=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,if(it.id==5)1 else 0,0,0,0)})
        val world=WorldBootstrap(galaxy)
        fun conditions(directory:Boolean)=world.initialize(2487,directory,state).filter {
            ByteBuffer.wrap(it).getShort(4).toInt() and 65535 == 0x031f
        }
        val all=conditions(true)
        assertEquals(listOf(4,3,3),all.map {it[6].toInt()})
        assertEquals(listOf(1,5,1),all.map {ByteBuffer.wrap(it).getInt(7)})
        assertEquals(2,all[1][11].toInt()) // current Empire owner, despite Alliance seed
        assertEquals(1,all[1][12].toInt()) // current rebel camp survives
        assertEquals(listOf(3),conditions(false).map {it[6].toInt()})
    }
}
