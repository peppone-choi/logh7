package org.logh7.engine

/** Initial economics/environment are gameplay design, not recovered server values. Author: 최병호. */
data class BaseCondition(val baseId: Int, val grid: Int, val power: Int, val camp: Int,
    val population: Int, val adultPopulation: Int, val tax: Int, val approval: Int,
    val peace: Int, val atmosphere: Int, val habitability: Int,
    val supplies: Int, val food: Int, val living: Int) {
    init {
        require(baseId > 0 && grid in 0..4999 && power in 0..4 && camp in 0..1)
        require(population >= 0 && adultPopulation in 0..population && tax in 0..100 &&
            approval in 0..100 && peace in 0..100 && atmosphere in 0..1 && habitability in 0..1 &&
            supplies >= 0 && food >= 0 && living >= 0)
    }
    companion object {
        fun habitable(base: GalaxyBase) = base.kind == 0 && base.model in setOf(60, 61, 80, 81, 100, 101, 102)
        fun initial(base: GalaxyBase, power: Int): BaseCondition {
            val habitable = habitable(base)
            val capital = (base.grid == 2487 && base.name == "オーディン") ||
                (base.grid == 2015 && base.name == "ハイネセン")
            require(!capital || habitable) { "Capital must be habitable" }
            val population = when { capital -> 1_000_000_000; habitable -> 100_000_000; else -> 100_000 }
            return BaseCondition(base.id, base.grid, power, 0, population, population / 5 * 3,
                20, 70, 80, if (habitable) 1 else 0, if (habitable) 1 else 0,
                100_000, population / 100, population / 100)
        }
    }
}
