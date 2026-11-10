package org.logh7.persistence

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.CountryId
import org.logh7.engine.OriginalCandidateProfile
import org.logh7.engine.OriginalStartingOffice
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
                    it.execute("CREATE TABLE nation_country (country_id bigint PRIMARY KEY); INSERT INTO nation_country VALUES (2),(3),(42)")
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

                connect().use {test->test.createStatement().use {
                    it.execute("""CREATE TABLE nation_asset (
                        asset_key text PRIMARY KEY, asset_kind text NOT NULL CHECK (asset_kind IN ('emblem','flag'))
                    )""")
                }}
                migrate("V5__country_images.sql")
                migrate("V15__original_candidate_portrait_asset.sql")
                store.load()
                val empire=CountryId(2)
                val commander=profile.copy(rank=5,startingOffice=OriginalStartingOffice.REINHARD_FLEET_2)
                assertTrue(store.supportsPortraits)
                assertFalse(store.supportsStartingOffice)
                assertFailsWith<IllegalArgumentException> {store.save(1004,0,commander,empire)}

                migrate("V16__original_candidate_starting_office.sql")
                assertTrue(store.load().all {it.profile.startingOffice==null})
                assertTrue(store.supportsStartingOffice)
                assertFailsWith<IllegalArgumentException> {store.save(1002,0,commander,empire)}
                assertFailsWith<IllegalArgumentException> {store.save(1004,0,commander)}
                assertFailsWith<IllegalArgumentException> {store.save(1004,0,commander,CountryId(42))}
                assertFailsWith<IllegalArgumentException> {store.save(1004,0,commander.copy(rank=6),empire)}
                connect().use {test->test.createStatement().use {
                    val rejected=assertFailsWith<SQLException> {it.executeUpdate("""UPDATE original_candidate_profile
                        SET starting_office='reinhard-fleet-2', rank=5 WHERE world_identity='$world' AND candidate_id=1001""")}
                    assertEquals("23514",rejected.sqlState)
                }}
                val assign=CountDownLatch(1)
                val assignments=listOf(1004L,1005L).map {id->CompletableFuture.supplyAsync {
                    check(assign.await(5,TimeUnit.SECONDS))
                    store.save(id,0,commander,empire)
                }}
                assign.countDown()
                val assigned=assignments.mapNotNull {it.get(7,TimeUnit.SECONDS)}.single()
                val rival=if(assigned.id==1004L)1005L else 1004L
                assertEquals(assigned,PostgresOriginalCandidates(::connect,world).load().single {it.profile.startingOffice!=null})
                assertNull(store.save(assigned.id,1,commander.copy(startingOffice=null),empire))
                assertNull(store.save(assigned.id,1,commander.copy(startingOffice=null)))
                assertNull(store.save(rival,0,commander,empire))
                connect().use {test->test.createStatement().use {
                    val duplicate=assertFailsWith<SQLException> {it.executeUpdate("""UPDATE original_candidate_profile
                        SET starting_office='reinhard-fleet-2', rank=5 WHERE world_identity='$world' AND candidate_id=1003""")}
                    assertEquals("23505",duplicate.sqlState)
                }}
                val edited=assertNotNull(store.save(assigned.id,1,commander.copy(givenName="라인하르트"),empire))
                assertEquals(2L,edited.revision)
                assertEquals(edited,store.load().single {it.profile.startingOffice!=null})
                assertNull(store.save(assigned.id,1,commander,empire))
                val other=PostgresOriginalCandidates(::connect,UUID.randomUUID())
                assertTrue(other.load().isEmpty())
                assertNotNull(other.save(rival,0,commander,empire))

                val alliance=CountryId(3)
                val vucock=profile.copy(surname="뷰코크",givenName="알렉산드르",face=100059,rank=5,
                    startingOffice=OriginalStartingOffice.VUCOCK_FLEET_5)
                assertFalse(store.supportsAllianceStartingOffice)
                assertFailsWith<IllegalArgumentException> {store.save(1099,0,vucock,alliance)}
                migrate("V17__alliance_original_starting_office.sql")
                assertEquals(edited,store.load().single {it.profile.startingOffice!=null})
                assertTrue(store.supportsStartingOffice && store.supportsAllianceStartingOffice)
                assertFailsWith<IllegalArgumentException> {store.save(1099,0,vucock,empire)}
                assertFailsWith<IllegalArgumentException> {store.save(1099,0,vucock.copy(rank=6),alliance)}
                val allianceOfficer=assertNotNull(store.save(1099,0,vucock,alliance))
                assertNull(store.save(1100,0,vucock,alliance))
                val reopened=PostgresOriginalCandidates(::connect,world)
                assertEquals(setOf(edited,allianceOfficer),reopened.load().filter {it.profile.startingOffice!=null}.toSet())
                assertTrue(reopened.supportsAllianceStartingOffice)
                val staden=commander.copy(surname="Staden",givenName="Admiral",startingOffice=OriginalStartingOffice.STADEN_FLEET_4)
                assertFalse(reopened.supportsStadenStartingOffice)
                assertFailsWith<IllegalArgumentException> {reopened.save(1101,0,staden,empire)}
                migrate("V18__staden_original_starting_office.sql")
                assertEquals(setOf(edited,allianceOfficer),reopened.load().filter {it.profile.startingOffice!=null}.toSet())
                assertTrue(reopened.supportsStartingOffice && reopened.supportsAllianceStartingOffice && reopened.supportsStadenStartingOffice)
                assertFailsWith<IllegalArgumentException> {reopened.save(1101,0,staden,alliance)}
                assertFailsWith<IllegalArgumentException> {reopened.save(1101,0,staden.copy(rank=6),empire)}
                val stadenOfficer=assertNotNull(reopened.save(1101,0,staden,empire))
                assertNull(reopened.save(1102,0,staden,empire))
                assertNull(reopened.save(1101,1,staden.copy(startingOffice=null),empire))
                assertEquals(setOf(edited,allianceOfficer,stadenOfficer),
                    PostgresOriginalCandidates(::connect,world).load().filter {it.profile.startingOffice!=null}.toSet())
                connect().use {test->test.createStatement().use {
                    val rejected=assertFailsWith<SQLException> {it.executeUpdate("""UPDATE original_candidate_profile
                        SET starting_office='vucock-fleet-5' WHERE world_identity='$world' AND candidate_id=${edited.id}""")}
                    assertEquals("23514",rejected.sqlState)
                }}
            } finally {
                db.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}
            }
        }
    }
}
