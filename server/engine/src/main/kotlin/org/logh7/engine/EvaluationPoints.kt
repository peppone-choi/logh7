package org.logh7.engine

/**
 * 評価ポイント sources (manual W p.34). Amounts are the approved replacement policy in
 * docs/server/design-evaluation-points.md (evidence:guess). Awards are written into the same stock
 * transaction as the triggering loss/occupation, so a restart cannot pay them twice. Author: Peppone Choi.
 */
internal object EvaluationPoints {
    const val SHIPS_PER_POINT=10
    const val FLAGSHIP=50L
    const val PLANET=100L
    const val FORTRESS=300L
    private const val MAXIMUM=0xffffffffL

    fun sunk(boats:Int):Long=if(boats<=0)0 else ((boats+SHIPS_PER_POINT-1)/SHIPS_PER_POINT).toLong()

    /** The character that fired [source]: a personal flagship's owner or a regular unit's tactical commander (0: none). */
    fun shooter(source:Int,players:Map<String,Navigator>,stocks:PlanetStocks):Int =
        players.values.firstOrNull {it.unitId==source}?.characterId
            ?: stocks.outfits.values.firstNotNullOfOrNull {o->
                o.activeShips?.values?.firstOrNull {it.id==source}?.let {TacticalAuthority.commander(o,it,stocks)}} ?: 0

    fun award(stocks:PlanetStocks,character:Int,points:Long):PlanetStocks {
        if(character<=0 || points<=0)return stocks
        val people=stocks.strategic.people
        if(character !in people && people.size>=4096)return stocks
        val person=people[character] ?: StrategicPerson()
        val next=minOf(MAXIMUM,person.evaluation+minOf(points,MAXIMUM))
        if(next==person.evaluation)return stocks
        return stocks.copy(strategic=stocks.strategic.copy(people=people+(character to person.copy(evaluation=next))))
    }

    /** Equal shares of [total]; the remainder goes to the lowest IDs so the sum is exact. */
    fun shares(total:Long,characters:Collection<Int>):Map<Int,Long> {
        val ids=characters.filter {it>0}.distinct().sorted()
        if(ids.isEmpty() || total<=0)return emptyMap()
        val base=total/ids.size;val rest=(total%ids.size).toInt()
        return ids.withIndex().associate {(i,id)->id to base+if(i<rest)1 else 0}
    }
}
