package org.logh7.protocol

import java.nio.ByteBuffer

/** Native TransactionSimpleDataBegin (0055b3a0) and card list (0055f670). */
object AppointmentDirectoryMessages {
    data class Query(val character:Int,val search:Int,val flags:Int,val role:Int,val base:Int,val raw:ByteArray)
    fun query(message:ByteArray):Query {
        require(message.size==31)
        val b=ByteBuffer.wrap(message);require(b.short.toInt()==0x1200)
        val character=b.int;val search=b.short.toInt() and 65535;val flags=b.short.toInt() and 65535
        val role=b.short.toInt() and 65535
        val base=b.getInt(25)
        require(character>0 && when(search) {
            18 -> flags==123 && message.drop(10).all { it==0.toByte() }
            // 命令/提案 tab target directory (CD p.28; first seen 2026-11-01 with flags 0x0984).
            29 -> role==0 && message.drop(12).all { it==0.toByte() }
            // New-mail recipient picker's office tab: flags and role carry stale values (run-343 0/175,
            // run-71 0x0646/51), everything after them is 0.
            15 -> message.drop(12).all { it==0.toByte() }
            28 -> OfficeCardKinds.isOffice(role) && !OfficeCardKinds.isOutfit(role)
            27 -> flags==80 && OfficeCardKinds.isOutfit(role)
            4,5 -> flags==80 && OfficeCardKinds.isOffice(role)
            else -> false
        }) {"Unsupported directory search: ${message.joinToString("") {"%02x".format(it)}}"}
        return Query(character,search,flags,role,base,message.copyOfRange(2,message.size))
    }
    private fun transaction(query:Query,opcode:Int,body:ByteArray)=listOf(
        LoginMessages.sessionMessage(0x1200,query.raw),LoginMessages.sessionMessage(opcode,body),
        LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    private fun java.io.DataOutputStream.name(value:String) {
        require(value.length<=12 && '\u0000' !in value)
        writeByte(value.length+1);(value+'\u0000').forEach { writeChar(it.code) }
    }
    data class Base(val id:Int,val grid:Int,val name:String)
    fun bases(query:Query,values:List<Base>):List<ByteArray> {
        require(values.size<=300)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeShort(values.size)
            for(base in values) {
                require(base.id in 1..65535 && base.grid in 0..4999)
                out.writeByte(1);out.writeInt(base.id);out.writeShort(base.grid);out.writeShort(base.id);out.name(base.name)
            }
        }
        return transaction(query,0x120d,bytes.toByteArray())
    }
    data class Person(val character:CharacterMessages.Generate,val cards:List<Int>,val prospectiveOffices:Int?=null)
    data class Outfit(val id:Int,val number:Int,val kind:Int,val base:WorldMessages.Base)
    /** 00562890: byte count; availability flag, outfit identity, number/kind, optional base. */
    fun outfits(query:Query,values:List<Outfit>):List<ByteArray> {
        require(query.search==27 && values.size<=180 && values.distinctBy {it.id}.size==values.size)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeByte(values.size)
            for(value in values) {
                require(value.id>0 && value.number in 0..255 && value.kind in 0..3)
                out.writeByte(1);out.writeInt(value.id);out.writeByte(value.number);out.writeByte(value.kind)
                out.write(WorldMessages.simpleBases(listOf(value.base)))
            }
        }
        return transaction(query,0x120c,bytes.toByteArray())
    }
    private fun java.io.DataOutputStream.person(value:Person) {
        val person=value.character
        require(value.cards.size<=16 && value.cards.all { it in 0..260 })
        writeInt(person.id.toInt());name(person.surname)
        // 0055ba80 stores the first u16 at record+0x20; 0057a1f0 reads it as rank.
        require(value.prospectiveOffices == null || value.prospectiveOffices in 1..15)
        // 0055bbe8 reads the second u16 into the adjacent word. Only appointment candidates carry the extension.
        writeShort(person.rank);writeShort(value.prospectiveOffices?.let {0x4c00 or it} ?: 0)
        writeByte(value.cards.size);value.cards.forEach(::writeShort)
        writeByte(0);writeByte(0) // Detailed unit/base locations are omitted from this personnel directory.
        val age=person.age.toLong()*CharacterMessages.AGE_SECONDS_PER_YEAR
        require(age in 0..0xffffffffL)
        writeInt(person.face);writeInt(age.toInt())
    }
    fun people(query:Query,values:List<Person>):List<ByteArray> {
        require(values.size<=200)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out -> out.writeByte(values.size);values.forEach {out.person(it)} }
        return transaction(query,0x1202,bytes.toByteArray())
    }
    /** One 命令/提案 tab group: own card, then targets with type 0 order / 1 proposal (evidence:client, 00563c20). */
    data class OrderSuggest(val card:Int,val targets:List<Pair<Int,Person>>)
    /** 0x120e NotifySimpleInformationOrderSuggestCharacter: at most ten cards of ten people each. */
    fun orderSuggest(query:Query,values:List<OrderSuggest>):List<ByteArray> {
        require(query.search in setOf(15,29) && values.size<=10 && values.all {it.targets.size<=10 && it.card in 0..260 &&
            it.targets.all {(type,_)->type in 0..1}})
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeByte(values.size)
            for(value in values) {
                out.writeShort(value.card);out.writeByte(value.targets.size)
                for((type,person) in value.targets) {out.writeByte(type);out.person(person)}
            }
        }
        return transaction(query,0x120e,bytes.toByteArray())
    }
    fun cards(query:Query,remaining:Int):List<ByteArray> {
        require(remaining in 0..255)
        return cards(query,if(remaining==0)emptyMap() else mapOf(55 to remaining))
    }
    fun cards(query:Query,vacancies:Map<Int,Int>):List<ByteArray> {
        require(vacancies.size<=300 && vacancies.all { (card,n)->OfficeCardKinds.isOffice(card) && n in 1..255 })
        val b=ByteBuffer.allocate(2+vacancies.size*7).putShort(vacancies.size.toShort())
        vacancies.toSortedMap().forEach { (card,n)->b.putShort(card.toShort()).putInt(0).put(n.toByte()) }
        return transaction(query,0x1208,b.array())
    }
}
