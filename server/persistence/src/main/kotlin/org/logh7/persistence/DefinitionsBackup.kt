package org.logh7.persistence

import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import org.postgresql.PGConnection

/**
 * Writes the whole definitions database (countries V1–V3, facilities V4 and anything later in the same
 * schema) as one psql script from a single REPEATABLE READ snapshot, so country and facility rows are
 * from the same moment. Restore into a database that already has the V1..Vn schema:
 * `psql -v ON_ERROR_STOP=1 -1 -f definitions.sql` (it truncates every table, then COPYs parents before
 * children and resets sequences). Author: Peppone Choi.
 */
object DefinitionsBackup {
    const val FILE="definitions.sql"
    private const val HEADER="-- LOGH7-DEFINITIONS-BACKUP-1"

    fun write(connect:()->Connection,target:Path) {
        connect().use {db->
            db.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ;db.isReadOnly=true;db.autoCommit=false
            try {
                db.createStatement().use {it.execute("SET LOCAL statement_timeout='10s'")}
                val tables=ordered(db)
                Files.createDirectories(target.parent)
                Files.newBufferedWriter(target).use {out->script(db,tables,out)}
                db.commit()
            } catch(failure:Exception) {
                try {db.rollback()} catch(rollback:Exception) {failure.addSuppressed(rollback)}
                throw failure
            }
        }
    }

    private fun quote(name:String)="\""+name.replace("\"","\"\"")+"\""

    /** Base tables of the current schema, parents (referenced tables) before children. */
    private fun ordered(db:Connection):List<String> {
        val tables=db.prepareStatement("SELECT c.relname FROM pg_class c WHERE c.relkind IN ('r','p') AND c.relnamespace=to_regnamespace(current_schema()) ORDER BY 1")
            .use {q->q.executeQuery().use {r->buildList {while(r.next())add(r.getString(1))}}}
        require(tables.isNotEmpty()) {"Definitions database has no tables"}
        val parents=tables.associateWith {mutableSetOf<String>()}
        db.prepareStatement("""SELECT child.relname,parent.relname FROM pg_constraint k JOIN pg_class child ON child.oid=k.conrelid
            JOIN pg_class parent ON parent.oid=k.confrelid WHERE k.contype='f' AND child.relnamespace=to_regnamespace(current_schema())""").use {q->
            q.executeQuery().use {r->while(r.next()) {val child=r.getString(1);val parent=r.getString(2)
                if(child!=parent && parent in parents)parents.getValue(child).add(parent)}}}
        val order=mutableListOf<String>();val done=mutableSetOf<String>()
        while(order.size<tables.size) {
            val ready=tables.filter {it !in done && parents.getValue(it).all(done::contains)}
            require(ready.isNotEmpty()) {"Definitions tables have a foreign-key cycle"}
            order+=ready;done+=ready
        }
        return order
    }

    private fun script(db:Connection,tables:List<String>,out:Writer) {
        out.write("$HEADER\n-- restore: psql -v ON_ERROR_STOP=1 -1 -f $FILE (into a database with the same V1..Vn schema)\n")
        out.write("SET client_encoding='UTF8';\n")
        out.write("TRUNCATE "+tables.joinToString(", ",transform=::quote)+";\n")
        val copy=db.unwrap(PGConnection::class.java).copyAPI
        for(table in tables) {
            val columns=db.prepareStatement("SELECT attname FROM pg_attribute WHERE attrelid=to_regclass(quote_ident(current_schema())||'.'||quote_ident(?)) AND attnum>0 AND NOT attisdropped AND attgenerated='' ORDER BY attnum")
                .use {q->q.setString(1,table);q.executeQuery().use {r->buildList {while(r.next())add(r.getString(1))}}}
            val list=columns.joinToString(", ",transform=::quote)
            out.write("COPY ${quote(table)} ($list) FROM stdin;\n")
            out.flush()
            copy.copyOut("COPY ${quote(table)} ($list) TO STDOUT",out)
            out.write("\\.\n")
        }
        db.prepareStatement("""SELECT s.relname,pg_sequence_last_value(s.oid),seq.seqstart FROM pg_class s JOIN pg_sequence seq ON seq.seqrelid=s.oid
            WHERE s.relkind='S' AND s.relnamespace=to_regnamespace(current_schema()) ORDER BY 1""").use {q->q.executeQuery().use {r->
            while(r.next()) {
                val last=r.getObject(2) as Long?
                out.write("SELECT setval('${quote(r.getString(1)).replace("'","''")}', ${last ?: r.getLong(3)}, ${last!=null});\n")
            }
        }}
        out.write("-- end\n")
    }
}
