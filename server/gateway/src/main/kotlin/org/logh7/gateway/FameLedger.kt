package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.logh7.engine.*
import org.logh7.protocol.SessionEndingMessages

/** U-27 fame (W p.34; amounts are the user-approved 2026-10-31 proposal, evidence:guess).
 * When a session result first appears, every registered character converts its evaluation points
 * plus a rank reward into fame at 100 points per fame point, added to its account once per
 * world/generation. The reward goes to living characters of a winning side (marshal 1000 down to
 * private 50) and, regardless of the outcome, to commodores and above who died in battle.
 * The next generation's character lottery reads the ledger. Author: Peppone Choi. */
class FameLedger private constructor(private val storage:Storage,private val world:String?):AutoCloseable {
    constructor(file:Path?):this(Storage(file),null)
    companion object {
        const val POINTS_PER_FAME=100L
        fun openShared(file:Path,forceDirectory:Boolean=true,requireExisting:Boolean=false):FameLedger {
            val storage=Storage(file,true,forceDirectory)
            try {
                storage.read {values->if(!Files.exists(file)) {
                    check(!requireExisting) {"Shared account fame is missing; restore it before starting existing worlds"}
                    storage.save(values)
                }}
                return FameLedger(storage,null)
            }catch(failure:Throwable){storage.close();throw failure}
        }
        /** Rank 2 (marshal) 1000 .. rank 20 (private) 50, linear; politicians (1) take the private value. */
        fun reward(rank:Int):Long=if(rank !in 2..20)50 else 1000L-Math.round((rank-2)*950.0/18)
        fun winners(result:SessionResult):Set<CountryId> =
            result.countries.filterValues {it.grade in setOf(SessionGrade.DECISIVE,SessionGrade.LIMITED,SessionGrade.LOCAL)}.keys
    }
    /** Views share one transaction lock and account totals; receipts belong to a durable world identity. */
    fun forWorld(identity:String):FameLedger {
        require(identity.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        return FameLedger(storage,identity)
    }
    private fun key(kind:String,generation:Long)=if(world==null)"@$kind/$generation" else "@$kind/$world/$generation"
    fun fame():Map<String,Int> = storage.read {p->
        p.stringPropertyNames().filter {!it.startsWith("@")}.associateWith {p.getProperty(it).toInt()}}
    val accounts:Map<String,Int> = object:AbstractMap<String,Int>() {
        override val entries get()=fame().entries
        override fun get(key:String):Int?=storage.read {it.getProperty(key)?.toInt()}
    }
    private fun points(snapshot:WorldSnapshot,profile:CharacterProfile):Pair<Long,Long> =
        points(checkNotNull(snapshot.planetStocks).strategic.people[profile.character.id.toInt()],
            profile.character.rank,profile.country in winners(checkNotNull(snapshot.result)))

    private fun points(person:StrategicPerson?,registeredRank:Int,won:Boolean):Pair<Long,Long> {
        val rank=person?.rank ?: registeredRank
        val dead=(person?.deceasedAt ?: 0)>0
        val bonus=if(!dead && won || dead && rank in 2..7)reward(rank) else 0
        val before=person?.evaluation ?: 0
        return before to Math.addExact(before,bonus)
    }
    /** A read never grants fame; the result observer must have durably settled this generation. */
    fun evaluation(snapshot:WorldSnapshot,characters:CharacterCreation,account:String):SessionEndingMessages.Evaluation? {
        val generation=snapshot.session?.generation ?: return null
        val p=storage.read {it}
        if(p.getProperty(key("settled",generation))==null)return null
        val saved=p.getProperty("${key("evaluation",generation)}/$account")
        val values=if(saved!=null)saved.split(',').map(String::toLong) else {
            // shortcut: Old ledgers lack snapshots. Derive only the latest settlement under the unchanged reward policy;
            // migrate these old records before changing that policy.
            val prefix=if(world==null)"@settled/" else "@settled/$world/"
            if(world==null && p.stringPropertyNames().any {it.startsWith(prefix) && (it.removePrefix(prefix).toLongOrNull() ?: 0)>generation})return null
            if(world!=null && p.getProperty("@legacy-latest/$world")!=generation.toString())return null
            if(snapshot.result==null || snapshot.planetStocks==null)return null
            val profile=WorldCharacters.profiles(characters.profiles(),snapshot)[account] ?: return null
            val (before,after)=points(snapshot,profile)
            val finalFame=p.getProperty(if(world==null)account else "@legacy-total/$world/$account","0").toLong();val gained=after/POINTS_PER_FAME
            check(finalFame>=gained) {"Fame ledger is inconsistent with the settled result"}
            listOf(before,after,finalFame-gained,finalFame)
        }
        require(values.size==4 && values.all {it>=0} && values[1]>=values[0] && values[3]>=values[2])
        // The ledger keeps the full sum used for fame; the ending packet has a u32 evaluation field.
        return SessionEndingMessages.Evaluation(values[0],minOf(values[1],0xffffffffL),values[2],values[3],0)
    }
    /** Returns the fame added per account, empty when this generation was already settled. */
    fun settle(snapshot:WorldSnapshot,characters:CharacterCreation):Map<String,Int> {
        if(snapshot.result==null)return emptyMap()
        val people=snapshot.planetStocks?.strategic?.people ?: return emptyMap()
        val generation=snapshot.session?.generation ?: return emptyMap()
        // Character menus read live fame while holding their own lock; never take that lock inside the bank lock.
        val (registered,casualties)=characters.fameParticipants()
        val profiles=WorldCharacters.profiles(registered,snapshot)
        val activeIds=profiles.values.map {it.character.id.toInt()}.toSet()
        val retiredPoints=casualties.entries.sortedWith(compareBy({people[it.key]?.deceasedAt ?: 0},{it.key}))
            .mapNotNull { (id,casualty) ->
                val person=people[id]?.takeIf {it.deceasedAt>0 && id !in activeIds} ?: return@mapNotNull null
                casualty.account to points(person,casualty.rank,won=false)
            }.groupBy({it.first},{it.second})
        return storage.read {properties->
        if(properties.getProperty(key("settled",generation))!=null)return@read emptyMap()
        val added=linkedMapOf<String,Int>()
        for(account in profiles.keys+retiredPoints.keys) {
            val active=profiles[account]?.let {points(snapshot,it)}
            val fallen=retiredPoints[account].orEmpty()
            val (before,after)=active ?: fallen.last()
            val gained=Math.toIntExact((active?.second ?: 0)/POINTS_PER_FAME+fallen.sumOf {it.second/POINTS_PER_FAME})
            val previousFame=properties.getProperty(account,"0").toInt()
            val finalFame=Math.addExact(previousFame,gained)
            if(gained>0) {
                added[account]=gained;properties.setProperty(account,finalFame.toString())
            }
            properties.setProperty("${key("evaluation",generation)}/$account","$before,$after,$previousFame,$finalFame")
        }
        properties.setProperty(key("settled",generation),"1")
        storage.save(properties)
        added
        }
    }
    /** Called only after this world's state writer locks have been acquired. The legacy source stays unchanged. */
    fun importLegacy(file:Path?) {
        val identity=checkNotNull(world)
        storage.read {target->
            if(target.getProperty("@imported/$identity")!=null)return@read
            val old=Properties()
            if(file!=null && Files.exists(file))Files.newBufferedReader(file).use {old.load(it)}
            var latest=0L
            for(name in old.stringPropertyNames()) {
                val value=old.getProperty(name)
                when {
                    !name.startsWith('@')-> {
                        require(FileAccounts.validName(name)) {"Invalid legacy fame account"}
                        val amount=value.toInt();require(amount>=0)
                        target.setProperty(name,Math.addExact(target.getProperty(name,"0").toInt(),amount).toString())
                        target.setProperty("@legacy-total/$identity/$name",value)
                    }
                    name.startsWith("@settled/")-> {
                        val generation=name.removePrefix("@settled/").toLong();require(generation>0 && value=="1")
                        latest=maxOf(latest,generation)
                        check(target.getProperty(key("settled",generation))==null) {"Legacy fame import conflicts with settled world history"}
                        target.setProperty(key("settled",generation),value)
                    }
                    name.startsWith("@evaluation/")-> {
                        val parts=name.removePrefix("@evaluation/").split('/');require(parts.size==2 && parts[0].toLong()>0 && FileAccounts.validName(parts[1]))
                        val values=value.split(',').map(String::toLong)
                        require(values.size==4 && values.all {it>=0} && values[1]>=values[0] && values[3]>=values[2])
                        check(target.getProperty("${key("evaluation",parts[0].toLong())}/${parts[1]}")==null)
                        target.setProperty("${key("evaluation",parts[0].toLong())}/${parts[1]}",value)
                    }
                    else->error("Unknown legacy fame metadata")
                }
            }
            target.setProperty("@legacy-latest/$identity",latest.toString())
            target.setProperty("@imported/$identity","1")
            storage.save(target)
        }
    }
    override fun close() {if(world==null)storage.close()}

    private class Storage(private val file:Path?,private val singleWriter:Boolean=false,private val forceDirectory:Boolean=false):AutoCloseable {
        private var memory=Properties()
        private var closed=false
        private var initialized=file?.let(Files::exists) ?: false
        private val channel:java.nio.channels.FileChannel?=if(!singleWriter)null else {
            val path=checkNotNull(file).toAbsolutePath();Files.createDirectories(path.parent)
            java.nio.channels.FileChannel.open(path.resolveSibling("${path.fileName}.lock"),java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE).also {opened->
                try {checkNotNull(opened.tryLock()) {"Account fame already has a writer"}}
                catch(failure:Throwable){opened.close();throw failure}
            }
        }
        @Synchronized fun <T> read(operation:(Properties)->T):T {
            check(!closed) {"Account fame store is closed"}
            check(!singleWriter || !initialized || Files.exists(checkNotNull(file))) {"Shared account fame disappeared; stop and restore it"}
            val values=Properties()
            if(file==null)values.putAll(memory) else if(Files.exists(file))Files.newBufferedReader(file).use {values.load(it)}
            if(singleWriter && file!=null && Files.exists(file))require(values.getProperty("@format")=="LOGH7-ACCOUNT-FAME-1") {
                "Invalid shared account fame format; preserve the file for recovery"}
            values.stringPropertyNames().filterNot {it.startsWith('@')}.forEach {name->
                require(FileAccounts.validName(name) && (values.getProperty(name).toIntOrNull() ?: -1)>=0) {"Invalid account fame total"}
            }
            return operation(values)
        }
        fun save(p:Properties) {
            if(singleWriter)p.setProperty("@format","LOGH7-ACCOUNT-FAME-1")
            if(file==null){memory=p;return}
            val parent=file.toAbsolutePath().parent;Files.createDirectories(parent)
            val temporary=Files.createTempFile(parent,"fame-",".tmp")
            try {
                java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE).use {output->
                    val writer=java.nio.channels.Channels.newWriter(output,Charsets.UTF_8)
                    p.store(writer,"LOGH7 account fame");writer.flush();output.force(true)
                }
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                initialized=true
                if(forceDirectory)java.nio.channels.FileChannel.open(parent,java.nio.file.StandardOpenOption.READ).use {it.force(true)}
            } finally {Files.deleteIfExists(temporary)}
        }
        @Synchronized override fun close() {if(!closed){closed=true;channel?.close()}}
    }
}
