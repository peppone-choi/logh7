package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.logh7.engine.*

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
    /** Returns the fame added per account, empty when this generation was already settled. */
    @Synchronized fun settle(snapshot:WorldSnapshot,characters:CharacterCreation):Map<String,Int> {
        val result=snapshot.result ?: return emptyMap()
        val generation=snapshot.session?.generation ?: return emptyMap()
        val stocks=snapshot.planetStocks ?: return emptyMap()
        val properties=load()
        if(properties.getProperty("@settled/$generation")!=null)return emptyMap()
        val winners=winners(result)
        val added=linkedMapOf<String,Int>()
        for(c in characters.registeredCharacters()) {
            val account=characters.accountByCharacter(c.id.toInt()) ?: continue
            val person=stocks.strategic.people[c.id.toInt()]
            val rank=person?.rank ?: c.rank
            val dead=(person?.deceasedAt ?: 0)>0
            val power=if(c.power==2)Power.EMPIRE else Power.ALLIANCE
            val bonus=if(!dead && power in winners || dead && rank in 2..7)reward(rank) else 0
            val gained=((person?.evaluation ?: 0)+bonus)/POINTS_PER_FAME
            if(gained<=0)continue
            added[account]=Math.toIntExact(gained)
            properties.setProperty(account,(properties.getProperty(account)?.toInt() ?: 0).plus(gained.toInt()).toString())
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
