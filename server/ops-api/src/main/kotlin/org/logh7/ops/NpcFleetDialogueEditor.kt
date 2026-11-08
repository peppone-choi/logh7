package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.logh7.engine.*

private fun PlanetStocks.editableNpcFleets()=outfits.values.filter { outfit ->
    outfit.activeShips?.values?.any {it.boats>0}==true && TacticalAuthority.uncommanded(outfit,this)
}.sortedBy {it.id}

internal fun Route.npcFleetDialogueRoutes(snapshots:SnapshotSource,store:NpcFleetDialogueStore?,published:PublishedNpcFleetDialogue,
    port:Int,audit:OpsAudit?,authenticated:Boolean) {
    get("/admin/npc-dialogue") {
        if(!authenticated) {call.respondText("운영자 인증이 필요합니다.",status=HttpStatusCode.Forbidden);return@get}
        val stocks=snapshots.snapshot().planetStocks
        val loaded=if(store!=null && stocks!=null)try {
            withContext(Dispatchers.IO){store.load(stocks.generation)}.also {published.publish(stocks.generation,it)}
        } catch(_:java.sql.SQLException){null} catch(_:IllegalArgumentException){null} else null
        val fleets=stocks?.editableNpcFleets().orEmpty()
        val requested=call.request.queryParameters["fleet"]?.toIntOrNull()
        val selected=fleets.firstOrNull {it.id==requested} ?: fleets.firstOrNull()
        val row=selected?.let {fleet->stocks?.let {published.current(it.generation,fleet.id)}}
        call.response.headers.append(HttpHeaders.CacheControl,"no-store")
        call.respondText(npcDialoguePage(stocks?.generation,fleets,selected,row,loaded!=null && audit!=null),ContentType.Text.Html)
    }
    post("/admin/npc-dialogue") {
        if(!authenticated) {call.respondText("운영자 인증이 필요합니다.",status=HttpStatusCode.Forbidden);return@post}
        val form=call.adminForm(port) ?: return@post
        val generation=form["generation"]?.toLongOrNull()
        val outfit=form["fleet"]?.toIntOrNull()
        val revision=form["revision"]?.toLongOrNull()
        val behavior=NpcCombatBehavior.fromKey(form["behavior"])
        val dialogue=runCatching {NpcFleetDialogue(form["fallback"].orEmpty().trim(),form["style"].orEmpty().trim(),
            form["prompt"].orEmpty().replace("\r\n","\n").trim())}.getOrNull()
        call.audited(audit,OpsAction.NPC_DIALOGUE_EDIT,form["operator"],"generation=$generation,fleet=$outfit,revision=$revision",
            mapOf(OpsOutcome.CONFLICT to "세션 또는 편집 버전이 바뀌었습니다. 최신 화면을 다시 여세요.",
                OpsOutcome.INVALID to "함대 또는 입력값을 확인하세요.")) {
            if(generation==null || generation<=0 || outfit==null || outfit<=0 || revision==null || revision<0 || dialogue==null || behavior==null)
                return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            val stocks=snapshots.snapshot().planetStocks ?: return@audited OpsOutcome.UNAVAILABLE
            if(stocks.generation!=generation || stocks.editableNpcFleets().none {it.id==outfit})return@audited OpsOutcome.CONFLICT
            withContext(Dispatchers.IO) {
                val row=store.save(generation,outfit,revision,dialogue,behavior) ?: return@withContext OpsOutcome.CONFLICT
                published.publish(generation,listOf(row))
                OpsOutcome.CHANGED
            }
        }
    }
}

private fun npcDialoguePage(generation:Long?,fleets:List<OutfitStock>,selected:OutfitStock?,row:NpcFleetDialogueRow?,editable:Boolean)=buildString {
    fun escape(text:String)=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LOGH7 NPC 교신</title><style>body{margin:40px auto;max-width:720px;padding:0 20px;background:#101b2a;color:#e6eef8;font:16px/1.6 system-ui,sans-serif}a{color:#9ed2ff}label{display:block;margin:12px 0}input,textarea,select,button{font:inherit;max-width:100%;box-sizing:border-box;padding:6px}input,textarea{display:block;width:100%}textarea{min-height:7em}button{margin:4px 0}</style>
<nav><a href="/admin">운영 현황</a></nav><h1>NPC 교신과 행동</h1><p>현재 세션의 무지휘 함대를 편집합니다. 기본 대사와 행동 성향은 모델 연결 없이 적용됩니다. 말투와 지침은 모델로 전달되므로 비밀 정보를 넣지 마세요.</p>""")
    if(selected==null || generation==null) {append("<p>편집할 NPC 함대가 없습니다.</p></html>");return@buildString}
    append("<form method=\"get\"><label>함대 <select name=\"fleet\">")
    for(fleet in fleets)append("<option value=\"${fleet.id}\"${if(fleet==selected)" selected" else ""}>함대 ${fleet.id} · 국가 ${fleet.country.value} · 거점 ${fleet.baseId}</option>")
    append("</select></label><button>열기</button></form>")
    val dialogue=row?.dialogue ?: NpcFleetDialogue()
    append("<p>세대 $generation · 편집 버전 ${row?.revision ?: 0}</p>")
    if(!editable)append("<p>저장하려면 운영자 인증, 감사 기록, 정의 DB V10이 필요합니다.</p>")
    append("<form id=\"edit\" method=\"post\" action=\"/admin/npc-dialogue\"><fieldset${if(editable)"" else " disabled"}>")
    append("<input type=\"hidden\" name=\"generation\" value=\"$generation\"><input type=\"hidden\" name=\"fleet\" value=\"${selected.id}\"><input type=\"hidden\" name=\"revision\" value=\"${row?.revision ?: 0}\">")
    append("<label>운영자 <input name=\"operator\" maxlength=\"64\"></label>")
    append("<label>기본 대사 <input name=\"fallback\" maxlength=\"16\" value=\"${escape(dialogue.fallback)}\" placeholder=\"${NpcFleetDialogue.DEFAULT_LINE}\"></label>")
    append("<label>말투 <input name=\"style\" maxlength=\"80\" value=\"${escape(dialogue.style)}\"></label>")
    append("<label>대사 지침 <textarea name=\"prompt\" maxlength=\"500\">${escape(dialogue.prompt)}</textarea></label>")
    append("<label>행동 성향 <select name=\"behavior\">")
    val behavior=row?.behavior ?: NpcCombatBehavior.STANDARD
    for(option in NpcCombatBehavior.entries) {
        val label=when(option) {
            NpcCombatBehavior.STANDARD->"기본"
            NpcCombatBehavior.PRECISION->"정밀 사격"
            NpcCombatBehavior.CAUTIOUS->"전력 보존"
        }
        append("<option value=\"${option.key}\"${if(option==behavior)" selected" else ""}>$label</option>")
    }
    append("</select></label><p>정밀 사격은 명중 효과가 높은 적을 우선합니다. 전력 보존은 남은 함정이 편제의 절반 이하일 때 철수를 시도합니다.</p>")
    append("<button>저장</button></fieldset></form><p id=\"status\" role=\"status\"></p>")
    append("""<script>const form=document.querySelector('#edit'),status=document.querySelector('#status');
form.onsubmit=async e=>{e.preventDefault();const fields=form.querySelector('fieldset'),body=new URLSearchParams(new FormData(form));fields.disabled=true;
try{const response=await fetch(form.action,{method:'POST',body});if(!response.ok){status.textContent=await response.text();return;}
location.replace('?fleet='+encodeURIComponent(body.get('fleet')));}catch{status.textContent='저장 상태를 확인할 수 없습니다.';}finally{fields.disabled=false;}};</script></html>""")
}
