package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.*

class LiveStateBackupTest {
    @TempDir lateinit var directory: Path

    @Test fun copiesEveryStoreButLocksInTheOfflineToolLayoutAndKeepsTheNewest() {
        val state = Files.createDirectories(directory.resolve("state"))
        val characters = Files.writeString(state.resolve("characters.properties"), "a=1\n")
        Files.writeString(state.resolve("characters.properties.mail.session-1"), "mail")
        Files.writeString(state.resolve("characters.properties.mail.session-1.lock"), "")
        Files.writeString(state.resolve("other.txt"), "not ours")
        val world = Files.createDirectories(state.resolve("world"))
        Files.writeString(world.resolve("slot-a"), "world")
        val session = Files.createDirectories(state.resolve("session").resolve("g1"))
        Files.writeString(session.resolve("journal"), "journal")
        val root = directory.resolve("backups")
        val backup = LiveStateBackup(characters, world, state.resolve("session"), root, keep = 2)
        val first = backup.finish(backup.copy(LocalDateTime.of(2026, 11, 4, 12, 0, 0)))
        assertEquals("state-20261104-120000", first.fileName.toString())
        assertFalse(Files.exists(root.resolve("state-20261104-120000.partial")))
        val manifest = Files.readString(first.resolve("manifest.json"))
        assertTrue("\"format\": \"LOGH7-STATE-BACKUP-1\"" in manifest && "\"live\": true" in manifest)
        assertTrue("\"name\": \"characters.properties.mail.session-1\"" in manifest && ".lock" !in manifest && "other.txt" !in manifest)
        assertTrue("\"name\": \"g1/journal\"" in manifest)
        assertEquals("journal", Files.readString(first.resolve("session/g1/journal")))
        assertFailsWith<IllegalArgumentException> { backup.copy(LocalDateTime.of(2026, 11, 4, 12, 0, 0)) }
        backup.finish(backup.copy(LocalDateTime.of(2026, 11, 4, 12, 30, 0)))
        backup.finish(backup.copy(LocalDateTime.of(2026, 11, 4, 13, 0, 0)))
        assertEquals(listOf("state-20261104-123000", "state-20261104-130000"),
            Files.list(root).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
        val other = directory.resolve("other-disk")
        val exported = backup.export(root.resolve("state-20261104-130000"), other)
        assertEquals(Files.readString(root.resolve("state-20261104-130000/manifest.json")), Files.readString(exported.resolve("manifest.json")))
        assertEquals("journal", Files.readString(exported.resolve("session/g1/journal")))
        assertFailsWith<IllegalArgumentException> { backup.export(root.resolve("state-20261104-130000"), other) }
    }

    @Test fun theAccountFileIsAnOptionalGroupWithItsOwnRoot() {
        val state = Files.createDirectories(directory.resolve("state"))
        val characters = Files.writeString(state.resolve("characters.properties"), "a=1\n")
        val world = Files.createDirectories(state.resolve("world"))
        val session = Files.createDirectories(state.resolve("session"))
        val accounts = Files.writeString(Files.createDirectories(directory.resolve("config")).resolve("accounts.properties"), "qa1=x\n")
        val backup = LiveStateBackup(characters, world, session, directory.resolve("backups"), keep = 1, accounts = accounts)
        val done = backup.finish(backup.copy(LocalDateTime.of(2026, 11, 6, 9, 0, 0)))
        assertEquals("qa1=x\n", Files.readString(done.resolve("accounts/accounts.properties")))
        val manifest = Files.readString(done.resolve("manifest.json"))
        assertTrue("\"group\": \"accounts\", \"name\": \"accounts.properties\"" in manifest)
        assertTrue("\"accounts\": " + "\"" + accounts.parent.toAbsolutePath().normalize().toString().replace("\\", "\\\\") + "\"" in manifest)
        assertTrue(" \"accounts\": \"accounts.properties\"," in manifest)
        // Without an account file the manifest is unchanged (older tools and backups keep working).
        val plain = LiveStateBackup(characters, world, session, directory.resolve("plain"), keep = 1)
        assertFalse("accounts" in Files.readString(plain.finish(plain.copy()).resolve("manifest.json")))
    }

    private fun store(): Triple<Path, Path, Path> {
        val state = Files.createDirectories(directory.resolve("state"))
        val characters = Files.writeString(state.resolve("characters.properties"), "a=1\n")
        val world = Files.createDirectories(state.resolve("world"))
        Files.writeString(world.resolve("CURRENT"), "LOGH7-WORLD-1\t1\t1\n")
        return Triple(characters, world, Files.createDirectories(state.resolve("session")))
    }

    @Test fun aDefinitionsDatabaseFailurePublishesNothing() {
        val (characters, world, session) = store()
        val root = directory.resolve("backups")
        val backup = LiveStateBackup(characters, world, session, root, keep = 2,
            definitions = { throw java.sql.SQLException("definitions database down") })
        val pending = backup.copy(LocalDateTime.of(2026, 11, 6, 14, 0, 0))
        assertFailsWith<java.sql.SQLException> { backup.finish(pending) }
        assertEquals(emptyList(), Files.list(root).use { it.toList() }, "no partial or finished backup is left")
    }

    @Test fun countriesAndFacilitiesAreBackedUpTogetherAndRestoreIntoAnotherDatabase() {
        val url = System.getenv("LOGH7_TEST_DATABASE_URL")
        org.junit.jupiter.api.Assumptions.assumeTrue(!url.isNullOrBlank(), "PostgreSQL definitions run in country-schema CI")
        val user = System.getenv("PGUSER") ?: "postgres"; val password = System.getenv("PGPASSWORD") ?: ""
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "")
        val (source, copy) = "backup_src_$suffix" to "backup_dst_$suffix"
        fun connect(schema: String) = java.sql.DriverManager.getConnection(url, user, password).also { it.schema = schema }
        fun migrate(schema: String) = connect(schema).use { db ->
            for (file in PostgresFacilityDefinitionsTest.ALL)
                db.createStatement().use { it.execute(checkNotNull(javaClass.getResourceAsStream("/db/migration/$file")).bufferedReader().use { r -> r.readText() }) }
        }
        fun tables(schema: String) = connect(schema).use { db -> db.createStatement().use { s ->
            s.executeQuery("""SELECT table_name||' '||(xpath('/row/c/text()', query_to_xml(format('select count(*)::text||'':''||md5(coalesce(string_agg(t::text, ''|'' order by t::text),'''')) c from %I.%I t',table_schema,table_name),false,true,'')))[1]::text
                FROM information_schema.tables WHERE table_schema='$schema' AND table_type='BASE TABLE' ORDER BY 1""").use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        } }
        java.sql.DriverManager.getConnection(url, user, password).use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $source"); it.execute("CREATE SCHEMA $copy") }
            try {
                migrate(source); migrate(copy)
                // Country rows (V1) and facility rows (V4) with non-ASCII text, quotes and a backslash.
                connect(source).use { db -> db.createStatement().use {
                    it.execute("INSERT INTO nation_currency VALUES ('fixture','시험 통화','Q',0)")
                    it.execute("INSERT INTO nation_calendar VALUES ('fixture','시험력',-310)")
                } }
                val facilities = PostgresFacilityDefinitions { connect(source) }
                assertEquals(org.logh7.engine.FacilityDefinitionOutcome.CHANGED, facilities.save(0, org.logh7.engine.FacilityDefinitionEdit.Kind(
                    org.logh7.engine.FacilityKindDefinition(22, "신'궁전", "황제의 \"궁전\" \\ 백슬래시.", "소속 판정."))))
                assertEquals(org.logh7.engine.FacilityDefinitionOutcome.CHANGED, facilities.save(1, org.logh7.engine.FacilityDefinitionEdit.Override(
                    org.logh7.engine.FacilityOverride(104923, "노이에 상수시", null))))
                val (characters, world, session) = store()
                val root = directory.resolve("backups")
                val backup = LiveStateBackup(characters, world, session, root, keep = 2, definitions = { connect(source) })
                val done = backup.finish(backup.copy(LocalDateTime.of(2026, 11, 6, 15, 0, 0)))
                val script = done.resolve("definitions/definitions.sql")
                assertTrue(Files.readString(script).startsWith("-- LOGH7-DEFINITIONS-BACKUP-1"))
                assertTrue("\"group\": \"definitions\", \"name\": \"definitions.sql\"" in Files.readString(done.resolve("manifest.json")))
                assertTrue(Files.isRegularFile(done.resolve("world/CURRENT")), "file state is still in the same backup")
                // Replay the script the way psql would: statements and COPY blocks, in one transaction.
                connect(copy).use { db ->
                    db.autoCommit = false
                    val api = db.unwrap(org.postgresql.PGConnection::class.java).copyAPI
                    val lines = Files.readAllLines(script)
                    var i = 0
                    while (i < lines.size) {
                        val line = lines[i++]
                        if (line.isBlank() || line.startsWith("--")) continue
                        if (line.startsWith("COPY ")) {
                            val data = buildString { while (lines[i] != "\\.") append(lines[i++]).append('\n') }; i++
                            api.copyIn(line.removeSuffix(";").removeSuffix(" FROM stdin") + " FROM STDIN", java.io.StringReader(data))
                        } else db.createStatement().use { it.execute(line) }
                    }
                    db.commit()
                }
                assertEquals(tables(source), tables(copy), "every country and facility table is identical after restore")
                val restored = PostgresFacilityDefinitions { connect(copy) }.load()
                assertEquals(2, restored.revision)
                assertEquals("노이에 상수시", restored.displayName(104923))
                assertEquals("황제의 \"궁전\" \\ 백슬래시.", restored.purpose(FacilityIds.palace))
                val exported = backup.export(done, directory.resolve("other-disk"))
                assertEquals(Files.readString(script), Files.readString(exported.resolve("definitions/definitions.sql")))
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $source CASCADE"); it.execute("DROP SCHEMA $copy CASCADE") }
            }
        }
    }

    private object FacilityIds { const val palace = 1049 * 100 + 23 }
}