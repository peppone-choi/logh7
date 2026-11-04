package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.SessionEndingMessages

/** U-27 fame (W p.34; amounts are the user-approved 2026-10-31 proposal, evidence:guess).
 * When a session result first appears, every registered character converts its evaluation points
 * plus a rank reward into fame at 100 points per fame point, added to its account once per
 * generation. The reward goes to living characters of a winning side (marshal 1000 down to
 * private 50) and, regardless of the outcome, to commodores and above who died in battle.
 * The next generation's character lottery reads the ledger. Author: Peppone Choi. */
class FameLedger(private val file:Path?) {
    companion object {
        const val POINTS_PER_FAME=100L
        /** Rank 2 (marshal) 1000 .. rank 20 (private) 50, linear; politicians (1) take the private value. */
        fun reward(rank:Int):Long=if(rank !in 2..20)50 else 1000L-Math.round((rank-2)*950.0/18)
        fun winners(result:SessionResult):Set<Power> =
            result.powers.filterValues {it.grade in setOf(SessionGrade.DECISIVE,SessionGrade.LIMITED,SessionGrade.LOCAL)}.keys
    }
    private var memory=Properties()
    @Synchronized private fun load():Properties=Properties().also {p->
        if(file==null){p.putAll(memory);return@also}
        if(Files.exists(file))Files.newBufferedReader(file).use {p.load(it)}
    }
    @Synchronized fun fame():Map<String,Int> = load().let {p->
        p.stringPropertyNames().filter {!it.startsWith("@")}.associateWith {p.getProperty(it).toInt()}}
    private fun points(snapshot:WorldSnapshot,c:CharacterMessages.Generate):Pair<Long,Long> {
        val person=checkNotNull(snapshot.planetStocks).strategic.people[c.id.toInt()]
        val rank=person?.rank ?: c.rank
        val dead=(person?.deceasedAt ?: 0)>0
        val power=if(c.power==2)Power.EMPIRE else Power.ALLIANCE
        val bonus=if(!dead && power in winners(checkNotNull(snapshot.result)) || dead && rank in 2..7)reward(rank) else 0
        val before=person?.evaluation ?: 0
        return before to Math.addExact(before,bonus)
    }
    /** A read never grants fame; the result observer must have durably settled this generation. */
    @Synchronized fun evaluation(snapshot:WorldSnapshot,characters:CharacterCreation,account:String):SessionEndingMessages.Evaluation? {
        val generation=snapshot.session?.generation ?: return null
        val p=load();if(p.getProperty("@settled/$generation")==null)return null
        val saved=p.getProperty("@evaluation/$generation/$account")
        val values=if(saved!=null)saved.split(',').map(String::toLong) else {
            // shortcut: Old ledgers lack snapshots. Derive only the latest settlement under the unchanged reward policy;
            // migrate these old records before changing that policy.
            if(p.stringPropertyNames().any {it.startsWith("@settled/") && it.substringAfterLast('/').toLong()>generation})return null
            if(snapshot.result==null || snapshot.planetStocks==null)return null
            val c=characters.character(account) ?: return null
            val (before,after)=points(snapshot,c)
            val finalFame=p.getProperty(account,"0").toLong();val gained=after/POINTS_PER_FAME
            check(finalFame>=gained) {"Fame ledger is inconsistent with the settled result"}
            listOf(before,after,finalFame-gained,finalFame)
        }
        require(values.size==4 && values.all {it>=0} && values[1]>=values[0] && values[3]>=values[2])
        return SessionEndingMessages.Evaluation(values[0],values[1],values[2],values[3],0)
    }
    /** Returns the fame added per account, empty when this generation was already settled. */
    @Synchronized fun settle(snapshot:WorldSnapshot,characters:CharacterCreation):Map<String,Int> {
        if(snapshot.result==null || snapshot.planetStocks==null)return emptyMap()
        val generation=snapshot.session?.generation ?: return emptyMap()
        val properties=load()
        if(properties.getProperty("@settled/$generation")!=null)return emptyMap()
        val added=linkedMapOf<String,Int>()
        for(c in characters.registeredCharacters()) {
            val account=characters.accountByCharacter(c.id.toInt()) ?: continue
            val (before,after)=points(snapshot,c);val gained=Math.toIntExact(after/POINTS_PER_FAME)
            val previousFame=properties.getProperty(account,"0").toInt()
            val finalFame=Math.addExact(previousFame,gained)
            if(gained>0) {
                added[account]=gained;properties.setProperty(account,finalFame.toString())
            }
            properties.setProperty("@evaluation/$generation/$account","$before,$after,$previousFame,$finalFame")
        }
        properties.setProperty("@settled/$generation","1")
        save(properties)
        return added
    }
    private fun save(p:Properties) {
        if(file==null){memory=p;return}
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary=Files.createTempFile(file.toAbsolutePath().parent,"fame-",".tmp")
        try {
            Files.newBufferedWriter(temporary).use {p.store(it,"LOGH7 account fame")}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {Files.deleteIfExists(temporary)}
    }
}
