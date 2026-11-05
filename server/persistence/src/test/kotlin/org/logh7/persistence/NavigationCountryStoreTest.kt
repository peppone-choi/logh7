package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NavigationCountryStoreTest {
    @TempDir lateinit var directory:Path

    @Test fun oldPowerNamesBecomeExactCountryAndStyleIdsWithoutForcingANewFormat() {
        val path=directory.resolve("navigation")
        val old="LOGH7-NAV-1\npilot\t1\t10000\tEMPIRE\t2017\t800\t700\t900\t4\n"+
            "other\t1\t10001\tALLIANCE\t2018\t600\t500\t800\t3\n"
        Files.writeString(path,old)
        FileNavigationStore(path).use {store->
            val players=store.load()
            assertEquals(CountryId(2),players.getValue("pilot").country)
            assertEquals(ShipStyleId("empire"),players.getValue("pilot").shipStyle)
            assertEquals(CountryId(3),players.getValue("other").country)
            assertEquals(ShipStyleId("alliance"),players.getValue("other").shipStyle)
            store.save(players)
            assertFalse(Files.readString(path).startsWith("LOGH7-NAV-20"))
            assertEquals(players,store.load())
        }
    }

    @Test fun newCountriesNoMembershipAndIndependentStylesKeepTheirWholeNavigationStateOnReopening() {
        val path=directory.resolve("navigation")
        val legacy=Navigator(1,10000,Power.EMPIRE,2017,800,700,900,moves=4,cpRecoveryAt=100,
            hull=FlagshipHull(Flagships.forPower(2).first().kind,100),supplies=9876,pcpUsed=37,mcpUsed=65)
        val motion=TacticalMotion(2017,100,TacticalPose(TacticalPoint(0f,0f),0f),1f,0f,
            listOf(TacticalPoint(0f,10f),TacticalPoint(0f,0f)),turnRate=1f)
        val moving=legacy.copy(country=CountryId(42),cpPaused=true,motion=motion,
            energy=TacticalEnergy(listOf(100)+List(10){0}))
        val players=mapOf("pilot" to moving,"no_country" to legacy.copy(characterId=10001,country=null),
            "other_style" to legacy.copy(characterId=10002,shipStyle=ShipStyleId("alliance"),
                hull=FlagshipHull(Flagships.forPower(3).first().kind,100)),
            "future" to legacy.copy(characterId=10003,hull=null,shipStyle=ShipStyleId("future_style")))
        FileNavigationStore(path).use {it.save(players)}
        assertTrue(Files.readString(path).startsWith("LOGH7-NAV-20\n"))
        FileNavigationStore(path).use {assertEquals(players,it.load())}
        val world=directory.resolve("world")
        val stock=PlanetStocks(1,0,emptyMap())
        FileWorldStateStore(world).use {it.saveWorld(players,stock)}
        FileWorldStateStore(world).use {
            assertEquals(players,it.loadNavigation())
            assertEquals(stock,it.loadStocks())
        }
    }

    @Test fun malformedCountriesStylesAndDisguisedLegacyRowsAreRejectedWithoutRewritingTheInput() {
        val path=directory.resolve("navigation")
        val player=Navigator(1,10000,CountryId(42),2017,800,700,900,shipStyle=ShipStyleId("empire"))
        FileNavigationStore(path).use {it.save(mapOf("pilot" to player))}
        val valid=Files.readString(path)
        val invalid=listOf("0","-1","042","+42","2147483648","EMPIRE").map {
            valid.replace("\t42\t2017\t","\t$it\t2017\t")
        }+listOf("","Empire","two styles").map {valid.replace("\tempire\n","\t$it\n")}+
            valid.replace("LOGH7-NAV-20","LOGH7-NAV-19")
        for(text in invalid) {
            assertNotEquals(valid,text)
            Files.writeString(path,text)
            assertFails {FileNavigationStore(path).use {it.load()}}
            assertEquals(text,Files.readString(path))
        }
    }
}
