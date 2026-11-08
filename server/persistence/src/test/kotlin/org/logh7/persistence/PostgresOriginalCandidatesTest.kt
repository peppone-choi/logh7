package org.logh7.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.CountryId
import org.logh7.engine.OriginalCandidateProfile
import org.logh7.engine.Peerage
import java.sql.DriverManager
import java.sql.SQLException
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
                fun migrate(file:String) {
                    val sql=checkNotNull(javaClass.getResourceAsStream("/db/migration/$file"))
                        .bufferedReader().use {it.readText()}
                    connect().use {test->test.createStatement().use {it.execute(sql)}}
                }
                migrate("V11__original_candidate_profile.sql")
                val world=UUID.randomUUID()
                val store=PostgresOriginalCandidates(::connect,world)
                val profile=OriginalCandidateProfile("키르히아이스","지크프리트",112,10,listOf(90,85,80,75,70,65,60,55))
                connect().use {test->test.createStatement().use {
                    it.execute("CREATE TABLE nation_country (country_id bigint PRIMARY KEY); INSERT INTO nation_country VALUES (2),(42)")
                    it.execute("""INSERT INTO original_candidate_profile VALUES
                        ('$world',1001,1,'키르히아이스','지크프리트',112,10,'{90,85,80,75,70,65,60,55}')""")
                }}
                assertEquals(profile,store.load().single().profile)
                assertFalse(store.supportsEditing)
                migrate("V12__original_candidate_country.sql")
                assertEquals(profile,store.load().single().profile)
                assertFalse(store.supportsEditing)
                migrate("V13__original_candidate_peerage.sql")
                assertEquals(profile,store.load().single().profile)
                assertTrue(store.supportsEditing)
                assertFalse(store.supportsFemaleFaces)
                migrate("V14__original_candidate_female_faces.sql")
                assertEquals(profile,store.load().single().profile)
                assertTrue(store.supportsFemaleFaces)
                assertFailsWith<SQLException> {store.save(1004,0,profile.copy(face=110005),CountryId(42))}
                assertNull(store.save(1003,1,profile,CountryId(42)))
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
                val first=store.save(1003,0,profile,CountryId(42))
                assertEquals(CountryId(42),first?.country)
                assertNull(store.save(1003,0,profile,CountryId(2)))
                val titled=profile.copy(face=10001,peerage=Peerage.COUNT)
                val moved=store.save(1003,1,titled,CountryId(2))
                assertEquals(CountryId(2),moved?.country)
                assertEquals(moved,PostgresOriginalCandidates(::connect,world).load().single {it.id==1003L})
                assertFailsWith<SQLException> {store.save(1001,2,titled)}
                connect().use {test->test.createStatement().use {
                    it.execute("""INSERT INTO original_candidate_profile
                        SELECT '$world',id,1,'후보','시험',112,10,'{50,50,50,50,50,50,50,50}',42
                        FROM generate_series(2000,2092) id""")
                }}
                val start=CountDownLatch(1)
                val contenders=listOf(2093L,2094L).map { id -> CompletableFuture.supplyAsync {
                    check(start.await(5,TimeUnit.SECONDS))
                    store.save(id,0,profile,CountryId(42))
                } }
                start.countDown()
                assertEquals(1,contenders.mapNotNull {it.get(7,TimeUnit.SECONDS)}.size)
                assertEquals(94,store.load().count {it.country==CountryId(42)})
                assertNull(store.save(1003,2,profile,CountryId(42)))
            } finally {
                db.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}
            }
        }
    }
}
