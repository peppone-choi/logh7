package org.logh7.gateway

import java.nio.file.Path

/** Preserve previous generations' files; a new session never reuses its previous characters. */
class CharacterStores(private val file: Path?) {
    private var cached: Pair<Long, CharacterCreation>? = null
    @Synchronized fun get(generation: Long): CharacterCreation {
        require(generation > 0)
        cached?.takeIf { it.first == generation }?.let { return it.second }
        val path = if (generation == 1L) file else file?.resolveSibling("${file.fileName}.session-$generation")
        return CharacterCreation(path).also { cached = generation to it }
    }
}
