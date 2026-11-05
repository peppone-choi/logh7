package org.logh7.ops

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class OpsEditorTest {
    @Test fun `editor resources are packaged and require existing operator authorization`() {
        val source=object:SnapshotSource {override fun snapshot()=WorldSnapshot(0,0)}
        val sink=object:CommandSink {override suspend fun submit(command:WorldCommand)=error("No mutation expected")}
        val port=ServerSocket(0).use {it.localPort}
        val token="fixture-editor-token"
        startOpsApi(sink,source,port,token=token).use { HttpClient.newHttpClient().use {client ->
            fun get(path:String,authorized:Boolean=true):HttpResponse<String> {
                val request=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                if(authorized)request.header("Authorization","Bearer $token")
                return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(401,get("/admin/text/",false).statusCode())
            assertEquals(401,get("/admin/text/editor.js",false).statusCode())
            assertTrue(get("/admin").body().contains("/admin/text/"))
            assertTrue(get("/admin/text/").body().contains("LOGH7 문구 편집"))
            assertTrue(get("/admin/text/editor.js").body().contains("LoghTranslationEditor"))
            assertEquals(200,get("/admin/text/editor-ui.js").statusCode())
            assertEquals(404,get("/admin/text/unknown.js").statusCode())
        }}
    }
}
