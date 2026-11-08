package org.logh7.engine

/** Editable starting data for a lottery candidate. Its ID and creation side are stable. */
data class OriginalCandidateProfile(
    val surname:String,val givenName:String,val face:Int,val rank:Int,val abilities:List<Int>,val peerage:Peerage?=null
) {
    init {
        require(listOf(surname,givenName).all { name ->
            name.isNotBlank() && name.length<=12 && name.none {it.isISOControl() || it.isSurrogate()}
        })
        require(rank in 2..20 && abilities.size==8 && abilities.all {it in 0..255})
    }

    // The two original male banks are contiguous in the CD's tcf.hed.
    val creationPower get()=if(face in 1..158)2 else 3
    fun matchesCreationPower(power:Int)=when(power) {
        2 -> face in 1..158
        3 -> face in 100001..100108 && rank!=3
        else -> false
    }
}

data class OriginalCandidateDefinition(val id:Long,val creationPower:Int,val profile:OriginalCandidateProfile)
data class OriginalCandidateEdit(val id:Long,val revision:Long,val profile:OriginalCandidateProfile,val country:CountryId?=null)

interface OriginalCandidateStore {
    val supportsEditing:Boolean get()=true
    fun load():List<OriginalCandidateEdit>
    fun save(id:Long,expected:Long,profile:OriginalCandidateProfile,country:CountryId?=null):OriginalCandidateEdit?
}

class PublishedOriginalCandidates(definitions:List<OriginalCandidateDefinition> = emptyList(),val canGrantPeerage:Boolean=true) {
    val defaults=definitions.associateBy {it.id}
    @Volatile private var edits=emptyMap<Long,OriginalCandidateEdit>()

    fun current()=edits
    fun accepts(id:Long,profile:OriginalCandidateProfile,country:CountryId?=null):Boolean {
        val side=defaults[id]?.creationPower ?: edits[id]?.profile?.creationPower
        if(id !in defaults && (id !in NEW_IDS || country==null))return false
        if(profile.peerage!=null && country==null)return false
        return profile.matchesCreationPower(side ?: profile.creationPower)
    }

    @Synchronized fun publish(rows:List<OriginalCandidateEdit>) {
        require(rows.all {it.revision>0 && accepts(it.id,it.profile,it.country)})
        edits=edits+rows.filter {row -> row.revision>(edits[row.id]?.revision ?: 0)}.associateBy {it.id}
    }

    companion object {
        val NEW_IDS=1003L..9999L
        // Leave packet space for a shared built-in candidate and five already accepted preferences.
        const val SCOPED_LIMIT=94
    }
}

/** The native peerage cards use the imperial display bank. The actual country's family must also support them. */
fun CountryCatalog.supportsNativePeerage(country:CountryId):Boolean =
    countries[country.value]?.let {it.nativePower==2 && families.getValue(it.family).peerage=="empire"}==true
