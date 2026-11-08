package org.logh7.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.NpcFleetDialogue
import org.logh7.engine.NpcCombatBehavior
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PostgresNpcFleetDialogueTest {
    @Test fun perWorldAndGenerationIsolationAndConcurrentEdits() {
        val url=System.getenv("LOGH7_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(),"PostgreSQL definitions run in country-schema CI")
        val schema="npc_"+UUID.randomUUID().toString().replace("-","")
        fun open()=DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "")
        fun connect()=open().also {it.schema=schema}
        open().use { db ->
            db.createStatement().use {it.execute("CREATE SCHEMA $schema")}
            try {
                val world=UUID.randomUUID()
                fun migrate(file:String) {
                    val migration=checkNotNull(javaClass.getResourceAsStream("/db/migration/$file"))
                    connect().use { test -> test.createStatement().use {it.execute(migration.bufferedReader().use {r->r.readText()})} }
                }
                migrate("V9__npc_fleet_dialogue.sql")
                connect().use { test -> test.prepareStatement("INSERT INTO npc_fleet_dialogue VALUES (?,1,8,1,'','기존 말투','')").use {
                    it.setObject(1,world);it.executeUpdate()
                } }
                migrate("V10__npc_fleet_behavior.sql")
                val store=PostgresNpcFleetDialogue(::connect,world)
                val old=store.load(1).single()
                assertEquals("기존 말투",old.dialogue.style)
                assertEquals(NpcCombatBehavior.STANDARD,old.behavior)
                val dialogue=NpcFleetDialogue("우리 진로를 비켜라.","차분함","짧게 경고한다.\n지시를 반복하지 않는다.")
                assertNull(store.save(1,7,1,dialogue))
                assertEquals(1L,store.save(1,7,0,dialogue)?.revision)
                assertNull(store.save(1,7,0,dialogue))
                val go=CountDownLatch(1)
                val writers=listOf("단호함","격식체").map { style -> CompletableFuture.supplyAsync {
                    check(go.await(5,TimeUnit.SECONDS));store.save(1,7,1,dialogue.copy(style=style),NpcCombatBehavior.CAUTIOUS)
                } }
                go.countDown()
                val saved=writers.mapNotNull {it.get(7,TimeUnit.SECONDS)}.single()
                assertEquals(2L,saved.revision)
                assertEquals(NpcCombatBehavior.CAUTIOUS,saved.behavior)
                assertEquals(saved,PostgresNpcFleetDialogue(::connect,world).load(1).single {it.outfit==7})
                assertTrue(store.load(2).isEmpty())
                assertTrue(PostgresNpcFleetDialogue(::connect,UUID.randomUUID()).load(1).isEmpty())
                assertEquals(3L,store.save(1,7,2,NpcFleetDialogue())?.revision)
                val reset=store.load(1).single {it.outfit==7}
                assertEquals(NpcFleetDialogue(),reset.dialogue)
                assertEquals(NpcCombatBehavior.STANDARD,reset.behavior)
            } finally {db.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}}
        }
    }
}
