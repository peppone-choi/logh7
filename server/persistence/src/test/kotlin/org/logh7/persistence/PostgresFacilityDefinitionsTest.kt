package org.logh7.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class PostgresFacilityDefinitionsTest {
    private fun fixture(migrations:List<String> = ALL,block:((()->Connection))->Unit) {
        val url=System.getenv("LOGH7_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(),"PostgreSQL definitions run in country-schema CI")
        val schema="facilities_"+UUID.randomUUID().toString().replace("-","")
        fun connect()=DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").also {it.schema=schema}
        DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").use {admin->
            admin.createStatement().use {it.execute("CREATE SCHEMA $schema")}
            try {
                connect().use {db->
                    for(file in migrations)
                        db.createStatement().use {it.execute(checkNotNull(javaClass.getResourceAsStream("/db/migration/$file")).bufferedReader().use {r->r.readText()})}
                }
                block(::connect)
            } finally {admin.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}}
        }
    }

    @Test fun editsPersistAcrossStoresAndInvalidOrStaleEditsChangeNothing()=fixture {connect->
        val store=PostgresFacilityDefinitions(connect)
        val initial=store.load()
        assertEquals(FacilityDefinitionDefaults.snapshot(),initial,"an empty V4 schema reads as the built-in defaults")
        val palace=FacilityDefinitionSnapshot.facilityId(1049,22)
        val kind=FacilityKindDefinition(22,"신궁전","황제가 머무는 궁전.","소속 국가 인물만 들어간다.")
        assertEquals(FacilityDefinitionOutcome.CHANGED,store.save(0,FacilityDefinitionEdit.Kind(kind)))
        assertEquals(FacilityDefinitionOutcome.CONFLICT,store.save(0,FacilityDefinitionEdit.Kind(kind.copy(name="다른 이름"))))
        assertEquals(FacilityDefinitionOutcome.UNCHANGED,store.save(1,FacilityDefinitionEdit.Kind(kind)))
        assertEquals(FacilityDefinitionOutcome.CHANGED,store.save(1,FacilityDefinitionEdit.Override(FacilityOverride(palace,"노이에 상수시",null))))
        assertEquals(FacilityDefinitionOutcome.UNCHANGED,store.save(2,FacilityDefinitionEdit.ClearOverride(FacilityDefinitionSnapshot.facilityId(1050,22))))
        val reopened=PostgresFacilityDefinitions(connect).load()
        assertEquals(2,reopened.revision)
        assertEquals("노이에 상수시",reopened.displayName(palace))
        assertEquals("황제가 머무는 궁전.",reopened.purpose(palace))
        assertEquals("신궁전",reopened.name(FacilityDefinitionSnapshot.facilityId(1050,22)))
        assertNull(reopened.name(FacilityDefinitionSnapshot.facilityId(1049,3)),"untouched kinds keep the server name")
        assertEquals(FacilityDefinitionOutcome.CHANGED,store.save(2,FacilityDefinitionEdit.ClearOverride(palace)))
        assertEquals("신궁전",store.load().displayName(palace))
        // A row a database CHECK rejects (bypassing the model) is reported as invalid, not an error.
        connect().use {db->db.createStatement().use {statement->
            assertFailsWith<SQLException> {statement.execute("INSERT INTO facility_kind VALUES (3,'','x','y')")}}}
        assertEquals(3,store.load().revision)
    }

    @Test fun concurrentEditsAtTheSameRevisionCommitOnlyOnce()=fixture {connect->
        val store=PostgresFacilityDefinitions(connect)
        Executors.newFixedThreadPool(2).use {pool->
            val gate=java.util.concurrent.CountDownLatch(1)
            val edits=listOf(FacilityDefinitionEdit.Kind(FacilityKindDefinition(3,"정원","쉼터.","공공.")),
                FacilityDefinitionEdit.Override(FacilityOverride(FacilityDefinitionSnapshot.facilityId(1049,2),"대광장",null)))
            val jobs=edits.map {edit->CompletableFuture.supplyAsync({gate.await();store.save(0,edit)},pool)}
            gate.countDown();val result=jobs.map {it.get()}
            assertEquals(1,result.count {it==FacilityDefinitionOutcome.CHANGED});assertEquals(1,result.count {it==FacilityDefinitionOutcome.CONFLICT})
        }
        assertEquals(1,store.load().revision)
    }

    @Test fun anOlderDefinitionsDatabaseKeepsCountriesAndReportsFacilitiesUnavailable()=fixture(ALL.take(3)) {connect->
        // V1–V3 only: the facility tables are absent, so the caller falls back to the defaults.
        assertFailsWith<SQLException> {PostgresFacilityDefinitions(connect).load()}
        connect().use {db->db.createStatement().use {statement->
            statement.executeQuery("SELECT revision FROM nation_definition_revision").use {assertTrue(it.next());assertEquals(0,it.getLong(1))}}}
    }

    companion object {
        val ALL=listOf("V1__national_definitions.sql","V2__country_colors.sql","V3__country_definition_revision.sql","V4__facility_definitions.sql")
    }
}
