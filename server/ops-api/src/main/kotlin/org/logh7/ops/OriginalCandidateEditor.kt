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
private val peerageNames=listOf("공작","후작","백작","자작","남작","제국기사")

private fun CountryCatalog.candidatePower(id:Int)=countries[id]?.takeIf {it.nativePower in 2..3}
    ?.let {families.getValue(it.family).creationPower}

private fun OriginalStartingOffice.label()=when(this) {
    OriginalStartingOffice.REINHARD_FLEET_2 -> "라인하르트 · 제2함대 사령관 (오딘)"
    OriginalStartingOffice.VUCOCK_FLEET_5 -> "뷰코크 · 제5함대 사령관 (산타 아나)"
}

/** The definition DB migrations still missing before [office] can be stored, or null when the store accepts it. */
private fun missingOfficeMigration(store:OriginalCandidateStore?,office:OriginalStartingOffice):String? {
    val alliance=office==OriginalStartingOffice.VUCOCK_FLEET_5
    return when {
        store?.supportsStartingOffice!=true -> if(alliance)"V16·V17" else "V16"
        alliance && !store.supportsAllianceStartingOffice -> "V17"
        else -> null
    }
}

internal fun Route.originalCandidateRoutes(store:OriginalCandidateStore?,published:PublishedOriginalCandidates,images:CountryImageStore?,
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
        val newCountry=call.request.queryParameters["new"]?.toIntOrNull()
        val portraits=if(loaded && store?.supportsPortraits==true) try {
            withContext(Dispatchers.IO){images?.list()?.filter {it.kind==CountryImageKind.PORTRAIT}}
        } catch(_:java.sql.SQLException){null} else null
        call.response.headers.append(HttpHeaders.CacheControl,"no-store")
        val editable=loaded && audit!=null && store?.supportsEditing==true && (!store.supportsPortraits || portraits!=null)
        call.respondText(candidatePage(published,id,newCountry,editable,store?.supportsFemaleFaces==true,
            store?.supportsPortraits==true,portraits,OriginalStartingOffice.entries.associateWith {missingOfficeMigration(store,it)}),
            ContentType.Text.Html)
    }
    post("/admin/original-candidates") {
        if(!authenticated) {
            call.respondText("운영자 인증이 필요합니다.",status=HttpStatusCode.Forbidden)
            return@post
        }
        val form=call.adminForm(port) ?: return@post
        val id=form["candidate"]?.toLongOrNull()
        val revision=form["revision"]?.toLongOrNull()
        val rawCountry=form["country"]
        val country=rawCountry?.toIntOrNull()?.takeIf {it>0}?.let(::CountryId)
        val creating=form["create"]=="1"
        val portrait=form["portrait"]?.takeIf {it.isNotEmpty()}
        val existingOffice=id?.let {published.current()[it]?.profile?.startingOffice}
        val profile=runCatching {
            val title=requireNotNull(form["peerage"]).toInt()
            require(title in 0..Peerage.entries.size)
            val office=when(val key=form["startingOffice"]) {
                null -> existingOffice
                "" -> null
                else -> requireNotNull(OriginalStartingOffice.fromKey(key))
            }
            OriginalCandidateProfile(form["surname"].orEmpty().trim(),form["given"].orEmpty().trim(),
                form["face"].orEmpty().toInt(),form["rank"].orEmpty().toInt(),
                abilities.indices.map {form["ability$it"].orEmpty().toInt()},Peerage.entries.getOrNull(title-1),portrait,office)
        }.getOrNull()
        call.audited(audit,OpsAction.ORIGINAL_CANDIDATE_EDIT,form["operator"],
            "candidate=$id,revision=$revision,country=${country?.value},create=$creating,portrait=${profile?.portraitKey}",
            mapOf(OpsOutcome.CONFLICT to "이미 쓰는 번호이거나 편집 버전이 바뀌었거나 국가의 후보 한도에 도달했습니다.",
                OpsOutcome.INVALID to "후보 번호, 응모 국가, 얼굴 번호, 등록 초상, 계급·작위와 능력치를 확인하세요.")) {
            if(id==null || revision==null || revision<0 || profile==null || rawCountry==null ||
                (rawCountry.isNotEmpty() && country==null) || !published.accepts(id,profile,country))
                return@audited OpsOutcome.INVALID
            val catalog=CountryCatalogLoader.current()
            if(country!=null && catalog.candidatePower(country.value)!=profile.creationPower)
                return@audited OpsOutcome.INVALID
            if(profile.peerage!=null && (!published.canGrantPeerage || country==null || !catalog.supportsNativePeerage(country)))
                return@audited OpsOutcome.INVALID
            if(creating) {
                if(revision!=0L || id in published.defaults || country==null)return@audited OpsOutcome.INVALID
            } else if(id !in published.defaults && id !in published.current())return@audited OpsOutcome.INVALID
            if(store?.supportsEditing!=true || (profile.gender==1 && !store.supportsFemaleFaces))
                return@audited OpsOutcome.UNAVAILABLE
            if(portrait!=null && !store.supportsPortraits)return@audited OpsOutcome.UNAVAILABLE
            if(profile.startingOffice?.let {missingOfficeMigration(store,it)}!=null)return@audited OpsOutcome.UNAVAILABLE
            if(store.supportsPortraits && form["portrait"]==null)return@audited OpsOutcome.INVALID
            withContext(Dispatchers.IO) {
                val saved=try {store.save(id,revision,profile,country)}
                    catch(_:IllegalArgumentException){return@withContext OpsOutcome.INVALID}
                if(saved==null)return@withContext OpsOutcome.CONFLICT
                published.publish(listOf(saved))
                OpsOutcome.CHANGED
            }
        }
    }
}

private fun candidatePage(published:PublishedOriginalCandidates,id:Long?,newCountry:Int?,editable:Boolean,femaleFaces:Boolean,
    portraitSchema:Boolean,portraits:List<CountryImageInfo>?,officeMigrations:Map<OriginalStartingOffice,String?>)=buildString {
    fun escape(text:String)=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun number(name:String,value:Int,min:Int,max:Int)=
        "<input type=\"number\" name=\"$name\" value=\"$value\" min=\"$min\" max=\"$max\" required>"
    val edits=published.current()
    val definitions=published.defaults
    val catalog=CountryCatalogLoader.current()
    val countries=catalog.countries.values.filter {catalog.candidatePower(it.id)!=null}
    val newPower=newCountry?.let(catalog::candidatePower)
    val creating=newPower!=null
    val profiles=definitions.mapValues {it.value.profile}+edits.mapValues {it.value.profile}
    val selected=if(creating)null else id?.takeIf {it in profiles} ?: profiles.keys.firstOrNull()
    append("""<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LOGH7 원작 인물 추첨</title><style>body{margin:40px auto;max-width:720px;padding:0 20px;background:#101b2a;color:#e6eef8;font:16px/1.6 system-ui,sans-serif}a{color:#9ed2ff}label{display:block;margin:8px 0}input,select,button{font:inherit;max-width:100%;padding:5px}fieldset{margin:16px 0}.abilities{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr))}</style>
<nav><a href="/admin">운영 현황</a></nav><h1>원작 인물 추첨</h1><p>현재 월드의 후보와 응모할 수 있는 국가를 관리합니다. 이미 당첨된 캐릭터는 기존 프로필을 사용합니다.</p>""")
    append("<form method=\"get\"><label>후보 <select name=\"candidate\">")
    for((candidate,profile) in profiles.toSortedMap()) {
        val country=edits[candidate]?.country?.value?.let {catalog.countries[it]?.name}
        append("<option value=\"$candidate\"${if(candidate==selected)" selected" else ""}>${escape(profile.surname)} ${escape(profile.givenName)} · $candidate · ${escape(country ?: "계열 공용")}</option>")
    }
    append("</select></label><button>열기</button></form>")
    if(editable && countries.isNotEmpty()) {
        append("<form method=\"get\"><label>새 후보를 추가할 국가 <select name=\"new\">")
        for(country in countries)append("<option value=\"${country.id}\"${if(country.id==newCountry)" selected" else ""}>${escape(country.name)}</option>")
        append("</select></label><button>새 후보</button></form>")
    }
    val edit=edits[selected]
    val profile=if(creating)definitions.values.firstOrNull {it.creationPower==newPower}?.profile else profiles[selected]
    if(profile==null) {append("<p>등록된 추첨 후보가 없습니다.</p></html>");return@buildString}
    val empire=profile.creationPower==2
    val revision=edit?.revision ?: 0
    append("<p>생성 계열 ${if(empire)"제국" else "동맹"} · 편집 버전 $revision</p>")
    if(portraitSchema && portraits==null)append("<p>등록 초상 목록을 불러올 수 없어 현재 후보를 편집할 수 없습니다.</p>")
    else if(!editable)append("<p>저장하려면 운영자 인증, 감사 기록, 정의 DB V13이 필요합니다.</p>")
    append("<form id=\"edit\" method=\"post\"><fieldset${if(editable)"" else " disabled"}><legend>추첨 시작 데이터</legend>")
    append("<input type=\"hidden\" name=\"revision\" value=\"$revision\">")
    if(creating) {
        val ids=PublishedOriginalCandidates.NEW_IDS
        val free=ids.firstOrNull {it !in profiles}
        append("<input type=\"hidden\" name=\"create\" value=\"1\"><label>후보 번호 ${number("candidate",free?.toInt() ?: ids.last.toInt(),ids.first.toInt(),ids.last.toInt())}</label>")
    } else append("<input type=\"hidden\" name=\"candidate\" value=\"$selected\">")
    val scoped=if(creating)newCountry else edit?.country?.value
    val sameBank=countries.filter {catalog.candidatePower(it.id)==profile.creationPower}
    append("<label>응모 국가 <select name=\"country\" ${if(selected in definitions)"" else "required"}>")
    if(selected in definitions)append("<option value=\"\">계열 공용</option>")
    if(scoped!=null && sameBank.none {it.id==scoped})append("<option value=\"\" selected disabled>호환 국가 선택 필요</option>")
    for(country in sameBank)append("<option value=\"${country.id}\"${if(country.id==scoped)" selected" else ""}>${escape(country.name)}</option>")
    append("</select></label>")
    append("<label>운영자 <input name=\"operator\" maxlength=\"64\"></label>")
    append("<label>성 <input name=\"surname\" maxlength=\"12\" value=\"${if(creating)"" else escape(profile.surname)}\" required></label>")
    append("<label>이름 <input name=\"given\" maxlength=\"12\" value=\"${if(creating)"" else escape(profile.givenName)}\" required></label>")
    append("<label>원본 얼굴 번호 <select name=\"face\" required>")
    for(gender in 0..1) {
        if(gender==1 && !femaleFaces && profile.gender!=1)continue
        val label=if(gender==0)"남성" else "여성"
        append("<optgroup label=\"$label\">")
        for(face in OriginalCandidateProfile.faceNumbers(profile.creationPower,gender))
            append("<option value=\"$face\"${if(face==profile.face)" selected" else ""}>$label $face</option>")
        append("</optgroup>")
    }
    append("</select></label>")
    if(!femaleFaces)append("<p>여성 초상을 선택하려면 정의 DB V14를 적용하세요.</p>")
    if(!portraitSchema)append("<p>등록 NPC 초상을 지정하려면 정의 DB V15를 적용하세요.</p>")
    if(portraits!=null) {
        val portrait=if(creating)null else portraits.firstOrNull {it.key==profile.portraitKey}
        append("<label>등록 NPC 초상 <select name=\"portrait\"><option value=\"\">원본 얼굴 사용</option>")
        for(image in portraits)
            append("<option value=\"${escape(image.key)}\" data-revision=\"${image.revision}\"${if(image==portrait)" selected" else ""}>${escape(image.title)} (${escape(image.key)})</option>")
        append("</select></label><img id=\"portrait-preview\" width=\"64\" height=\"80\" style=\"object-fit:contain\" alt=\"등록 초상 미리보기\" hidden>")
        append("<p><a href=\"/admin/images\">NPC 초상 등록·교체</a> · 등록 초상은 해당 플레이어의 로비 게임 시작 카드와 전략 HUD, 같은 방에 있는 다른 플레이어의 배경 인물 칸에 표시됩니다.</p>")
    }
    append("<label>시작 계급 <select name=\"rank\">")
    for((index,rank) in militaryRanks.withIndex()) {
        val code=index+2
        if(empire || code!=3)append("<option value=\"$code\"${if(code==profile.rank)" selected" else ""}>$rank</option>")
    }
    append("</select></label><label>시작 작위 <select name=\"peerage\"><option value=\"0\">작위 없음</option>")
    for(peerage in Peerage.entries)append("<option value=\"${peerage.ordinal+1}\"${if(profile.peerage==peerage)" selected" else ""}>${peerageNames[peerage.ordinal]}</option>")
    append("</select></label><p>작위를 주려면 제국 작위 카드를 지원하는 응모 국가와 영속 월드 저장이 필요합니다.</p>")
    val boundOffice=edit?.profile?.startingOffice
    // Each creation side offers the office its native country accepts: Reinhard for the empire, Vucock for the alliance.
    val sideCountry=(if(empire)Power.EMPIRE else Power.ALLIANCE).country
    val office=boundOffice ?: OriginalStartingOffice.entries.firstOrNull {it.country==sideCountry}
    val officeRank=office?.let {candidate ->
        (2..20).firstOrNull {candidate.accepts(PublishedOriginalCandidates.NEW_IDS.first,candidate.country,it)}}
    val officeRankName=officeRank?.let {militaryRanks[it-2]}
    val officeHolder=edits.values.firstOrNull {it.id!=selected && it.profile.startingOffice==office}
    val officeMigration=office?.let {officeMigrations[it]}
    val officeEligible=office!=null && officeRank!=null && scoped==office.country.value &&
        (creating || (selected!=null && selected !in definitions && selected in PublishedOriginalCandidates.NEW_IDS))
    if(boundOffice!=null) {
        append("<p>시작 직책 <strong>${boundOffice.label()}</strong> · 게시 후에는 변경할 수 없습니다. 응모 국가나 시작 계급${officeRankName?.let {"($it)"}.orEmpty()}을 바꾸면 저장되지 않습니다.</p>")
    } else if(office==null || officeRank==null) {
        // No original starting office exists for this creation side.
    } else if(officeMigration!=null) {
        append("<p>시작 직책 ${office.label()}을 지정하려면 정의 DB ${officeMigration}을 적용하세요.</p>")
    } else if(officeEligible && officeHolder!=null) {
        append("<p>시작 직책 ${office.label()}은 후보 ${officeHolder.id}에 이미 지정되어 있습니다.</p>")
    } else if(officeEligible) {
        append("<label>시작 직책 <select name=\"startingOffice\" data-country=\"${office.country.value}\" data-rank=\"$officeRank\"><option value=\"\">지정 없음</option>")
        append("<option value=\"${office.key}\">${office.label()}</option></select></label>")
        append("<p>시작 계급이 ${officeRankName}일 때만 지정할 수 있습니다. 게시 후에는 해제하거나 다른 후보로 옮길 수 없습니다.</p>")
    }
    append("<div class=\"abilities\">")
    for((index,name) in abilities.withIndex())append("<label>$name ${number("ability$index",profile.abilities[index],0,255)}</label>")
    append("</div><button>저장</button></fieldset></form><p id=\"status\" role=\"status\"></p>")
    append("""<script>const form=document.querySelector('#edit'),status=document.querySelector('#status');
const portrait=form.elements.portrait,preview=document.querySelector('#portrait-preview');
if(portrait){const update=()=>{preview.hidden=!portrait.value;
if(portrait.value)preview.src='/admin/images/file/'+encodeURIComponent(portrait.value)+'?revision='+portrait.selectedOptions[0].dataset.revision;
else preview.removeAttribute('src');};portrait.onchange=update;update();}
const office=form.elements.startingOffice;
if(office){const sync=()=>{const allowed=form.elements.country.value===office.dataset.country&&form.elements.rank.value===office.dataset.rank;
office.disabled=!allowed;if(!allowed)office.value='';};form.elements.country.onchange=sync;form.elements.rank.onchange=sync;sync();}
form.onsubmit=async e=>{e.preventDefault();const fields=form.querySelector('fieldset'),body=new URLSearchParams(new FormData(form));fields.disabled=true;
try{const response=await fetch(form.action,{method:'POST',body});if(!response.ok){status.textContent=await response.text();return;}
location.replace('?candidate='+encodeURIComponent(body.get('candidate')));}catch{status.textContent='저장 상태를 확인할 수 없습니다.';}finally{fields.disabled=false;}};</script></html>""")
}
