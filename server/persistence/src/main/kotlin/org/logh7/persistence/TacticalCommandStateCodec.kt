package org.logh7.persistence

import java.io.*
import java.util.Base64
import org.logh7.engine.*

/** Bounded, canonical binary state inside STOCK-30. No Java object deserialization. Author: Peppone Choi. */
internal object TacticalCommandStateCodec {
    private fun DataOutputStream.ints(values:List<Int>) {writeInt(values.size);values.forEach(::writeInt)}
    private fun DataInputStream.count(max:Int)=readInt().also {require(it in 0..max)}
    private fun DataInputStream.ints(max:Int)=List(count(max)){readInt()}
    private fun DataOutputStream.command(c:TacticalCommand) {
        writeInt(c.opcode);ints(c.units);writeInt(c.base);writeInt(c.kind);writeInt(c.targetKind);writeInt(c.target)
        writeFloat(c.velocity);writeFloat(c.direction);writeInt(c.points.size)
        c.points.forEach {writeFloat(it.x);writeFloat(it.y)};ints(c.payload)
    }
    private fun DataInputStream.command()=TacticalCommand(readInt(),ints(32),readInt(),readInt(),readInt(),readInt(),readFloat(),readFloat(),
        List(count(32)){TacticalPoint(readFloat(),readFloat())},ints(800))
    private fun DataOutputStream.mission(m:TacticalMissionState,version:Int) {
        writeInt(m.character);writeInt(m.mission);writeInt(m.targetKind);writeInt(m.target);writeLong(m.assignedAt);writeLong(m.checkedAt);writeBoolean(m.completed);if(version==2)writeInt(m.grid ?: -1)
    }
    private fun DataInputStream.mission(version:Int)=TacticalMissionState(readInt(),readInt(),readInt(),readInt(),readLong(),readLong(),readBoolean(),
        if(version==2)readInt().also {require(it in -1..4999)}.takeIf {it>=0} else null)
    private fun DataOutputStream.map(values:Map<Int,Int>) {writeInt(values.size);values.toSortedMap().forEach {(k,v)->writeInt(k);writeInt(v)}}
    private fun DataInputStream.map():Map<Int,Int> {
        val result=linkedMapOf<Int,Int>();repeat(count(1000)){require(result.put(readInt(),readInt())==null)};return result
    }
    fun encode(s:TacticalCommandState):String {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out->with(out) {
            val version=if(s.aiSwitches.isEmpty() && s.creditedMerits.isEmpty() && s.aiNextAt.isEmpty() &&
                (s.missions.values+s.proposals.values).none {it.grid!=null})1 else 2
            writeInt(version);writeInt(s.orders.size)
            s.orders.forEach {writeInt(it.character);writeInt(it.grid);writeLong(it.acceptedAt);writeLong(it.readyAt);command(it.command)}
            map(s.morale);map(s.authority);map(s.formations)
            for(values in listOf(s.missions,s.proposals)) {writeInt(values.size);values.toSortedMap().values.forEach {mission(it,version)}}
            writeInt(s.air.size);s.air.toSortedMap().values.forEach {
                writeInt(it.character);writeInt(it.grid);writeInt(it.source);writeInt(it.target);writeInt(it.mode);writeLong(it.nextAt)
                writeBoolean(it.slowVelocity!=null);it.slowVelocity?.let(::writeFloat)
            }
            writeInt(s.fortresses.size);s.fortresses.toSortedMap().forEach {(id,m)->writeInt(id);writeInt(m.grid);writeUTF(TacticalMotionCodec.encode(m.motion));writeLong(m.reportedAt)}
            map(s.ranks);writeInt(s.merits.size);s.merits.toSortedMap().forEach {(id,v)->writeInt(id);writeLong(v)}
            map(s.emergencySupplies)
            map(s.formationLeaders)
            if(version==2) {map(s.aiSwitches);writeInt(s.creditedMerits.size);s.creditedMerits.toSortedMap().forEach {(id,v)->writeInt(id);writeLong(v)}
                writeInt(s.aiNextAt.size);s.aiNextAt.toSortedMap().forEach {(id,v)->writeInt(id);writeLong(v)}}
        }}
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }
    fun decode(text:String):TacticalCommandState {
        require(text.length in 1..2_000_000)
        val input=DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(text)))
        return input.use {with(it) {
            val version=readInt();require(version in 1..2)
            val orders=List(count(1000)){TacticalQueuedCommand(readInt(),readInt(),readLong(),readLong(),command())}
            val morale=map();val authority=map();val formations=map()
            fun readMissions():Map<Int,TacticalMissionState> {
                val result=linkedMapOf<Int,TacticalMissionState>();repeat(count(1000)){val m=mission(version);require(result.put(m.character,m)==null)};return result
            }
            val missions=readMissions();val proposals=readMissions()
            val air=linkedMapOf<Int,TacticalAirWing>()
            repeat(count(1000)) {
                val w=TacticalAirWing(readInt(),readInt(),readInt(),readInt(),readInt(),readLong(),if(readBoolean())readFloat() else null)
                require(air.put(w.source,w)==null)
            }
            val fortresses=linkedMapOf<Int,TacticalFortressMotion>()
            repeat(count(350)){val id=readInt();val m=TacticalFortressMotion(readInt(),TacticalMotionCodec.decode(readUTF()),readLong());require(fortresses.put(id,m)==null)}
            val ranks=map();val merits=linkedMapOf<Int,Long>()
            repeat(count(1000)){require(merits.put(readInt(),readLong())==null)}
            val emergency=map();val leaders=map()
            val ai=if(version==2)map() else emptyMap()
            val credits=linkedMapOf<Int,Long>()
            if(version==2)repeat(count(1000)){require(credits.put(readInt(),readLong())==null)}
            val aiNextAt=linkedMapOf<Int,Long>()
            if(version==2)repeat(count(1000)){require(aiNextAt.put(readInt(),readLong())==null)}
            require(available()==0)
            TacticalCommandState(orders,morale,authority,formations,missions,proposals,air,fortresses,ranks,merits,emergency,leaders,ai,credits,aiNextAt).also {require(encode(it)==text)}
        }}
    }
}
