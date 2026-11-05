package org.logh7.gateway

import java.util.HexFormat
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages

/** Stored country and ship style are independent of the original client's display byte. */
data class CharacterProfile(val character:CharacterMessages.Generate,val country:CountryId?,val shipStyle:ShipStyleId) {
    init { require(character.category==4 && character.id>0) }

    internal fun encoded():String {
        val wire=CharacterMessages.generateReply(character)
        return "v2:${if(character.generated) "generated" else "original"}:${country?.value ?: "-"}:${shipStyle.value}:"+
            HexFormat.of().formatHex(wire.copyOfRange(4,wire.size))
    }

    companion object {
        fun legacy(character:CharacterMessages.Generate)=CharacterProfile(character,CountryId(character.power),ShipStyleId.fromLegacy(character.power))

        internal fun decoded(value:String):CharacterProfile {
            if(value.startsWith("v2:")) {
                val fields=value.split(':')
                require(fields.size==5 && fields[1] in setOf("generated","original")) { "Invalid character profile" }
                val country=if(fields[2]=="-")null else CountryId(fields[2].toInt().also {
                    require(it.toString()==fields[2]) { "Invalid character country" }
                })
                val data=CharacterMessages.generate(HexFormat.of().parseHex(fields[4])).copy(generated=fields[1]=="generated")
                return CharacterProfile(data,country,ShipStyleId(fields[3]))
            }
            var data=CharacterMessages.generate(HexFormat.of().parseHex(value.removePrefix("original:")))
                .copy(generated=!value.startsWith("original:"))
            // Only old records need the placeholder/defection hull repair. A v2 record may intentionally
            // fly a different country's models, and must never be rewritten from its display byte.
            if(data.generated && data.power in 2..3 && data.shipKind==0 && data.shipType in setOf(0,2,3))
                data=data.copy(shipKind=Flagships.forType(data.power,data.shipType).kind)
            if(data.power in 2..3 && data.shipKind>0 && Flagships.find(data.shipKind)?.let {it.power!=data.power}==true)
                data=data.copy(shipKind=Flagships.forType(data.power,data.shipType).kind)
            return legacy(data)
        }
    }
}
