package org.logh7.engine

/** Editable starting data for a lottery candidate. Its ID and creation side are stable. */
data class OriginalCandidateProfile(
    val surname:String,val givenName:String,val face:Int,val rank:Int,val abilities:List<Int>
) {
    init {
        require(listOf(surname,givenName).all { name ->
            name.isNotBlank() && name.length<=12 && name.none {it.isISOControl() || it.isSurrogate()}
        })
        require(rank in 2..20 && abilities.size==8 && abilities.all {it in 0..255})
    }

    // The two original male banks are contiguous in the CD's tcf.hed.
    fun matchesCreationPower(power:Int)=when(power) {
        2 -> face in 1..158
        3 -> face in 100001..100108 && rank!=3
        else -> false
    }
}

data class OriginalCandidateDefinition(val id:Long,val creationPower:Int,val profile:OriginalCandidateProfile)
data class OriginalCandidateEdit(val id:Long,val revision:Long,val profile:OriginalCandidateProfile)

interface OriginalCandidateStore {
    fun load():List<OriginalCandidateEdit>
    fun save(id:Long,expected:Long,profile:OriginalCandidateProfile):OriginalCandidateEdit?
}

class PublishedOriginalCandidates(definitions:List<OriginalCandidateDefinition> = emptyList()) {
    val defaults=definitions.associateBy {it.id}
    @Volatile private var edits=emptyMap<Long,OriginalCandidateEdit>()

    fun current()=edits
    fun accepts(id:Long,profile:OriginalCandidateProfile)=defaults[id]?.let {
        profile.matchesCreationPower(it.creationPower)
    } ?: false

    @Synchronized fun publish(rows:List<OriginalCandidateEdit>) {
        require(rows.all {it.revision>0 && accepts(it.id,it.profile)})
        edits=edits+rows.filter {row -> row.revision>(edits[row.id]?.revision ?: 0)}.associateBy {it.id}
    }
}
