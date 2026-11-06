package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class FacilityGuideWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,2000,3000)),deploymentInitialized=true)
    private val query=OutfitPartyMessages.Query(104938,16,6)
    private fun fields(packet:ByteArray):List<String> {
        val b=ByteBuffer.wrap(packet);assertEquals(0x032f,b.getShort(4).toInt());b.position(6+19)
        assertEquals(10,b.get().toInt())
        return (0..9).map {i->assertEquals(i+1,b.int);assertEquals(i,b.get().toInt());assertEquals(0,b.get().toInt())
            val size=b.get().toInt();if(size==0)"" else buildString {repeat(size-1){append(b.char)};assertEquals('\u0000',b.char)}}
    }
    @Test fun livePublishedKindAndInstanceEditsAreReadAtEachQueryWithoutChangingTheFacilityIdentity() {
        val published=PublishedFacilityDefinitions()
        val world=WorldBootstrap(galaxy,facilityDefinitions=published::current)
        val original=fields(world.facilityGuide(query,2,stock))
        assertEquals("통수본부",original.drop(2).joinToString(""))
        val changed=published.current().applied(FacilityDefinitionEdit.Kind(FacilityKindDefinition(37,"새 본부","함대와 물자 배정을 관리합니다.","담당 직무가 필요합니다.")))!!
        published.publish(changed)
        assertEquals("새 본부",fields(world.facilityGuide(query,2,stock)).drop(2).joinToString(""))
        val overridden=changed.applied(FacilityDefinitionEdit.Override(FacilityOverride(104938,"오딘 전략본부","이 거점의 안내입니다.")))!!
        published.publish(overridden)
        assertEquals("오딘 전략본부",fields(world.facilityGuide(query,2,stock)).drop(2).joinToString(""))
        assertEquals("이 거점의 안내입니다.",fields(world.facilityGuide(query.copy(base=17),2,stock)).drop(2).joinToString(""))
        assertTrue(world.facilityCommands.commands(37).contains("할당"))
        assertTrue(world.facilityCommands.commands(0).contains(FacilityCommandNames.name(21)))
        for(kind in 0..38)assertTrue(world.facilityCommands.commands(kind).joinToString(" · ").length<=192,"kind $kind")
        assertFalse(published.publish(changed));assertEquals(2,published.current().revision)
        val captured=stock.copy(bases=stock.bases.mapValues {it.value.copy(owner=Allegiance.Neutral)})
        assertEquals("시설 정보 없음",fields(world.facilityGuide(query,2,captured)).drop(2).joinToString(""))
        assertEquals(stock.bases.getValue(1049).supplies,1000)
    }
    @Test fun encryptedFacilityDescriptionQueryIsReadOnlyAndUsesTheOriginalBoundedDecoderLayout()=runBlocking<Unit> {
        val store=object:PlanetStockStore {override fun load()=stock;override fun save(stocks:PlanetStocks)=Unit}
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=store).use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            for(ignored in 0..300)if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01)break
            val fragments=(0..8).map {page->
                val body=ByteBuffer.allocate(11).putShort(0x032e).putInt(query.outfit).putInt(16+page).put(6).array()
                val result=fields(client.request(body,0x032f,"facilityGuide"))
                assertEquals("0",result[0]);result.drop(2).joinToString("")
            }
            assertEquals("통수본부",fragments[0]);assertTrue(fragments.subList(1,4).joinToString("").isNotBlank())
            assertTrue(fragments.subList(7,9).joinToString("").contains("할당"))
        }
    }
}
