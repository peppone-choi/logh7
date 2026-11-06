package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.logh7.engine.*

/**
 * Facility name/purpose editing with the same login, origin check, revision conflict and durable audit
 * as the country definition editor. Without a definitions database the page still shows what each
 * facility is for, read-only. Author: Peppone Choi.
 */
internal fun Route.facilityDefinitionRoutes(store:FacilityDefinitionStore?,published:PublishedFacilityDefinitions?,
    galaxy:Galaxy?,commands:FacilityCommandCatalog,port:Int,audit:OpsAudit?) {
    get("/admin/facilities") {
        val loaded=if(store==null)null else try {withContext(Dispatchers.IO){store.load()}}
            catch(_:java.sql.SQLException){null} catch(_:IllegalArgumentException){null}
        val shown=loaded ?: published?.current() ?: FacilityDefinitionDefaults.snapshot()
        call.response.headers.append("Cache-Control","no-store")
        call.respondText(facilityDefinitionPage(shown,editable=loaded!=null,storeConfigured=store!=null,galaxy,commands,
            call.request.queryParameters["kind"]?.toIntOrNull()),ContentType.Text.Html)
    }
    post("/admin/facilities") {
        val form=call.adminForm(port) ?: return@post
        val parsed=runCatching {
            val revision=requireNotNull(form["revision"]?.toLongOrNull());require(revision>=0)
            fun text(key:String)=(form[key] ?: "").trim().also {require(it.length<=FacilityDefinitionSnapshot.TEXT_LIMIT)}
            fun optional(key:String)=text(key).takeIf {it.isNotEmpty()}
            val edit=when(form["entity"]) {
                "kind"->FacilityDefinitionEdit.Kind(FacilityKindDefinition(requireNotNull(form["kind"]?.toIntOrNull()),optional("name"),text("purpose"),text("usage")))
                "override"->FacilityDefinitionEdit.Override(FacilityOverride(requireNotNull(form["facility_id"]?.toIntOrNull()),optional("name"),optional("purpose")))
                "clear"->FacilityDefinitionEdit.ClearOverride(requireNotNull(form["facility_id"]?.toIntOrNull()))
                else->throw IllegalArgumentException()
            }
            revision to edit
        }.getOrNull()
        val details=parsed?.let {(revision,edit)->"revision=$revision,"+when(edit) {
            is FacilityDefinitionEdit.Kind->"kind=${edit.value.kind},name=${edit.value.name ?: "-"}"
            is FacilityDefinitionEdit.Override->"facility=${edit.value.facilityId},name=${edit.value.name ?: "-"}"
            is FacilityDefinitionEdit.ClearOverride->"clear=${edit.facilityId}"
        }}
        call.audited(audit,OpsAction.FACILITY_DEFINITION_EDIT,form["operator"],details,
            mapOf(OpsOutcome.INVALID to "invalid facility definition",OpsOutcome.CONFLICT to "facility definitions changed; reload before editing")) {
            if(parsed==null)return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            when(withContext(Dispatchers.IO){store.save(parsed.first,parsed.second)}) {
                FacilityDefinitionOutcome.CHANGED->{
                    // Show the edit in game at once; the periodic reload in the app would also pick it up.
                    if(published!=null)try {published.publish(withContext(Dispatchers.IO){store.load()})}
                        catch(_:java.sql.SQLException){} catch(_:IllegalArgumentException){}
                    OpsOutcome.CHANGED
                }
                FacilityDefinitionOutcome.UNCHANGED->OpsOutcome.UNCHANGED
                FacilityDefinitionOutcome.CONFLICT->OpsOutcome.CONFLICT
                FacilityDefinitionOutcome.INVALID->OpsOutcome.INVALID
            }
        }
    }
}

internal fun facilityDefinitionPage(snapshot:FacilityDefinitionSnapshot,editable:Boolean,storeConfigured:Boolean,galaxy:Galaxy?,
    commands:FacilityCommandCatalog,requested:Int?):String=buildString {
    fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LOGH7 시설 정의</title>
<style>body{font:16px/1.6 system-ui,"Malgun Gothic",sans-serif;max-width:1100px;margin:auto;padding:24px;background:#101b2a;color:#e6eef8}a{color:#9ed2ff}input,select,button,textarea{font:inherit;padding:7px;max-width:100%;box-sizing:border-box}textarea{width:100%;min-height:4em}label{display:grid;grid-template-columns:180px minmax(120px,1fr);gap:12px;margin:14px 0}button{cursor:pointer}.note{color:#a3b9cd}#status{white-space:pre-wrap;min-height:40px}table{border-collapse:collapse;width:100%;font-size:14px}th,td{border-bottom:1px solid #2c4057;padding:6px;text-align:left;vertical-align:top}.wrap{overflow-x:auto}.muted{color:#7f95ab}@media(max-width:600px){label{grid-template-columns:1fr}}</style>
<nav><a href="/admin">운영 현황</a> · <a href="/admin/countries/definitions">국가 정의</a> · <a href="/admin/world">세계 편집</a></nav><h1>시설 정의</h1>""")
    append("<p class=\"note\">시설 종류(원본 시설 분류 번호)와 시설 ID(거점 ID×100+종류+1)는 바뀌지 않습니다. 이름을 비우면 서버 기본 이름을 씁니다. 이용 조건 문장은 안내용이며 실제 출입은 방별 접근·소속·직위 판정을 따릅니다. 지원 명령은 엔진 규칙에서 읽기만 하고 이 화면에서 추가하지 않습니다.</p>")
    if(!editable) append("<p><strong>${if(storeConfigured)"시설 정의 DB를 읽지 못했습니다(V4 이행·연결 확인). 게임은 마지막 정의로 표시합니다." else "정의 DB가 설정되지 않아 기본 정의를 읽기 전용으로 보여 줍니다."}</strong></p>")
    append("<p class=\"note\">정의 버전 ${snapshot.revision}</p><div class=\"wrap\"><table><thead><tr><th>종류</th><th>기본 이름</th><th>현재 이름</th><th>용도</th><th>이용 조건</th><th>지원 명령</th></tr></thead><tbody>")
    for((kind,value) in snapshot.kinds.toSortedMap()) {
        val supported=commands.commands(kind)
        append("<tr><td><a href=\"?kind=$kind#kind\">$kind</a></td><td>${escape(FacilityDefinitionDefaults.name(kind))}</td><td>${value.name?.let(::escape) ?: "<span class=\"muted\">기본값</span>"}</td>")
        append("<td>${escape(value.purpose)}</td><td>${escape(value.usage)}</td><td>${if(supported.isEmpty())"<span class=\"muted\">미연결</span>" else supported.joinToString(", ",transform=::escape)}</td></tr>")
    }
    append("</tbody></table></div>")
    val baseNames=galaxy?.bases?.associate {it.id to it.name}.orEmpty()
    append("<h2>거점별 재정의</h2>")
    if(snapshot.overrides.isEmpty()) append("<p class=\"muted\">거점별 재정의가 없습니다.</p>")
    else {
        append("<div class=\"wrap\"><table><thead><tr><th>시설 ID</th><th>거점</th><th>종류</th><th>이름</th><th>용도</th></tr></thead><tbody>")
        for(o in snapshot.overrides.values.sortedBy {it.facilityId})
            append("<tr><td>${o.facilityId}</td><td>${o.baseId} ${escape(baseNames[o.baseId].orEmpty())}</td><td>${o.kind} ${escape(FacilityDefinitionDefaults.name(o.kind))}</td><td>${o.name?.let(::escape) ?: "<span class=\"muted\">종류 정의</span>"}</td><td>${o.purpose?.let(::escape) ?: "<span class=\"muted\">종류 정의</span>"}</td></tr>")
        append("</tbody></table></div>")
    }
    if(!editable) {append("</html>");return@buildString}
    val selected=requested?.takeIf {it in snapshot.kinds} ?: snapshot.kinds.keys.min()
    val kind=snapshot.kinds.getValue(selected)
    fun hidden(name:String,value:String)=append("<input type=\"hidden\" name=\"$name\" value=\"${escape(value)}\">")
    append("<h2 id=\"kind\">종류 정의 수정</h2><form method=\"get\"><label>시설 종류<select name=\"kind\">")
    for(k in snapshot.kinds.keys.sorted())append("<option value=\"$k\"${if(k==selected)" selected" else ""}>$k · ${escape(snapshot.kinds.getValue(k).name ?: FacilityDefinitionDefaults.name(k))}</option>")
    append("</select></label><button>열기</button></form><form class=\"edit\" action=\"/admin/facilities\" method=\"post\">")
    hidden("entity","kind");hidden("revision",snapshot.revision.toString());hidden("kind",selected.toString())
    append("<label>운영자<input name=\"operator\" maxlength=\"64\" required></label>")
    append("<label>이름 (비우면 \"${escape(FacilityDefinitionDefaults.name(selected))}\")<input name=\"name\" maxlength=\"24\" value=\"${escape(kind.name.orEmpty())}\"></label>")
    append("<label>용도 설명<textarea name=\"purpose\" maxlength=\"200\">${escape(kind.purpose)}</textarea></label>")
    append("<label>이용 조건 안내<textarea name=\"usage\" maxlength=\"200\">${escape(kind.usage)}</textarea></label><button>종류 정의 저장</button></form>")
    append("<h2>거점별 재정의 수정</h2><form class=\"edit\" action=\"/admin/facilities\" method=\"post\">")
    hidden("entity","override");hidden("revision",snapshot.revision.toString())
    append("<label>운영자<input name=\"operator\" maxlength=\"64\" required></label>")
    if(galaxy!=null) {
        append("<label>거점<select id=\"base\">")
        for(b in galaxy.bases.sortedBy {it.id})append("<option value=\"${b.id}\">${b.id} · ${escape(b.name)}</option>")
        append("</select></label><label>시설 종류<select id=\"facility-kind\">")
        for(k in snapshot.kinds.keys.sorted())append("<option value=\"$k\">$k · ${escape(snapshot.kinds.getValue(k).name ?: FacilityDefinitionDefaults.name(k))}</option>")
        append("</select></label>")
    }
    append("<label>시설 ID<input name=\"facility_id\" type=\"number\" min=\"101\" required></label>")
    append("<label>이름 (비우면 종류 정의)<input name=\"name\" maxlength=\"24\"></label><label>용도 설명 (비우면 종류 정의)<textarea name=\"purpose\" maxlength=\"200\"></textarea></label>")
    append("<label><span>재정의 지우기</span><input type=\"checkbox\" name=\"clear\" value=\"1\"></label><button>거점 재정의 저장</button></form><p id=\"status\" role=\"status\"></p>")
    append("""<script>const status=document.getElementById('status');let busy=false;
const base=document.getElementById('base'),kind=document.getElementById('facility-kind');
function sync(){const form=document.querySelector('form.edit:last-of-type');if(base&&kind)form.elements.facility_id.value=Number(base.value)*100+Number(kind.value)+1;}
if(base){base.onchange=sync;kind.onchange=sync;sync();}
for(const form of document.querySelectorAll('form.edit'))form.onsubmit=async event=>{event.preventDefault();if(busy)return;const body=new URLSearchParams(new FormData(form));
if(body.get('clear')==='1'){body.set('entity','clear');body.delete('clear');}
busy=true;for(const field of form.elements)field.disabled=true;try{const response=await fetch(form.action,{method:'POST',body});const text=await response.text();
if(!response.ok)throw Error(response.status===409?'다른 정의가 저장됐습니다. 최신 자료를 다시 열어 수정하세요.':response.status===400?'값을 확인하세요(이름 24자·설명 200자, 줄바꿈 불가, 재정의는 이름이나 용도 중 하나 필요).':response.status===503?'DB 또는 감사 기록을 사용할 수 없습니다.':'저장 실패: '+text);
status.textContent=text==='unchanged'?'변경된 정의가 없습니다.':'저장했습니다. 최신 정의를 다시 읽습니다.';if(text==='changed')location.reload();}
catch(error){status.textContent=error.message;}finally{busy=false;for(const field of form.elements)field.disabled=false;}};</script></html>""")
}
