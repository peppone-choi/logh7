package org.logh7.app

import java.nio.file.Files
import java.nio.file.Path
import org.logh7.engine.BattleDeath
import org.logh7.engine.NavigationStore
import org.logh7.engine.PlanetStockStore

internal fun worldCharacterFile(environment: RuntimeEnvironment, hasWorld: Boolean): Path? {
    environment["LOGH7_CHARACTERS_FILE"]?.let { return Path.of(it) }
    if (!hasWorld) return null
    fun path(key: String) = environment[key]?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
    val worldState = path("LOGH7_WORLD_STATE_DIR")
    val navigation = path("LOGH7_NAVIGATION_FILE")
    val anchor = navigation?.takeUnless { worldState != null && it.startsWith(worldState) } ?: worldState ?: return null
    val name = requireNotNull(anchor.fileName) { "Set LOGH7_CHARACTERS_FILE when the state path is a filesystem root" }
    val file = anchor.resolveSibling("$name.characters.properties")
    require(file != path("LOGH7_PLANET_STOCK_FILE")) {
        "Inferred character path overlaps world storage: $file. Set LOGH7_CHARACTERS_FILE explicitly."
    }
    return file
}

internal fun requireWorldCharacterHistory(file: Path, generation: Long, navigation: NavigationStore?,
    stock: PlanetStockStore?, participants: Set<String>) {
    val current = if (generation == 1L) file else file.resolveSibling("${file.fileName}.session-$generation")
    if (Files.isRegularFile(current)) return
    val pilots = navigation?.load().orEmpty().filterValues { it.generation == generation }
    val stocks = stock?.load()?.takeIf { it.generation == generation }
    val deceasedAccounts = pilots.filterValues { BattleDeath.deceased(stocks, it.characterId) }.keys
    check(deceasedAccounts.containsAll(pilots.keys + participants)) {
        "Missing character registration file for world generation $generation: $current. " +
            "Restore the matching character backup or set LOGH7_CHARACTERS_FILE to the original character store."
    }
}
