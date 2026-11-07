package org.logh7.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.security.MessageDigest
import org.logh7.engine.*

/** Originals and every edit commit together with the public definitions revision. Author: Peppone Choi. */
class PostgresCountryImages(private val connect:()->Connection):CountryImageStore {
    private val columns="a.asset_key,a.asset_kind,v.revision,v.title,v.description,v.sha256,v.width,v.height"
    private fun info(row:ResultSet)=CountryImageInfo(row.getString(1),CountryImageKind.entries.single {it.key==row.getString(2)},row.getLong(3),
        row.getString(4),row.getString(5),row.getString(6),row.getInt(7),row.getInt(8))
    private fun <T> query(db:Connection,sql:String,vararg args:Any?,read:(ResultSet)->T):List<T> = db.prepareStatement(sql).use {q->
        q.queryTimeout=5;args.forEachIndexed {i,v->q.setObject(i+1,v)}
        q.executeQuery().use {rows->buildList {while(rows.next())add(read(rows))}}
    }
    private fun update(db:Connection,sql:String,vararg args:Any?)=db.prepareStatement(sql).use {q->
        q.queryTimeout=5;args.forEachIndexed {i,v->if(v is ByteArray)q.setBytes(i+1,v) else q.setObject(i+1,v)};q.executeUpdate()
    }
    override fun list():List<CountryImageInfo> = connect().use {db->query(db,"SELECT $columns FROM nation_image_current c JOIN nation_image_version v USING(asset_key,revision) JOIN nation_asset a USING(asset_key) ORDER BY a.asset_key",read=::info)}
    override fun history(key:String):List<CountryImageInfo> = connect().use {db->query(db,"SELECT $columns FROM nation_image_version v JOIN nation_asset a USING(asset_key) WHERE a.asset_key=? ORDER BY v.revision DESC LIMIT 100",key,read=::info)}
    private fun read(db:Connection,key:String,revision:Long?):CountryImageFile? {
        val sql=if(revision==null)"SELECT $columns,v.source_png,v.rgba32 FROM nation_image_current c JOIN nation_image_version v USING(asset_key,revision) JOIN nation_asset a USING(asset_key) WHERE a.asset_key=?"
            else "SELECT $columns,v.source_png,v.rgba32 FROM nation_image_version v JOIN nation_asset a USING(asset_key) WHERE a.asset_key=? AND v.revision=?"
        val args=if(revision==null)arrayOf<Any?>(key) else arrayOf<Any?>(key,revision)
        return query(db,sql,*args) {CountryImageFile(info(it),it.getBytes(9),it.getBytes(10))}.singleOrNull()
    }
    override fun read(key:String,revision:Long?):CountryImageFile?=connect().use {read(it,key,revision)}
    private fun transaction(operation:(Connection,Long)->CountryImageOutcome):CountryImageOutcome = connect().use {db->
        db.autoCommit=false
        try {
            db.createStatement().use {it.execute("SET LOCAL lock_timeout='2s'");it.execute("SET LOCAL statement_timeout='5s'")}
            val revision=query(db,"SELECT revision FROM nation_definition_revision WHERE singleton=TRUE FOR UPDATE") {it.getLong(1)}.single()
            val result=operation(db,revision)
            if(result==CountryImageOutcome.CHANGED) {
                check(update(db,"UPDATE nation_definition_revision SET revision=? WHERE singleton=TRUE",Math.addExact(revision,1))==1);db.commit()
            } else db.rollback()
            result
        } catch(failure:Exception) {
            try {db.rollback()}catch(closing:Exception){failure.addSuppressed(closing)}
            if(failure is IllegalArgumentException || failure is SQLException && failure.sqlState in setOf("23503","23505","23514"))CountryImageOutcome.INVALID else throw failure
        }
    }
    private fun write(db:Connection,key:String,kind:CountryImageKind,revision:Long,title:String,description:String,png:ByteArray,rgba:ByteArray,sha:String,width:Int,height:Int) {
        val pack="upload.$key"
        val manifest=MessageDigest.getInstance("SHA-256").digest("$key|$revision|$sha".toByteArray()).joinToString(""){"%02x".format(it)}
        update(db,"INSERT INTO nation_asset_pack(pack_key,version,manifest_sha256) VALUES(?,?,?)",pack,revision.toInt(),manifest)
        update(db,"""INSERT INTO nation_asset(asset_key,pack_key,pack_version,asset_kind,relative_path,sha256) VALUES(?,?,?,?,?,?)
            ON CONFLICT(asset_key) DO UPDATE SET pack_key=EXCLUDED.pack_key,pack_version=EXCLUDED.pack_version,relative_path=EXCLUDED.relative_path,sha256=EXCLUDED.sha256""",
            key,pack,revision.toInt(),kind.key,"uploaded/$sha.png",sha)
        update(db,"INSERT INTO nation_image_version(asset_key,revision,title,description,sha256,width,height,source_png,rgba32) VALUES(?,?,?,?,?,?,?,?,?)",
            key,revision,title,description,sha,width,height,png,rgba)
        update(db,"INSERT INTO nation_image_current(asset_key,revision) VALUES(?,?) ON CONFLICT(asset_key) DO UPDATE SET revision=EXCLUDED.revision",key,revision)
    }
    override fun save(key:String,kind:CountryImageKind,expectedRevision:Long,title:String,description:String,upload:CountryImageUpload?):CountryImageOutcome {
        if(!CountryImageStore.validKey(key) || !CountryImageStore.validText(title,description) || expectedRevision !in 0 until Int.MAX_VALUE.toLong())return CountryImageOutcome.INVALID
        return transaction {db,_->
            val old=read(db,key,null)
            when {
                (old?.info?.revision ?: 0L) != expectedRevision->CountryImageOutcome.CONFLICT
                old==null && (upload==null || query(db,"SELECT asset_key FROM nation_asset WHERE asset_key=?",key){it.getString(1)}.isNotEmpty())->CountryImageOutcome.INVALID
                old!=null && old.info.kind!=kind->CountryImageOutcome.INVALID
                old!=null && old.info.title==title && old.info.description==description && (upload==null || old.info.sha256==upload.sha256)->CountryImageOutcome.UNCHANGED
                else->{
                    write(db,key,kind,expectedRevision+1,title,description,upload?.png ?: checkNotNull(old).png,upload?.rgba ?: checkNotNull(old).rgba,
                        upload?.sha256 ?: checkNotNull(old).info.sha256,upload?.width ?: checkNotNull(old).info.width,upload?.height ?: checkNotNull(old).info.height)
                    CountryImageOutcome.CHANGED
                }
            }
        }
    }
    override fun restore(key:String,expectedRevision:Long,sourceRevision:Long):CountryImageOutcome {
        if(!CountryImageStore.validKey(key) || expectedRevision !in 1 until Int.MAX_VALUE.toLong() || sourceRevision<=0)return CountryImageOutcome.INVALID
        return transaction {db,_->
            val current=read(db,key,null);val source=read(db,key,sourceRevision)
            when {
                current==null || source==null->CountryImageOutcome.NOT_FOUND
                current.info.revision!=expectedRevision->CountryImageOutcome.CONFLICT
                current.info.title==source.info.title && current.info.description==source.info.description && current.info.sha256==source.info.sha256->CountryImageOutcome.UNCHANGED
                else->{write(db,key,current.info.kind,expectedRevision+1,source.info.title,source.info.description,source.png,source.rgba,source.info.sha256,source.info.width,source.info.height);CountryImageOutcome.CHANGED}
            }
        }
    }
    override fun bind(country:Int,expectedDefinitionsRevision:Long,slot:CountryImageKind,key:String?):CountryImageOutcome {
        if(country<=0 || expectedDefinitionsRevision<0 || key!=null && !CountryImageStore.validKey(key))return CountryImageOutcome.INVALID
        return transaction {db,revision->
            if(revision!=expectedDefinitionsRevision)return@transaction CountryImageOutcome.CONFLICT
            val source=query(db,"""SELECT p.native_key,p.emblem_asset,p.flag_asset,p.map_icon_asset,c.asset_profile FROM nation_country c
                JOIN nation_asset_profile p ON c.asset_profile=p.profile_key WHERE c.country_id=?""",country) {
                Array(5){i->it.getString(i+1)}}.singleOrNull() ?: return@transaction CountryImageOutcome.NOT_FOUND
            val index=when(slot){CountryImageKind.EMBLEM->1;CountryImageKind.FLAG->2;CountryImageKind.MAP_ICON->3}
            if(source[index]==key)return@transaction CountryImageOutcome.UNCHANGED
            if(key!=null && read(db,key,null)?.info?.kind!=slot)return@transaction CountryImageOutcome.INVALID
            source[index]=key
            if(source.take(4).all {it==null})source[0]="native/default"
            val prefix="uploaded_country_${country}_"
            val shared=query(db,"SELECT country_id FROM nation_country WHERE asset_profile=? AND country_id<>? LIMIT 1",source[4],country){it.getLong(1)}.isNotEmpty()
            val profile=if(source[4].startsWith(prefix) && !shared)source[4] else prefix+java.util.UUID.randomUUID().toString().replace("-","").take(12)
            update(db,"""INSERT INTO nation_asset_profile(profile_key,native_key,emblem_asset,flag_asset,map_icon_asset) VALUES(?,?,?,?,?)
                ON CONFLICT(profile_key) DO UPDATE SET native_key=EXCLUDED.native_key,emblem_asset=EXCLUDED.emblem_asset,flag_asset=EXCLUDED.flag_asset,map_icon_asset=EXCLUDED.map_icon_asset""",
                profile,*source.take(4).toTypedArray())
            check(update(db,"UPDATE nation_country SET asset_profile=? WHERE country_id=?",profile,country)==1)
            CountryImageOutcome.CHANGED
        }
    }
    override fun snapshot():CountryImageSnapshot = connect().use {db->
        db.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ;db.isReadOnly=true;db.autoCommit=false
        try {
            val revision=query(db,"SELECT revision FROM nation_definition_revision WHERE singleton=TRUE"){it.getLong(1)}.single()
            val countries=query(db,"""SELECT c.country_id,p.native_key,e.rgba32,m.rgba32,p.emblem_asset,p.flag_asset,p.map_icon_asset FROM nation_country c
                JOIN nation_asset_profile p ON c.asset_profile=p.profile_key
                LEFT JOIN nation_image_current ec ON ec.asset_key=p.emblem_asset LEFT JOIN nation_image_version e ON (e.asset_key,e.revision)=(ec.asset_key,ec.revision)
                LEFT JOIN nation_image_current mc ON mc.asset_key=p.map_icon_asset LEFT JOIN nation_image_version m ON (m.asset_key,m.revision)=(mc.asset_key,mc.revision)
                ORDER BY c.country_id LIMIT 351""") {
                val id=it.getLong(1);require(id in 1..Int.MAX_VALUE)
                id.toInt() to CountryImageSelection(when(it.getString(2)){"native/empire","empire"->2;"native/alliance","alliance"->3;else->0},it.getBytes(3),it.getBytes(4),it.getString(5),it.getString(6),it.getString(7))
            }
            CountryImageSnapshot(revision,countries.toMap()).also {require(countries.size<=350);db.commit()}
        } catch(failure:Exception){try {db.rollback()}catch(closing:Exception){failure.addSuppressed(closing)};throw failure}
    }
}
