package org.logh7.app

import kotlinx.coroutines.*
import org.logh7.engine.WorldEngine
import org.logh7.engine.SessionRules
import org.logh7.engine.MemorySessionGenerationStore
import org.logh7.persistence.FileSessionGenerationStore
import org.logh7.persistence.FileSessionStore
import org.logh7.gateway.Gateway
import org.logh7.gateway.captureDirectoryFrom
import org.logh7.gateway.EngineGameAdmission
import org.logh7.gateway.WorldBootstrap
import org.logh7.engine.Galaxy
import org.logh7.ops.startOpsApi
import org.logh7.ops.OpsAudit
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

fun main() {
    val environment=RuntimeEnvironment.load()
    val sessions=SessionRuntimeConfiguration.load(environment)
    val directory=environment["LOGH7_SESSIONS_FILE"]?.takeIf {it.isNotBlank()}?.let {org.logh7.gateway.GameSessionDirectory()}
    val accounts=environment["LOGH7_ACCOUNTS_FILE"]?.let {org.logh7.gateway.FileAccounts(Path.of(it)).also {file->file.names()}}
    val runtimes=ArrayDeque<AutoCloseable>()
    fun closeAll()=synchronized(runtimes) {
        var failure:Throwable?=null
        while(runtimes.isNotEmpty())try {runtimes.removeFirst().close()}catch(caught:Throwable) {
            if(failure==null)failure=caught else failure.addSuppressed(caught)
        }
        failure?.let {throw it}
    }
    try {
        val fame=if(directory==null)null else org.logh7.gateway.FameLedger.openShared(
            Path.of(checkNotNull(sessions.first().environment["LOGH7_ACCOUNT_FAME_FILE"])),
            forceDirectory=environment["LOGH7_SESSION_DIRECTORY_SYNC"]!="files-only",
            requireExisting=sessions.any {java.nio.file.Files.exists(Path.of(checkNotNull(it.environment["LOGH7_SESSION_STORE_DIR"])).resolve("world.identity"),java.nio.file.LinkOption.NOFOLLOW_LINKS)})
            .also {runtimes.addFirst(it)}
        val worldIdentities=mutableSetOf<String>()
        sessions.forEach {
            it.stateDirectory?.let(java.nio.file.Files::createDirectories)
            runtimes.addFirst(startGameRuntime(it.environment,directory,it.id,it.name,accounts,fame,worldIdentities))
        }
        Runtime.getRuntime().addShutdownHook(Thread {closeAll()})
        CountDownLatch(1).await()
    } finally {closeAll()}
}

internal fun startGameRuntime(environment:RuntimeEnvironment,lobby:org.logh7.gateway.GameSessionDirectory?=null,
    sessionId:Int=1,sessionName:String=org.logh7.protocol.LobbyMessages.LOCAL_SESSION_NAME,
    sharedAccounts:org.logh7.gateway.FileAccounts?=null,accountFame:org.logh7.gateway.FameLedger?=null,
    worldIdentities:MutableSet<String> = mutableSetOf()):AutoCloseable {
    fun port(name:String,default:Int):Int {
        val value=environment[name]?.let {it.toIntOrNull() ?: error("$name must be an integer port")} ?: default
        require(value in 1..65535) {"$name must be in 1..65535"}
        return value
    }
    val gamePort=port("LOGH7_GAME_PORT",47900)
    val updatePort=port("LOGH7_UPDATE_PORT",47902)
    val sessionPort=port("LOGH7_SESSION_PORT",47903)
    val opsPort=port("LOGH7_OPS_PORT",47901)
    require(setOf(gamePort,updatePort,sessionPort,opsPort).size==4) {"Game, update, session and ops ports must be distinct"}
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val resources = ArrayDeque<AutoCloseable>()
    resources.addFirst(AutoCloseable { scope.cancel() })
    fun closeResources(): Unit = synchronized(resources) {
        var failure: Throwable? = null
        while (resources.isNotEmpty()) {
            try { resources.removeFirst().close() }
            catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }
    try {
        val rules = environment["LOGH7_SESSION_RULES"]?.let { path -> java.nio.file.Files.newInputStream(Path.of(path)).use { SessionRules.load(it) } } ?: SessionRules.load()
        val characterFile = environment["LOGH7_CHARACTERS_FILE"]?.let { Path.of(it) }
        val generationFile = environment["LOGH7_SESSION_GENERATION_FILE"]?.let { Path.of(it) }
            ?: characterFile?.resolveSibling("${characterFile.fileName}.session-generation")
        val generations = generationFile?.let(::FileSessionGenerationStore) ?: MemorySessionGenerationStore()
        val sessionDirectory = environment["LOGH7_SESSION_STORE_DIR"]?.let { Path.of(it) }
        val world = environment["LOGH7_WORLD_FILE"]?.let { WorldBootstrap(Galaxy.load(Path.of(it))) }
        val directorySync = when (environment["LOGH7_SESSION_DIRECTORY_SYNC"]) {
            null, "required" -> org.logh7.persistence.DirectorySync.REQUIRED
            "files-only" -> org.logh7.persistence.DirectorySync.FILE_CONTENTS_ONLY
            else -> error("LOGH7_SESSION_DIRECTORY_SYNC must be required or files-only")
        }
        val store = sessionDirectory?.let { FileSessionStore(it, rules, initialGeneration = generations.current(), directorySync = directorySync) }
        if (store?.directorySync == org.logh7.persistence.DirectorySync.FILE_CONTENTS_ONLY)
            println("Session persistence: forced file contents; directory power-loss durability is OS-managed")
        val (navigationPath, stockPath) = try { Pair(
            if (world == null) null else environment["LOGH7_NAVIGATION_FILE"]?.let { Path.of(it) }
                ?: requireNotNull(characterFile) { "World navigation requires LOGH7_CHARACTERS_FILE or LOGH7_NAVIGATION_FILE" }
                    .resolveSibling("${characterFile.fileName}.navigation"),
            if (world == null) null else environment["LOGH7_PLANET_STOCK_FILE"]?.let { Path.of(it) }
                ?: requireNotNull(characterFile) { "World production requires LOGH7_CHARACTERS_FILE or LOGH7_PLANET_STOCK_FILE" }
                    .resolveSibling("${characterFile.fileName}.planet-stocks"))
        } catch (failure: Throwable) {
            try { store?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        val worldState = try { environment["LOGH7_WORLD_STATE_DIR"]?.let {
            require(world != null && it.isNotBlank()) { "World state storage requires a world and a nonempty directory" }
            org.logh7.persistence.FileWorldStateStore(Path.of(it), navigationPath, stockPath)
        } } catch (failure: Throwable) {
            try { store?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        if (worldState != null) resources.addFirst(worldState)
        val navigationStore = try { worldState?.navigationStore ?: navigationPath?.let { org.logh7.persistence.FileNavigationStore(it) } }
        catch (failure: Throwable) {
            try { store?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        val stockStore = try { worldState?.stockStore ?: stockPath?.let { org.logh7.persistence.FilePlanetStockStore(it) } }
        catch (failure: Throwable) {
            try { navigationStore?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            try { store?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        val engine = try { WorldEngine(scope, rules, generationStore = generations, durability = store,
            galaxy = world?.galaxy, navigationStore = navigationStore, stockStore = stockStore, worldStateCommitter = worldState,
            npcPolicy = org.logh7.engine.NpcTacticalPolicy(strategic = true), awaitIdentities = world != null) }
        catch (failure: Throwable) {
            try { stockStore?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            try { navigationStore?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            try { store?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        resources.addFirst(AutoCloseable { runBlocking { engine.drainAndClose() } })
        val fame=accountFame?.let {shared->
            val identity=org.logh7.persistence.FileWorldIdentity.loadOrCreate(checkNotNull(sessionDirectory),directorySync)
            synchronized(worldIdentities) {check(worldIdentities.add(identity)) {"Two sessions have the same persistent world identity"}}
            resources.addFirst(AutoCloseable {synchronized(worldIdentities){worldIdentities.remove(identity)}})
            shared.forWorld(identity).also {it.importLegacy(characterFile?.resolveSibling("${characterFile.fileName}.fame"))}
        }
        val captureDirectory = captureDirectoryFrom(environment["LOGH7_CAPTURE_DIR"])
        // A live view of the account file: the operator page edits it and new logins read the change.
        val accountFile = sharedAccounts ?: environment["LOGH7_ACCOUNTS_FILE"]?.let { org.logh7.gateway.FileAccounts(Path.of(it)).also { a -> a.names() } }
        val gateway = Gateway(captureDirectory,
            accountFile ?: emptyMap(),
            characterFile,
            gameSeconds = { checkNotNull(engine.snapshot().session).gameSeconds }, admission = EngineGameAdmission(engine, scope), world = world,
            navigation = if (world == null) null else org.logh7.gateway.EngineGameNavigation(engine, scope),
            stock = if (world == null) null else org.logh7.gateway.EngineGameStock(engine, scope),
            administration = if (world == null) null else org.logh7.gateway.EngineGameAdministration(engine, scope, world.galaxy),
            sessionDirectory=lobby,sessionId=sessionId,sessionName=sessionName,accountFame=fame)
        resources.addFirst(gateway)
        val audit = environment["LOGH7_OPS_AUDIT_FILE"]?.let { OpsAudit.open(Path.of(it)) }
        if (audit != null) resources.addFirst(audit)
        // LOGH7_OPS_TOKEN_FILE suits container secrets; a trailing newline in the file is not part of the token.
        val opsToken = environment["LOGH7_OPS_TOKEN"]?.takeIf { it.isNotEmpty() }
            ?: environment["LOGH7_OPS_TOKEN_FILE"]?.let { java.nio.file.Files.readString(Path.of(it)).trim() }?.takeIf { it.isNotEmpty() }
        // LOGH7_BACKUP_DIR enables live backups (operator button); LOGH7_BACKUP_INTERVAL_MINUTES schedules them.
        val backups = environment["LOGH7_BACKUP_DIR"]?.let { dir ->
            ScheduledBackups(engine, org.logh7.persistence.LiveStateBackup(
                requireNotNull(characterFile) { "Live backups need LOGH7_CHARACTERS_FILE" },
                Path.of(requireNotNull(environment["LOGH7_WORLD_STATE_DIR"]) { "Live backups need LOGH7_WORLD_STATE_DIR" }),
                requireNotNull(sessionDirectory) { "Live backups need LOGH7_SESSION_STORE_DIR" }, Path.of(dir),
                keep = environment["LOGH7_BACKUP_KEEP"]?.toInt() ?: 48),
                environment["LOGH7_BACKUP_EXPORT_DIR"]?.let { Path.of(it) })
        }
        backups?.schedule(scope, environment["LOGH7_BACKUP_INTERVAL_MINUTES"]?.toLong() ?: 0)
        val countryColors=environment["LOGH7_DEFINITIONS_JDBC_URL"]?.takeIf {it.isNotBlank()}?.let {url ->
                require(url.startsWith("jdbc:postgresql:")) {"LOGH7_DEFINITIONS_JDBC_URL must select PostgreSQL"}
                val properties=java.util.Properties().apply {
                    environment["LOGH7_DEFINITIONS_DB_USER"]?.let {setProperty("user",it)}
                    environment["LOGH7_DEFINITIONS_DB_PASSWORD"]?.let {setProperty("password",it)}
                    setProperty("connectTimeout","2");setProperty("socketTimeout","5")
                }
                val delegate=org.logh7.persistence.PostgresCountryColors {java.sql.DriverManager.getConnection(url,properties)}
                object:org.logh7.engine.CountryColorStore {
                    override fun list()=delegate.list().also {world?.publishCountryAppearances(it)}
                    override fun save(countryId:Int,expectedRevision:Long,colors:org.logh7.engine.CountryColors)=
                        delegate.save(countryId,expectedRevision,colors).also {
                            if(it==org.logh7.engine.CountryColorOutcome.CHANGED)try {list()} catch(_:java.sql.SQLException) {
                                // The commit succeeded; a display refresh failure must not report a failed save.
                                System.err.println("Country colors saved; display refresh deferred")
                            }
                        }
                }
            }
        if(countryColors!=null)scope.launch(Dispatchers.IO) {
            var failed=false
            while(isActive) {
                try {countryColors.list();failed=false} catch(_:java.sql.SQLException) {
                    if(!failed)System.err.println("Country definitions DB unavailable; retaining the last display snapshot")
                    failed=true
                }
                delay(5000)
            }
        }
        val ops = startOpsApi(engine, engine, port = opsPort, audit = audit, token = opsToken,
            host = environment["LOGH7_OPS_BIND"] ?: "127.0.0.1",
            users = environment["LOGH7_OPS_USERS_FILE"]?.let { org.logh7.ops.OpsUsers(Path.of(it)) }, backups = backups,
            countryColors=countryColors,
            checklist=environment["LOGH7_CHECKLIST_FILE"]?.let {Path.of(it)} ?: listOf(Path.of("docs/IMPLEMENTATION-CHECKLIST.md"),Path.of("../docs/IMPLEMENTATION-CHECKLIST.md")).firstOrNull(java.nio.file.Files::isRegularFile),
            galaxy=world?.galaxy,accounts = accountFile?.let { file -> object : org.logh7.ops.AccountAdmin {
                override fun names() = file.names()
                override fun set(account: String, password: String) =
                    org.logh7.gateway.FileAccounts.validName(account) && org.logh7.gateway.FileAccounts.validPassword(password) && run { file.set(account, password); true }
                override fun remove(account: String) = file.remove(account)
            } })
        resources.addFirst(ops)
        val bindAddress = environment["LOGH7_BIND_ADDRESS"] ?: "127.0.0.1"
        gateway.start(gamePort = gamePort, updatePort = updatePort, bindAddress = bindAddress, sessionPort = sessionPort, sessionAddress = environment["LOGH7_SESSION_ADDRESS"] ?: bindAddress,
            additionalBindAddresses = listOfNotNull(environment["LOGH7_HOST_ONLY_ADDRESS"]))
        println("LOGH7 session $sessionId ($sessionName): $bindAddress:$gamePort / $updatePort / $sessionPort; ops: 127.0.0.1:$opsPort; capture: ${captureDirectory?.toAbsolutePath() ?: "off"}")
        return AutoCloseable {closeResources()}
    } catch (failure: Throwable) {
        try { closeResources() } catch (closing: Throwable) { failure.addSuppressed(closing) }
        throw failure
    }
}
