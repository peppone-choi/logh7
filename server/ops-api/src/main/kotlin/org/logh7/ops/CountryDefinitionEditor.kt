package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.logh7.engine.*

/** Definition edits use the same authentication, origin and durable audit as world edits. Author: Peppone Choi. */
internal fun Route.countryDefinitionRoutes(store:CountryDefinitionStore?,port:Int,audit:OpsAudit?) {
    get("/admin/countries/definitions") {
        val snapshot=if(store==null)null else try {withContext(Dispatchers.IO){store.load()}}
            catch(_:java.sql.SQLException){null} catch(_:IllegalArgumentException){null}
        call.response.headers.append("Cache-Control","no-store")
        call.respondText(countryDefinitionPage(snapshot,call.request.queryParameters["entity"],call.request.queryParameters["key"],
            call.request.queryParameters["new"]=="1"),ContentType.Text.Html,if(snapshot==null)HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
    }
    post("/admin/countries/definitions") {
        val form=call.adminForm(port) ?: return@post
        val parsed=runCatching {
            val revision=requireNotNull(form["revision"]?.toLongOrNull());require(revision>=0)
            val create=when(form["create"]){"true"->true;"false"->false;else->throw IllegalArgumentException()}
            fun required(key:String)=requireNotNull(form[key]).also {require(it.length<=128)}
            fun optional(key:String)=form[key]?.takeIf {it.isNotEmpty()}
            val edit=when(form["entity"]) {
                "country"->CountryDefinitionEdit.Country(CountryDefinition(required("id").toInt(),required("key"),required("name"),required("family"),
                    required("native_power").toInt(),required("native_camp").toInt(),required("assets"),optional("calendar"),optional("ship_style")),create)
                "calendar"->CountryDefinitionEdit.Calendar(CountryCalendar(required("key"),required("name"),required("year_offset").toInt()),create)
                "family"->CountryDefinitionEdit.Family(CountryFamily(required("key"),required("name"),required("calendar"),required("commands"),
                    required("ranks"),required("offices"),required("peerage"),required("ship_style")),required("currency"),create)
                else->throw IllegalArgumentException()
            }
            revision to edit
        }.getOrNull()
        call.audited(audit,OpsAction.COUNTRY_DEFINITION_EDIT,form["operator"],parsed?.let {"revision=${it.first},edit=${it.second}"},
            mapOf(OpsOutcome.INVALID to "invalid definition or reference",OpsOutcome.CONFLICT to "definitions changed; reload before editing")) {
            if(parsed==null)return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            when(withContext(Dispatchers.IO){store.save(parsed.first,parsed.second)}) {
                CountryDefinitionOutcome.CHANGED->OpsOutcome.CHANGED
                CountryDefinitionOutcome.UNCHANGED->OpsOutcome.UNCHANGED
                CountryDefinitionOutcome.CONFLICT->OpsOutcome.CONFLICT
                CountryDefinitionOutcome.INVALID->OpsOutcome.INVALID
            }
        }
    }
}

internal fun countryDefinitionPage(snapshot:CountryDefinitionSnapshot?,requested:String?,key:String?,create:Boolean):String=buildString {
    fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LOGH7 국가 정의</title>
<style>body{font:16px/1.6 system-ui,"Malgun Gothic",sans-serif;max-width:1000px;margin:auto;padding:24px;background:#101b2a;color:#e6eef8}a{color:#9ed2ff}input,select,button{font:inherit;padding:7px;max-width:100%;box-sizing:border-box}label{display:grid;grid-template-columns:180px minmax(120px,1fr);gap:12px;margin:16px 0}button{cursor:pointer}.note{color:#a3b9cd}#status{white-space:pre-wrap;min-height:40px}.preview{padding:16px;border:1px solid #536b85;border-radius:8px}@media(max-width:600px){label{grid-template-columns:1fr}}</style>
<nav><a href="/admin">운영 현황</a> · <a href="/admin/countries">국가 색상</a> · <a href="/admin/world">세계 편집</a></nav><h1>국가 정의</h1>""")
    if(snapshot==null) {append("<p>국가 정의 DB 또는 자료를 사용할 수 없습니다. DB 연결·V1/V2/V3 이행·필수 국가 자료를 확인하세요.</p></html>");return@buildString}
    val catalog=snapshot.catalog
    val entity=requested?.takeIf {it in setOf("country","family","calendar")} ?: "country"
    val entries=when(entity) {
        "country"->catalog.countries.values.map {it.id.toString() to it.name}
        "family"->catalog.families.values.map {it.key to it.name}
        else->catalog.calendars.values.map {it.key to it.name}
    }
    val selected=key?.takeIf {candidate->entries.any {it.first==candidate}} ?: entries.first().first
    append("<p><a href=\"?entity=country\">국가</a> · <a href=\"?entity=family\">계열</a> · <a href=\"?entity=calendar\">역법</a></p>")
    append("<form method=\"get\"><input type=\"hidden\" name=\"entity\" value=\"$entity\"><label>편집 대상<select name=\"key\">")
    entries.forEach {(id,name)->append("<option value=\"${escape(id)}\"${if(id==selected)" selected" else ""}>${escape(id)} · ${escape(name)}</option>")}
    append("</select></label><button>열기</button> <a href=\"?entity=$entity&new=1\">새 항목 추가</a></form>")
    append("<p class=\"note\">전체 정의 버전 ${snapshot.revision} · ${if(create)"새 항목 추가" else "기존 항목 수정"}</p><form id=\"definition\" action=\"/admin/countries/definitions\" method=\"post\">")
    for((name,value) in mapOf("entity" to entity,"revision" to snapshot.revision.toString(),"create" to create.toString()))append("<input type=\"hidden\" name=\"$name\" value=\"$value\">")
    fun input(name:String,label:String,value:String,limit:Int=64,readonly:Boolean=false,type:String="text") {
        append("<label>${escape(label)}<input name=\"$name\" value=\"${escape(value)}\" maxlength=\"$limit\" type=\"$type\"${if(readonly)" readonly" else ""} required></label>")
    }
    fun select(name:String,label:String,values:List<String>,value:String?,inherit:Boolean=false) {
        append("<label>${escape(label)}<select name=\"$name\"${if(inherit)"" else " required"}>")
        if(inherit)append("<option value=\"\"${if(value==null)" selected" else ""}>계열 기본값</option>")
        values.forEach {
            val label=when(name) {
                "family"->catalog.families[it]?.name
                "calendar"->catalog.calendars[it]?.name
                "ship_style"->when(it){"empire"->"제국형";"alliance"->"동맹형";else->null}
                else->null
            }
            append("<option value=\"${escape(it)}\"${if(it==value)" selected" else ""}>${escape(label?.let {label->"$label ($it)"} ?: it)}</option>")
        }
        append("</select></label>")
    }
    input("operator","운영자","",64)
    when(entity) {
        "country"->{
            val country=catalog.countries.getValue(selected.toInt())
            input("id","국가 ID",if(create)"" else country.id.toString(),readonly=!create,type="number")
            input("key","식별 키",if(create)"" else country.key)
            input("name","국가 이름",if(create)"" else country.name,24)
            select("family","계열",catalog.families.keys.toList(),country.family)
            select("calendar","역법 재정의",catalog.calendars.keys.toList(),if(create)null else country.calendarOverride,true)
            select("ship_style","함정 스타일 재정의",snapshot.references.getValue("ship_style"),if(create)null else country.shipStyleOverride,true)
            select("assets","자산 프로필",snapshot.references.getValue("assets"),country.assets)
            input("native_power","원본 표시 진영 (0~4)",country.nativePower.toString(),readonly=!create && country.id in setOf(2,3),type="number")
            input("native_camp","원본 캠프 표시 (0~1)",country.nativeCamp.toString(),readonly=!create && country.id in setOf(2,3),type="number")
            append("<p class=\"note\">국가 ID가 소속을 구분합니다. 추가 국가 등록은 생성·가입·건국을 활성화하지 않습니다. 기존 부대와 기함은 모델을 유지하고 새 편성·생산부터 새 스타일을 사용합니다. 자산 프로필은 등록 참조이며 이미지 업로드·게임 연결은 진행 중입니다.</p>")
        }
        "family"->{
            val family=catalog.families.getValue(selected)
            input("key","계열 키",if(create)"" else family.key,readonly=!create)
            input("name","계열 이름",if(create)"" else family.name,40)
            select("calendar","기본 역법",catalog.calendars.keys.toList(),family.calendar)
            for((name,label,value) in listOf(Triple("currency","기본 통화",snapshot.familyCurrencies[family.key]),Triple("commands","커맨드 체계",family.commands),
                Triple("ranks","계급 체계",family.ranks),Triple("offices","직위 체계",family.offices),Triple("peerage","작위 체계",family.peerage),Triple("ship_style","함정 스타일",family.shipStyle)))
                select(name,label,snapshot.references.getValue(name),value)
            append("<p class=\"note\">이 계열을 쓰는 모든 국가에 기본값을 적용합니다. 커맨드·계급·직위·작위·통화는 정의 참조이며 실제 권한/잔액 이행과 별도로 연결 중입니다.</p>")
        }
        else->{
            val calendar=catalog.calendars.getValue(selected)
            input("key","역법 키",if(create)"" else calendar.key,readonly=!create)
            input("name","역법 이름",if(create)"" else calendar.name,12)
            input("year_offset","우주력에 더할 연도",if(create)"0" else calendar.yearOffset.toString(),type="number")
            append("<p class=\"note\">예: 우주력 795년에 −309를 더하면 제국력 486년입니다. 원년보다 이르면 개원전으로 표시하며 0년을 만들지 않습니다.</p>")
        }
    }
    append("""<section class="preview" aria-live="polite"><strong>미리보기</strong><p id="preview"></p></section><button>정의 저장</button></form><p id="status" role="status"></p>
<script>const form=document.getElementById('definition'),status=document.getElementById('status');let busy=false;
function preview(){const name=form.elements.name.value;const offset=form.elements.year_offset;const year=offset?795+Number(offset.value):0;document.getElementById('preview').textContent=offset?name+' '+(year>=1?year+'년':'개원전 '+(1-year)+'년')+' (우주력 795년)':name+' · '+(form.elements.ship_style?.value||'계열 기본 스타일');}
form.oninput=preview;form.onsubmit=async event=>{event.preventDefault();if(busy)return;const body=new URLSearchParams(new FormData(form));busy=true;for(const field of form.elements)field.disabled=true;try{const response=await fetch(form.action,{method:'POST',body});const text=await response.text();if(!response.ok)throw Error(response.status===409?'다른 정의가 저장됐습니다. 최신 자료를 다시 열어 수정하세요.':response.status===400?'값·참조·기존 표시 진영·모델 지원 범위를 확인하세요.':response.status===503?'DB 또는 감사 기록을 사용할 수 없습니다.':'저장 실패: '+text);status.textContent=text==='unchanged'?'변경된 정의가 없습니다.':'DB에 저장했습니다. 최신 정의를 읽는 중입니다.';if(text==='changed'){const key=body.get('entity')==='country'?body.get('id'):body.get('key');location.assign('?entity='+encodeURIComponent(body.get('entity'))+'&key='+encodeURIComponent(key));}}catch(error){status.textContent=error.message;}finally{busy=false;for(const field of form.elements)field.disabled=false;}};preview();</script></html>""")
}
