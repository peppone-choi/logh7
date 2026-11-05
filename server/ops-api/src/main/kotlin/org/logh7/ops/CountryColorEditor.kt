package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.logh7.engine.*

/** Country colors are independent of ally/enemy indicators. Author: Peppone Choi (peppone-choi). */
internal fun Route.countryColorRoutes(store:CountryColorStore?,port:Int,audit:OpsAudit?) {
    get("/admin/countries") {
        val countries=if(store==null)null else try {withContext(Dispatchers.IO){store.list()}} catch(_:java.sql.SQLException){null}
        call.response.headers.append("Cache-Control","no-store")
        call.respondText(countryColorPage(countries,call.request.queryParameters["country"]?.toIntOrNull()),ContentType.Text.Html,
            if(countries==null)HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
    }
    post("/admin/countries/colors") {
        val form=call.adminForm(port) ?: return@post
        val parsed=runCatching {
            val id=checkNotNull(form["country"]?.toIntOrNull());val revision=checkNotNull(form["revision"]?.toLongOrNull())
            require(id>0 && revision>=0)
            val colors=CountryColors(CountryColors.parse(form["primary"].orEmpty()),CountryColors.parse(form["secondary"].orEmpty()),
                CountryColors.parse(form["map"].orEmpty()),CountryColors.parse(form["fleet"].orEmpty()))
            Triple(id,revision,colors)
        }.getOrNull()
        val details=parsed?.let {(id,revision,c)->"country=$id,revision=$revision,primary=${CountryColors.hex(c.primary)},secondary=${CountryColors.hex(c.secondary)},map=${CountryColors.hex(c.map)},fleet=${CountryColors.hex(c.fleet)}"}
        call.audited(audit,OpsAction.COUNTRY_COLOR_EDIT,form["operator"],details,mapOf(
            OpsOutcome.INVALID to "invalid country or #RRGGBB color values",OpsOutcome.NOT_FOUND to "country not found",
            OpsOutcome.CONFLICT to "country colors changed; reload before editing")) {
            if(parsed==null)return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            when(withContext(Dispatchers.IO){store.save(parsed.first,parsed.second,parsed.third)}) {
                CountryColorOutcome.CHANGED->OpsOutcome.CHANGED
                CountryColorOutcome.UNCHANGED->OpsOutcome.UNCHANGED
                CountryColorOutcome.CONFLICT->OpsOutcome.CONFLICT
                CountryColorOutcome.NOT_FOUND->OpsOutcome.NOT_FOUND
            }
        }
    }
}

internal fun countryColorPage(countries:List<CountryAppearance>?,selected:Int?):String=buildString {
    fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LOGH7 국가 색상</title>
<style>body{font:16px/1.6 system-ui,"Malgun Gothic",sans-serif;max-width:1000px;margin:auto;padding:24px;background:#101b2a;color:#e6eef8}a{color:#9ed2ff}input,select,button{font:inherit;padding:6px;margin:5px}select{max-width:100%}button{cursor:pointer}label{display:block;margin:14px 0}input[type=color]{width:70px;height:40px;vertical-align:middle;padding:2px}input:disabled{opacity:.4}.panes{display:flex;flex-wrap:wrap;gap:40px}.preview{min-width:260px;padding:24px;background:#0a1521;border:1px solid #3b5067;border-radius:8px;align-self:start}.badge{border:3px solid var(--primary,transparent);padding:16px;color:var(--secondary,#dce8f2);min-width:200px}.map{display:inline-block;width:22px;height:22px;margin:20px;border:2px solid #728299;border-radius:50%;background:var(--map,transparent)}.fleet{display:inline-block;margin:20px;color:var(--fleet,#728299);font-size:32px}.friendly{color:#87cbff}.hostile{color:#ffa69d}.note{font-size:13px;color:#a3b9cd}#status{min-height:32px;white-space:pre-wrap}</style>
<nav><a href="/admin">운영 현황</a> · <a href="/admin/countries/definitions">국가 정의</a> · <a href="/admin/world">세계 편집</a> · <a href="/admin/progress">작업 진행</a></nav><h1>국가 색상</h1>
<p>국가별 대표색·보조색·지도·함대 표시색을 정합니다. 재정의를 끄면 원본 자산의 색상을 유지합니다.</p>""")
    if(countries==null){append("<p>국가 정의 DB를 사용할 수 없습니다. 운영 설정과 DB 연결 상태를 확인하세요.</p></html>");return@buildString}
    if(countries.isEmpty()){append("<p>등록된 국가가 없습니다. 국가 정의를 DB에 가져온 뒤 편집할 수 있습니다.</p></html>");return@buildString}
    val country=countries.firstOrNull {it.countryId==selected} ?: countries.first()
    append("<form method=\"get\"><label>국가 <select name=\"country\">")
    countries.forEach {append("<option value=\"${it.countryId}\"${if(it.countryId==country.countryId)" selected" else ""}>${it.countryId} · ${escape(it.name)}</option>")}
    append("</select><button>열기</button></label></form><p class=\"note\">계열: ${escape(country.family)} · 편집 버전 ${country.revision}</p>")
    append("""<p class="note">저장한 지도색은 게임의 전략 지도 이름·국가 아이콘에 반영됩니다. 다른 화면은 연결 중입니다. 아군·적군 식별은 국가색과 별도로 유지합니다.</p><div class="panes"><form id="colors" action="/admin/countries/colors" method="post">""")
    append("<input type=\"hidden\" name=\"country\" value=\"${country.countryId}\"><input type=\"hidden\" name=\"revision\" value=\"${country.revision}\"><label>운영자 <input name=\"operator\" pattern=\"[A-Za-z0-9._-]{1,64}\" required autocomplete=\"username\"></label>")
    for((key,label,value) in listOf(Triple("primary","대표색",country.colors.primary),Triple("secondary","보조색",country.colors.secondary),Triple("map","지도 표시색",country.colors.map),Triple("fleet","함대 표시색",country.colors.fleet)))
        append("<label>$label <input type=\"checkbox\" name=\"${key}_override\"${if(value!=null)" checked" else ""}> 재정의 <input type=\"color\" name=\"$key\" aria-label=\"$label 선택\" value=\"${CountryColors.hex(value ?: 0)}\"${if(value==null)" disabled" else ""}><output id=\"${key}_label\"></output></label>")
    append("""<button>색상 저장</button><button type="button" id="defaults">원본 자산색으로</button></form><section id="preview" class="preview"><h2>미리보기</h2>""")
    append("<div class=\"badge\">${escape(country.name)}</div>")
    append("""<div><span class="map" aria-label="지도 표시"></span><span class="fleet" aria-label="함대 표시">△</span></div><p><span class="friendly">○ 아군</span> · <span class="hostile">◇ 적군</span></p><p class="note">회색·투명 표시는 원본 자산색을 지정하지 않은 상태입니다.</p></section></div><p id="status" role="status"></p>
<script>
const form=document.getElementById('colors'),preview=document.getElementById('preview'),status=document.getElementById('status'),keys=['primary','secondary','map','fleet'];let busy=false;
function refresh(){for(const key of keys){const enabled=form.elements[key+'_override'].checked;form.elements[key].disabled=!enabled||busy;preview.style.setProperty('--'+key,enabled?form.elements[key].value:(key==='secondary'?'#dce8f2':key==='fleet'?'#728299':'transparent'));document.getElementById(key+'_label').textContent=enabled?form.elements[key].value.toUpperCase():'원본 유지';}}
form.oninput=refresh;document.getElementById('defaults').onclick=()=>{if(busy)return;for(const key of keys)form.elements[key+'_override'].checked=false;refresh();};
form.onsubmit=async event=>{event.preventDefault();if(busy)return;const body=new URLSearchParams(new FormData(form));for(const key of keys)body.set(key,form.elements[key+'_override'].checked?form.elements[key].value:'inherit');busy=true;for(const field of form.elements)field.disabled=true;try{const response=await fetch(form.action,{method:'POST',body});const text=await response.text();if(!response.ok)throw Error(response.status===409?'다른 변경이 저장됐습니다. 이 국가를 다시 열어 최신 색상에서 수정하세요.':response.status===503?'국가 DB 또는 감사 기록을 사용할 수 없습니다. 운영 상태를 확인하세요.':'저장 실패: '+text);if(text==='changed')form.elements.revision.value=(BigInt(form.elements.revision.value)+1n).toString();status.textContent=text==='unchanged'?'변경된 색상이 없습니다.':'색상을 DB에 저장했습니다.';}catch(error){status.textContent=error.message;}finally{busy=false;for(const field of form.elements)field.disabled=false;refresh();}};refresh();
</script></html>""")
}
