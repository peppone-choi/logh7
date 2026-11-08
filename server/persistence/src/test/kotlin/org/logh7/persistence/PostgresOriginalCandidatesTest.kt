package org.logh7.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.OriginalCandidateProfile
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PostgresOriginalCandidatesTest {
    @Test fun isolatedWorldsAndConcurrentVersions() {
        val url=System.getenv("LOGH7_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(),"PostgreSQL definitions run in country-schema CI")
        val schema="candidate_"+UUID.randomUUID().toString().replace("-","")
        fun open()=DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "")
        fun connect()=open().also {it.schema=schema}
        open().use { db ->
            db.createStatement().use {it.execute("CREATE SCHEMA $schema")}
            try {
                val sql=checkNotNull(javaClass.getResourceAsStream("/db/migration/V11__original_candidate_profile.sql"))
                    .bufferedReader().use {it.readText()}
                connect().use {test->test.createStatement().use {it.execute(sql)}}
                val world=UUID.randomUUID()
                val store=PostgresOriginalCandidates(::connect,world)
                val profile=OriginalCandidateProfile("키르히아이스","지크프리트",112,10,listOf(90,85,80,75,70,65,60,55))
                assertNull(store.save(1001,1,profile))
                assertEquals(1L,store.save(1001,0,profile)?.revision)
                assertNull(store.save(1001,0,profile))
                val go=CountDownLatch(1)
                val writers=listOf(8,9).map { rank -> CompletableFuture.supplyAsync {
                    check(go.await(5,TimeUnit.SECONDS))
                    store.save(1001,1,profile.copy(rank=rank))
                } }
                go.countDown()
                val updated=writers.mapNotNull {it.get(7,TimeUnit.SECONDS)}.single()
                assertEquals(2L,updated.revision)
                assertEquals(listOf(updated),PostgresOriginalCandidates(::connect,world).load())
                assertTrue(PostgresOriginalCandidates(::connect,UUID.randomUUID()).load().isEmpty())
            } finally {
                db.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}
            }
        }
    }
}
