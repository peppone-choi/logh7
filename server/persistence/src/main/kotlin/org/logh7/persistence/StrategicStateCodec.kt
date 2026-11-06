package org.logh7.persistence

import org.logh7.engine.*

/** Separate from the navigation-only fuel/docking checkpoint. Author: Peppone Choi. */
internal object StrategicStateCodec {
    fun encode(state:StrategicState):String {
        val countryOffices=state.countryOffices
        return listOf(
        state.people.toSortedMap().entries.joinToString(";") {(id,p)->listOf(id,p.rank ?: "-",p.groundTactics,p.retiredUntil,
            p.peerage?.ordinal ?: "-",p.merit,p.influence,p.evaluation,p.money,
            p.abilities?.joinToString(",") ?: "-",p.decorations.sorted().joinToString(",").ifEmpty {"-"})
            .plus(if(p.airTactics>0 || p.deceasedAt>0 || p.militaryRank>0)listOf(p.airTactics) else emptyList())
            .plus(if(p.deceasedAt>0 || p.militaryRank>0)listOf(p.deceasedAt) else emptyList())
            .plus(if(p.militaryRank>0)listOf(p.militaryRank) else emptyList()).joinToString(":")}.ifEmpty {"-"},
        state.suppressions.joinToString(";") {"${it.character}:${it.outfit}:${it.base}:${it.power}:${it.readyAt}"}.ifEmpty {"-"},
        state.offices.joinToString(";") {"${it.card}:${it.seat}:${it.character}"+
            if(countryOffices)":${it.country.value}" else ""}.ifEmpty {"-"},
        state.fiefs.toSortedMap().entries.joinToString(";") {"${it.key}:${it.value}"}.ifEmpty {"-"},
        state.operations.joinToString(";") {p->listOf(p.id,p.planner,p.card,p.power,p.grid,p.force.ordinal,
            p.purpose.ordinal,p.units,p.activatesAt,p.state.ordinal,p.issuedAt ?: "-",
            p.outfits.sorted().joinToString(",").ifEmpty {"-"},p.characters.sorted().joinToString(",").ifEmpty {"-"},
            p.defended.sorted().joinToString(",").ifEmpty {"-"},p.outcome?.ordinal ?: "-",p.issuer).joinToString(":")}.ifEmpty {"-"}
    ).plus(if(state.transports.isEmpty() && !countryOffices)emptyList() else listOf(state.transports.joinToString(";") {t->
        fun stacks(m:Map<UnitStack,Int>)=m.entries.joinToString(",") {"${it.key.kind}.${it.key.level}.${it.value}"}.ifEmpty {"-"}
        listOf(t.id,t.country.value,t.source,t.destination,t.supplies,stacks(t.units.ships),stacks(t.units.troops),t.carrier,t.carrierBoats).joinToString(":")}.ifEmpty {"-"}))
    .plus(if(countryOffices)listOf(state.capitals.entries.sortedBy {it.key.value}.joinToString(";") {"${it.key.value}:${it.value}"}.ifEmpty {"-"}) else emptyList())
    .joinToString("|").also {require(it.length<=2_000_000) {"Strategic state is too large"}}
    }
    fun decode(text:String,countryOffices:Boolean=false):StrategicState {
        require(text.length<=2_000_000)
        val sections=text.split('|');require(if(countryOffices)sections.size==7 else sections.size==5 || sections.size==6 && sections[5].isNotEmpty())
        val people=linkedMapOf<Int,StrategicPerson>()
        if(sections[0]!="-")for(row in sections[0].split(';')) {
            val f=row.split(':');require(f.size==11 || f.size==12 && f[11].toInt()>0 || f.size==13 && f[12].toLong()>0 || f.size==14 && f[13].toInt()>0)
            require(people.put(f[0].toInt(),StrategicPerson(f[1].takeUnless {it=="-"}?.toInt(),f[2].toInt(),f[3].toLong(),
                f[4].takeUnless {it=="-"}?.toInt()?.let {Peerage.entries[it]},f[5].toLong(),f[6].toInt(),f[7].toLong(),f[8].toLong(),
                f[9].takeUnless {it=="-"}?.split(',')?.map(String::toInt),
                f[10].takeUnless {it=="-"}?.split(',')?.map(String::toInt)?.toSet().orEmpty(),if(f.size>=12)f[11].toInt() else 0,
                if(f.size>=13)f[12].toLong() else 0,if(f.size==14)f[13].toInt() else 0))==null)
        }
        val orders=if(sections[1]=="-")emptyList() else sections[1].split(';').map {row->
            val f=row.split(':');require(f.size==5)
            SuppressionOrder(f[0].toInt(),f[1].toInt(),f[2].toInt(),f[3].toInt(),f[4].toLong())
        }
        val offices=if(sections[2]=="-")emptyList() else sections[2].split(';').map {row->
            val f=row.split(':');require(f.size==if(countryOffices)4 else 3)
            if(countryOffices)OfficeHolding(f[0].toInt(),f[1].toInt(),f[2].toInt(),CountryId(f[3].toInt()))
            else OfficeHolding(f[0].toInt(),f[1].toInt(),f[2].toInt())
        }
        val fiefs=linkedMapOf<Int,Int>()
        if(sections[3]!="-")for(row in sections[3].split(';')) {
            val f=row.split(':');require(f.size==2);require(fiefs.put(f[0].toInt(),f[1].toInt())==null)
        }
        fun ids(value:String)=if(value=="-")emptySet() else value.split(',').map(String::toInt).toSet()
        val operations=if(sections[4]=="-")emptyList() else sections[4].split(';').map {row->
            val f=row.split(':');require(f.size==16)
            StrategicOperation(f[0].toInt(),f[1].toInt(),f[2].toInt(),f[3].toInt(),f[4].toInt(),
                StrategicForce.entries[f[5].toInt()],StrategicPurpose.entries[f[6].toInt()],f[7].toInt(),f[8].toLong(),
                StrategicPlanState.entries[f[9].toInt()],f[10].takeUnless {it=="-"}?.toLong(),ids(f[11]),ids(f[12]),ids(f[13]),
                f[14].takeUnless {it=="-"}?.toInt()?.let {OperationOutcome.entries[it]},f[15].toInt())
        }
        fun stacks(value:String)=if(value=="-")emptyMap() else value.split(',').associate {s->
            val f=s.split('.');require(f.size==3);UnitStack(f[0].toInt(),f[1].toInt()) to f[2].toInt()}
        val transports=if(sections.size==5 || sections[5]=="-")emptyList() else sections[5].split(';').map {row->
            val f=row.split(':');require(f.size==9)
            TransportPackage(f[0].toInt(),CountryId(f[1].toInt()),f[2].toInt(),f[3].toInt(),f[4].toInt(),UnitInventory(stacks(f[5]),stacks(f[6])),
                f[7].toInt(),f[8].toInt())
        }
        val capitals=linkedMapOf<CountryId,Int>()
        if(countryOffices && sections[6]!="-")for(row in sections[6].split(';')) {
            val f=row.split(':');require(f.size==2 && capitals.put(CountryId(f[0].toInt()),f[1].toInt())==null)
        }
        return StrategicState(people,orders,offices,fiefs,operations,transports,capitals).also {require(encode(it)==text) {"Noncanonical strategic state"}}
    }
}
