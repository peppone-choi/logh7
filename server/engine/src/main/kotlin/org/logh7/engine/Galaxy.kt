package org.logh7.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class GalaxySystem(val kind: Int, val grid: Int, val star: Int, val name: String, val power: Int = 0)
data class GalaxyBase(val id: Int, val grid: Int, val model: Int, val kind: Int, val name: String,
    val physicalClass: Int, val radius: Float, val cycle: Int, val direction: Int, val angle: Float, val diameter: Float,
    /** CD manual p.54: only mobile fortresses take the in-grid 【移動】 command (U-26). */
    val mobile: Boolean = false)

/** Confirmed manual placements plus explicitly labelled local defaults, loaded outside the repository. */
class Galaxy(val systems: List<GalaxySystem>, val bases: List<GalaxyBase>, starts: Map<Power, Int>,
    val defaultCell: Int = 1, terrain: List<String>? = null,
    countryStarts:Map<CountryId,Int> = emptyMap(),initialOwners:Map<Int,Allegiance> = emptyMap()) {
    /** Keep existing two-country consumers consistent when their own starts are explicitly edited. */
    val starts:Map<Power,Int> = starts.mapValues {(power,grid)->countryStarts[power.country] ?: grid}
    /** Per-world spawn locations; an additional country never inherits another country's start. */
    val countryStarts:Map<CountryId,Int> = this.starts.mapKeys {it.key.country}+countryStarts
    /** Historical owners stay independent of current occupation and the original system display code. */
    val initialOwners:Map<Int,Allegiance>
    val width = 100
    val height = 50
    val cells: List<Int>
    val facilities: Map<Int, List<PlanetFacility>> by lazy {
        bases.associate { base -> base.id to Places.forBase(base, systems.single { it.grid == base.grid }.power) }
    }
    val conditions: Map<Int, BaseCondition> by lazy {
        bases.associate { base -> base.id to BaseCondition.initial(base, systems.single { it.grid == base.grid }.power) }
    }
    init {
        require(defaultCell in 0..2 && systems.size in 1..86 && bases.size <= 350)
        require(systems.map { it.kind }.distinct().size == systems.size)
        require(systems.map { it.grid }.distinct().size == systems.size)
        require(systems.all { it.kind in 3..88 && it.grid in 0..4999 && it.star in 0..6 && it.name.isNotBlank() && it.power in 0..4 })
        val locations = systems.map { it.grid }.toSet()
        require(bases.map { it.id }.distinct().size == bases.size)
        require(bases.all { it.id in 1..65535 && it.grid in locations && it.model in 0..129 && it.kind in 0..1 &&
            it.name.length in 1..12 && '\u0000' !in it.name && it.physicalClass in 0..255 &&
            it.radius.isFinite() && it.radius >= 0 && it.cycle > 0 && it.direction in 0..1 &&
            it.angle.isFinite() && it.diameter.isFinite() && it.diameter > 0 && (!it.mobile || it.kind == 1) })
        require(starts.keys == Power.entries.toSet() && starts.values.all { it in locations })
        require(this.countryStarts.size<=350 && this.countryStarts.values.all {it in locations})
        require(initialOwners.keys.all {id->bases.any {it.id==id}}) {"Initial owner references an unknown base"}
        this.initialOwners=java.util.Collections.unmodifiableMap(bases.associate {base->base.id to
            (initialOwners[base.id] ?: Allegiance.fromLegacy(systems.single {it.grid==base.grid}.power))})
        val map = terrain?.let(::terrainCells)
        if (map != null) require(systems.all { map[it.grid] == SPACE_CELL }) { "A star system lies outside the navigable terrain" }
        cells = MutableList(5000) { map?.get(it) ?: defaultCell }.also { values -> systems.forEach { values[it.grid] = it.kind } }.toList()
    }
    /** Black holes; Null_galaxy.mdx bh_01..03 (evidence:client). */
    val blackHoles: List<Int> by lazy { cells.indices.filter { cells[it] == BLACK_HOLE_CELL } }
    fun terrain(grid: Int): GridTerrain = if (grid in cells.indices && cells[grid] == PLASMA_STORM_CELL) GridTerrain.PLASMA_STORM
        else GridTerrain.NONE
    /** Room identities and backgrounds follow the saved owner after conquest. */
    fun facilitiesAt(baseId: Int, stocks: PlanetStocks? = null,countries:CountryCatalog=CountryCatalogLoader.current()): List<PlanetFacility> {
        val owner=stocks?.let {checkNotNull(it.bases[baseId]).owner} ?: initialOwners.getValue(baseId)
        val power=countries.nativePower(owner)
        val country=owner.country
        val capitals=stocks?.strategic?.capitals.orEmpty()
        // Explicit capitals choose institutional types by office rules, images by the display code.
        val capitalSystem=when {
            country!=null && capitals[country]==baseId -> OrganizationOffices.system(country,countries) ?: 0
            country in capitals || baseId in capitals.values || country?.value !in setOf(2,3) -> 0
            else -> null
        }
        return Places.forBase(bases.single {it.id==baseId},power,baseId in stocks?.strategic?.fiefs.orEmpty(),capitalSystem)
    }
    fun kind(grid: Int): GridKind = when {
        grid !in cells.indices -> GridKind.IMPASSABLE
        cells[grid] in 3..88 -> GridKind.STAR_SYSTEM
        // A plasma storm is space blocked by terrain (W p.31), not an impassable grid.
        cells[grid] == SPACE_CELL || cells[grid] == PLASMA_STORM_CELL -> GridKind.SPACE
        else -> GridKind.IMPASSABLE
    }
    companion object {
        /** constmsg group 24: 0 plasma storm, 1 space, 2 impassable, 3..88 star systems. */
        const val PLASMA_STORM_CELL = 0
        const val SPACE_CELL = 1
        const val IMPASSABLE_CELL = 2
        /** Extra kind-table row drawn by 004d40bc as model 7 (type 3, fixedstar 8); never a star system. */
        const val BLACK_HOLE_CELL = 89
        /** Neutron stars (ns_01..03): constmsg group 71 gives them a whole-grid movement radius, so they are
         * impassable like black holes (approved 2026-10-30). Drawn as a plain impassable grid; no map model exists. */
        val neutronStars = listOf(1875, 3537, 4117)
        /**
         * 100x50 rows: '.' space, 'N' neutron star (impassable), '#' impassable, '~' plasma storm, 'B' black hole.
         * The navigable outline is manual p.101's in-game map (evidence:manual); plasma storms
         * along the corridor flanks are a design value (evidence:guess). Author: Peppone Choi.
         */
        fun terrainCells(rows: List<String>): List<Int> {
            require(rows.size == 50 && rows.all { it.length == 100 }) { "Terrain must be 100x50" }
            return rows.joinToString("").map { when (it) {
                '.' -> SPACE_CELL; '#', 'N' -> IMPASSABLE_CELL; '~' -> PLASMA_STORM_CELL; 'B' -> BLACK_HOLE_CELL
                else -> error("Unknown terrain cell $it") } }
        }
        fun manualTerrain(): List<String> = checkNotNull(Galaxy::class.java.getResourceAsStream("/world/terrain-p101.txt")) {
            "Missing manual terrain" }.bufferedReader(Charsets.US_ASCII).use { it.readLines() }.filter { it.isNotEmpty() }
        fun load(path: Path): Galaxy {
            require(Files.size(path) <= 1_000_000) { "World seed too large" }
            val p = Properties().also { Files.newBufferedReader(path).use(it::load) }
            val schema=p.getProperty("schema")
            require(schema in setOf("1","2","3") && p.getProperty("unmapped.cells") == "local-space-default")
            fun int(key: String) = requireNotNull(p.getProperty(key)) { "Missing world key $key" }.toInt()
            val systems = List(int("systems").also { require(it in 1..86) }) { i ->
                val prefix = "system.$i."
                GalaxySystem(int(prefix + "kind"), int(prefix + "grid"), int(prefix + "star"), p.getProperty(prefix + "name"),
                    p.getProperty(prefix + "power", "0").toInt())
            }
            val bases = List(int("bases").also { require(it in 0..350) }) { i ->
                val prefix = "base.$i."
                fun number(key: String) = p.getProperty(prefix + key).toFloat()
                GalaxyBase(int(prefix + "id"), int(prefix + "grid"), int(prefix + "model"), int(prefix + "kind"),
                    p.getProperty(prefix + "name"), int(prefix + "class"), number("radius"), int(prefix + "cycle"),
                    int(prefix + "direction"), number("angle"), number("diameter"),
                    p.getProperty(prefix + "mobile", "false").toBooleanStrict())
            }
            // "terrain=none" keeps the previous all-space map for isolated experiments.
            val terrain = if (p.getProperty("terrain", "manual-p101") == "none") null else manualTerrain()
            val countryStarts=p.stringPropertyNames().filter {it.startsWith("start.country.")}.associate {key->
                val value=key.removePrefix("start.country.")
                val id=CountryId(value.toInt().also {require(it.toString()==value) {"Invalid country start ID"}})
                id to int(key)
            }
            require(countryStarts.isEmpty() || schema in setOf("2","3")) {"Country starts require world schema 2"}
            val owners=p.stringPropertyNames().filter {it.startsWith("base.") && it.endsWith(".owner")}.associate {key->
                val index=key.removePrefix("base.").removeSuffix(".owner")
                val i=index.toInt().also {require(it.toString()==index && it in bases.indices) {"Invalid initial owner base index"}}
                val text=p.getProperty(key)
                val owner=when(text) {
                    "U"->Allegiance.Unspecified;"N"->Allegiance.Neutral;"P"->Allegiance.Pirates
                    else->{
                        require(text.startsWith("C:")) {"Invalid initial base owner"}
                        val id=CountryId(text.removePrefix("C:").toInt())
                        require(text=="C:${id.value}") {"Non-canonical initial base owner"}
                        Allegiance.Country(id)
                    }
                }
                bases[i].id to owner
            }
            require(owners.isEmpty() || schema=="3") {"Initial owners require world schema 3"}
            return Galaxy(systems, bases, mapOf(Power.EMPIRE to int("start.empire"), Power.ALLIANCE to int("start.alliance")),
                terrain = terrain,countryStarts=countryStarts,initialOwners=owners)
        }
    }
}
