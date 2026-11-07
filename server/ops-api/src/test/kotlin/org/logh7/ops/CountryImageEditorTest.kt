package org.logh7.ops

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class CountryImageEditorTest {
    @TempDir lateinit var directory:Path
    @Test fun authenticatedAndAuditedImageFormsRejectInvalidFilesAndStaleEdits() {
        var file:CountryImageFile?=null;var writes=0
        val store=object:CountryImageStore {
            override fun list()=listOfNotNull(file?.info)
            override fun history(key:String)=list()
            override fun read(key:String,revision:Long?)=file?.takeIf {it.info.key==key && (revision==null || revision==it.info.revision)}
            override fun snapshot()=CountryImageSnapshot(0,emptyMap())
            override fun save(key:String,kind:CountryImageKind,expectedRevision:Long,title:String,description:String,upload:CountryImageUpload?):CountryImageOutcome {
                if(expectedRevision!=(file?.info?.revision ?: 0))return CountryImageOutcome.CONFLICT
                writes++
                file=CountryImageFile(CountryImageInfo(key,kind,expectedRevision+1,title,description,upload?.sha256 ?: checkNotNull(file).info.sha256,
                    upload?.width ?: checkNotNull(file).info.width,upload?.height ?: checkNotNull(file).info.height),upload?.png ?: checkNotNull(file).png,upload?.rgba ?: checkNotNull(file).rgba)
                return CountryImageOutcome.CHANGED
            }
            override fun restore(key:String,expectedRevision:Long,sourceRevision:Long)=CountryImageOutcome.NOT_FOUND
            override fun bind(country:Int,expectedDefinitionsRevision:Long,slot:CountryImageKind,key:String?)=CountryImageOutcome.NOT_FOUND
        }
        val definitions=object:CountryDefinitionStore {
            override fun load()=CountryDefinitionSnapshot(0,CountryCatalogLoader.load(),emptyMap(),emptyMap())
            override fun save(expectedRevision:Long,edit:CountryDefinitionEdit)=error("Image forms never rewrite country identities")
        }
        val source=object:SnapshotSource{override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink{override suspend fun submit(command:WorldCommand)=error("Image changes are display only")}
        val port=ServerSocket(0).use {it.localPort};val token="fixture-image-token";val auditFile=directory.resolve("audit.jsonl")
        val png=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",it)}.toByteArray()
        OpsAudit.open(auditFile).use {audit->startOpsApi(sink,source,port,audit,token,countryImages=store,countryDefinitions=definitions).use {HttpClient.newHttpClient().use {client->
            fun request(path:String,values:Map<String,String>?=null,authorized:Boolean=true,origin:String="http://127.0.0.1:$port"):HttpResponse<ByteArray> {
                val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                if(authorized)builder.header("Authorization","Bearer $token")
                if(values==null)builder.GET() else {
                    val body=values.entries.joinToString("&"){URLEncoder.encode(it.key,Charsets.UTF_8)+"="+URLEncoder.encode(it.value,Charsets.UTF_8)}
                    builder.header("Origin",origin).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
                }
                return client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray())
            }
            val values=mapOf("operator" to "peppone","key" to "badge","kind" to "emblem","revision" to "0","title" to "<script>문장</script>","description" to "설명","png" to Base64.getEncoder().encodeToString(png))
            assertEquals(401,request("/admin/images",authorized=false).statusCode())
            assertEquals(401,request("/admin/images/save",values,authorized=false).statusCode())
            assertEquals(403,request("/admin/images/save",values,origin="https://example.invalid").statusCode())
            assertEquals(400,request("/admin/images/save",values+("png" to "invalid")).statusCode());assertEquals(0,writes)
            assertEquals(200,request("/admin/images/save",values).statusCode());assertEquals(1,writes)
            val page=String(request("/admin/images?key=badge&country=3").body(),Charsets.UTF_8)
            assertTrue(page.substringAfter("<form id=\"image-edit\"").substringBefore("</form>").contains("name=\"country\" value=\"3\""));assertTrue(page.contains("&lt;script&gt;문장&lt;/script&gt;"));assertFalse(page.contains("<script>문장"));assertTrue(page.contains("history-preview"))
            assertEquals(409,request("/admin/images/save",values).statusCode());assertEquals(1,writes)
            assertEquals(200,request("/admin/images/save",values+mapOf("revision" to "1","png" to "","title" to "새 이름")).statusCode())
            val preview=request("/admin/images/file/badge?revision=2")
            assertEquals(200,preview.statusCode());assertContentEquals(png,preview.body());assertEquals("nosniff",preview.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertEquals(401,request("/admin/images/file/badge",authorized=false).statusCode())
            assertEquals(400,request("/admin/images/restore",mapOf("operator" to "peppone","key" to "😀","revision" to "2","source" to "1")).statusCode())
        }}}
        val audit=Files.readString(auditFile);assertTrue(audit.contains("IMAGE_EDIT"));assertTrue(audit.contains("CONFLICT"));assertFalse(audit.contains(token));assertFalse(audit.contains(Base64.getEncoder().encodeToString(png)))
    }
}
