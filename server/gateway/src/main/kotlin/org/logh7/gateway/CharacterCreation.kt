package org.logh7.gateway

import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.OriginalCharacterMessages
import org.logh7.engine.Flagships
import java.text.Normalizer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.time.Clock
import java.security.SecureRandom

/** Local P2 creation policy; guessed initial ability/rank/ship values are documented separately. */
class CharacterCreation(private val file: Path? = null, private val clock: Clock = Clock.systemUTC(),
    private val lotteryWindowMillis: Long = 60_000, private val random: java.util.Random = SecureRandom(),
    private val originals: List<OriginalCharacterMessages.Candidate> = OriginalCharacterMessages.localCandidates,
    private val accountFame: Map<String, Int> = emptyMap(),
    starterKinds: Map<Int, List<Int>> = mapOf(2 to listOf(0), 3 to listOf(89))) {
    // Current user policy: standard battleship. A later grant policy may supply multiple kinds.
    private val starters = starterKinds.mapValues { (_, kinds) -> kinds.toList() }
    private var nextId = 10_000L
    private val pending = mutableMapOf<String, CharacterMessages.Generate>()
    private val registered = mutableMapOf<String, CharacterProfile>()
    private val applications = mutableMapOf<String, List<Long>>()
    private var deadline = 0L
    init {
        require(lotteryWindowMillis in 1..3_600_000)
        require(originals.map { it.character.id }.distinct().size == originals.size && originals.all { it.minimumFame >= 0 && it.character.id in 1..9999 })
        require(accountFame.values.all { it >= 0 })
        require(starters.keys == setOf(2, 3) && starters.all { (power, kinds) ->
            kinds.isNotEmpty() && kinds.distinct().size == kinds.size && kinds.all { Flagships.find(it)?.power == power }
        })
        if (file != null && Files.exists(file)) {
            val properties = Properties().also { Files.newBufferedReader(file).use(it::load) }
            for (account in properties.stringPropertyNames()) {
                val value = properties.getProperty(account)
                if (account == "@deadline") { deadline = value.toLong(); continue }
                if (account.startsWith("@application/")) {
                    applications[account.removePrefix("@application/")] = value.split(',').map(String::toLong); continue
                }
                val profile=CharacterProfile.decoded(value)
                registered[account] = profile
                nextId = maxOf(nextId, profile.character.id + 1)
            }
            require(applications.size <= 4096 && (applications.isEmpty() || deadline > 0))
            require(applications.all { (account, ids) -> account !in registered && ids.size in 1..5 &&
                ids.distinct().size == ids.size && ids.all { id -> originals.any { it.character.id == id } } })
        }
    }
    private fun displayed(data:CharacterMessages.Generate)=if(data.generated)data else KoreanDisplayNames.original(data)
    @Synchronized fun character(account: String): CharacterMessages.Generate? { resolveLottery(); return registered[account]?.character?.let(::displayed) }
    @Synchronized fun profiles():Map<String,CharacterProfile> { resolveLottery();return registered.toMap() }
    /** U-11: a character that died in battle leaves the account, which may then create a new one. */
    @Synchronized fun retire(account: String, id: Long): Boolean {
        if (registered[account]?.character?.id != id) return false
        val next = registered - account
        save(next); registered.remove(account); pending.remove(account); return true
    }
    /** U-25 defection (user decision 2026-11-01): the one sanctioned change of a registered character's side. */
    @Synchronized fun defect(account: String, id: Long, power: Int): Boolean {
        require(power in 2..3)
        val current = registered[account]?.takeIf { it.character.id == id && it.country != org.logh7.engine.CountryId(power) } ?: return false
        val data=current.character
        val next = registered + (account to CharacterProfile.legacy(data.copy(power = power,
            shipKind = Flagships.forType(power, data.shipType).kind)))
        save(next); registered[account] = next.getValue(account); return true
    }
    /** U-27 carry-over source: generated characters as stored (originals never carry over, W p.11). */
    @Synchronized fun generatedCharacters():Map<String,CharacterMessages.Generate> {
        resolveLottery();return registered.filterValues {it.character.generated}.mapValues {it.value.character}.toSortedMap()
    }
    /** U-27 carry-over into this (next) generation; an account that already has a character keeps it. */
    @Synchronized fun carry(account:String,data:CharacterMessages.Generate):Boolean {
        return carry(account,CharacterProfile.legacy(data))
    }
    @Synchronized fun carry(account:String,profile:CharacterProfile):Boolean {
        val data=profile.character
        require(data.generated && data.category==4 && data.id>0)
        if (account in registered || registered.values.any {it.character.id==data.id}) return false
        val next=registered+(account to profile)
        save(next);registered[account]=profile;nextId=maxOf(nextId,data.id+1);return true
    }
    @Synchronized fun registeredCharacters():List<CharacterMessages.Generate> {
        resolveLottery();return registered.values.map {it.character}.sortedBy { it.id }.map(::displayed)
    }
    @Synchronized fun accountByCharacter(id:Int):String? {
        resolveLottery();return registered.entries.singleOrNull {it.value.character.id==id.toLong()}?.key
    }
    @Synchronized fun characterById(id: Int): CharacterMessages.Generate? {
        resolveLottery()
        return registered.values.singleOrNull { it.character.id == id.toLong() }?.character?.let(::displayed)
    }
    @Synchronized fun entries(account: String): List<Long> { resolveLottery(); return applications[account].orEmpty().toList() }
    @Synchronized fun candidates(account: String): List<OriginalCharacterMessages.Candidate> {
        resolveLottery()
        if (account in registered || account in pending) return emptyList()
        // Local accounts have fame 0. Original numeric conditions are unknown.
        val currentFame=fame(account)
        return originals.filter { it.minimumFame <= currentFame && registered.values.none { owned -> owned.character.id == it.character.id } }
            .map {it.copy(character=KoreanDisplayNames.original(it.character))}
    }
    fun fame(account: String): Int = accountFame[account] ?: 0
    @Synchronized fun entryState(account: String, id: Long): ByteArray {
        val eligible = candidates(account).any { it.character.id == id }
        val competingPreferences = applications.values.filter { id in it }.take(5).map { (it.indexOf(id) + 1).toLong() }
        return OriginalCharacterMessages.entryState(id, eligible, competingPreferences)
    }
    @Synchronized fun apply(account: String, ids: List<Long>): ByteArray {
        require(ids.size in 1..5 && ids.distinct().size == ids.size)
        val available = candidates(account).map { it.character.id }.toSet()
        require(ids.all { it in available }) { "Ineligible original-character application" }
        check(applications.size < 4096 || account in applications)
        val next = applications + (account to ids.toList())
        val nextDeadline = if (applications.isEmpty()) clock.millis() + lotteryWindowMillis else deadline
        save(registered, next, nextDeadline)
        applications[account] = ids.toList(); deadline = nextDeadline
        println("original application preferences=${ids.joinToString()} deadline=$deadline")
        return OriginalCharacterMessages.applicationReply(ids)
    }
    private fun resolveLottery() {
        if (applications.isEmpty() || clock.millis() < deadline) return
        val winners = registered.toMutableMap()
        val accounts = applications.keys.toMutableList().also { java.util.Collections.shuffle(it, random) }
        for (account in accounts) {
            if (account in winners) continue
            val id = applications.getValue(account).firstOrNull { candidate -> winners.values.none { it.character.id == candidate } } ?: continue
            val candidate = originals.single { it.character.id == id }
            winners[account] = CharacterProfile.legacy(candidate.character.copy(generated = false))
        }
        save(winners, emptyMap(), 0)
        registered.clear(); registered.putAll(winners); applications.clear(); deadline = 0
        println("original lottery resolved assigned=${accounts.count { it in winners }}")
    }
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    private fun save(values: Map<String, CharacterProfile>, entries: Map<String, List<Long>> = applications, closesAt: Long = deadline) {
        if (file == null) return
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val properties = Properties()
        values.forEach { (account, profile) -> properties.setProperty(account, profile.encoded()) }
        if (entries.isNotEmpty()) {
            properties.setProperty("@deadline", closesAt.toString())
            entries.forEach { (account, ids) -> properties.setProperty("@application/$account", ids.joinToString(",")) }
        }
        val temporary = Files.createTempFile(target.parent, "characters-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, "LOGH7 local characters") }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun accept(account: String, message: CharacterMessages.Generate): ByteArray {
        resolveLottery()
        require(account !in applications) { "Original-character application pending" }
        require(message.power in 2..3 && message.gender in 0..1)
        require(message.origin in if (message.power == 2) setOf(0, 1, 2, 4) else setOf(3, 4))
        require(message.surname.isNotBlank() && message.givenName.isNotBlank())
        require(message.category in 0..4)
        if (message.category == 4 && registered[account]?.character == message) return CharacterMessages.generateReply(message)
        require(account !in registered) { "Account already owns a character in this session" }
        val previous = pending[account]
        val response = if (message.category == 0) {
            require(originals.none { normalized(it.character.surname) == normalized(message.surname) ||
                normalized(KoreanDisplayNames.original(it.character).surname) == normalized(message.surname) }) { "Surname already reserved" }
            require((pending + registered.mapValues {it.value.character}).none { (owner, data) -> owner != account && normalized(data.surname) == normalized(message.surname) }) { "Surname already reserved" }
            check(pending.size < 4096 || previous != null)
            message.copy(id = previous?.id ?: nextId++, check = 1)
        } else {
            requireNotNull(previous) { "No character reservation" }
            require(message.id == previous.id && message.power == previous.power && message.origin == previous.origin &&
                message.gender == previous.gender && message.surname == previous.surname && message.givenName == previous.givenName) { "Creation identity changed" }
            when (message.category) {
                1 -> {
                    require(previous.category in 0..1 && message.age in 18..59 && message.birthMonth in 1..12 && message.birthDay in 1..30 && message.face > 0)
                    val ship = if (previous.category == 0) starters.getValue(previous.power).let { kinds ->
                        checkNotNull(Flagships.find(kinds[if (kinds.size == 1) 0 else random.nextInt(kinds.size)]))
                    }
                        else checkNotNull(Flagships.find(previous.shipKind))
                    message.copy(abilities = List(8) { 50 }, bonus = 10, special = 0, title = 0, rank = 20,
                        shipType = ship.type, shipKind = ship.kind, shipName = "", check = 1)
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
                    require((pending + registered.mapValues {it.value.character}).none { (owner, data) -> owner != account && data.shipName.isNotEmpty() && normalized(data.shipName) == normalized(message.shipName) }) { "Flagship name already reserved" }
                    previous.copy(category = 3, shipName = message.shipName)
                }
                4 -> {
                    require(previous.category == 3 && message == previous.copy(category = 4)) { "Registration data changed" }
                    val profile=CharacterProfile.legacy(message)
                    save(registered + (account to profile))
                    registered[account] = profile
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
