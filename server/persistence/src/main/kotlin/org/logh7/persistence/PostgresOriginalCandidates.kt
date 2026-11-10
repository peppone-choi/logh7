package org.logh7.persistence

import org.logh7.engine.OriginalCandidateEdit
import org.logh7.engine.OriginalCandidateProfile
import org.logh7.engine.OriginalCandidateStore
import org.logh7.engine.OriginalStartingOffice
import org.logh7.engine.CandidatePortrait
import org.logh7.engine.CountryId
import org.logh7.engine.Peerage
import org.logh7.engine.PublishedOriginalCandidates
import java.sql.Connection
import java.sql.Types
import java.util.UUID

class PostgresOriginalCandidates(private val connect:()->Connection,private val world:UUID):OriginalCandidateStore {
    @Volatile override var supportsEditing=false
        private set
    @Volatile override var supportsFemaleFaces=false
        private set
    @Volatile override var supportsPortraits=false
        private set
    @Volatile override var supportsStartingOffice=false
        private set
    @Volatile override var supportsAllianceStartingOffice=false
        private set
    @Volatile override var supportsStadenStartingOffice=false
        private set
    @Volatile override var supportsPaettaStartingOffice=false
        private set
    @Volatile override var supportsFogelStartingOffice=false
        private set

    @Volatile override var supportsMerkatzStartingOffice=false
        private set

    override fun load():List<OriginalCandidateEdit> = connect().use { db ->
        db.prepareStatement("""
            SELECT bool_or(conname='original_candidate_profile_native_face_check'),
                bool_or(conname='original_candidate_profile_starting_offices_check'),
                bool_or(conname='original_candidate_profile_staden_starting_office_check'),
                bool_or(conname='original_candidate_profile_paetta_starting_office_check'),
                bool_or(conname='original_candidate_profile_fogel_starting_office_check'),
                bool_or(conname='original_candidate_profile_merkatz_starting_office_check')
            FROM pg_constraint
            WHERE conrelid=to_regclass('original_candidate_profile') AND convalidated
        """).use { query ->
            query.queryTimeout=5
            query.executeQuery().use { rows ->
                rows.next()
                supportsFemaleFaces=rows.getBoolean(1)
                supportsMerkatzStartingOffice=rows.getBoolean(6)
                supportsFogelStartingOffice=supportsMerkatzStartingOffice || rows.getBoolean(5)
                supportsPaettaStartingOffice=supportsFogelStartingOffice || rows.getBoolean(4)
                supportsStadenStartingOffice=supportsPaettaStartingOffice || rows.getBoolean(3)
                supportsAllianceStartingOffice=supportsStadenStartingOffice || rows.getBoolean(2)
            }
        }
        db.prepareStatement("SELECT * FROM original_candidate_profile WHERE world_identity=? ORDER BY candidate_id").use { query ->
            query.queryTimeout=5
            query.setObject(1,world)
            query.executeQuery().use { rows -> buildList {
                val columns=rows.metaData
                val labels=(1..columns.columnCount).map(columns::getColumnLabel).toSet()
                val scoped="country_id" in labels
                val peerageColumn="peerage" in labels
                val portraitColumn="portrait_asset" in labels
                val officeColumn="starting_office" in labels
                while(rows.next()) {
                    val values=rows.getArray("abilities")
                    val abilities=try {(values.array as Array<*>).map {(it as Number).toInt()}} finally {values.free()}
                    val country=if(scoped)rows.getInt("country_id").takeUnless {rows.wasNull()}?.let(::CountryId) else null
                    val peerage=if(peerageColumn)rows.getInt("peerage").takeUnless {rows.wasNull()}?.let {Peerage.entries[it-1]} else null
                    val office=if(officeColumn)rows.getString("starting_office")?.let {key->
                        checkNotNull(OriginalStartingOffice.fromKey(key))
                    } else null
                    add(OriginalCandidateEdit(rows.getLong("candidate_id"),rows.getLong("revision"),
                        OriginalCandidateProfile(rows.getString("surname"),rows.getString("given_name"),
                            rows.getInt("face"),rows.getInt("rank"),abilities,peerage,
                            if(portraitColumn)rows.getString("portrait_asset") else null,startingOffice=office),country))
                }
                supportsEditing=peerageColumn
                supportsPortraits=portraitColumn
                supportsStartingOffice=officeColumn
            } }
        }
    }

    override fun loadPortraits():Map<String,CandidatePortrait> {
        if(!supportsPortraits)return emptyMap()
        return connect().use {db->
            db.prepareStatement("""
                SELECT c.asset_key,c.revision,v.rgba32 FROM (
                    SELECT DISTINCT portrait_asset FROM original_candidate_profile
                    WHERE world_identity=? AND portrait_asset IS NOT NULL
                ) bindings JOIN nation_image_current c ON c.asset_key=bindings.portrait_asset
                JOIN nation_image_version v USING(asset_key,revision)
                JOIN nation_asset a USING(asset_key) WHERE a.asset_kind='portrait'
            """).use {query->
                query.queryTimeout=5
                query.setObject(1,world)
                query.executeQuery().use {rows->buildMap {
                    while(rows.next())put(rows.getString(1),CandidatePortrait(rows.getLong(2),rows.getBytes(3)))
                }}
            }
        }
    }

    override fun save(id:Long,expected:Long,profile:OriginalCandidateProfile,country:CountryId?):OriginalCandidateEdit? {
        require(id in 1..9999 && expected>=0)
        val portraitColumn=supportsPortraits
        val portrait=profile.portraitKey
        val officeColumn=supportsStartingOffice
        val office=profile.startingOffice
        require(portrait==null || portraitColumn)
        require(office==null || officeColumn && office.accepts(id,country,profile.rank))
        require(office!=OriginalStartingOffice.VUCOCK_FLEET_5 || supportsAllianceStartingOffice)
        require(office!=OriginalStartingOffice.STADEN_FLEET_4 || supportsStadenStartingOffice)
        require(office!=OriginalStartingOffice.PAETTA_FLEET_2 || supportsPaettaStartingOffice)
        require(office!=OriginalStartingOffice.FOGEL_FLEET_5 || supportsFogelStartingOffice)
        require(office!=OriginalStartingOffice.MERKATZ_FLEET_3 || supportsMerkatzStartingOffice)
        val sql=if(expected==0L)"""INSERT INTO original_candidate_profile
            (surname,given_name,face,rank,abilities,country_id,peerage${if(portraitColumn)",portrait_asset" else ""}
                ${if(officeColumn)",starting_office" else ""},world_identity,candidate_id,revision)
            VALUES (?,?,?,?,?,?,?${if(portraitColumn)",?" else ""}${if(officeColumn)",?" else ""},?,?,1)
            ON CONFLICT (world_identity,candidate_id) DO NOTHING RETURNING revision"""
        else """UPDATE original_candidate_profile SET surname=?,given_name=?,face=?,rank=?,abilities=?,country_id=?,peerage=?
            ${if(portraitColumn)",portrait_asset=?" else ""}${if(officeColumn)",starting_office=?" else ""},revision=revision+1
            WHERE world_identity=? AND candidate_id=? AND revision=?
            ${if(officeColumn)"AND (starting_office IS NULL OR starting_office=?)" else ""} RETURNING revision"""
        return connect().use { db ->
            db.autoCommit=false
            try {
                if(portrait!=null)db.prepareStatement("""
                    SELECT 1 FROM nation_image_current JOIN nation_asset USING(asset_key)
                    WHERE asset_key=? AND asset_kind='portrait'
                """).use { query ->
                    query.queryTimeout=5
                    query.setString(1,portrait)
                    query.executeQuery().use {require(it.next()) {"Unknown portrait asset"}}
                }
                if(country!=null)lockWorld(db)
                if((country!=null && countryIsFull(db,country,id)) || (office!=null && officeIsTaken(db,office,id))) {
                    db.rollback()
                    return null
                }
                val saved=db.prepareStatement(sql).use { query ->
                    query.queryTimeout=5
                    query.setString(1,profile.surname)
                    query.setString(2,profile.givenName)
                    query.setInt(3,profile.face)
                    query.setInt(4,profile.rank)
                    val abilities=db.createArrayOf("smallint",profile.abilities.toTypedArray())
                    try {
                        query.setArray(5,abilities)
                        if(country==null)query.setNull(6,Types.BIGINT) else query.setLong(6,country.value.toLong())
                        val peerage=profile.peerage
                        if(peerage==null)query.setNull(7,Types.SMALLINT) else query.setInt(7,peerage.ordinal+1)
                        var index=8
                        if(portraitColumn)query.setString(index++,portrait)
                        if(officeColumn)query.setString(index++,office?.key)
                        query.setObject(index++,world)
                        query.setInt(index++,id.toInt())
                        if(expected>0) {
                            query.setLong(index++,expected)
                            if(officeColumn)query.setString(index,office?.key)
                        }
                        query.executeQuery().use {rows ->
                            if(rows.next())OriginalCandidateEdit(id,rows.getLong(1),profile,country) else null
                        }
                    } finally {abilities.free()}
                }
                db.commit()
                saved
            } catch(failure:Exception) {
                try {db.rollback()} catch(rollback:Exception){failure.addSuppressed(rollback)}
                throw failure
            }
        }
    }

    private fun lockWorld(db:Connection) {
        // Concurrent additions and transfers must count rows after earlier writes in this world commit.
        db.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
            it.queryTimeout=5
            it.setLong(1,world.mostSignificantBits xor world.leastSignificantBits)
            it.execute()
        }
    }

    private fun countryIsFull(db:Connection,country:CountryId,id:Long):Boolean =
        db.prepareStatement("SELECT count(*) FROM original_candidate_profile WHERE world_identity=? AND country_id=? AND candidate_id<>?").use {
            it.queryTimeout=5
            it.setObject(1,world)
            it.setLong(2,country.value.toLong())
            it.setLong(3,id)
            it.executeQuery().use {rows -> rows.next();rows.getInt(1)>=PublishedOriginalCandidates.SCOPED_LIMIT}
        }

    private fun officeIsTaken(db:Connection,office:OriginalStartingOffice,id:Long):Boolean =
        db.prepareStatement("SELECT 1 FROM original_candidate_profile WHERE world_identity=? AND starting_office=? AND candidate_id<>?").use {
            it.queryTimeout=5
            it.setObject(1,world)
            it.setString(2,office.key)
            it.setLong(3,id)
            it.executeQuery().use {rows -> rows.next()}
        }
}
