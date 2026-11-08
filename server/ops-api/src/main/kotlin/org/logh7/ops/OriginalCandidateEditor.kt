package org.logh7.ops

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.logh7.engine.*

private val abilities=listOf("통솔","정치","운영","정보","지휘","기동","공격","방어")
private val militaryRanks=listOf("원수","상급대장","대장","중장","소장","준장","대령","중령","소령","대위",
    "중위","소위","준위","상사","중사","하사","상등병","일등병","이등병")

internal fun Route.originalCandidateRoutes(store:OriginalCandidateStore?,published:PublishedOriginalCandidates,
    port:Int,audit:OpsAudit?,authenticated:Boolean) {
    get("/admin/original-candidates") {
        if(!authenticated) {
            call.respondText("운영자 인증이 필요합니다.",status=HttpStatusCode.Forbidden)
            return@get
        }
        val loaded=if(store==null)false else try {
            withContext(Dispatchers.IO){published.publish(store.load())}
            true
        } catch(_:java.sql.SQLException){false} catch(_:IllegalArgumentException){false}
        val id=call.request.queryParameters["candidate"]?.toLongOrNull()
        call.response.headers.append(HttpHeaders.CacheControl,"no-store")
        call.respondText(candidatePage(published,id,loaded && audit!=null),ContentType.Text.Html)
    }
    post("/admin/original-candidates") {
        if(!authenticated) {
            call.respondText("운영자 인증이 필요합니다.",status=HttpStatusCode.Forbidden)
            return@post
        }
        val form=call.adminForm(port) ?: return@post
        val id=form["candidate"]?.toLongOrNull()
        val revision=form["revision"]?.toLongOrNull()
        val profile=runCatching {
            OriginalCandidateProfile(form["surname"].orEmpty().trim(),form["given"].orEmpty().trim(),
                form["face"].orEmpty().toInt(),form["rank"].orEmpty().toInt(),
                abilities.indices.map {form["ability$it"].orEmpty().toInt()})
        }.getOrNull()
        call.audited(audit,OpsAction.ORIGINAL_CANDIDATE_EDIT,form["operator"],"candidate=$id,revision=$revision",
            mapOf(OpsOutcome.CONFLICT to "편집 버전이 바뀌었습니다. 최신 화면을 다시 여세요.",
                OpsOutcome.INVALID to "인물 이름, 얼굴 번호, 계급과 능력치 범위를 확인하세요.")) {
            if(id==null || revision==null || revision<0 || profile==null || !published.accepts(id,profile))
                return@audited OpsOutcome.INVALID
            if(store==null)return@audited OpsOutcome.UNAVAILABLE
            withContext(Dispatchers.IO) {
                val saved=store.save(id,revision,profile) ?: return@withContext OpsOutcome.CONFLICT
                published.publish(listOf(saved))
                OpsOutcome.CHANGED
            }
        }
    }
}

private fun candidatePage(published:PublishedOriginalCandidates,id:Long?,editable:Boolean)=buildString {
    fun escape(text:String)=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun number(name:String,value:Int,min:Int,max:Int)=
        "<input type=\"number\" name=\"$name\" value=\"$value\" min=\"$min\" max=\"$max\" required>"
    val edits=published.current()
    val definitions=published.defaults
    val selected=definitions[id] ?: definitions.values.firstOrNull()
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LOGH7 원작 인물 추첨</title><style>body{margin:40px auto;max-width:720px;padding:0 20px;background:#101b2a;color:#e6eef8;font:16px/1.6 system-ui,sans-serif}a{color:#9ed2ff}label{display:block;margin:8px 0}input,select,button{font:inherit;max-width:100%;padding:5px}fieldset{margin:16px 0}.abilities{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr))}</style>
<nav><a href="/admin">운영 현황</a></nav><h1>원작 인물 추첨</h1><p>현재 월드의 추첨 후보를 편집합니다. 새 추첨 결과부터 적용되며 이미 당첨된 캐릭터는 기존 프로필을 사용합니다.</p>""")
    if(selected==null) {append("<p>등록된 추첨 후보가 없습니다.</p></html>");return@buildString}
    append("<form method=\"get\"><label>후보 <select name=\"candidate\">")
    for(candidate in definitions.values) {
        val profile=edits[candidate.id]?.profile ?: candidate.profile
        append("<option value=\"${candidate.id}\"${if(candidate==selected)" selected" else ""}>${escape(profile.surname)} ${escape(profile.givenName)} · ${candidate.id}</option>")
    }
    append("</select></label><button>열기</button></form>")
    val edit=edits[selected.id]
    val profile=edit?.profile ?: selected.profile
    val empire=selected.creationPower==2
    val revision=edit?.revision ?: 0
    append("<p>생성 계열 ${if(empire)"제국" else "동맹"} · 편집 버전 $revision</p>")
    if(!editable)append("<p>저장하려면 운영자 인증, 감사 기록, 정의 DB V11이 필요합니다.</p>")
    append("<form id=\"edit\" method=\"post\"><fieldset${if(editable)"" else " disabled"}><legend>추첨 시작 데이터</legend>")
    append("<input type=\"hidden\" name=\"candidate\" value=\"${selected.id}\"><input type=\"hidden\" name=\"revision\" value=\"$revision\">")
    append("<label>운영자 <input name=\"operator\" maxlength=\"64\"></label>")
    append("<label>성 <input name=\"surname\" maxlength=\"12\" value=\"${escape(profile.surname)}\" required></label>")
    append("<label>이름 <input name=\"given\" maxlength=\"12\" value=\"${escape(profile.givenName)}\" required></label>")
    val faces=if(empire)1..158 else 100001..100108
    append("<label>원본 얼굴 번호 ${number("face",profile.face,faces.first,faces.last)} (${faces.first}–${faces.last})</label>")
    append("<label>시작 계급 <select name=\"rank\">")
    for((index,rank) in militaryRanks.withIndex()) {
        val code=index+2
        if(empire || code!=3)append("<option value=\"$code\"${if(code==profile.rank)" selected" else ""}>$rank</option>")
    }
    append("</select></label><div class=\"abilities\">")
    for((index,name) in abilities.withIndex())append("<label>$name ${number("ability$index",profile.abilities[index],0,255)}</label>")
    append("</div><button>저장</button></fieldset></form><p id=\"status\" role=\"status\"></p>")
    append("""<script>const form=document.querySelector('#edit'),status=document.querySelector('#status');
form.onsubmit=async e=>{e.preventDefault();const fields=form.querySelector('fieldset'),body=new URLSearchParams(new FormData(form));fields.disabled=true;
try{const response=await fetch(form.action,{method:'POST',body});if(!response.ok){status.textContent=await response.text();return;}
location.replace('?candidate='+encodeURIComponent(body.get('candidate')));}catch{status.textContent='저장 상태를 확인할 수 없습니다.';}finally{fields.disabled=false;}};</script></html>""")
}
