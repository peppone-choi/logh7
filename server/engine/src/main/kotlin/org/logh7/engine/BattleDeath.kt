package org.logh7.engine

/** U-11 death in battle (W p.52 "戦死は現在未実装" announced it). User decision 2026-10-31: a sunk
 * flagship kills its character with a low probability; the character then leaves play and its
 * account creates a new one at the next login. The 5% odds and the deterministic per-sinking roll
 * are evidence:guess. Offices are vacated at once. Author: Peppone Choi. */
object BattleDeath {
    const val PERCENT=5
    fun dies(character:Int,seconds:Long):Boolean=java.util.Random(seconds*1_000_003L+character*97L+11).nextInt(100)<PERCENT
    fun apply(stocks:PlanetStocks,victims:List<Int>,seconds:Long,roll:(Int,Long)->Boolean=::dies):PlanetStocks {
        var next=stocks
        for(id in victims.distinct().sorted()) {
            val person=next.strategic.people[id] ?: StrategicPerson()
            if(person.deceasedAt>0 || !roll(id,seconds))continue
            if(id !in next.strategic.people && next.strategic.people.size>=4096)continue
            val cleared = StrategyActions.clearOffices(next, id)
            // A partial retreat can leave tactical authority without an office in the detached outfit.
            next = cleared.copy(
                tacticalCommands = cleared.tacticalCommands.copy(
                    authority = cleared.tacticalCommands.authority.filterValues { it != id }),
                strategic = cleared.strategic.copy(
                    people = cleared.strategic.people + (id to person.copy(deceasedAt = maxOf(1L, seconds)))))
        }
        return if(next===stocks)stocks else FlagshipMarketActions.settle(next,seconds)
    }
    fun deceased(stocks:PlanetStocks?,character:Int)=(stocks?.strategic?.people?.get(character)?.deceasedAt ?: 0)>0
}
