package org.logh7.engine

import java.util.concurrent.atomic.AtomicReference

data class NpcFleetDialogue(val fallback:String="",val style:String="",val prompt:String="") {
    init {
        require(fallback.isEmpty() || validLine(fallback))
        require(style.length<=80 && style.none {it.isISOControl() || it.isSurrogate()})
        require(prompt.length<=500 && prompt.none {it.isSurrogate() || it.isISOControl() && it!='\n'})
    }
    companion object {
        const val DEFAULT_LINE="이곳에서 물러나라."
        fun validLine(text:String)=text.length in 5..16 && text.any {it in '가'..'힣'} &&
            text.all {it in '가'..'힣' || it in " ,.!?"}
    }
}

data class NpcFleetDialogueRow(val generation:Long,val outfit:Int,val revision:Long,val dialogue:NpcFleetDialogue)

interface NpcFleetDialogueStore {
    fun load(generation:Long):List<NpcFleetDialogueRow>
    fun save(generation:Long,outfit:Int,expected:Long,dialogue:NpcFleetDialogue):NpcFleetDialogueRow?
}

/** Each instance belongs to one persistent world. Late reads cannot undo a newer edit or generation. */
class PublishedNpcFleetDialogue {
    private data class Snapshot(val generation:Long,val rows:Map<Int,NpcFleetDialogueRow>)
    private val published=AtomicReference(Snapshot(0,emptyMap()))

    fun current(generation:Long,outfit:Int)=published.get().takeIf {it.generation==generation}?.rows?.get(outfit)

    fun publish(generation:Long,rows:List<NpcFleetDialogueRow>) {
        published.updateAndGet { before ->
            if(generation<before.generation)before else {
                val previous=if(generation==before.generation)before.rows else emptyMap()
                val newer=rows.filter {it.revision>(previous[it.outfit]?.revision ?: 0)}
                Snapshot(generation,previous+newer.associateBy {it.outfit})
            }
        }
    }
}
