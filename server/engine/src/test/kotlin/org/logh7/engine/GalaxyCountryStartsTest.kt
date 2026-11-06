package org.logh7.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class GalaxyCountryStartsTest {
    @TempDir lateinit var directory:Path
    private fun world(schema:String,extra:String):Galaxy {
        val file=directory.resolve("world.properties")
        Files.writeString(file,"""schema=$schema
unmapped.cells=local-space-default
terrain=none
systems=2
system.0.kind=3
system.0.grid=2487
system.0.star=4
system.0.name=First
system.1.kind=4
system.1.grid=2490
system.1.star=4
system.1.name=Second
bases=0
start.empire=2487
start.alliance=2490
$extra
""")
        return Galaxy.load(file)
    }
    @Test fun currentWorldsKeepTheirStartsAndNewCountriesHaveNoImplicitFallback() {
        val galaxy=world("1","")
        assertEquals(mapOf(CountryId(2) to 2487,CountryId(3) to 2490),galaxy.countryStarts)
        assertNull(galaxy.countryStarts[CountryId(42)])
    }
    @Test fun schemaTwoSupportsPerCountryStartsWithoutChangingTheLegacyProjection() {
        val galaxy=world("2","start.country.42=2490\nstart.country.2=2490")
        assertEquals(2490,galaxy.countryStarts[CountryId(42)]);assertEquals(2490,galaxy.countryStarts[CountryId(2)])
        assertEquals(2490,galaxy.starts.getValue(Power.EMPIRE))
    }
    @Test fun invalidStartsAndUnversionedExtensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> {world("1","start.country.42=2490")}
        for(key in listOf("0","-1","042","+42","2147483648","oops"))
            assertFailsWith<IllegalArgumentException> {world("2","start.country.$key=2490")}
        assertFailsWith<IllegalArgumentException> {world("2","start.country.42=2488")}
        assertFailsWith<IllegalArgumentException> {world("2",(10..359).joinToString("\n") {"start.country.$it=2490"})}
        assertFailsWith<IllegalArgumentException> {world("3","")}
    }
}
