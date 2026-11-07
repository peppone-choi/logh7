package org.logh7.protocol

/** Visible outfit membership uses actual country IDs beside native display fields. Author: Peppone Choi. */
object OutfitAffiliationMessages {
    const val MODE=13
    const val PAGE=60
    data class Owner(val id:Int,val country:Int,val power:Int,val camp:Int)
    fun replies(query:OutfitPartyMessages.Query,grid:Int,generation:Long,owners:List<Owner>):List<ByteArray> {
        require(query.mode==MODE && query.outfit in 1..65535 && query.base>=0)
        require(grid in 0..4999 && generation>0 && owners.size<=65535)
        require(owners.all {it.id>0 && it.country>=0 && it.power in 0..4 && it.camp in 0..1})
        require(owners.map {it.id}.distinct().size==owners.size)
        val remaining=owners.filter {it.id>query.base}.sortedBy {it.id}
        val rows=remaining.take(PAGE);val digits=generation.toString()
        val fields=listOf("FA1",rows.size.toString(),(rows.lastOrNull()?.id ?: query.base).toString(),
            if(remaining.size>PAGE)"1" else "0",grid.toString(),digits.take(12),digits.drop(12),"","","")
        fun frame(kind:Int,number:Int,members:List<OutfitPartyMessages.Member>)=
            OutfitPartyMessages.party(query,2,kind,number,emptyList(),emptyList(),0,0,members)
        return listOf(frame(0,0,fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)}))+
            rows.chunked(10).mapIndexed {index,chunk->frame(1,index,chunk.map {
                OutfitPartyMessages.Member(it.id,it.camp,it.power,it.country.toString())})}
    }
}
