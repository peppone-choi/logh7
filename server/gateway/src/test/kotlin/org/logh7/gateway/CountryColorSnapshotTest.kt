package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.assertContentEquals

class CountryColorSnapshotTest {
    @Test fun `late polling reads cannot replace a saved color and newer inherit resets remain valid`() {
        val world=WorldBootstrap(Galaxy(listOf(GalaxySystem(3,1,0,"Fixture")),emptyList(),Power.entries.associateWith {1}))
        val query=OutfitPartyMessages.Query(2,17,4)
        fun appearance(revision:Long,map:Int?)=CountryAppearance(2,"Empire","EMPIRE",CountryColors(map=map),revision)
        fun expected(map:String,revision:Long)=LoginMessages.sessionMessage(0x032f,
            CountryColorMessages.reply(query,2,listOf("inherit","inherit",map,"inherit"),revision))
        val input=mutableListOf(appearance(4,0xc46bff))
        world.publishCountryAppearances(input)
        input.clear()
        assertContentEquals(expected("C46BFF",4),world.countryColors(query,2))
        world.publishCountryAppearances(listOf(appearance(3,0xffaa22)))
        assertContentEquals(expected("C46BFF",4),world.countryColors(query,2))
        world.publishCountryAppearances(listOf(appearance(5,null)))
        assertContentEquals(expected("inherit",5),world.countryColors(query,2))
    }
}
