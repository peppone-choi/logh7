package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class FleetOfficeCatalogueTest {
    @Test fun serverRulesAndNativeCatalogueAgreeOnAllTenFleetCards() {
        val b=ByteBuffer.wrap(WorldMessages.cards());assertEquals(261,b.short.toInt())
        repeat(261) {card->
            val start=b.position();assertEquals(card,b.short.toInt())
            FleetOffice.of(card)?.let {role->
                assertEquals(role.power,b.get(start+3).toInt());assertEquals(11,b.get(start+5).toInt())
                assertEquals(role.authority,b.getShort(start+6).toInt())
                assertEquals(role.lowest,b.get(start+10).toInt());assertEquals(2,b.get(start+11).toInt())
                assertEquals(role.power,BootstrapMessages.OfficeCard(card,200).power)
            }
            b.position(start+18);val count=b.get().toInt() and 255;b.position(b.position()+count*2)
        }
        assertFalse(b.hasRemaining())
    }
    @Test fun aFullFleetRosterKeepsTenDistinctPeopleAndHugePublicRostersFailWithinTheNativeBuffer() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000,aideId=10001,
                staff=FleetStaff(10002,10003,(10004..10009).toList()))),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
        fun person(id:Int)=CharacterMessages.Generate(4,id.toLong(),2,0,0,"Person$id","",30,1,1,1,List(8){50},0,0,0,11,0,0,"Ship",0)
        val world=WorldBootstrap(galaxy)
        val party=world.outfitParty(OutfitPartyMessages.Query(200,1049,0),2,stocks,characterById=::person)
        assertEquals(10,party[25].toInt())
        val many=stocks.copy(outfits=(1..12).associate {i->i to OutfitStock(i,2,OutfitKind.FLEET,1049,300+i,
            staff=FleetStaff(officers=(1..6).map {10000+i*10+it}))},
            warehouses=(1..12).associate {i->(300+i) to OutfitWarehouse(300+i,2,OutfitKind.FLEET,1049,i)})
        val rejected=world.cardCharacters(42,many,::person)
        assertEquals(1,ByteBuffer.wrap(rejected).int);assertEquals(0,rejected[8].toInt())
    }
}
