package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import org.logh7.engine.*

/** Auth is inherited from the existing operator API. Author: Peppone Choi (peppone-choi). */
internal fun Route.worldEditorRoutes(commands:CommandSink,snapshots:SnapshotSource,port:Int,audit:OpsAudit?,galaxy:Galaxy?) {
    suspend fun inventory():PlanetStocks? {
        val state=snapshots.snapshot()
        if(galaxy==null || snapshots.health()!=EngineHealth.RUNNING)return state.planetStocks
        val generation=state.session?.generation ?: return state.planetStocks
        val result=CompletableDeferred<PlanetStocks>()
        commands.submit(WorldCommand.Stock(generation,result))
        return result.await()
    }
    get("/admin/world") {
        val stocks=inventory()
        val base=call.request.queryParameters["base"]?.toIntOrNull()
        call.respondText(worldEditorPage(stocks,base,galaxy),ContentType.Text.Html)
    }
    get("/admin/world/live") {
        val stocks=inventory()
        val id=call.request.queryParameters["base"]?.toIntOrNull()
        val base=stocks?.bases?.get(id)
        if(base==null)call.respondText("base unavailable",status=HttpStatusCode.NotFound)
        else {
            val version=AdminWorldEditing.version(stocks.generation,base)
            val economy=base.economy?.let {"""{"population":${it.population},"adults":${it.adults},"approval":${it.approval},"peace":${it.peace}}"""} ?: "null"
            // Long balances and generations stay decimal strings in JavaScript.
            call.respondText("""{"generation":"${stocks.generation}","base":${base.baseId},"version":"$version","supplies":${base.supplies},"food":${base.food},"mineral":${base.mineral},"treasury":"${base.treasury}","economy":$economy}""",ContentType.Application.Json)
        }
    }
    post("/admin/world/base") {
        val form=call.adminForm(port) ?: return@post
        val request=runCatching {
            val economy=if(listOf("population","adults","approval","peace").any {form[it]!=null})PlanetEconomy(
                checkNotNull(form["population"]?.toIntOrNull()),checkNotNull(form["adults"]?.toIntOrNull()),
                checkNotNull(form["approval"]?.toIntOrNull()),checkNotNull(form["peace"]?.toIntOrNull())) else null
            AdminBaseEdit(
            checkNotNull(form["generation"]?.toLongOrNull()),checkNotNull(form["base"]?.toIntOrNull()),form["version"].orEmpty(),
            checkNotNull(form["supplies"]?.toIntOrNull()),checkNotNull(form["food"]?.toIntOrNull()),
            checkNotNull(form["mineral"]?.toIntOrNull()),checkNotNull(form["treasury"]?.toLongOrNull()),economy)}.getOrNull()
        val details=request?.let {"base=${it.baseId},generation=${it.generation},supplies=${it.supplies},food=${it.food},mineral=${it.mineral},treasury=${it.treasury}"+
            it.economy?.let {e->",population=${e.population},adults=${e.adults},approval=${e.approval},peace=${e.peace}"}.orEmpty()}
        val messages=mapOf(OpsOutcome.INVALID to "값을 확인하세요. 성인 인구는 전체 인구 이하, 민심·치안은 0~100%여야 합니다. 재고·인구·금고는 음수일 수 없습니다.",
            OpsOutcome.NOT_FOUND to "거점을 찾을 수 없습니다.",OpsOutcome.CONFLICT to "이 거점이 변경됐습니다. 최신 값을 불러온 뒤 다시 수정하세요.")
        call.audited(audit,OpsAction.WORLD_BASE_EDIT,form["operator"].orEmpty(),details,messages) {
            if(request==null)return@audited OpsOutcome.INVALID
            val result=CompletableDeferred<AdminBaseEditResult>()
            commands.submit(WorldCommand.AdminEditBase(request,result))
            when(result.await().outcome) {
                AdminEditOutcome.CHANGED -> OpsOutcome.CHANGED
                AdminEditOutcome.UNCHANGED -> OpsOutcome.UNCHANGED
                AdminEditOutcome.CONFLICT -> OpsOutcome.CONFLICT
                AdminEditOutcome.INVALID -> OpsOutcome.INVALID
                AdminEditOutcome.NOT_FOUND -> OpsOutcome.NOT_FOUND
                AdminEditOutcome.UNAVAILABLE -> OpsOutcome.UNAVAILABLE
            }
        }
    }
}

internal fun worldEditorPage(stocks:PlanetStocks?,selected:Int?,galaxy:Galaxy?):String = buildString {
    fun esc(value:Any?)=value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LOGH7 세계 편집</title>
<style>body{font:16px/1.6 system-ui,sans-serif;max-width:1000px;margin:auto;padding:24px;background:#101b2a;color:#e6eef8}a{color:#9ed2ff}input,select,button{font:inherit;padding:8px;margin:5px}input,select{max-width:100%}label{display:block}table{border-collapse:collapse}td,th{padding:8px 20px;border:1px solid #789}button{cursor:pointer}#status{min-height:32px;white-space:pre-wrap}.panes{display:flex;flex-wrap:wrap;gap:40px}</style>
<nav><a href="/admin">운영 현황</a> · <a href="/admin/text/">문구 편집</a></nav><h1>세계 편집</h1>
<p>행성·거점의 현재 재고·금고와 인구·민심·치안을 수정합니다. 새로 조회한 값은 오른쪽에 표시하며 편집 중인 입력은 유지합니다.</p>""")
    if(stocks==null || stocks.bases.isEmpty()) {append("<p>세계 재고를 사용할 수 없습니다. 세계 정의와 저장소 설정을 확인하세요.</p></html>");return@buildString}
    val base=stocks.bases[selected] ?: stocks.bases.toSortedMap().values.first()
    val names=galaxy?.bases.orEmpty().associate {it.id to it.name}
    append("<form method=\"get\"><label>거점 <select name=\"base\">")
    for((id,_) in stocks.bases.toSortedMap())append("<option value=\"$id\"${if(id==base.baseId)" selected" else ""}>$id · ${esc(names[id] ?: "거점")}</option>")
    append("</select><button>열기</button></label></form><div class=\"panes\"><section><h2>수정할 값</h2>")
    append("<form id=\"edit\" action=\"/admin/world/base\" method=\"post\"><input type=\"hidden\" name=\"generation\" value=\"${stocks.generation}\"><input type=\"hidden\" name=\"base\" value=\"${base.baseId}\"><input type=\"hidden\" name=\"version\" value=\"${AdminWorldEditing.version(stocks.generation,base)}\">")
    append("<label>운영자 <input name=\"operator\" pattern=\"[A-Za-z0-9._-]{1,64}\" required autocomplete=\"username\"></label>")
    fun number(key:String,label:String,value:Int,maximum:Int=Int.MAX_VALUE) {
        append("<label>$label <input name=\"$key\" type=\"number\" min=\"0\" max=\"$maximum\" step=\"1\" value=\"$value\" required></label>")
    }
    for((key,label,value) in listOf(Triple("supplies","군수물자",base.supplies),Triple("food","식량",base.food),Triple("mineral","광물",base.mineral)))
        number(key,label,value)
    append("<label>거점 금고 <input name=\"treasury\" inputmode=\"numeric\" pattern=\"[0-9]{1,19}\" value=\"${base.treasury}\" required></label>")
    base.economy?.let {
        append("<h3>인구와 민심·치안</h3>")
        number("population","전체 인구 (명)",it.population);number("adults","성인 인구 (명)",it.adults)
        number("approval","민심 (%)",it.approval,100);number("peace","치안 (%)",it.peace,100)
        append("<p>성인 인구는 전체 인구를 넘을 수 없습니다.</p>")
    } ?: append("<p>인구 정보가 아직 없습니다. 화면을 다시 열어 확인하세요.</p>")
    append("<button id=\"save\">현재 세계에 저장</button><button type=\"button\" id=\"reload\">최신 값 불러오기</button></form></section>")
    append("""<section><h2>실시간 현재 값</h2><table><tbody><tr><th>세대</th><td id="live-generation"></td></tr><tr><th>군수물자</th><td id="live-supplies"></td></tr><tr><th>식량</th><td id="live-food"></td></tr><tr><th>광물</th><td id="live-mineral"></td></tr><tr><th>거점 금고</th><td id="live-treasury"></td></tr><tr><th>전체 인구 (명)</th><td id="live-population"></td></tr><tr><th>성인 인구 (명)</th><td id="live-adults"></td></tr><tr><th>민심</th><td id="live-approval"></td></tr><tr><th>치안</th><td id="live-peace"></td></tr></tbody></table></section></div><p id="status" role="status"></p>
<script>
const form=document.getElementById('edit'),status=document.getElementById('status');let busy=false,sequence=0;
const economyKeys=['population','adults','approval','peace'];
async function refresh(){const request=++sequence;try{const response=await fetch('/admin/world/live?base='+encodeURIComponent(form.elements.base.value),{cache:'no-store'});if(!response.ok)throw Error('현재 값 조회 실패: '+response.status);const current=await response.json();if(request!==sequence)return null;for(const key of ['generation','supplies','food','mineral','treasury'])document.getElementById('live-'+key).textContent=current[key];for(const key of economyKeys)document.getElementById('live-'+key).textContent=current.economy?current.economy[key]+(['approval','peace'].includes(key)?'%':''):'미집계';if(current.version!==form.elements.version.value)status.textContent='이 거점이 변경됐습니다. 최신 값을 불러온 뒤 다시 수정하세요.';return current;}catch(error){if(request===sequence)status.textContent=error.message;return null;}}
function populate(current){for(const key of ['generation','version','supplies','food','mineral','treasury'])form.elements[key].value=current[key];if(current.economy)for(const key of economyKeys)if(form.elements[key])form.elements[key].value=current.economy[key];}
document.getElementById('reload').onclick=async()=>{if(busy)return;const current=await refresh();if(current){populate(current);status.textContent='최신 값을 불러왔습니다.';}};
form.onsubmit=async event=>{event.preventDefault();if(busy)return;const body=new URLSearchParams(new FormData(form));busy=true;for(const input of form.elements)input.disabled=true;try{const response=await fetch(form.action,{method:'POST',body});const text=await response.text();if(!response.ok)throw Error(response.status===409?'이 거점이 변경됐습니다. 최신 값을 불러온 뒤 다시 수정하세요.':text);const current=await refresh();if(current)populate(current);status.textContent=(text==='unchanged'?'변경된 값이 없습니다.':'저장했습니다. 다음 게임 조회부터 반영됩니다.')+(current?'':' 현재 값 재조회에 실패했습니다. 다시 불러오세요.');}catch(error){status.textContent=error.message;}finally{busy=false;for(const input of form.elements)input.disabled=false;}};
refresh();setInterval(()=>{if(!busy)refresh();},2000);
</script></html>""")
}
