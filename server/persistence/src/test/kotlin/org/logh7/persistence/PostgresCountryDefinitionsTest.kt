package org.logh7.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class PostgresCountryDefinitionsTest {
    private fun fixture(block:((()->Connection))->Unit) {
        val url=System.getenv("LOGH7_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(),"PostgreSQL definitions run in country-schema CI")
        val schema="definitions_"+UUID.randomUUID().toString().replace("-","")
        fun connect()=DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").also {it.schema=schema}
        DriverManager.getConnection(url,System.getenv("PGUSER") ?: "postgres",System.getenv("PGPASSWORD") ?: "").use {admin->
            admin.createStatement().use {it.execute("CREATE SCHEMA $schema")}
            try {
                connect().use {db->
                    for(file in listOf("V1__national_definitions.sql","V2__country_colors.sql","V3__country_definition_revision.sql"))
                        db.createStatement().use {it.execute(checkNotNull(javaClass.getResourceAsStream("/db/migration/$file")).bufferedReader().use {r->r.readText()})}
                    fun insert(sql:String,values:List<Any>) {db.prepareStatement(sql).use {query->values.forEachIndexed {i,value->query.setObject(i+1,value)};query.executeUpdate()}}
                    val catalog=CountryCatalogLoader.load()
                    insert("INSERT INTO nation_currency VALUES (?,?,?,?)",listOf("fixture","시험 통화","Q",0))
                    for(c in catalog.calendars.values)insert("INSERT INTO nation_calendar VALUES (?,?,?)",listOf(c.key,c.name,c.yearOffset))
                    for((table,values) in mapOf("nation_command_system" to catalog.families.values.map {it.commands},"nation_rank_system" to catalog.families.values.map {it.ranks},
                        "nation_office_system" to catalog.families.values.map {it.offices},"nation_peerage_system" to catalog.families.values.map {it.peerage}))
                        for(value in values.distinct())insert("INSERT INTO $table VALUES (?)",listOf(value))
                    for(style in catalog.families.values.map {it.shipStyle}.distinct())insert("INSERT INTO nation_ship_style VALUES (?,?)",listOf(style,style))
                    for(profile in catalog.countries.values.map {it.assets}.distinct())insert("INSERT INTO nation_asset_profile (profile_key,native_key) VALUES (?,?)",listOf(profile,profile))
                    for(f in catalog.families.values)insert("INSERT INTO nation_family VALUES (?,?,?,?,?,?,?,?,?)",listOf(f.key,f.name,f.calendar,"fixture",f.commands,f.ranks,f.offices,f.peerage,f.shipStyle))
                    for(c in catalog.countries.values)insert("INSERT INTO nation_country (country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile) VALUES (?,?,?,?,?,?,?)",
                        listOf(c.id,c.key,c.name,c.family,c.nativePower,c.nativeCamp,c.assets))
                }
                block(::connect)
            } finally {admin.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}}
        }
    }

    @Test fun definitionsPersistOverridesNewEntriesAndRejectInvalidOrStaleEdits()=fixture {connect->
        val store=PostgresCountryDefinitions(connect);val initial=store.load()
        assertEquals(0,initial.revision);assertEquals(CountryCatalogLoader.load().countries,initial.catalog.countries)
        val empire=initial.catalog.countries.getValue(2)
        val swapped=empire.copy(name="제국 시험",shipStyleOverride="alliance")
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(0,CountryDefinitionEdit.Country(swapped)))
        assertEquals(ShipStyleId("alliance"),store.load().catalog.shipStyle(CountryId(2)))
        assertEquals(CountryDefinitionOutcome.CONFLICT,store.save(0,CountryDefinitionEdit.Country(empire)))
        assertEquals(CountryDefinitionOutcome.UNCHANGED,store.save(1,CountryDefinitionEdit.Country(swapped)))
        for(invalid in listOf(swapped.copy(nativePower=3),swapped.copy(shipStyleOverride="missing"),swapped.copy(assets="missing"),swapped.copy(name="\n")))
            assertEquals(CountryDefinitionOutcome.INVALID,store.save(1,CountryDefinitionEdit.Country(invalid)))
        assertEquals(1,store.load().revision)
        assertEquals(CountryDefinitionOutcome.INVALID,store.save(1,CountryDefinitionEdit.Country(swapped,create=true)))
        val calendar=CountryCalendar("fixture","시험력",-310)
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(1,CountryDefinitionEdit.Calendar(calendar,create=true)))
        val custom=swapped.copy(id=42,key="fixture",calendarOverride=calendar.key)
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(2,CountryDefinitionEdit.Country(custom,create=true)))
        val reopened=PostgresCountryDefinitions(connect).load()
        assertEquals(3,reopened.revision);assertEquals(485,reopened.catalog.year(42,795))
        val family=reopened.catalog.families.getValue("empire").copy(key="fixture",name="시험 계열",calendar=calendar.key,shipStyle="alliance")
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(3,CountryDefinitionEdit.Family(family,"fixture",create=true)))
        val changed=custom.copy(family=family.key,calendarOverride=null,shipStyleOverride=null)
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(4,CountryDefinitionEdit.Country(changed)))
        assertEquals(485,store.load().catalog.year(42,795));assertEquals(ShipStyleId("alliance"),store.load().catalog.shipStyle(CountryId(42)))
        connect().use {db->db.createStatement().use {statement->
            statement.executeQuery("SELECT count(*) FROM nation_membership").use {assertTrue(it.next());assertEquals(0,it.getInt(1))}
            statement.executeQuery("SELECT count(*) FROM nation_territory").use {assertTrue(it.next());assertEquals(0,it.getInt(1))}
            statement.executeQuery("SELECT count(*) FROM nation_money_account").use {assertTrue(it.next());assertEquals(0,it.getInt(1))}
        }}
    }

    @Test fun concurrentFoundingReservationsUseOneDefinitionPerWorldAndFounder()=fixture {connect->
        val store=PostgresCountryDefinitions(connect)
        val world=UUID.randomUUID()
        val style=ShipStyleId("alliance")
        val pool=Executors.newFixedThreadPool(2)
        val same=try {
            (1..2).map {
                CompletableFuture.supplyAsync({
                    checkNotNull(CountryFoundingRegistry(world,store).reserve(1,10000,"captain","자유 항성국",style))
                },pool)
            }.map {it.get(12,java.util.concurrent.TimeUnit.SECONDS)}
        } finally {pool.shutdownNow()}
        assertEquals(same[0],same[1])
        val other=checkNotNull(CountryFoundingRegistry(UUID.randomUUID(),store).reserve(1,10000,"captain","다른 세계",style))
        assertNotEquals(same[0].id,other.id)
        val snapshot=store.load()
        assertEquals(2,snapshot.catalog.countries.values.count(CountryFounding::isFounded))
        assertEquals(CountryDefinitionOutcome.INVALID,store.save(snapshot.revision,CountryDefinitionEdit.Country(same[0].copy(key="changed_key"))))
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(snapshot.revision,CountryDefinitionEdit.Country(same[0].copy(name="수정 항성국"))))
    }

    @Test fun displayCurrencyCanBeCreatedRenamedAndOverriddenWithoutEditingAccounts() = fixture { connect ->
        val store = PostgresCountryDefinitions(connect)
        val original = store.load()
        val empire = original.catalog.countries.getValue(2)
        val currency = CountryCurrency("imperial_mark", "제국 마르크", "RM")
        assertEquals("fixture", original.currency(CountryId(2))?.key)
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(0, CountryDefinitionEdit.Currency(currency, create = true)))
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(1, CountryDefinitionEdit.Country(empire.copy(currencyOverride = currency.key))))
        val renamed = currency.copy(name = "은하 마르크", symbol = "GM")
        assertEquals(CountryDefinitionOutcome.CONFLICT, store.save(1, CountryDefinitionEdit.Currency(renamed)))
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(2, CountryDefinitionEdit.Currency(renamed)))
        assertEquals(CountryDefinitionOutcome.INVALID, store.save(3, CountryDefinitionEdit.Currency(renamed.copy(symbol = "01234"))))
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(3, CountryDefinitionEdit.Country(empire.copy(name = "이름 변경"), keepCurrencyOverride = true)))
        val reopened = PostgresCountryDefinitions(connect).load()
        assertEquals(renamed, reopened.currency(CountryId(2)))
        assertEquals("fixture", reopened.currency(CountryId(3))?.key)
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(4, CountryDefinitionEdit.Country(empire)))
        assertEquals("fixture", store.load().currency(CountryId(2))?.key)
        connect().use { db -> db.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM nation_money_account").use {
                assertTrue(it.next())
                assertEquals(0, it.getInt(1))
            }
        } }
    }

    @Test fun imperialMarkMigrationKeepsOperatorChoicesAndDoesNotChangeTheRevisionTwice() = fixture { connect ->
        val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V7__imperial_mark.sql"))
            .bufferedReader().use { it.readText() }
        connect().use { db -> db.createStatement().use { statement ->
            statement.execute("INSERT INTO nation_currency VALUES ('native_funds','원본 자금','',0)")
            statement.execute("UPDATE nation_family SET currency_key='native_funds' WHERE family_key='empire'")
            statement.execute("UPDATE nation_country SET currency_override='fixture' WHERE country_id=2")
            statement.execute(migration)
            statement.execute(migration)
        } }
        val store = PostgresCountryDefinitions(connect)
        val first = store.load()
        assertEquals(1L, first.revision)
        assertEquals("제국마르크", first.currencies.getValue("imperial_mark").name)
        assertEquals("imperial_mark", first.familyCurrencies["empire"])
        assertEquals("fixture", first.familyCurrencies["new_empire"])
        assertEquals("fixture", first.currency(CountryId(2))?.key)
        assertEquals("fixture", first.currency(CountryId(3))?.key)
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(1,
            CountryDefinitionEdit.Country(first.catalog.countries.getValue(2).copy(currencyOverride = null))))
        assertEquals("제국마르크", store.load().currency(CountryId(2))?.name)
    }

    @Test fun allianceDinarMigrationChangesOnlyTheUneditedDefault() = fixture { connect ->
        val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V8__alliance_dinar.sql"))
            .bufferedReader().use { it.readText() }
        connect().use { db -> db.createStatement().use { statement ->
            statement.execute("INSERT INTO nation_currency VALUES ('native_funds','원본 자금','',0)")
            statement.execute("UPDATE nation_family SET currency_key='native_funds' WHERE family_key='alliance'")
            statement.execute("UPDATE nation_country SET currency_override='fixture' WHERE country_id=3")
            statement.execute(migration)
            statement.execute(migration)
        } }
        val store = PostgresCountryDefinitions(connect)
        val first = store.load()
        assertEquals(1L, first.revision)
        assertEquals("디나르", first.currencies.getValue("alliance_dinar").name)
        assertEquals("alliance_dinar", first.familyCurrencies["alliance"])
        assertEquals("fixture", first.currency(CountryId(3))?.key)
        assertEquals("fixture", first.currency(CountryId(2))?.key)
        assertEquals(CountryDefinitionOutcome.CHANGED, store.save(1,
            CountryDefinitionEdit.Country(first.catalog.countries.getValue(3).copy(currencyOverride = null))))
        assertEquals("디나르", store.load().currency(CountryId(3))?.name)
    }

    @Test fun creationPresetCanBeOverriddenWithoutChangingTheOfficeOrShipStyle()=fixture {connect->
        val store=PostgresCountryDefinitions(connect)
        val before=store.load().catalog.families.getValue("new_empire")
        assertNull(before.creationStyle)
        assertEquals(2,before.creationPower)
        connect().use {db->
            val migration=checkNotNull(javaClass.getResourceAsStream("/db/migration/V6__country_creation_style.sql"))
            db.createStatement().use {it.execute(migration.bufferedReader().use {reader->reader.readText()})}
        }
        val changed=before.copy(creationStyle="alliance")
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(0,CountryDefinitionEdit.Family(changed,"fixture")))
        assertEquals(changed,store.load().catalog.families.getValue(before.key))
        assertEquals(3,store.load().catalog.families.getValue(before.key).creationPower)
        val renamed = before.copy(name = "시험 신제국 계열")
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(1,CountryDefinitionEdit.Family(renamed,"fixture",keepCreationStyle=true)))
        assertEquals("alliance",store.load().catalog.families.getValue(before.key).creationStyle)
        assertEquals(CountryDefinitionOutcome.CHANGED,store.save(2,CountryDefinitionEdit.Family(before,"fixture")))
        assertEquals(before,store.load().catalog.families.getValue(before.key))
    }

    @Test fun editsOfDifferentDefinitionsStillConflictAtTheSharedVersion()=fixture {connect->
        val store=PostgresCountryDefinitions(connect);val initial=store.load()
        Executors.newFixedThreadPool(2).use {pool->
            val gate=java.util.concurrent.CountDownLatch(1)
            val edits=listOf(CountryDefinitionEdit.Country(initial.catalog.countries.getValue(2).copy(name="시험 제국")),
                CountryDefinitionEdit.Calendar(initial.catalog.calendars.getValue("imperial").copy(name="시험력")))
            val jobs=edits.map {edit->CompletableFuture.supplyAsync({gate.await();store.save(0,edit)},pool)}
            gate.countDown();val result=jobs.map {it.get()}
            assertEquals(1,result.count {it==CountryDefinitionOutcome.CHANGED});assertEquals(1,result.count {it==CountryDefinitionOutcome.CONFLICT})
        }
        assertEquals(1,store.load().revision)
    }

    @Test fun aReadBegunBeforeACommitCannotMixItsOldRevisionWithNewCountryRows()=fixture {connect->
        val store=PostgresCountryDefinitions(connect);val before=store.load()
        val reader=PostgresCountryDefinitions {
            val db=connect()
            Proxy.newProxyInstance(javaClass.classLoader,arrayOf(Connection::class.java)) {_,method,args->
                val value=try {method.invoke(db,*(args ?: emptyArray()))}catch(e:java.lang.reflect.InvocationTargetException){throw e.targetException}
                if(method.name=="prepareStatement" && args?.firstOrNull()=="SELECT revision FROM nation_definition_revision WHERE singleton=TRUE") {
                    val query=value as PreparedStatement
                    Proxy.newProxyInstance(javaClass.classLoader,arrayOf(PreparedStatement::class.java)) {_,operation,parameters->
                        val result=try {operation.invoke(query,*(parameters ?: emptyArray()))}catch(e:java.lang.reflect.InvocationTargetException){throw e.targetException}
                        if(operation.name=="executeQuery")assertEquals(CountryDefinitionOutcome.CHANGED,
                            store.save(0,CountryDefinitionEdit.Country(before.catalog.countries.getValue(2).copy(name="새 제국"))))
                        result
                    }
                } else value
            } as Connection
        }
        val read=reader.load()
        assertEquals(0,read.revision);assertEquals(before.catalog.countries,read.catalog.countries)
        assertEquals(1,store.load().revision);assertEquals("새 제국",store.load().catalog.countries.getValue(2).name)
    }
}
