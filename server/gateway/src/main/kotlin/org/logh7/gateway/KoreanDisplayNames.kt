package org.logh7.gateway

/** Display-only names; source rule keys and saved identities remain unchanged. Author: Peppone Choi. */
internal object KoreanDisplayNames {
    /** Only reviewed built-in identities; never translate arbitrary player names. */
    fun original(character: org.logh7.protocol.CharacterMessages.Generate): org.logh7.protocol.CharacterMessages.Generate {
        val source = org.logh7.protocol.OriginalCharacterMessages.localCandidates.singleOrNull {
            it.character.id == character.id && it.character.power == character.power &&
                it.character.surname == character.surname && it.character.givenName == character.givenName
        } ?: return character
        return character.copy(surname = catalogue(source.character.surname), givenName = catalogue(source.character.givenName))
    }
    private val translations: Map<String, String> by lazy {
        buildMap {
            for (file in listOf("ui-terms.ko.tsv", "world-names.ko.tsv")) {
                val stream = checkNotNull(KoreanDisplayNames::class.java.getResourceAsStream("/l10n/$file")) { "Missing display names: $file" }
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                        val fields = line.split('\t')
                        require(fields.size == 2 && fields.all { it.isNotEmpty() }) { "Invalid display name row" }
                        val before = put(fields[0], fields[1])
                        require(before == null || before == fields[1]) { "Conflicting display name: ${fields[0]}" }
                    }
                }
            }
        }
    }
    /** Call only for server-owned catalogue/base names, never player text. */
    fun catalogue(source: String): String {
        translations[source]?.let { return it }
        val stem = source.trimEnd('Ⅰ','Ⅱ','Ⅲ','Ⅳ','Ⅴ','Ⅵ','Ⅶ','Ⅷ')
        return translations[stem]?.plus(source.removePrefix(stem)) ?: source
    }
    fun base(source: String): String = (translations[source] ?: run {
        val stem = source.trimEnd { it in '0'..'9' }
        translations[stem]?.plus(source.removePrefix(stem)) ?: source
    }).also {
        require(it.length in 1..12 && it.none { c -> c == '\u0000' || c.isSurrogate() }) { "Base display name exceeds native capacity" }
    }
}
