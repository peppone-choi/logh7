package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class OriginalCandidateEditorTest {
    @TempDir lateinit var directory:Path

    private class Store:OriginalCandidateStore {
        var office=true
        var allianceOffice=false
        var stadenOffice=false
        var paettaOffice=false
        var fogelOffice=false
        val saved=mutableMapOf<Long,OriginalCandidateEdit>()
        override val supportsStartingOffice get()=office
        override val supportsAllianceStartingOffice get()=allianceOffice
        override val supportsStadenStartingOffice get()=stadenOffice
        override val supportsPaettaStartingOffice get()=paettaOffice
        override val supportsFogelStartingOffice get()=fogelOffice
        override fun load()=saved.values.toList()
        override fun save(id:Long,expected:Long,profile:OriginalCandidateProfile,country:CountryId?):OriginalCandidateEdit? {
            if(expected!=(saved[id]?.revision ?: 0))return null
            return OriginalCandidateEdit(id,expected+1,profile,country).also {saved[id]=it}
        }
    }

    private class Editor(private val client:HttpClient,private val port:Int,private val token:String) {
        fun request(path:String,values:Map<String,String>?=null):HttpResponse<String> {
            val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Authorization","Bearer $token")
            if(values==null)builder.GET() else {
                val body=values.entries.joinToString("&"){URLEncoder.encode(it.key,Charsets.UTF_8)+"="+URLEncoder.encode(it.value,Charsets.UTF_8)}
                builder.header("Origin","http://127.0.0.1:$port").header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
            }
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
        }
        fun save(values:Map<String,String>)=request("/admin/original-candidates",values).statusCode()
        fun page(query:String)=request("/admin/original-candidates?$query").body()
    }

    private val abilities=List(8){50}

    private fun withEditor(store:Store,block:Editor.()->Unit) {
        val published=PublishedOriginalCandidates(listOf(
            OriginalCandidateDefinition(1,2,OriginalCandidateProfile("제국","후보",1,13,abilities)),
            OriginalCandidateDefinition(2,3,OriginalCandidateProfile("동맹","후보",100001,13,abilities))))
        val source=object:SnapshotSource{override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink{override suspend fun submit(command:WorldCommand)=error("Candidate edits never queue world commands")}
        val port=ServerSocket(0).use {it.localPort};val token="fixture-candidate-token"
        OpsAudit.open(directory.resolve("audit.jsonl")).use {audit->startOpsApi(sink,source,port,audit,token,
            originalCandidateStore=store,originalCandidates=published).use {HttpClient.newHttpClient().use {client->
            Editor(client,port,token).block()
        }}}
    }

    @Test fun allianceVucockOfficeFollowsV17CapabilityAndStaysBound() {
        val store=Store()
        withEditor(store) {
            val vucock=OriginalStartingOffice.VUCOCK_FLEET_5
            val form=mapOf("operator" to "peppone","candidate" to "1003","revision" to "0","create" to "1","country" to "3",
                "surname" to "뷰코크","given" to "알렉산드르","face" to "100001","rank" to "5","peerage" to "0",
                "startingOffice" to vucock.key)+abilities.indices.associate {"ability$it" to "60"}

            // V16 only: the imperial office stays selectable, the alliance office asks for V17 and refuses to save.
            assertTrue(page("new=2").contains("value=\"${OriginalStartingOffice.REINHARD_FLEET_2.key}\""))
            val v16=page("new=3")
            assertTrue(v16.contains("정의 DB V17을 적용"));assertFalse(v16.contains("name=\"startingOffice\""))
            assertEquals(503,save(form));assertTrue(store.saved.isEmpty())
            store.office=false
            assertTrue(page("new=3").contains("정의 DB V16·V17을 적용"));assertTrue(page("new=2").contains("정의 DB V16을 적용"))

            // V17: the alliance form offers Vucock only for country 3 at rank 5 and the same checks reject other routes.
            store.office=true;store.allianceOffice=true
            val v17=page("new=3")
            assertTrue(v17.contains("value=\"${vucock.key}\""));assertTrue(v17.contains("뷰코크 · 제5함대 사령관 (산타 아나)"))
            assertTrue(v17.contains("data-country=\"3\" data-rank=\"5\""));assertFalse(v17.contains(OriginalStartingOffice.REINHARD_FLEET_2.key))
            assertFalse(v17.contains(OriginalStartingOffice.STADEN_FLEET_4.key))
            assertFalse(page("new=2").contains(vucock.key))
            assertEquals(400,save(form+("rank" to "4")))
            assertEquals(400,save(form+mapOf("country" to "2","face" to "1")))
            assertEquals(400,save(form+mapOf("candidate" to "2","create" to "0")))
            assertTrue(store.saved.isEmpty())
            assertEquals(200,save(form))
            assertEquals(vucock,store.saved.getValue(1003).profile.startingOffice)

            // Once published the office is shown as bound and cannot be cleared, while other edits keep it.
            val bound=page("candidate=1003")
            assertTrue(bound.contains("<strong>뷰코크 · 제5함대 사령관 (산타 아나)</strong>"));assertFalse(bound.contains("name=\"startingOffice\""))
            val edit=form-"create"-"startingOffice"+("revision" to "1")
            assertEquals(400,save(edit+("startingOffice" to "")))
            assertEquals(200,save(edit+("given" to "시드니")))
            assertEquals(vucock,store.saved.getValue(1003).profile.startingOffice)
            assertTrue(page("new=3").contains("후보 1003에 이미 지정"))
        }
    }

    @Test fun empireOffersReinhardAndStadenSeparatelyAcrossV18() {
        val store=Store().apply {allianceOffice=true}
        withEditor(store) {
            val reinhard=OriginalStartingOffice.REINHARD_FLEET_2
            val staden=OriginalStartingOffice.STADEN_FLEET_4
            val stadenLabel="슈타덴 · 제4함대 사령관 (하펜)"
            val form=mapOf("operator" to "peppone","candidate" to "1003","revision" to "0","create" to "1","country" to "2",
                "surname" to "슈타덴","given" to "후보","face" to "1","rank" to "5","peerage" to "0",
                "startingOffice" to staden.key)+abilities.indices.associate {"ability$it" to "60"}

            // Before V18: Reinhard stays selectable, Staden only asks for V18 and its save is refused.
            val v17=page("new=2")
            assertTrue(v17.contains("value=\"${reinhard.key}\""));assertFalse(v17.contains("value=\"${staden.key}\""))
            assertTrue(v17.contains("시작 직책 ${stadenLabel}을 지정하려면 정의 DB V18을 적용"))
            assertFalse(page("new=3").contains("V18"))
            assertEquals(503,save(form));assertTrue(store.saved.isEmpty())
            store.office=false
            val v15=page("new=2")
            assertTrue(v15.contains("정의 DB V16·V18을 적용"));assertTrue(v15.contains("정의 DB V16을 적용"))
            assertFalse(v15.contains("name=\"startingOffice\""))

            // V18: one select offers both imperial offices, each bound to country 2 at rank 5; Vucock stays on the alliance side.
            store.office=true;store.stadenOffice=true
            val v18=page("new=2")
            assertEquals(1,Regex("name=\"startingOffice\"").findAll(v18).count())
            assertTrue(v18.contains("<option value=\"${reinhard.key}\" data-country=\"2\" data-rank=\"5\">"))
            assertTrue(v18.contains("<option value=\"${staden.key}\" data-country=\"2\" data-rank=\"5\">$stadenLabel</option>"))
            assertFalse(v18.contains("V18"));assertFalse(v18.contains(OriginalStartingOffice.VUCOCK_FLEET_5.key))
            assertFalse(v18.contains(OriginalStartingOffice.PAETTA_FLEET_2.key));assertFalse(v18.contains("V19"))
            val alliance=page("new=3")
            assertTrue(alliance.contains("value=\"${OriginalStartingOffice.VUCOCK_FLEET_5.key}\""));assertFalse(alliance.contains(staden.key))
            assertEquals(400,save(form+("rank" to "4")))
            assertEquals(400,save(form+mapOf("country" to "3","face" to "100001")))
            assertEquals(400,save(form+mapOf("candidate" to "1","create" to "0")))
            assertTrue(store.saved.isEmpty())
            assertEquals(200,save(form))
            assertEquals(staden,store.saved.getValue(1003).profile.startingOffice)

            // Staden's holder does not block Reinhard; once both are held the empire form offers no office.
            val held=page("new=2")
            assertTrue(held.contains("시작 직책 ${stadenLabel}은 후보 1003에 이미 지정"))
            assertTrue(held.contains("value=\"${reinhard.key}\""));assertFalse(held.contains("value=\"${staden.key}\""))
            assertEquals(400,save(form+mapOf("candidate" to "1004")))
            assertEquals(200,save(form+mapOf("candidate" to "1004","surname" to "라인하르트","startingOffice" to reinhard.key)))
            assertEquals(reinhard,store.saved.getValue(1004).profile.startingOffice)
            val full=page("new=2")
            assertTrue(full.contains("후보 1003에 이미 지정"));assertTrue(full.contains("후보 1004에 이미 지정"))
            assertFalse(full.contains("name=\"startingOffice\""))

            // Bound Staden stays fixed through later edits and cannot be swapped for Reinhard.
            val bound=page("candidate=1003")
            assertTrue(bound.contains("<strong>$stadenLabel</strong>"));assertFalse(bound.contains("name=\"startingOffice\""))
            val edit=form-"create"-"startingOffice"+("revision" to "1")
            assertEquals(400,save(edit+("startingOffice" to reinhard.key)))
            assertEquals(200,save(edit+("given" to "카를")))
            assertEquals(staden,store.saved.getValue(1003).profile.startingOffice)
        }
    }

    @Test fun allianceOffersVucockAndPaettaSeparatelyAcrossV19() {
        val store=Store().apply {allianceOffice=true;stadenOffice=true}
        withEditor(store) {
            val vucock=OriginalStartingOffice.VUCOCK_FLEET_5
            val paetta=OriginalStartingOffice.PAETTA_FLEET_2
            val paettaLabel="파에타 · 제2함대 사령관 (하이네센)"
            val form=mapOf("operator" to "peppone","candidate" to "1003","revision" to "0","create" to "1","country" to "3",
                "surname" to "파에타","given" to "후보","face" to "100001","rank" to "5","peerage" to "0",
                "startingOffice" to paetta.key)+abilities.indices.associate {"ability$it" to "60"}

            // Before V19: Vucock stays selectable, Paetta only asks for V19 and its save is refused.
            val v18=page("new=3")
            assertTrue(v18.contains("value=\"${vucock.key}\""));assertFalse(v18.contains("value=\"${paetta.key}\""))
            assertTrue(v18.contains("시작 직책 ${paettaLabel}을 지정하려면 정의 DB V19을 적용"))
            assertFalse(page("new=2").contains("V19"))
            assertEquals(503,save(form));assertTrue(store.saved.isEmpty())
            store.office=false
            val v15=page("new=3")
            assertTrue(v15.contains("정의 DB V16·V19을 적용"));assertTrue(v15.contains("정의 DB V16·V17을 적용"))
            assertFalse(v15.contains("name=\"startingOffice\""))

            // V19: one select offers both alliance offices, each bound to country 3 at rank 5; imperial offices stay on the empire side.
            store.office=true;store.paettaOffice=true
            val v19=page("new=3")
            assertEquals(1,Regex("name=\"startingOffice\"").findAll(v19).count())
            assertTrue(v19.contains("<option value=\"${vucock.key}\" data-country=\"3\" data-rank=\"5\">"))
            assertTrue(v19.contains("<option value=\"${paetta.key}\" data-country=\"3\" data-rank=\"5\">$paettaLabel</option>"))
            assertFalse(v19.contains("V19"))
            assertFalse(v19.contains(OriginalStartingOffice.REINHARD_FLEET_2.key));assertFalse(v19.contains(OriginalStartingOffice.STADEN_FLEET_4.key))
            val empire=page("new=2")
            assertTrue(empire.contains("value=\"${OriginalStartingOffice.STADEN_FLEET_4.key}\""));assertFalse(empire.contains(paetta.key))
            assertEquals(400,save(form+("rank" to "4")))
            assertEquals(400,save(form+mapOf("country" to "2","face" to "1")))
            assertEquals(400,save(form+mapOf("candidate" to "2","create" to "0")))
            assertTrue(store.saved.isEmpty())
            assertEquals(200,save(form))
            assertEquals(paetta,store.saved.getValue(1003).profile.startingOffice)

            // Paetta's holder does not block Vucock; once both are held the alliance form offers no office.
            val held=page("new=3")
            assertTrue(held.contains("시작 직책 ${paettaLabel}은 후보 1003에 이미 지정"))
            assertTrue(held.contains("value=\"${vucock.key}\""));assertFalse(held.contains("value=\"${paetta.key}\""))
            assertEquals(400,save(form+mapOf("candidate" to "1004")))
            assertEquals(200,save(form+mapOf("candidate" to "1004","surname" to "뷰코크","startingOffice" to vucock.key)))
            assertEquals(vucock,store.saved.getValue(1004).profile.startingOffice)
            val full=page("new=3")
            assertTrue(full.contains("후보 1003에 이미 지정"));assertTrue(full.contains("후보 1004에 이미 지정"))
            assertFalse(full.contains("name=\"startingOffice\""))

            // Bound Paetta stays fixed through later edits and cannot be swapped for Vucock.
            val bound=page("candidate=1003")
            assertTrue(bound.contains("<strong>$paettaLabel</strong>"));assertFalse(bound.contains("name=\"startingOffice\""))
            val edit=form-"create"-"startingOffice"+("revision" to "1")
            assertEquals(400,save(edit+("startingOffice" to vucock.key)))
            assertEquals(200,save(edit+("given" to "클레멘트")))
            assertEquals(paetta,store.saved.getValue(1003).profile.startingOffice)
        }
    }

    @Test fun empireOffersFogelAlongsideReinhardAndStadenAcrossV20() {
        val store=Store().apply {allianceOffice=true;stadenOffice=true;paettaOffice=true}
        withEditor(store) {
            val reinhard=OriginalStartingOffice.REINHARD_FLEET_2
            val staden=OriginalStartingOffice.STADEN_FLEET_4
            val fogel=OriginalStartingOffice.FOGEL_FLEET_5
            val fogelLabel="포겔 · 제5함대 사령관 (하펜)"
            val form=mapOf("operator" to "peppone","candidate" to "1003","revision" to "0","create" to "1","country" to "2",
                "surname" to "포겔","given" to "후보","face" to "1","rank" to "5","peerage" to "0",
                "startingOffice" to fogel.key)+abilities.indices.associate {"ability$it" to "60"}

            // Before V20: Reinhard and Staden stay selectable, Fogel only asks for V20 and its save is refused.
            val v19=page("new=2")
            assertTrue(v19.contains("value=\"${reinhard.key}\""));assertTrue(v19.contains("value=\"${staden.key}\""))
            assertFalse(v19.contains("value=\"${fogel.key}\""))
            assertTrue(v19.contains("시작 직책 ${fogelLabel}을 지정하려면 정의 DB V20을 적용"))
            assertFalse(page("new=3").contains("V20"))
            assertEquals(503,save(form));assertTrue(store.saved.isEmpty())
            store.office=false
            val v15=page("new=2")
            assertTrue(v15.contains("정의 DB V16·V20을 적용"));assertTrue(v15.contains("정의 DB V16·V18을 적용"))
            assertFalse(v15.contains("name=\"startingOffice\""))

            // V20: one select offers all three imperial offices at country 2 rank 5 (중장); alliance offices stay apart.
            store.office=true;store.fogelOffice=true
            val v20=page("new=2")
            assertEquals(1,Regex("name=\"startingOffice\"").findAll(v20).count())
            assertTrue(v20.contains("<option value=\"${reinhard.key}\" data-country=\"2\" data-rank=\"5\">"))
            assertTrue(v20.contains("<option value=\"${staden.key}\" data-country=\"2\" data-rank=\"5\">"))
            assertTrue(v20.contains("<option value=\"${fogel.key}\" data-country=\"2\" data-rank=\"5\">$fogelLabel</option>"))
            assertTrue(v20.contains("시작 계급이 중장일 때만"));assertFalse(v20.contains("V20"))
            assertFalse(v20.contains(OriginalStartingOffice.VUCOCK_FLEET_5.key));assertFalse(v20.contains(OriginalStartingOffice.PAETTA_FLEET_2.key))
            assertFalse(page("new=3").contains(fogel.key))
            assertEquals(400,save(form+("rank" to "4")))
            assertEquals(400,save(form+mapOf("country" to "3","face" to "100001")))
            assertEquals(400,save(form+mapOf("candidate" to "1","create" to "0")))
            assertTrue(store.saved.isEmpty())
            assertEquals(200,save(form))
            assertEquals(fogel,store.saved.getValue(1003).profile.startingOffice)

            // Fogel's holder blocks only Fogel; Staden at the same base stays selectable.
            val held=page("new=2")
            assertTrue(held.contains("시작 직책 ${fogelLabel}은 후보 1003에 이미 지정"))
            assertTrue(held.contains("value=\"${staden.key}\""));assertFalse(held.contains("value=\"${fogel.key}\""))
            assertEquals(400,save(form+mapOf("candidate" to "1004")))
            assertEquals(200,save(form+mapOf("candidate" to "1004","surname" to "슈타덴","startingOffice" to staden.key)))
            assertEquals(staden,store.saved.getValue(1004).profile.startingOffice)

            // Bound Fogel stays fixed through later edits and cannot be swapped for Reinhard.
            val bound=page("candidate=1003")
            assertTrue(bound.contains("<strong>$fogelLabel</strong>"));assertTrue(bound.contains("시작 계급(중장)"))
            assertFalse(bound.contains("name=\"startingOffice\""))
            val edit=form-"create"-"startingOffice"+("revision" to "1")
            assertEquals(400,save(edit+("startingOffice" to reinhard.key)))
            assertEquals(200,save(edit+("given" to "변경")))
            assertEquals(fogel,store.saved.getValue(1003).profile.startingOffice)
        }
    }
}
