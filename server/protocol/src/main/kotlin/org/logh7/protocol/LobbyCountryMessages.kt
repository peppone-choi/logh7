package org.logh7.protocol

/**
 * Mode 7 of the 032e/032f extension: the lobby country catalogue, display-only. One query returns a
 * header frame and up to [PAGE] country rows, each its own 032f body echoing the query identity. Every
 * frame carries ten text members (id 1..10, kind = field index, rank 0, at most 12 characters); there
 * are no ships, troops or supplies, and the viewer power is fixed to 2 so it can be sent before a
 * character exists. Author: Peppone Choi.
 *
 * Query: outfit = target session id (1..65535), base = nonce*512+offset (nonce 1..127, offset 0..350).
 * Header (kind 0, number 0): NC1, total, offset, rowCount, revision[0..12), revision[12..), elapsed game seconds,
 * generation[0..12), generation[12..), total regular bases of all defined countries (the session-wide denominator;
 * the client reads the session name from its 2006 list).
 * Row (kind 1, number = index in page): countryId, name[0..12), name[12..24), primary RGB or inherit,
 * registered characters, regular bases, native power, native camp, family key, eligibility bits.
 */
object LobbyCountryMessages {
    const val MODE=7
    const val PAGE=6
    const val MAX_OFFSET=350
    const val MAX_FRAMES=1+PAGE
    const val CREATE=1
    const val LOTTERY=2
    // The normal six-row selector never requests the page beyond the 350th country.
    const val EMBLEM_PROBE = 127 * 512 + MAX_OFFSET

    data class Request(val query:OutfitPartyMessages.Query) {
        val sessionId:Int get()=query.outfit
        val nonce:Int get()=query.base/512
        val offset:Int get()=query.base%512
        init {
            require(query.mode==MODE && query.outfit in 1..65535) {"Invalid lobby country session"}
            require(nonce in 1..127 && offset in 0..MAX_OFFSET) {"Invalid lobby country nonce or offset"}
        }
    }
    fun request(query:OutfitPartyMessages.Query):Request=Request(query)

    data class Header(val total:Int,val offset:Int,val rows:Int,val revision:Long,val gameSeconds:Long,
        val generation:Long,val totalRegularBases:Int,val unaffiliatedCreation:Boolean=false) {
        init {
            require(total in 0..MAX_OFFSET && offset in 0..MAX_OFFSET && rows in 0..PAGE && rows<=maxOf(0,total-offset))
            require(revision>=0 && gameSeconds>=0 && generation>=0 && totalRegularBases in 0..65535 &&
                (!unaffiliatedCreation || generation>0))
        }
    }
    data class Row(val countryId:Int,val name:String,val primary:Int?,val registeredCharacters:Int,val regularBases:Int,
        val nativePower:Int,val nativeCamp:Int,val familyKey:String,val eligibility:Int) {
        init {
            require(countryId>0 && name.length<=24 && (primary==null || primary in 0..0xffffff))
            require(registeredCharacters>=0 && regularBases>=0 && nativePower in 0..255 && nativeCamp in 0..255)
            require(familyKey.length<=12 && eligibility in 0..(CREATE or LOTTERY))
        }
    }

    private fun frame(request:Request,kind:Int,number:Int,fields:List<String>):ByteArray {
        require(fields.size==10 && fields.all {it.length<=12 && it.none {c->c=='\u0000' || c.isISOControl() || c.isSurrogate()}})
        return OutfitPartyMessages.party(request.query,2,kind,number,emptyList(),emptyList(),0,0,
            fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)})
    }
    fun header(request:Request,header:Header):ByteArray {
        val revision=header.revision.toString();val generation=header.generation.toString()
        return frame(request,0,0,listOf("NC1",header.total.toString(),header.offset.toString(),header.rows.toString(),
            revision.take(12),revision.drop(12),header.gameSeconds.toString(),generation.take(12),generation.drop(12),header.totalRegularBases.toString()))
            .also { if(header.unaffiliatedCreation)it[10]=1 }
    }
    fun row(request:Request,index:Int,row:Row):ByteArray {
        require(index in 0 until PAGE)
        return frame(request,1,index,listOf(row.countryId.toString(),row.name.take(12),row.name.drop(12),
            row.primary?.let {"%06X".format(it)} ?: "inherit",row.registeredCharacters.toString(),row.regularBases.toString(),
            row.nativePower.toString(),row.nativeCamp.toString(),row.familyKey,row.eligibility.toString()))
    }
    /** Header plus rows, at most [MAX_FRAMES] 032f bodies for one query. */
    fun reply(request:Request,header:Header,rows:List<Row>):List<ByteArray> {
        require(rows.size==header.rows && header.offset==request.offset)
        return listOf(header(request,header))+rows.mapIndexed {index,row->row(request,index,row)}
    }
    /** An unknown or unavailable session: an empty catalogue that reveals nothing about it. */
    fun empty(request:Request):List<ByteArray> = listOf(header(request,Header(0,request.offset,0,0,0,0,0)))

    fun emblemCapability(request: Request): ByteArray {
        require(request.query.base == EMBLEM_PROBE)
        return frame(request, 0, 0, listOf("NC2", "0", "350", "1", "0", "", "0", "0", "", "0"))
    }
}
