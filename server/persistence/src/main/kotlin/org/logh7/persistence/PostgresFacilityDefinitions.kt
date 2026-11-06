package org.logh7.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import org.logh7.engine.*

/**
 * Facility definitions in PostgreSQL (V4). Rows overlay [FacilityDefinitionDefaults]; a kind without a
 * row keeps the built-in text. One revision row serializes edits like the country definitions.
 * Author: Peppone Choi.
 */
class PostgresFacilityDefinitions(private val connect:()->Connection):FacilityDefinitionStore {
    private fun <T> Connection.rows(sql:String,read:(ResultSet)->T):List<T> = prepareStatement(sql).use {query->
        query.queryTimeout=5
        query.executeQuery().use {rows->buildList {while(rows.next())add(read(rows))}}
    }
    private fun snapshot(db:Connection,lock:Boolean=false):FacilityDefinitionSnapshot {
        val revision=db.rows("SELECT revision FROM facility_definition_revision WHERE singleton=TRUE"+if(lock)" FOR UPDATE" else "") {it.getLong(1)}.single()
        val kinds=db.rows("SELECT kind,display_name,purpose,usage FROM facility_kind ORDER BY kind LIMIT 100") {
            FacilityKindDefinition(it.getInt(1),it.getString(2),it.getString(3),it.getString(4))}
        val overrides=db.rows("SELECT facility_id,display_name,purpose FROM facility_override ORDER BY facility_id LIMIT ${FacilityDefinitionSnapshot.MAX_OVERRIDES+1}") {
            FacilityOverride(it.getInt(1),it.getString(2),it.getString(3))}
        require(kinds.size<=FacilityKindDefinition.MAX_KIND+1 && overrides.size<=FacilityDefinitionSnapshot.MAX_OVERRIDES)
        return FacilityDefinitionSnapshot(revision,FacilityDefinitionDefaults.snapshot().kinds+kinds.associateBy {it.kind},overrides.associateBy {it.facilityId})
    }
    override fun load():FacilityDefinitionSnapshot = connect().use {db->
        db.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ;db.isReadOnly=true;db.autoCommit=false
        try {snapshot(db).also {db.commit()}} catch(failure:Exception) {rollback(db,failure);throw failure}
    }
    override fun save(expectedRevision:Long,edit:FacilityDefinitionEdit):FacilityDefinitionOutcome {
        require(expectedRevision>=0)
        return connect().use {db->
            db.transactionIsolation=Connection.TRANSACTION_READ_COMMITTED;db.autoCommit=false
            try {
                db.createStatement().use {it.execute("SET LOCAL lock_timeout='2s'");it.execute("SET LOCAL statement_timeout='5s'")}
                val before=snapshot(db,lock=true)
                if(before.revision!=expectedRevision) {db.rollback();return@use FacilityDefinitionOutcome.CONFLICT}
                val after=before.applied(edit) ?: run {db.rollback();return@use FacilityDefinitionOutcome.INVALID}
                if(after===before) {db.rollback();return@use FacilityDefinitionOutcome.UNCHANGED}
                when(edit) {
                    is FacilityDefinitionEdit.Kind -> edit.value.let {k->update(db,"""INSERT INTO facility_kind (kind,display_name,purpose,usage) VALUES (?,?,?,?)
                        ON CONFLICT (kind) DO UPDATE SET display_name=EXCLUDED.display_name,purpose=EXCLUDED.purpose,usage=EXCLUDED.usage""",
                        listOf(k.kind,k.name,k.purpose,k.usage))}
                    is FacilityDefinitionEdit.Override -> edit.value.let {o->update(db,"""INSERT INTO facility_override (facility_id,display_name,purpose) VALUES (?,?,?)
                        ON CONFLICT (facility_id) DO UPDATE SET display_name=EXCLUDED.display_name,purpose=EXCLUDED.purpose""",
                        listOf(o.facilityId,o.name,o.purpose))}
                    is FacilityDefinitionEdit.ClearOverride -> update(db,"DELETE FROM facility_override WHERE facility_id=?",listOf(edit.facilityId))
                }
                update(db,"UPDATE facility_definition_revision SET revision=? WHERE singleton=TRUE",listOf(after.revision))
                db.commit();FacilityDefinitionOutcome.CHANGED
            } catch(failure:Exception) {
                rollback(db,failure)
                if(failure is IllegalArgumentException || failure is SQLException && failure.sqlState in setOf("23503","23505","23514"))FacilityDefinitionOutcome.INVALID
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
