package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import org.logh7.engine.*

/** Image bytes stay authenticated; game clients receive only bounded thumbnails. Author: Peppone Choi. */
internal fun Route.countryImageRoutes(store:CountryImageStore?,definitions:CountryDefinitionStore?,port:Int,audit:OpsAudit?) {
    get("/admin/images") {
        val data=if(store==null || definitions==null)null else try {withContext(Dispatchers.IO){
            Triple(store.list(),definitions.load(),store.snapshot())}}catch(_:Exception){null}
        val key=call.request.queryParameters["key"]?.takeIf(CountryImageStore::validKey)
        val history=if(data==null || key==null)emptyList() else withContext(Dispatchers.IO){store!!.history(key)}
        call.response.headers.append("Cache-Control","no-store")
        call.respondText(countryImagePage(data,key,history,call.request.queryParameters["country"]?.toIntOrNull()),ContentType.Text.Html,
            if(data==null)HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
    }
    get("/admin/images/file/{key}") {
        val key=call.parameters["key"].orEmpty();val requested=call.request.queryParameters["revision"]
        val revision=requested?.toLongOrNull()
        if(!CountryImageStore.validKey(key) || requested!=null && (revision==null || revision<=0)) {call.respond(HttpStatusCode.BadRequest);return@get}
        val file=if(store==null)null else try {withContext(Dispatchers.IO){store.read(key,revision)}}catch(_:Exception){call.respond(HttpStatusCode.ServiceUnavailable);return@get}
        if(file==null) {call.respond(HttpStatusCode.NotFound);return@get}
        call.response.headers.append("Cache-Control","no-store");call.response.headers.append("X-Content-Type-Options","nosniff")
        call.respondBytes(file.png,ContentType.Image.PNG)
    }
    post("/admin/images/save") {
        val form=call.imageForm(port) ?: return@post
        val parsed=runCatching {
            val key=checkNotNull(form["key"]);val kind=CountryImageKind.entries.single {it.key==form["kind"]}
            val revision=checkNotNull(form["revision"]?.toLongOrNull())
            require(CountryImageStore.validKey(key) && revision>=0 && CountryImageStore.validText(form["title"].orEmpty(),form["description"].orEmpty()))
            val text=form["png"].orEmpty();require(text.length<=(CountryImageUpload.MAX_BYTES+2)/3*4)
            val upload=if(text.isEmpty())null else withContext(Dispatchers.Default){CountryImageUpload.decode(Base64.getDecoder().decode(text))}
            ImageEdit(key,kind,revision,form["title"].orEmpty(),form["description"].orEmpty(),upload)
        }.getOrNull()
        call.audited(audit,OpsAction.IMAGE_EDIT,form["operator"],parsed?.let {"asset=${it.key},revision=${it.revision},sha=${it.upload?.sha256 ?: "unchanged"}"},imageErrors) {
            if(parsed==null)return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            withContext(Dispatchers.IO){store.save(parsed.key,parsed.kind,parsed.revision,parsed.title,parsed.description,parsed.upload)}.ops()
        }
    }
    post("/admin/images/restore") {
        val form=call.imageForm(port) ?: return@post
        call.audited(audit,OpsAction.IMAGE_RESTORE,form["operator"],"asset=${imageAuditValue(form["key"])},restore=${imageAuditValue(form["source"])}",imageErrors) {
            val key=form["key"].orEmpty();val revision=form["revision"]?.toLongOrNull();val source=form["source"]?.toLongOrNull()
            if(!CountryImageStore.validKey(key) || revision==null || source==null)return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            withContext(Dispatchers.IO){store.restore(key,revision,source)}.ops()
        }
    }
    post("/admin/images/bind") {
        val form=call.imageForm(port) ?: return@post
        call.audited(audit,OpsAction.IMAGE_BIND,form["operator"],"country=${imageAuditValue(form["country"])},slot=${imageAuditValue(form["slot"])},asset=${imageAuditValue(form["key"])}",imageErrors) {
            val country=form["country"]?.toIntOrNull();val revision=form["revision"]?.toLongOrNull()
            val slot=CountryImageKind.entries.singleOrNull {it.key==form["slot"]};val key=form["key"]?.takeIf {it.isNotEmpty()}
            if(country==null || revision==null || slot==null || slot==CountryImageKind.PORTRAIT)return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            withContext(Dispatchers.IO){store.bind(country,revision,slot,key)}.ops()
        }
    }
}
private data class ImageEdit(val key:String,val kind:CountryImageKind,val revision:Long,val title:String,val description:String,val upload:CountryImageUpload?)
private fun imageAuditValue(value:String?)=value.orEmpty().filter {it in ' '..'~'}.take(64)
private val imageErrors=mapOf(OpsOutcome.INVALID to "올바른 이미지 정보와 PNG(최대 4 MiB, 가로·세로 2048 이하)를 입력하세요.",
    OpsOutcome.CONFLICT to "다른 수정이 저장되었습니다. 화면을 새로 열고 확인하세요.",OpsOutcome.NOT_FOUND to "이미지 또는 국가가 없습니다.")
private fun CountryImageOutcome.ops():OpsOutcome=when(this) {
    CountryImageOutcome.CHANGED->OpsOutcome.CHANGED;CountryImageOutcome.UNCHANGED->OpsOutcome.UNCHANGED
    CountryImageOutcome.CONFLICT->OpsOutcome.CONFLICT;CountryImageOutcome.NOT_FOUND->OpsOutcome.NOT_FOUND;CountryImageOutcome.INVALID->OpsOutcome.INVALID
}
private suspend fun ApplicationCall.imageForm(port:Int):Map<String,String>? {
    if(crossOrigin(port))return null
    val maximum=CountryImageUpload.MAX_BYTES*4+65536
    val bytes=withContext(Dispatchers.IO){receiveStream().readNBytes(maximum+1)}
    if(bytes.size>maximum) {respondText("이미지 요청이 너무 큽니다.",status=HttpStatusCode.PayloadTooLarge);return null}
    val form=runCatching {
        require(request.contentType().withoutParameters()==ContentType.Application.FormUrlEncoded)
        val values=linkedMapOf<String,String>()
        for(field in String(bytes,UTF_8).split('&')) {
            val pair=field.split('=',limit=2);require(pair.size==2 && values.size<20)
            val key=URLDecoder.decode(pair[0],UTF_8);require(key !in values)
            values[key]=URLDecoder.decode(pair[1],UTF_8)
        };values
    }.getOrNull()
    if(form==null)respondText("올바른 이미지 폼이 아닙니다.",status=HttpStatusCode.BadRequest)
    return form
}

internal fun countryImagePage(data:Triple<List<CountryImageInfo>,CountryDefinitionSnapshot,CountryImageSnapshot>?,key:String?,history:List<CountryImageInfo>,countryId:Int?):String=buildString {
    fun e(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LOGH7 이미지 관리</title>
<style>body{font:16px/1.6 system-ui,"Malgun Gothic",sans-serif;max-width:1100px;margin:auto;padding:24px;background:#101b2a;color:#e6eef8}a{color:#9ed2ff}input,select,textarea,button{font:inherit;max-width:100%;padding:7px;box-sizing:border-box}label{display:block;margin:12px 0}textarea{width:100%;height:90px}.preview{width:96px;height:96px;object-fit:contain;background:repeating-conic-gradient(#234 0% 25%,#456 0% 50%) 0/16px 16px}table{width:100%;border-collapse:collapse}td,th{padding:10px;text-align:left;border-bottom:1px solid #456}.grid{display:grid;grid-template-columns:1fr 1fr;gap:32px}small,.note{color:#a3b9cd}#status{white-space:pre-wrap;min-height:30px}button{cursor:pointer}@media(max-width:700px){.grid{grid-template-columns:1fr}table{font-size:14px}}</style>
<nav><a href="/admin">운영 현황</a> · <a href="/admin/countries/definitions">국가 정의</a> · <a href="/admin/countries">국가 색상</a></nav><h1>이미지 관리</h1>""")
    if(data==null){append("<p>이미지 DB를 사용할 수 없습니다. 국가 정의 DB와 이미지 저장 형식을 확인하세요.</p></html>");return@buildString}
    val (images,definitions,snapshot)=data;val selected=images.firstOrNull {it.key==key}
    val country=definitions.catalog.countries[countryId] ?: definitions.catalog.countries.values.firstOrNull()
    fun hidden(name:String,value:String){append("<input type=\"hidden\" name=\"$name\" value=\"${e(value)}\">")}
    fun operator(){append("<label>운영자 <input name=\"operator\" required pattern=\"[A-Za-z0-9._-]{1,64}\" autocomplete=\"username\"></label>")}
    fun label(kind:CountryImageKind)=when(kind){CountryImageKind.EMBLEM->"국가 문장";CountryImageKind.FLAG->"깃발";CountryImageKind.MAP_ICON->"지도 아이콘";CountryImageKind.PORTRAIT->"NPC 초상"}
    append("<p>PNG 원본과 수정 이력을 보관합니다. 문장·지도 아이콘은 비율을 유지한 32×32 표시본에 국가색을 입혀 게임에 반영합니다. 깃발의 게임 화면 연결은 준비 중입니다.</p><div class=\"grid\"><section><h2>${if(selected==null)"이미지 등록" else "이미지 수정"}</h2><form id=\"image-edit\" action=\"/admin/images/save\" method=\"post\">")
    hidden("revision",(selected?.revision ?: 0).toString());hidden("country",country?.id?.toString().orEmpty());operator()
    append("<label>이미지 키 <input name=\"key\" required pattern=\"[a-z][a-z0-9_]{0,39}\" maxlength=\"40\" value=\"${e(selected?.key.orEmpty())}\"${if(selected!=null)" readonly" else ""}></label><label>용도 <select name=\"kind\">")
    CountryImageKind.entries.forEach {append("<option value=\"${it.key}\"${if(it==selected?.kind)" selected" else ""}${if(selected!=null && it!=selected.kind)" disabled" else ""}>${label(it)}</option>")}
    append("</select></label><label>이름 <input name=\"title\" required maxlength=\"80\" value=\"${e(selected?.title.orEmpty())}\"></label><label>설명 <textarea name=\"description\" maxlength=\"1000\">${e(selected?.description.orEmpty())}</textarea></label><label>PNG 파일 <input id=\"png-file\" type=\"file\" accept=\"image/png\"${if(selected==null)" required" else ""}></label><p class=\"note\">최대 4 MiB · 가로/세로 2048 이하. 파일을 선택하지 않으면 이름·설명만 수정합니다. 등록한 용도는 유지합니다.</p><img id=\"upload-preview\" class=\"preview\" alt=\"이미지 미리보기\"${if(selected==null)" hidden" else " src=\"/admin/images/file/${selected.key}?revision=${selected.revision}\""}><p><button>저장</button> <a href=\"/admin/images?country=${country?.id ?: 0}\">새 이미지 등록</a></p></form>")
    if(selected!=null) {
        append("<p class=\"note\">버전 ${selected.revision} · ${selected.width}×${selected.height}<br>SHA-256 ${selected.sha256}</p><form action=\"/admin/images/restore\" method=\"post\">")
        hidden("key",selected.key);hidden("revision",selected.revision.toString());hidden("country",country?.id?.toString().orEmpty());operator()
        append("<label>이전 버전 <select name=\"source\">")
        history.forEach {append("<option value=\"${it.revision}\">${it.revision} · ${e(it.title)} · ${it.width}×${it.height}</option>")}
        append("</select></label><img id=\"history-preview\" data-key=\"${selected.key}\" class=\"preview\" alt=\"선택한 이전 버전 미리보기\" src=\"/admin/images/file/${selected.key}?revision=${selected.revision}\"><p><button>이 버전으로 복원</button></p><p class=\"note\">최근 100개 버전을 표시합니다. 복원은 새 버전을 만들고 기존 이력을 보존합니다.</p></form>")
    }
    append("</section><section><h2>국가에 연결</h2><form method=\"get\"><label>국가 <select name=\"country\">")
    definitions.catalog.countries.values.forEach {append("<option value=\"${it.id}\"${if(it.id==country?.id)" selected" else ""}>${e(it.name)} (${it.id})</option>")}
    append("</select></label><button>열기</button></form>")
    if(country!=null)for(kind in CountryImageKind.entries.filter {it!=CountryImageKind.PORTRAIT}) {
        val binding=snapshot.countries[country.id]
        val current=when(kind){CountryImageKind.EMBLEM->binding?.emblemKey;CountryImageKind.FLAG->binding?.flagKey;CountryImageKind.MAP_ICON->binding?.mapIconKey;CountryImageKind.PORTRAIT->null}
        append("<form action=\"/admin/images/bind\" method=\"post\">");hidden("country",country.id.toString());hidden("revision",definitions.revision.toString());hidden("slot",kind.key);operator()
        append("<label>${label(kind)} <select name=\"key\"><option value=\"\">기본 문양 사용</option>")
        images.filter {it.kind==kind}.forEach {append("<option value=\"${it.key}\"${if(it.key==current)" selected" else ""}>${e(it.title)} (${it.key})</option>")}
        if(current!=null && images.none {it.key==current})append("<option value=\"${e(current)}\" selected>기존 자산 (${e(current)})</option>")
        append("</select></label><button>${label(kind)} 연결 저장</button></form>")
    }
    append("</section></div><p id=\"status\" role=\"status\"></p><h2>등록 이미지</h2><table><tr><th>미리보기</th><th>이름 / 키</th><th>용도</th><th>버전</th></tr>")
    images.forEach {append("<tr><td><img class=\"preview\" alt=\"${e(it.title)}\" src=\"/admin/images/file/${it.key}?revision=${it.revision}\"></td><td><a href=\"?key=${it.key}&amp;country=${country?.id ?: 0}\">${e(it.title)}</a><br><small>${it.key}</small></td><td>${label(it.kind)}</td><td>${it.revision}</td></tr>")}
    append("""</table><script>
const status=document.getElementById('status'),file=document.getElementById('png-file'),preview=document.getElementById('upload-preview');let busy=false,previewUrl;
file.onchange=()=>{if(previewUrl)URL.revokeObjectURL(previewUrl);if(file.files[0]){previewUrl=URL.createObjectURL(file.files[0]);preview.src=previewUrl;preview.hidden=false;}};
const historyPreview=document.getElementById('history-preview');if(historyPreview)document.querySelector('select[name=source]').onchange=event=>{historyPreview.src='/admin/images/file/'+historyPreview.dataset.key+'?revision='+event.target.value;};
for(const form of document.querySelectorAll('form[method=post]'))form.onsubmit=async event=>{event.preventDefault();if(busy)return;busy=true;const buttons=document.querySelectorAll('button');buttons.forEach(b=>b.disabled=true);status.textContent='저장 중…';try{const body=new URLSearchParams(new FormData(form));if(form.id==='image-edit'&&file.files[0]){if(file.files[0].size>4194304)throw Error('PNG 파일은 4 MiB 이하여야 합니다.');const bytes=new Uint8Array(await file.files[0].arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=32768)binary+=String.fromCharCode(...bytes.subarray(i,i+32768));body.set('png',btoa(binary));}const response=await fetch(form.action,{method:'POST',body});const text=await response.text();if(!response.ok)throw Error(text);status.textContent='저장했습니다. 갱신 중…';location.href='/admin/images?'+new URLSearchParams({key:body.get('key')||'',country:body.get('country')||''});}catch(error){status.textContent=error.message;busy=false;buttons.forEach(b=>b.disabled=false);}};
</script></html>""")
}
