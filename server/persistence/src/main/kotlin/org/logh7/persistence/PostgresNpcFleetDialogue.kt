package org.logh7.persistence

import org.logh7.engine.NpcFleetDialogue
import org.logh7.engine.NpcFleetDialogueRow
import org.logh7.engine.NpcFleetDialogueStore
import org.logh7.engine.NpcCombatBehavior
import java.sql.Connection
import java.util.UUID

class PostgresNpcFleetDialogue(private val connect:()->Connection,private val world:UUID):NpcFleetDialogueStore {
    override fun load(generation:Long):List<NpcFleetDialogueRow> = connect().use { db ->
        db.prepareStatement("SELECT outfit_id,revision,fallback,style,prompt,behavior FROM npc_fleet_dialogue WHERE world_identity=? AND generation=?").use { query ->
            query.queryTimeout=5
            query.setObject(1,world); query.setLong(2,generation)
            query.executeQuery().use { rows -> buildList {
                while(rows.next())add(NpcFleetDialogueRow(generation,rows.getInt(1),rows.getLong(2),
                    NpcFleetDialogue(rows.getString(3),rows.getString(4),rows.getString(5)),
                    requireNotNull(NpcCombatBehavior.fromKey(rows.getString(6)))))
            } }
        }
    }

    override fun save(generation:Long,outfit:Int,expected:Long,dialogue:NpcFleetDialogue,behavior:NpcCombatBehavior):NpcFleetDialogueRow? {
        require(generation>0 && outfit>0 && expected>=0)
        val sql=if(expected==0L)"""INSERT INTO npc_fleet_dialogue (fallback,style,prompt,behavior,world_identity,generation,outfit_id,revision)
            VALUES (?,?,?,?,?,?,?,1) ON CONFLICT (world_identity,generation,outfit_id) DO NOTHING RETURNING revision"""
        else """UPDATE npc_fleet_dialogue SET fallback=?,style=?,prompt=?,behavior=?,revision=revision+1
            WHERE world_identity=? AND generation=? AND outfit_id=? AND revision=? RETURNING revision"""
        return connect().use { db -> db.prepareStatement(sql).use { query ->
            query.queryTimeout=5
            query.setString(1,dialogue.fallback);query.setString(2,dialogue.style);query.setString(3,dialogue.prompt)
            query.setString(4,behavior.key);query.setObject(5,world);query.setLong(6,generation);query.setInt(7,outfit)
            if(expected>0)query.setLong(8,expected)
            query.executeQuery().use { rows ->
                if(rows.next())NpcFleetDialogueRow(generation,outfit,rows.getLong(1),dialogue,behavior) else null
            }
        } }
    }
}
