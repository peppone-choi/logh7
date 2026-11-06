package org.logh7.engine

/**
 * Facility names and descriptions an operator can edit. Author: Peppone Choi.
 *
 * Keys are the existing facility model: kind = constmsg group 73 (0..38 in the CD client), facility
 * id = baseId*100+kind+1 ([Places.forBase]). A kind definition applies to every facility of that kind;
 * an override applies to one facility. `name == null` keeps the server's default Korean name; the kind
 * number, keys and original rule strings never change here. Commands are only read from the engine
 * ([FacilityCommandCatalog]); this file adds no command.
 */
data class FacilityKindDefinition(val kind:Int,val name:String?,val purpose:String,val usage:String) {
    init {
        require(kind in 0..MAX_KIND) {"Invalid facility kind"}
        require(name==null || FacilityDefinitionSnapshot.validText(name,FacilityDefinitionSnapshot.NAME_LIMIT)) {"Invalid facility name"}
        require(FacilityDefinitionSnapshot.validOptionalText(purpose,FacilityDefinitionSnapshot.TEXT_LIMIT)) {"Invalid facility purpose"}
        require(FacilityDefinitionSnapshot.validOptionalText(usage,FacilityDefinitionSnapshot.TEXT_LIMIT)) {"Invalid facility usage"}
    }
    companion object { const val MAX_KIND=98 }
}

data class FacilityOverride(val facilityId:Int,val name:String?,val purpose:String?) {
    init {
        require(facilityId>100 && facilityId%100 in 1..FacilityKindDefinition.MAX_KIND+1) {"Invalid facility id"}
        require(name!=null || purpose!=null) {"Empty facility override"}
        require(name==null || FacilityDefinitionSnapshot.validText(name,FacilityDefinitionSnapshot.NAME_LIMIT)) {"Invalid facility name"}
        require(purpose==null || FacilityDefinitionSnapshot.validText(purpose,FacilityDefinitionSnapshot.TEXT_LIMIT)) {"Invalid facility purpose"}
    }
    val kind:Int get()=FacilityDefinitionSnapshot.kindOf(facilityId)
    val baseId:Int get()=facilityId/100
}

data class FacilityDefinitionSnapshot(val revision:Long,val kinds:Map<Int,FacilityKindDefinition>,val overrides:Map<Int,FacilityOverride>) {
    init {
        require(revision>=0)
        require(kinds.size<=FacilityKindDefinition.MAX_KIND+1 && kinds.all {(kind,value)->kind==value.kind})
        require(overrides.size<=MAX_OVERRIDES && overrides.all {(id,value)->id==value.facilityId})
    }
    /** Edited name, or null for the server's default Korean name ([FacilityDefinitionDefaults.name]). */
    fun name(facilityId:Int):String? = overrides[facilityId]?.name ?: kinds[kindOf(facilityId)]?.name
    fun displayName(facilityId:Int):String = name(facilityId) ?: FacilityDefinitionDefaults.name(kindOf(facilityId))
    fun purpose(facilityId:Int):String = overrides[facilityId]?.purpose ?: kinds[kindOf(facilityId)]?.purpose.orEmpty()
    fun usage(facilityId:Int):String = kinds[kindOf(facilityId)]?.usage.orEmpty()

    /** The snapshot after [edit] with the next revision, this snapshot when nothing changes, or null when invalid. */
    fun applied(edit:FacilityDefinitionEdit):FacilityDefinitionSnapshot? {
        val next=when(edit) {
            is FacilityDefinitionEdit.Kind -> if(kinds[edit.value.kind]==edit.value) return this else copy(kinds=kinds+(edit.value.kind to edit.value))
            is FacilityDefinitionEdit.Override -> if(overrides[edit.value.facilityId]==edit.value) return this
                else copy(overrides=overrides+(edit.value.facilityId to edit.value))
            is FacilityDefinitionEdit.ClearOverride -> if(edit.facilityId !in overrides) return this else copy(overrides=overrides-edit.facilityId)
        }
        return runCatching {next.copy(revision=Math.addExact(revision,1))}.getOrNull()
    }

    companion object {
        const val NAME_LIMIT=24
        const val TEXT_LIMIT=200
        const val MAX_OVERRIDES=20000
        fun kindOf(facilityId:Int)=facilityId%100-1
        fun facilityId(baseId:Int,kind:Int)=Math.addExact(Math.multiplyExact(baseId,100),kind+1)
        fun validText(value:String,limit:Int)=value.isNotBlank() && value.length<=limit && value.none {it.isISOControl() || it.isSurrogate()}
        fun validOptionalText(value:String,limit:Int)=value.isEmpty() || validText(value,limit)
    }
}

sealed interface FacilityDefinitionEdit {
    data class Kind(val value:FacilityKindDefinition):FacilityDefinitionEdit
    data class Override(val value:FacilityOverride):FacilityDefinitionEdit
    data class ClearOverride(val facilityId:Int):FacilityDefinitionEdit {
        init { require(facilityId>100 && facilityId%100 in 1..FacilityKindDefinition.MAX_KIND+1) }
    }
}
enum class FacilityDefinitionOutcome { CHANGED, UNCHANGED, CONFLICT, INVALID }
interface FacilityDefinitionStore {
    fun load():FacilityDefinitionSnapshot
    fun save(expectedRevision:Long,edit:FacilityDefinitionEdit):FacilityDefinitionOutcome
}

/** The definitions the game currently shows. Readers call [current]; an older revision never replaces a newer one. */
class PublishedFacilityDefinitions(initial:FacilityDefinitionSnapshot=FacilityDefinitionDefaults.snapshot()) {
    @Volatile private var value=initial
    fun current():FacilityDefinitionSnapshot=value
    /** True when [next] became current; a lower revision (a stale reload) is ignored. */
    @Synchronized fun publish(next:FacilityDefinitionSnapshot):Boolean {
        if(next.revision<value.revision) return false
        value=next
        return true
    }
}

/** Commands usable at a facility kind, read from the engine's rules. The default lists none (shown as 미연결). */
fun interface FacilityCommandCatalog {
    fun commands(kind:Int):List<String>
    companion object { val UNCONNECTED=FacilityCommandCatalog {emptyList()} }
}

/**
 * Built-in Korean names (D-4: direct translation of the client's group-73 terms) and short default
 * descriptions written for operators. Descriptions are design text (`evidence:guess`), not manual quotes;
 * entry rules come from spot access and membership/office checks, which these sentences only summarize.
 */
object FacilityDefinitionDefaults {
    private val names=listOf("정청","방위사령부","중앙광장","공원","우주항","에너지 플랜트","자원채굴 플랜트","군수공장","농업 플랜트","창고",
        "조병공창","계류소","훈련소","병영","대공포대","번화가","거주구","호텔","저택","방송국","사관학교","요새포대","황궁","기함공창",
        "최고평의회","자치령주부","제국 주페잔 변무관 사무소","동맹 주페잔 변무관 사무소","주페잔 변무관 사무소","우주함대사령부","과학기술총감부",
        "군무성","헌병본부","후방근무본부","국방위원회","장갑척탄병총감부","통합작전본부","통수본부","내각부")
    private val purposes=listOf(
        "행성 행정의 중심. 총독이 집무하며 세율 같은 행성 운영이 이뤄진다.",
        "행성·요새의 방위를 지휘하는 기관.",
        "인물이 모이는 공공장소. 대중을 상대로 한 활동에 쓰인다.",
        "누구나 머무를 수 있는 휴식 공간.",
        "함선이 드나드는 곳. 행성과 우주를 잇는 관문이다.",
        "행성의 에너지를 생산하는 생산 시설.",
        "광물 자원을 캐는 생산 시설.",
        "군수 물자를 생산하는 생산 시설.",
        "식량을 생산하는 생산 시설.",
        "물자를 보관하는 보관 시설.",
        "함정과 병기를 생산하는 시설.",
        "함정을 계류해 보관하는 시설.",
        "부대 훈련에 쓰는 시설.",
        "지상 부대가 머무는 시설.",
        "행성 방위용 대공 포대.",
        "상점과 주점이 모인 거리. 인물 사이의 교류 장소다.",
        "주민이 사는 구역. 인물의 거처가 있다.",
        "숙박 시설. 로비와 객실이 있다.",
        "영지를 가진 인물의 저택. 야회·사냥 같은 사교 행사가 열린다.",
        "보도와 선전에 쓰는 시설.",
        "사관을 길러 내는 교육 시설. 강의실과 전술 시뮬레이터가 있다.",
        "요새의 방위 포대.",
        "제국 황제의 궁전. 국가 최고 기관이다.",
        "기함을 구입하거나 개장하는 시설.",
        "자유행성동맹의 최고 의결 기관.",
        "페잔 자치령주의 집무 기관.",
        "페잔에 주재하는 제국의 외교 사무소.",
        "페잔에 주재하는 동맹의 외교 사무소.",
        "페잔에 주재하는 외교 사무소.",
        "우주함대를 지휘하는 군 기관.",
        "군의 과학기술을 관장하는 기관.",
        "제국군의 인사와 군정을 관장하는 기관.",
        "군 내부 치안을 맡는 헌병 기관.",
        "동맹군의 병참을 관장하는 기관.",
        "동맹의 국방 정책을 맡는 기관.",
        "장갑척탄병(지상 부대)을 관장하는 기관.",
        "동맹군의 작전을 총괄하는 기관.",
        "제국군의 작전을 총괄하는 기관.",
        "제국의 국무를 맡는 기관.")
    private const val ORGANIZATION="소속 국가의 기관이다. 들어갈 수 있는지와 안쪽 방의 이용은 소속·직위 판정을 따른다."
    private const val PUBLIC="기항 중인 행성에서 이용하는 공공 시설이다. 방마다 출입 조건이 다를 수 있다."
    private const val FACILITY="행성의 시설 목록과 기항 제약을 따른다. 방마다 출입 조건이 다를 수 있다."
    private fun usage(kind:Int)=when(kind) {
        0,1,22,in 24..38 -> ORGANIZATION
        2,3,15,16,17,19 -> PUBLIC
        18 -> "저택 소유자와 초대받은 인물이 이용한다. 방마다 출입 조건이 다르다."
        20 -> "소속 국가의 사관학교다. 강의·시뮬레이터 이용은 소속 판정을 따른다."
        else -> FACILITY
    }
    val kindCount:Int get()=names.size
    fun name(kind:Int):String=names.getOrNull(kind) ?: "시설 $kind"
    fun kind(kind:Int)=FacilityKindDefinition(kind,null,purposes.getOrElse(kind) {""},if(kind<names.size)usage(kind) else "")
    fun snapshot()=FacilityDefinitionSnapshot(0,names.indices.associateWith(::kind),emptyMap())
}
