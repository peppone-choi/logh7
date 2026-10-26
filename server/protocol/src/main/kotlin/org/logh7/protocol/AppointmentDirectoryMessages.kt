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
            28 -> role in setOf(55,56,57,192,193)
            4 -> flags==80 && role in setOf(55,56,57,192,193)
            else -> false
        })
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
    data class Person(val character:CharacterMessages.Generate,val cards:List<Int>)
    fun people(query:Query,values:List<Person>):List<ByteArray> {
        require(values.size<=200)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeByte(values.size)
            for(value in values) {
                val person=value.character
                require(value.cards.size<=16 && value.cards.all { it in 0..260 })
                out.writeInt(person.id.toInt());out.name(person.surname)
                // 0055ba80 stores the first u16 at record+0x20; 0057a1f0 reads it as rank.
                out.writeShort(person.rank);out.writeShort(0)
                out.writeByte(value.cards.size);value.cards.forEach(out::writeShort)
                out.writeByte(0);out.writeByte(0) // Detailed unit/base locations are omitted from this personnel directory.
                val age=person.age.toLong()*CharacterMessages.AGE_SECONDS_PER_YEAR
                require(age in 0..0xffffffffL)
                out.writeInt(person.face);out.writeInt(age.toInt())
            }
        }
        return transaction(query,0x1202,bytes.toByteArray())
    }
    fun cards(query:Query,remaining:Int):List<ByteArray> {
        require(remaining in 0..255)
        return cards(query,if(remaining==0)emptyMap() else mapOf(55 to remaining))
    }
    fun cards(query:Query,vacancies:Map<Int,Int>):List<ByteArray> {
        require(vacancies.size<=5 && vacancies.all { (card,n)->card in setOf(55,56,57,192,193) && n in 1..255 })
        val b=ByteBuffer.allocate(2+vacancies.size*7).putShort(vacancies.size.toShort())
        vacancies.toSortedMap().forEach { (card,n)->b.putShort(card.toShort()).putInt(0).put(n.toByte()) }
        return transaction(query,0x1208,b.array())
    }
}
