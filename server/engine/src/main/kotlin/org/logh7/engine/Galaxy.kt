package org.logh7.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class GalaxySystem(val kind: Int, val grid: Int, val star: Int, val name: String)
data class GalaxyBase(val id: Int, val grid: Int, val model: Int, val kind: Int, val name: String,
    val physicalClass: Int, val radius: Float, val cycle: Int, val direction: Int, val angle: Float, val diameter: Float)

/** Confirmed manual placements plus explicitly labelled local defaults, loaded outside the repository. */
class Galaxy(val systems: List<GalaxySystem>, val bases: List<GalaxyBase>, val starts: Map<Power, Int>,
    val defaultCell: Int = 1) {
    val width = 100
    val height = 50
    val cells: List<Int>
    init {
        require(defaultCell in 0..2 && systems.size in 1..86 && bases.size <= 350)
        require(systems.map { it.kind }.distinct().size == systems.size)
        require(systems.map { it.grid }.distinct().size == systems.size)
        require(systems.all { it.kind in 3..88 && it.grid in 0..4999 && it.star in 0..6 && it.name.isNotBlank() })
        val locations = systems.map { it.grid }.toSet()
        require(bases.map { it.id }.distinct().size == bases.size)
        require(bases.all { it.id in 1..65535 && it.grid in locations && it.model in 0..129 && it.kind in 0..1 &&
            it.name.length in 1..12 && '\u0000' !in it.name && it.physicalClass in 0..255 &&
            it.radius.isFinite() && it.radius >= 0 && it.cycle > 0 && it.direction in 0..1 &&
            it.angle.isFinite() && it.diameter.isFinite() && it.diameter > 0 })
        require(starts.keys == Power.entries.toSet() && starts.values.all { it in locations })
        cells = MutableList(5000) { defaultCell }.also { values -> systems.forEach { values[it.grid] = it.kind } }.toList()
    }
    fun kind(grid: Int): GridKind = when {
        grid !in cells.indices -> GridKind.IMPASSABLE
        cells[grid] >= 3 -> GridKind.STAR_SYSTEM
        cells[grid] == 1 -> GridKind.SPACE
        else -> GridKind.IMPASSABLE
    }
    companion object {
        fun load(path: Path): Galaxy {
            require(Files.size(path) <= 1_000_000) { "World seed too large" }
            val p = Properties().also { Files.newBufferedReader(path).use(it::load) }
            require(p.getProperty("schema") == "1" && p.getProperty("unmapped.cells") == "local-space-default")
            fun int(key: String) = requireNotNull(p.getProperty(key)) { "Missing world key $key" }.toInt()
            val systems = List(int("systems").also { require(it in 1..86) }) { i ->
                val prefix = "system.$i."
                GalaxySystem(int(prefix + "kind"), int(prefix + "grid"), int(prefix + "star"), p.getProperty(prefix + "name"))
            }
            val bases = List(int("bases").also { require(it in 0..350) }) { i ->
                val prefix = "base.$i."
                fun number(key: String) = p.getProperty(prefix + key).toFloat()
                GalaxyBase(int(prefix + "id"), int(prefix + "grid"), int(prefix + "model"), int(prefix + "kind"),
                    p.getProperty(prefix + "name"), int(prefix + "class"), number("radius"), int(prefix + "cycle"),
                    int(prefix + "direction"), number("angle"), number("diameter"))
            }
            return Galaxy(systems, bases, mapOf(Power.EMPIRE to int("start.empire"), Power.ALLIANCE to int("start.alliance")))
        }
    }
}
