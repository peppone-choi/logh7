package org.logh7.gateway

import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.OriginalCharacterMessages
import org.logh7.engine.Flagships
import org.logh7.engine.CountryCatalogLoader
import org.logh7.engine.CountryId
import org.logh7.engine.OriginalCandidateDefinition
import org.logh7.engine.OriginalCandidateEdit
import org.logh7.engine.OriginalCandidateProfile
import org.logh7.engine.PublishedOriginalCandidates
import org.logh7.engine.ShipStyleId
import org.logh7.engine.supportsNativePeerage
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
    starterKinds: Map<Int, List<Int>>? = null,
    shipStyles:Map<Int,ShipStyleId>? = null,
    private val publishedOriginals:PublishedOriginalCandidates=PublishedOriginalCandidates()) {
    companion object {
        fun originalDefinitions()=OriginalCharacterMessages.localCandidates.map { candidate ->
            val data=KoreanDisplayNames.original(candidate.character)
            OriginalCandidateDefinition(data.id,data.power,
                OriginalCandidateProfile(data.surname,data.givenName,data.face,data.rank,data.abilities))
        }
    }
    private val configuredStyles=shipStyles?.toMap()
    private val styles get()=configuredStyles ?: CountryCatalogLoader.current().let {catalog->
        setOf(2,3).associateWith {catalog.shipStyle(CountryId(it))}}
    // Current user policy: standard battleship. A later grant policy may supply multiple kinds.
    private val configuredStarters=starterKinds?.mapValues {(_,kinds)->kinds.toList()}
    private val starters get()=configuredStarters ?: styles.mapValues {(_,style)->listOf(Flagships.forType(style.nativePower,0).kind)}
    /** The wizard uses [creationPower]. Once registered the original game uses [nativePower]. */
    data class CountrySelection(val country:CountryId,val nativePower:Int,val shipStyle:ShipStyleId,
        val creationPower:Int=nativePower) {
        init { require(nativePower in 2..3 && creationPower in 2..3) { "Invalid native power" }; shipStyle.nativePower }
        internal fun encoded(): String {
            val legacy = "${country.value}:$nativePower:${shipStyle.value}"
            return if (creationPower == nativePower) legacy else "$legacy:$creationPower"
        }
        internal companion object {
            fun decoded(value:String)=value.split(':').let {
                require(it.size in 3..4 && it[0].toInt().toString()==it[0]) { "Invalid country selection" }
                val nativePower = it[1].toInt()
                CountrySelection(CountryId(it[0].toInt()),nativePower,ShipStyleId(it[2]),it.getOrNull(3)?.toInt() ?: nativePower)
            }
        }
    }
    /** An unfinished creation is bound to the selection and the connection (owner) that started it. */
    private data class Binding(val selection:CountrySelection?,val owner:String?)
    private var nextId = 10_000L
    private val pending = mutableMapOf<String, CharacterMessages.Generate>()
    private val bindings = mutableMapOf<String, Binding>()
    private val registered = mutableMapOf<String, CharacterProfile>()
    private val applications = mutableMapOf<String, List<Long>>()
    private val applicationSelections = mutableMapOf<String, CountrySelection>()
    private var deadline = 0L
    private val originalIds=originals.map {it.character.id}.toSet()
    init {
        require(lotteryWindowMillis in 1..3_600_000)
        require(originalIds.size == originals.size && originals.all { it.minimumFame >= 0 && it.character.id in 1..9999 })
        require(accountFame.values.all { it >= 0 })
        require(styles.keys==setOf(2,3))
        require(starters.keys == setOf(2, 3) && starters.all { (power, kinds) ->
            kinds.isNotEmpty() && kinds.distinct().size == kinds.size && kinds.all { Flagships.find(it)?.power == styles.getValue(power).nativePower }
        })
        if (file != null && Files.exists(file)) {
            val properties = Properties().also { Files.newBufferedReader(file).use(it::load) }
            for (account in properties.stringPropertyNames()) {
                val value = properties.getProperty(account)
                if (account == "@deadline") { deadline = value.toLong(); continue }
                if (account.startsWith("@application/")) {
                    applications[account.removePrefix("@application/")] = value.split(',').map(String::toLong); continue
                }
                if (account.startsWith("@selection/")) {
                    applicationSelections[account.removePrefix("@selection/")] = CountrySelection.decoded(value); continue
                }
                val profile=CharacterProfile.decoded(value)
                registered[account] = profile
                nextId = maxOf(nextId, profile.character.id + 1)
            }
            require(applications.size <= 4096 && (applications.isEmpty() || deadline > 0))
            val roster=originalPool(publishedOriginals.current()).associateBy {it.character.id}
            require(applications.all { (account, ids) -> account !in registered && ids.size in 1..5 &&
                ids.distinct().size == ids.size && ids.all { id -> roster[id]?.let {
                    val selection=applicationSelections[account]
                    if(selection==null)id in originalIds else selection.creationPower==it.character.power
                } == true } }) { "Pending original lottery requires its candidate definitions" }
            require(applicationSelections.keys.all { it in applications }) { "Selection without application" }
        }
    }
    private fun displayed(data:CharacterMessages.Generate)=if(data.generated)data else KoreanDisplayNames.original(data)
    private fun originalPool(edits:Map<Long,OriginalCandidateEdit>):List<OriginalCharacterMessages.Candidate> {
        fun OriginalCharacterMessages.Candidate.edited(id:Long=character.id):OriginalCharacterMessages.Candidate {
            val edit=edits[id]?.profile ?: return this
            return copy(character=character.copy(id=id,surname=edit.surname,givenName=edit.givenName,
                face=edit.face,gender=edit.gender,rank=edit.rank,abilities=edit.abilities,title=edit.peerage?.let {it.ordinal+1} ?: 0,
                shipName=if(id==character.id)character.shipName else "Local $id"))
        }
        val added=edits.values.filter {it.id !in originalIds}.sortedBy {it.id}.map {row->
            OriginalCharacterMessages.localCandidates.first {it.character.power==row.profile.creationPower}.edited(row.id)
        }
        return originals.map {it.edited()}+added
    }
    // Preserve the model already chosen if the administrator changes definitions during the wizard.
    private fun profile(data:CharacterMessages.Generate)=CharacterProfile(data,CountryId(data.power),
        ShipStyleId.fromLegacy(checkNotNull(Flagships.find(data.shipKind)).power))
    @Synchronized fun character(account: String): CharacterMessages.Generate? { resolveLottery(); return registered[account]?.character?.let(::displayed) }
    @Synchronized fun profiles():Map<String,CharacterProfile> { resolveLottery();return registered.toMap() }
    /** U-11: a character that died in battle leaves the account, which may then create a new one. */
    @Synchronized fun retire(account: String, id: Long): Boolean {
        if (registered[account]?.character?.id != id) return false
        val next = registered - account
        save(next); registered.remove(account); pending.remove(account); bindings.remove(account); return true
    }
    /** Drops an unfinished creation; with an [owner], only the one that connection started. Registered characters
     * and accepted lottery applications are kept. */
    @Synchronized fun cancelCreation(account:String,owner:String?=null):Boolean {
        if (account !in pending || (owner != null && bindings[account]?.owner != owner)) return false
        pending.remove(account); bindings.remove(account); return true
    }
    /** U-25 defection (user decision 2026-11-01): the one sanctioned change of a registered character's side. */
    @Synchronized fun defect(account: String, id: Long, power: Int): Boolean {
        require(power in 2..3)
        return defect(account,id,CountryId(power))
    }
    @Synchronized fun defect(account:String,id:Long,country:CountryId,committedStyle:ShipStyleId?=null,
        catalog:org.logh7.engine.CountryCatalog=CountryCatalogLoader.current()):Boolean {
        val current = registered[account]?.takeIf { it.character.id == id && it.country != country } ?: return false
        val definition=catalog.countries[country.value] ?: return false
        val style=committedStyle ?: configuredStyles?.get(country.value) ?: catalog.shipStyle(country)
        if(definition.nativePower !in 2..3 || style.value !in setOf("empire","alliance"))return false
        val data=current.character
        val next = registered + (account to CharacterProfile(data.copy(power=definition.nativePower,title=0,
            shipKind=Flagships.forType(style.nativePower,data.shipType).kind),country,style))
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
    @Synchronized fun applicationSelection(account:String):CountrySelection? {resolveLottery();return applicationSelections[account]}
    fun hasEditedCandidates()=publishedOriginals.current().isNotEmpty()
    @Synchronized fun candidates(account: String, selection: CountrySelection? = null): List<OriginalCharacterMessages.Candidate> {
        resolveLottery()
        if (account in registered || account in pending) return emptyList()
        // Local accounts have fame 0. Original numeric conditions are unknown.
        val currentFame=fame(account)
        val selected=selection ?: applicationSelections[account]
        val edits=publishedOriginals.current()
        val accepted=applications[account].orEmpty().takeIf {selected==applicationSelections[account]}.orEmpty()
        val owned=registered.values.mapTo(mutableSetOf()) {it.character.id}
        return originalPool(edits).filter {candidate->
            val id=candidate.character.id
            val country=edits[id]?.country
            candidate.minimumFame<=currentFame && id !in owned &&
                (selected==null || candidate.character.power==selected.creationPower) &&
                (country==null || country==selected?.country || id in accepted)
        }.map {it.copy(character=KoreanDisplayNames.original(it.character))}
    }
    fun fame(account: String): Int = accountFame[account] ?: 0
    @Synchronized fun entryState(account: String, id: Long, selection: CountrySelection? = null): ByteArray {
        val eligible = candidates(account, selection).any { it.character.id == id }
        val competingPreferences = applications.values.filter { id in it }.take(5).map { (it.indexOf(id) + 1).toLong() }
        return OriginalCharacterMessages.entryState(id, eligible, competingPreferences)
    }
    @Synchronized fun apply(account: String, ids: List<Long>, selection: CountrySelection? = null): ByteArray {
        require(ids.size in 1..5 && ids.distinct().size == ids.size)
        // An application keeps the country it was made for; changing it needs a new session flow, not a resubmit.
        require(account !in applications || applicationSelections[account] == selection) { "Application country changed" }
        val available = candidates(account, selection).map { it.character.id }.toSet()
        require(ids.all { it in available }) { "Ineligible original-character application" }
        check(applications.size < 4096 || account in applications)
        val next = applications + (account to ids.toList())
        val nextSelections = if (selection == null) applicationSelections - account else applicationSelections + (account to selection)
        val nextDeadline = if (applications.isEmpty()) clock.millis() + lotteryWindowMillis else deadline
        save(registered, next, nextDeadline, nextSelections)
        applications[account] = ids.toList(); deadline = nextDeadline
        if (selection != null) applicationSelections[account] = selection
        println("original application preferences=${ids.joinToString()} deadline=$deadline")
        return OriginalCharacterMessages.applicationReply(ids)
    }
    private fun resolveLottery() {
        if (applications.isEmpty() || clock.millis() < deadline) return
        val winners = registered.toMutableMap()
        val roster=originalPool(publishedOriginals.current()).associateBy {it.character.id}
        val countries=CountryCatalogLoader.current()
        val accounts = applications.keys.toMutableList().also { java.util.Collections.shuffle(it, random) }
        for (account in accounts) {
            if (account in winners) continue
            val id = applications.getValue(account).firstOrNull { candidate -> winners.values.none { it.character.id == candidate } } ?: continue
            val character=roster.getValue(id).character
            val selection=applicationSelections[account]
            val style=selection?.shipStyle ?: styles.getValue(character.power)
            // The hero keeps its own model unless the chosen style differs; then the same-type model of that style.
            val kind=if(style==ShipStyleId.fromLegacy(character.power))character.shipKind else Flagships.forType(style.nativePower,character.shipType).kind
            val country=selection?.country ?: CountryId(character.power)
            val title=character.title.takeIf {publishedOriginals.canGrantPeerage && countries.supportsNativePeerage(country)} ?: 0
            val won=character.copy(generated = false,shipKind=kind,power=selection?.nativePower ?: character.power,title=title)
            winners[account] = if(selection==null)profile(won) else CharacterProfile(won,selection.country,selection.shipStyle)
        }
        save(winners, emptyMap(), 0, emptyMap())
        registered.clear(); registered.putAll(winners); applications.clear(); applicationSelections.clear(); deadline = 0
        println("original lottery resolved assigned=${accounts.count { it in winners }}")
    }
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    private fun save(values: Map<String, CharacterProfile>, entries: Map<String, List<Long>> = applications, closesAt: Long = deadline,
        selections: Map<String, CountrySelection> = applicationSelections) {
        if (file == null) return
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val properties = Properties()
        values.forEach { (account, profile) -> properties.setProperty(account, profile.encoded()) }
        if (entries.isNotEmpty()) {
            properties.setProperty("@deadline", closesAt.toString())
            entries.forEach { (account, ids) -> properties.setProperty("@application/$account", ids.joinToString(",")) }
            selections.filterKeys { it in entries }.forEach { (account, s) -> properties.setProperty("@selection/$account", s.encoded()) }
        }
        val temporary = Files.createTempFile(target.parent, "characters-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, "LOGH7 local characters") }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun accept(account: String, message: CharacterMessages.Generate, selection: CountrySelection? = null,
        owner: String? = null): ByteArray {
        resolveLottery()
        require(account !in applications) { "Original-character application pending" }
        require(message.power in 2..3 && message.gender in 0..1)
        // The client's power byte must be the selected country's display code; never substituted with 2/3.
        require(selection == null || message.power == selection.creationPower) { "Power differs from the selected creation preset" }
        require(message.origin in if (message.power == 2) setOf(0, 1, 2, 4) else setOf(3, 4))
        require(message.surname.isNotBlank() && message.givenName.isNotBlank())
        require(message.category in 0..4)
        val storedMessage = if (selection == null) message else message.copy(power = selection.nativePower)
        if (message.category == 4 && registered[account]?.let { it.character == storedMessage &&
                (selection == null || it.country == selection.country && it.shipStyle == selection.shipStyle) } == true)
            return CharacterMessages.generateReply(message)
        require(account !in registered) { "Account already owns a character in this session" }
        val previous = pending[account]
        // A started creation stays on its country and connection; another connection must cancel it first.
        if (previous != null) require(bindings[account] == Binding(selection, owner)) { "Creation country or connection changed" }
        val response = if (message.category == 0) {
            val requested=normalized(message.surname)
            require(originals.none { normalized(it.character.surname) == requested ||
                normalized(KoreanDisplayNames.original(it.character).surname) == requested } &&
                publishedOriginals.current().values.none {normalized(it.profile.surname)==requested}) { "Surname already reserved" }
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
                    val ship = if (previous.category == 0) starterKinds(previous.power, selection).let { kinds ->
                        checkNotNull(Flagships.find(kinds[if (kinds.size == 1) 0 else random.nextInt(kinds.size)]))
                    }
                        else checkNotNull(Flagships.find(previous.shipKind))
                    // User policy (2026-11-06): generated captains start as ensigns; original candidates keep their own data.
                    message.copy(abilities = List(8) { 50 }, bonus = 10, special = 0, title = 0, rank = 13,
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
                    val profile=if(selection==null)profile(message) else CharacterProfile(storedMessage,selection.country,selection.shipStyle)
                    save(registered + (account to profile))
                    registered[account] = profile
                    println("character registered id=${message.id} power=${message.power}")
                    message
                }
                else -> error("Unexpected creation category")
            }
        }
        if (response.category == 4) { pending.remove(account); bindings.remove(account) }
        else { pending[account] = response; bindings[account] = Binding(selection, owner) }
        return CharacterMessages.generateReply(response)
    }
    /** Starter models follow the selected ship style, independent of the office family or display code. */
    private fun starterKinds(power:Int,selection:CountrySelection?):List<Int> {
        if (selection == null) return starters.getValue(power)
        return starters.entries.firstOrNull { styles[it.key] == selection.shipStyle }?.value
            ?: listOf(Flagships.forType(selection.shipStyle.nativePower, 0).kind)
    }
}
