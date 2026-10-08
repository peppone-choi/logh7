package org.logh7.gateway

import org.logh7.protocol.CharacterMessages
import java.text.Normalizer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Local P2 creation policy; guessed initial ability/rank/ship values are documented separately. */
class CharacterCreation(private val file: Path? = null) {
    private var nextId = 10_000L
    private val pending = mutableMapOf<String, CharacterMessages.Generate>()
    private val registered = mutableMapOf<String, CharacterMessages.Generate>()
    init {
        if (file != null && Files.exists(file)) {
            val properties = Properties().also { Files.newBufferedReader(file).use(it::load) }
            for (account in properties.stringPropertyNames()) {
                val data = CharacterMessages.generate(java.util.HexFormat.of().parseHex(properties.getProperty(account)))
                require(data.category == 4 && data.id > 0)
                registered[account] = data
                nextId = maxOf(nextId, data.id + 1)
            }
        }
    }
    @Synchronized fun character(account: String): CharacterMessages.Generate? = registered[account]
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    private fun save(values: Map<String, CharacterMessages.Generate>) {
        if (file == null) return
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val properties = Properties()
        values.forEach { (account, data) ->
            val encoded = CharacterMessages.generateReply(data)
            properties.setProperty(account, java.util.HexFormat.of().formatHex(encoded.copyOfRange(4, encoded.size)))
        }
        val temporary = Files.createTempFile(target.parent, "characters-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, "LOGH7 local characters") }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun accept(account: String, message: CharacterMessages.Generate): ByteArray {
        require(message.power in 2..3 && message.gender in 0..1)
        require(message.origin in if (message.power == 2) setOf(0, 1, 2, 4) else setOf(3, 4))
        require(message.surname.isNotBlank() && message.givenName.isNotBlank())
        require(message.category in 0..4)
        if (message.category == 4 && registered[account] == message) return CharacterMessages.generateReply(message)
        require(account !in registered) { "Account already owns a character in this session" }
        val previous = pending[account]
        val response = if (message.category == 0) {
            require((pending + registered).none { (owner, data) -> owner != account && normalized(data.surname) == normalized(message.surname) }) { "Surname already reserved" }
            check(pending.size < 4096 || previous != null)
            message.copy(id = previous?.id ?: nextId++, check = 1)
        } else {
            requireNotNull(previous) { "No character reservation" }
            require(message.id == previous.id && message.power == previous.power && message.origin == previous.origin &&
                message.gender == previous.gender && message.surname == previous.surname && message.givenName == previous.givenName) { "Creation identity changed" }
            when (message.category) {
                1 -> {
                    require(previous.category in 0..1 && message.age in 18..59 && message.birthMonth in 1..12 && message.birthDay in 1..30 && message.face > 0)
                    message.copy(abilities = List(8) { 50 }, bonus = 10, special = 0, title = 0, rank = 20, shipType = 3, shipKind = 0, shipName = "", check = 1)
                }
                2 -> {
                    require(previous.category == 1)
                    require(message.age == previous.age && message.birthMonth == previous.birthMonth && message.birthDay == previous.birthDay && message.face == previous.face)
                    require(message.abilities.zip(previous.abilities).all { (value, base) -> value in base..89 })
                    val spent = message.abilities.sum() - previous.abilities.sum()
                    require(spent <= previous.bonus && message.bonus == previous.bonus - spent)
                    previous.copy(category = 2, abilities = message.abilities.toList(), bonus = message.bonus)
                }
                3 -> {
                    require(previous.category == 2 && message.shipName.isNotBlank())
                    require((pending + registered).none { (owner, data) -> owner != account && data.shipName.isNotEmpty() && normalized(data.shipName) == normalized(message.shipName) }) { "Flagship name already reserved" }
                    previous.copy(category = 3, shipName = message.shipName)
                }
                4 -> {
                    require(previous.category == 3 && message == previous.copy(category = 4)) { "Registration data changed" }
                    save(registered + (account to message))
                    registered[account] = message
                    println("character registered id=${message.id} power=${message.power}")
                    message
                }
                else -> error("Unexpected creation category")
            }
        }
        if (response.category == 4) pending.remove(account) else pending[account] = response
        return CharacterMessages.generateReply(response)
    }
}
