package org.logh7.persistence

import org.logh7.engine.OriginalCandidateEdit
import org.logh7.engine.OriginalCandidateProfile
import org.logh7.engine.OriginalCandidateStore
import java.sql.Connection
import java.util.UUID

class PostgresOriginalCandidates(private val connect:()->Connection,private val world:UUID):OriginalCandidateStore {
    override fun load():List<OriginalCandidateEdit> = connect().use { db ->
        db.prepareStatement("SELECT candidate_id,revision,surname,given_name,face,rank,abilities FROM original_candidate_profile WHERE world_identity=?").use { query ->
            query.queryTimeout=5
            query.setObject(1,world)
            query.executeQuery().use { rows -> buildList {
                while(rows.next()) {
                    val values=rows.getArray(7)
                    val abilities=try {(values.array as Array<*>).map {(it as Number).toInt()}} finally {values.free()}
                    add(OriginalCandidateEdit(rows.getLong(1),rows.getLong(2),
                        OriginalCandidateProfile(rows.getString(3),rows.getString(4),rows.getInt(5),rows.getInt(6),abilities)))
                }
            } }
        }
    }

    override fun save(id:Long,expected:Long,profile:OriginalCandidateProfile):OriginalCandidateEdit? {
        require(id in 1..9999 && expected>=0)
        val sql=if(expected==0L)"""INSERT INTO original_candidate_profile
            (surname,given_name,face,rank,abilities,world_identity,candidate_id,revision)
            VALUES (?,?,?,?,?,?,?,1) ON CONFLICT (world_identity,candidate_id) DO NOTHING RETURNING revision"""
        else """UPDATE original_candidate_profile SET surname=?,given_name=?,face=?,rank=?,abilities=?,revision=revision+1
            WHERE world_identity=? AND candidate_id=? AND revision=? RETURNING revision"""
        return connect().use { db -> db.prepareStatement(sql).use { query ->
            query.queryTimeout=5
            query.setString(1,profile.surname)
            query.setString(2,profile.givenName)
            query.setInt(3,profile.face)
            query.setInt(4,profile.rank)
            val abilities=db.createArrayOf("smallint",profile.abilities.toTypedArray())
            try {
                query.setArray(5,abilities)
                query.setObject(6,world)
                query.setInt(7,id.toInt())
                if(expected>0)query.setLong(8,expected)
                query.executeQuery().use { rows -> if(rows.next())OriginalCandidateEdit(id,rows.getLong(1),profile) else null }
            } finally { abilities.free() }
        } }
    }
}
