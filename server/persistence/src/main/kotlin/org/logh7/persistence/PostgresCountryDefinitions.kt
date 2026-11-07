package org.logh7.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import org.logh7.engine.*

/** One revision row serializes definition edits; readers see one database snapshot. Author: Peppone Choi. */
class PostgresCountryDefinitions(private val connect:()->Connection):CountryDefinitionStore {
    private fun <T> Connection.rows(sql:String,read:(ResultSet)->T):List<T> = prepareStatement(sql).use {query->
        query.queryTimeout=5
        query.executeQuery().use {rows->buildList {while(rows.next())add(read(rows))}}
    }
    private fun snapshot(db:Connection):CountryDefinitionSnapshot {
        val revision=db.rows("SELECT revision FROM nation_definition_revision WHERE singleton=TRUE") {it.getLong(1)}.single()
        val calendars=db.rows("SELECT calendar_key,display_name,year_offset FROM nation_calendar ORDER BY calendar_key LIMIT 351") {
            CountryCalendar(it.getString(1),it.getString(2),it.getInt(3))}
        // Older definition schemas have no creation override, and keep the inherited preset.
        val families=db.rows("SELECT f.*, to_jsonb(f)->>'creation_style' AS creation_override FROM nation_family f ORDER BY family_key LIMIT 351") {
            CountryFamily(it.getString("family_key"),it.getString("display_name"),it.getString("calendar_key"),
                it.getString("command_system"),it.getString("rank_system"),it.getString("office_system"),it.getString("peerage_system"),
                it.getString("ship_style"),it.getString("creation_override")) to it.getString("currency_key")}
        val countries=db.rows("SELECT * FROM nation_country ORDER BY country_id LIMIT 351") {
            val id=it.getLong("country_id");require(id in 1..Int.MAX_VALUE)
            CountryDefinition(id.toInt(),it.getString("country_key"),it.getString("display_name"),it.getString("family_key"),
                it.getInt("native_power"),it.getInt("native_camp"),it.getString("asset_profile"),it.getString("calendar_override"),it.getString("ship_style_override"))}
        require(calendars.size<=350 && families.size<=350 && countries.size<=350)
        val references=mapOf("currency" to "nation_currency.currency_key","commands" to "nation_command_system.system_key",
            "ranks" to "nation_rank_system.system_key","offices" to "nation_office_system.system_key","peerage" to "nation_peerage_system.system_key",
            "ship_style" to "nation_ship_style.style_key","assets" to "nation_asset_profile.profile_key").mapValues {(_,source)->
            val (table,column)=source.split('.')
            db.rows("SELECT $column FROM $table ORDER BY $column LIMIT 1001") {it.getString(1)}.also {require(it.size<=1000)}
        }
        return CountryDefinitionSnapshot(revision,CountryCatalog(calendars,families.map {it.first},countries),references,families.associate {it.first.key to it.second})
    }
    override fun load():CountryDefinitionSnapshot = connect().use {db->
        db.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ;db.isReadOnly=true;db.autoCommit=false
        try {snapshot(db).also {db.commit()}} catch(failure:Exception) {rollback(db,failure);throw failure}
    }
    override fun save(expectedRevision:Long,edit:CountryDefinitionEdit):CountryDefinitionOutcome {
        require(expectedRevision>=0)
        return connect().use {db->
            db.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED;db.autoCommit=false
            try {
                db.createStatement().use {it.execute("SET LOCAL lock_timeout='2s'");it.execute("SET LOCAL statement_timeout='5s'")}
                val revision=db.rows("SELECT revision FROM nation_definition_revision WHERE singleton=TRUE FOR UPDATE") {it.getLong(1)}.single()
                if(revision!=expectedRevision) {db.rollback();return@use CountryDefinitionOutcome.CONFLICT}
                val before=snapshot(db);val source=before.catalog
                val (create,exists)=when(edit) {
                    is CountryDefinitionEdit.Country->edit.create to (edit.value.id in source.countries)
                    is CountryDefinitionEdit.Family->edit.create to (edit.value.key in source.families)
                    is CountryDefinitionEdit.Calendar->edit.create to (edit.value.key in source.calendars)
                }
                if(create==exists) {db.rollback();return@use CountryDefinitionOutcome.INVALID}
                val calendars=source.calendars.toMutableMap();val families=source.families.toMutableMap();val countries=source.countries.toMutableMap()
                var familyCurrencies=before.familyCurrencies
                val unchanged=when(edit) {
                    is CountryDefinitionEdit.Country -> (countries.put(edit.value.id,edit.value)==edit.value)
                    is CountryDefinitionEdit.Calendar -> (calendars.put(edit.value.key,edit.value)==edit.value)
                    is CountryDefinitionEdit.Family -> {
                        val family = if (edit.keepCreationStyle) edit.value.copy(creationStyle = source.families[edit.value.key]?.creationStyle) else edit.value
                        familyCurrencies=familyCurrencies+(edit.value.key to edit.currency)
                        families.put(family.key,family)==family && before.familyCurrencies[family.key]==edit.currency
                    }
                }
                CountryDefinitionSnapshot(revision,CountryCatalog(calendars.values.toList(),families.values.toList(),countries.values.toList()),before.references,familyCurrencies)
                if(unchanged) {db.rollback();return@use CountryDefinitionOutcome.UNCHANGED}
                when(edit) {
                    is CountryDefinitionEdit.Country -> edit.value.let {c->
                        update(db,"""INSERT INTO nation_country (country_id,country_key,display_name,family_key,native_power,native_camp,asset_profile,calendar_override,ship_style_override)
                            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (country_id) DO UPDATE SET country_key=EXCLUDED.country_key,
                            display_name=EXCLUDED.display_name,family_key=EXCLUDED.family_key,native_power=EXCLUDED.native_power,
                            native_camp=EXCLUDED.native_camp,asset_profile=EXCLUDED.asset_profile,calendar_override=EXCLUDED.calendar_override,ship_style_override=EXCLUDED.ship_style_override""",
                            listOf(c.id,c.key,c.name,c.family,c.nativePower,c.nativeCamp,c.assets,c.calendarOverride,c.shipStyleOverride))
                    }
                    is CountryDefinitionEdit.Calendar -> edit.value.let {c->update(db,"""INSERT INTO nation_calendar (calendar_key,display_name,year_offset) VALUES (?,?,?)
                        ON CONFLICT (calendar_key) DO UPDATE SET display_name=EXCLUDED.display_name,year_offset=EXCLUDED.year_offset""",listOf(c.key,c.name,c.yearOffset))}
                    is CountryDefinitionEdit.Family -> families.getValue(edit.value.key).let {f->update(db,"""INSERT INTO nation_family (family_key,display_name,calendar_key,currency_key,command_system,rank_system,office_system,peerage_system,ship_style)
                        VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (family_key) DO UPDATE SET display_name=EXCLUDED.display_name,calendar_key=EXCLUDED.calendar_key,
                        currency_key=EXCLUDED.currency_key,command_system=EXCLUDED.command_system,rank_system=EXCLUDED.rank_system,
                        office_system=EXCLUDED.office_system,peerage_system=EXCLUDED.peerage_system,ship_style=EXCLUDED.ship_style""",
                        listOf(f.key,f.name,f.calendar,edit.currency,f.commands,f.ranks,f.offices,f.peerage,f.shipStyle))
                        if (source.families[f.key]?.creationStyle != f.creationStyle) {
                            update(db,"UPDATE nation_family SET creation_style=? WHERE family_key=?",listOf(f.creationStyle,f.key))
                        }
                    }
                }
                update(db,"UPDATE nation_definition_revision SET revision=? WHERE singleton=TRUE",listOf(Math.addExact(revision,1)))
                db.commit();CountryDefinitionOutcome.CHANGED
            } catch(failure:Exception) {
                rollback(db,failure)
                if(failure is IllegalArgumentException || failure is SQLException && failure.sqlState in setOf("23503","23505","23514","42703"))CountryDefinitionOutcome.INVALID
                else throw failure
            }
        }
    }
    private fun update(db:Connection,sql:String,values:List<Any?>) = db.prepareStatement(sql).use {query->
        query.queryTimeout=5;values.forEachIndexed {index,value->query.setObject(index+1,value)}
        check(query.executeUpdate()==1)
    }
    private fun rollback(db:Connection,failure:Exception) {try {db.rollback()} catch(rollback:Exception){failure.addSuppressed(rollback)}}
}
