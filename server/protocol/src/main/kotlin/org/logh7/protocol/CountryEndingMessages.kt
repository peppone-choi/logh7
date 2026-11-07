package org.logh7.protocol

/** Public per-country session results, independent of the native two display rows. Author: Peppone Choi. */
object CountryEndingMessages {
    const val MODE=14
    const val PAGE=3
    const val MAXIMUM=350
    data class Request(val query:OutfitPartyMessages.Query) {
        val session:Int get()=query.outfit
        val offset:Int get()=query.base%512
        val nonce:Int get()=query.base/512
        init {require(query.mode==MODE && session in 1..65535 && nonce in 1..127 && offset in 0..MAXIMUM)}
    }
    data class Row(val country:Int,val name:String,val primary:Int?,val characters:Int,val grade:Int,
        val populationCondition:Int,val fleetCondition:Int,val eliminated:Boolean,
        val planets:Int,val fortresses:Int,val population:Long,val ships:Long,val troops:Long) {
        init {
            require(country>0 && name.isNotBlank() && name.length<=24)
            require(primary==null || primary in 0..0xffffff)
            require(characters>=0 && grade in setOf(0,1,2,3,255) && populationCondition in setOf(0,1,255) && fleetCondition in setOf(0,1,255))
            require(planets in 0..65535 && fortresses in 0..65535 && population>=0 && ships>=0 && troops>=0)
        }
    }
    fun reply(request:Request,generation:Long,seconds:Long,status:Int,rows:List<Row>,viewerCountry:Int=0):List<ByteArray> {
        require(generation>=0 && seconds>=0 && status in setOf(0,1,3,4,5))
        require(rows.size<=MAXIMUM && rows.map {it.country}.distinct().size==rows.size)
        require(status!=0 || generation==0L && seconds==0L && rows.isEmpty())
        require(status==0 || generation>0)
        require(viewerCountry>=0 && (status!=0 || viewerCountry==0))
        val page=rows.sortedBy {it.country}.drop(request.offset).take(PAGE)
        fun digits(value:Long)=value.toString().let {listOf(it.take(12),it.drop(12))}
        fun frame(kind:Int,number:Int,fields:List<String>):ByteArray {
            require(fields.size==10 && fields.all {it.length<=12 && it.none {c->c.isISOControl() || c.isSurrogate()}})
            return OutfitPartyMessages.party(request.query,2,kind,number,emptyList(),emptyList(),0,0,
                fields.mapIndexed {i,value->OutfitPartyMessages.Member(i+1,i,0,value)})
        }
        return listOf(frame(0,0,listOf("CE1",rows.size.toString(),request.offset.toString(),page.size.toString())+
            digits(generation)+digits(seconds)+listOf(status.toString(),viewerCountry.toString())))+page.flatMapIndexed {i,row->listOf(
                frame(1,i,listOf(row.country.toString(),row.name.take(12),row.name.drop(12),row.grade.toString(),
                    row.characters.toString(),row.populationCondition.toString(),row.fleetCondition.toString(),
                    row.primary?.let {"%06X".format(it)} ?: "inherit",if(row.eliminated)"1" else "0","")),
                frame(2,i,listOf(row.planets.toString(),row.fortresses.toString())+digits(row.population)+
                    digits(row.ships)+digits(row.troops)+listOf("","")))}
    }
    fun empty(request:Request)=reply(request,0,0,0,emptyList())
}
