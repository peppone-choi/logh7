package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class CountryDefinitionEditorTest {
    @TempDir lateinit var directory:Path
    @Test fun operatorCanEditDefinitionsWithAuthenticationOriginConflictAndAuditChecks() {
        val bundled=CountryCatalogLoader.load()
        val original=bundled.countries.getValue(2).copy(name="나라 <script>")
        var snapshot=CountryDefinitionSnapshot(9007199254740993,CountryCatalog(bundled.calendars.values.toList(),bundled.families.values.toList(),
            bundled.countries.values.map {if(it.id==2)original else it}),
            mapOf("ship_style" to listOf("empire","alliance"),"assets" to listOf("empire","alliance"),"commands" to listOf("logh7"),
                "ranks" to listOf("empire","alliance"),"offices" to listOf("empire","alliance"),"peerage" to listOf("empire","none"),"currency" to listOf("fixture")),
            bundled.families.keys.associateWith {"fixture"})
        var writes=0;var unavailable=false
        val store=object:CountryDefinitionStore {
            override fun load()=snapshot.also {if(unavailable)throw java.sql.SQLException("fixture unavailable")}
            override fun save(expectedRevision:Long,edit:CountryDefinitionEdit):CountryDefinitionOutcome {
                writes++
                if(unavailable)throw java.sql.SQLException("fixture unavailable")
                if(expectedRevision!=snapshot.revision)return CountryDefinitionOutcome.CONFLICT
                val country=(edit as? CountryDefinitionEdit.Country)?.value ?: return CountryDefinitionOutcome.INVALID
                if(!CountryDefinitionSnapshot.validName(country.name,24))return CountryDefinitionOutcome.INVALID
                if(country==snapshot.catalog.countries[2])return CountryDefinitionOutcome.UNCHANGED
                snapshot=snapshot.copy(revision=snapshot.revision+1,catalog=CountryCatalog(snapshot.catalog.calendars.values.toList(),
                    snapshot.catalog.families.values.toList(),snapshot.catalog.countries.values.map {if(it.id==2)country else it}))
                return CountryDefinitionOutcome.CHANGED
            }
        }
        val source=object:SnapshotSource {override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink {override suspend fun submit(command:WorldCommand)=error("Definitions do not edit world ownership")}
        val port=ServerSocket(0).use {it.localPort};val token="fixture-definition-token";val file=directory.resolve("audit.jsonl")
        OpsAudit.open(file).use {audit->startOpsApi(sink,source,port,audit,token,countryDefinitions=store).use {HttpClient.newHttpClient().use {client->
            fun request(body:String?=null,authorized:Boolean=true,origin:String="http://127.0.0.1:$port",query:String=""):HttpResponse<String> {
                val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/countries/definitions$query"))
                if(authorized)builder.header("Authorization","Bearer $token")
                if(body==null)builder.GET() else builder.header("Origin",origin).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
                return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
            }
            fun form(revision:Long=snapshot.revision,name:String="Changed")="entity=country&create=false&operator=peppone&revision=$revision&id=2&key=galactic_empire&name=$name&family=empire&native_power=2&native_camp=0&assets=empire&calendar=&ship_style=alliance"
            assertEquals(401,request(authorized=false).statusCode())
            val page=request();assertEquals(200,page.statusCode());assertTrue(page.body().contains("나라 &lt;script&gt;"))
            assertTrue(page.body().contains("value=\"9007199254740993\""));assertFalse(page.body().contains("name=\"calendar\" required"),"inherit selection must be submittable")
            assertEquals(200,request(query="?entity=family&new=1").statusCode());assertEquals(200,request(query="?entity=calendar&new=1").statusCode())
            val edit=form();assertEquals(401,request(edit,authorized=false).statusCode());assertEquals(403,request(edit,origin="https://foreign.invalid").statusCode())
            assertEquals(0,writes)
            assertEquals(400,request(form(name="%0A")).statusCode());assertEquals(original,snapshot.catalog.countries[2])
            assertEquals(200,request(edit).statusCode());assertEquals(ShipStyleId("alliance"),snapshot.catalog.shipStyle(CountryId(2)))
            assertEquals(409,request(edit).statusCode());assertEquals("unchanged",request(form()).body())
            val unicodeName="새 \"제국\" \\"
            assertEquals(200,request(form(name=java.net.URLEncoder.encode(unicodeName,Charsets.UTF_8))).statusCode())
            assertEquals(unicodeName,snapshot.catalog.countries.getValue(2).name)
            unavailable=true;assertEquals(503,request().statusCode());assertEquals(500,request(form()).statusCode())
            val log=Files.readString(file)
            assertTrue(log.contains("COUNTRY_DEFINITION_EDIT"));assertTrue(log.contains("shipStyleOverride=alliance"))
            for(outcome in listOf("INVALID","CHANGED","CONFLICT","UNCHANGED","FAILED"))assertTrue(log.contains("\"outcome\":\"$outcome\""))
            assertTrue(log.contains("\\u000a"),"invalid newlines remain inside one escaped audit record")
            assertTrue(log.contains("새 \\\"제국\\\" \\\\"),"Korean quotes and backslashes are JSON escaped")
        }}}
        val noAuditPort=ServerSocket(0).use {it.localPort}
        startOpsApi(sink,source,noAuditPort,countryDefinitions=store).use {HttpClient.newHttpClient().use {client->
            val request=HttpRequest.newBuilder(URI("http://127.0.0.1:$noAuditPort/admin/countries/definitions"))
                .header("Origin","http://127.0.0.1:$noAuditPort").header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("operator=peppone")).build()
            assertEquals(503,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode())
        }}
    }
}
