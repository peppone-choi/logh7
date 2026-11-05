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

class PostgresCountryColorsTest {
    @Test fun `PostgreSQL retains colors and serializes first and later concurrent edits`() {
        val url=System.getenv("LOGH7_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(),"PostgreSQL integration runs in the country-schema CI job")
        val schema="palette_"+UUID.randomUUID().toString().replace("-","")
        fun connect():Connection=DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").also {it.schema=schema}
        DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").use {admin ->
            admin.createStatement().use {it.execute("CREATE SCHEMA $schema")}
            try {
                connect().use {db->db.createStatement().use {statement ->
                    for(file in listOf("V1__national_definitions.sql","V2__country_colors.sql")) {
                        val ddl=checkNotNull(javaClass.getResourceAsStream("/db/migration/$file")).bufferedReader().use {it.readText()}
                        statement.execute(ddl)
                    }
                    statement.execute("""INSERT INTO nation_calendar VALUES ('fixture','Fixture',0);
                        INSERT INTO nation_currency VALUES ('fixture','Fixture','',0);
                        INSERT INTO nation_command_system VALUES ('fixture');
                        INSERT INTO nation_rank_system VALUES ('fixture');
                        INSERT INTO nation_office_system VALUES ('fixture');
                        INSERT INTO nation_peerage_system VALUES ('fixture');
                        INSERT INTO nation_ship_style VALUES ('fixture','Fixture');
                        INSERT INTO nation_asset_profile (profile_key,native_key) VALUES ('fixture','native/fixture');
                        INSERT INTO nation_family VALUES ('fixture','Fixture','fixture','fixture','fixture','fixture','fixture','fixture','fixture');
                        INSERT INTO nation_country (country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile)
                        VALUES (2,'fixture','Fixture','fixture',2,0,'fixture'),(100,'additional','Additional','fixture',2,0,'fixture');""")
                }}
                val store=PostgresCountryColors(::connect)
                assertEquals(listOf(2,100),store.list().map {it.countryId})
                assertTrue(store.list().all {it.revision==0L && it.colors==CountryColors()})
                fun race(expected:Long):List<CountryColorOutcome> = Executors.newFixedThreadPool(2).use {pool ->
                    val gate=java.util.concurrent.CountDownLatch(1)
                    // Both proposals must change the preceding round's value; a no-op correctly keeps its revision.
                    val jobs=listOf(0x123456+expected.toInt(),0xabcdef-expected.toInt()).map {color->CompletableFuture.supplyAsync({gate.await();store.save(2,expected,CountryColors(color,0,0xffffff,color))},pool)}
                    gate.countDown();jobs.map {it.get()}
                }
                for(expected in listOf(0L,1L)) {
                    val result=race(expected)
                    assertEquals(1,result.count {it==CountryColorOutcome.CHANGED})
                    assertEquals(1,result.count {it==CountryColorOutcome.CONFLICT})
                    assertEquals(expected+1,store.list().first().revision)
                }
                val saved=store.list().first()
                assertEquals(saved,PostgresCountryColors(::connect).list().first())
                assertEquals(CountryColorOutcome.CONFLICT,store.save(2,0,CountryColors()))
                assertEquals(CountryColorOutcome.UNCHANGED,store.save(2,saved.revision,saved.colors))
                assertEquals(CountryColorOutcome.CHANGED,store.save(2,saved.revision,CountryColors()))
                assertEquals(CountryColors(),store.list().first().colors)
                assertEquals(3L,store.list().first().revision)
                assertEquals(CountryColorOutcome.NOT_FOUND,store.save(999,0,CountryColors()))
                connect().use {db->db.createStatement().use {statement->
                    assertEquals("23514",assertFailsWith<SQLException>{statement.executeUpdate("UPDATE nation_country_colors SET primary_rgb=-1 WHERE country_id=2")}.sqlState)
                    assertEquals("23503",assertFailsWith<SQLException>{statement.executeUpdate("INSERT INTO nation_country_colors (country_id,revision) VALUES (999,1)")}.sqlState)
                }}
                assertEquals(CountryColors(),store.list().first().colors)
            } finally {admin.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}}
        }
    }
}
