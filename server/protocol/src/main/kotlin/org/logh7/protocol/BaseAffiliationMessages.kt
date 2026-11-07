package org.logh7.protocol

/** Public base sovereignty, separate from native power/model bytes. Author: Peppone Choi. */
object BaseAffiliationMessages {
    const val MODE=12
    const val MAXIMUM=350
    data class Owner(val id:Int,val country:Int,val power:Int,val camp:Int)
    fun replies(query:OutfitPartyMessages.Query,owners:List<Owner>):List<ByteArray> {
        require(query.mode==MODE && query.outfit==0 && query.base in 1..65535)
        require(owners.size<=MAXIMUM && owners.map {it.id}.distinct().size==owners.size)
        require(owners.all {it.id in 1..65535 && it.country>=0 && it.power in 0..4 && it.camp in 0..1})
        val rows=owners.sortedBy {it.id}
        fun frame(kind:Int,number:Int,members:List<OutfitPartyMessages.Member>)=
            OutfitPartyMessages.party(query,2,kind,number,emptyList(),emptyList(),0,0,members)
        val header=listOf("BA1",rows.size.toString())+List(8){""}
        return listOf(frame(0,0,header.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)}))+
            rows.chunked(10).mapIndexed {index,chunk->frame(1,index,chunk.map {
                OutfitPartyMessages.Member(it.id,it.camp,it.power,it.country.toString())})}
    }
}
