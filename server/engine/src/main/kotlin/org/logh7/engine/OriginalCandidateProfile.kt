package org.logh7.engine

enum class OriginalStartingOffice(val key:String,val country:CountryId,val fleetId:Int,val fleetNumber:Int,
    val baseId:Int,val role:FleetOffice,val rank:Int=5) {
    REINHARD_FLEET_2("reinhard-fleet-2",CountryId(2),2,2,1049,FleetOffice.EMPIRE_COMMANDER),
    VUCOCK_FLEET_5("vucock-fleet-5",CountryId(3),137,5,1086,FleetOffice.ALLIANCE_COMMANDER),
    STADEN_FLEET_4("staden-fleet-4",CountryId(2),4,4,1015,FleetOffice.EMPIRE_COMMANDER),
    PAETTA_FLEET_2("paetta-fleet-2",CountryId(3),134,2,1177,FleetOffice.ALLIANCE_COMMANDER),
    FOGEL_FLEET_5("fogel-fleet-5",CountryId(2),5,5,1015,FleetOffice.EMPIRE_COMMANDER),
    MERKATZ_FLEET_3("merkatz-fleet-3",CountryId(2),3,3,1026,FleetOffice.EMPIRE_COMMANDER,rank=4);

    fun accepts(id:Long,country:CountryId?,rank:Int):Boolean =
        id in PublishedOriginalCandidates.NEW_IDS && country==this.country && rank==this.rank

    companion object {
        fun fromKey(key:String):OriginalStartingOffice? = entries.singleOrNull {it.key==key}
    }
}

data class OriginalBerthClaim(val account:String,val character:Int,val country:CountryId?,val rank:Int,
    val office:OriginalStartingOffice?) {
    internal fun berth():Pair<OriginalStartingOffice,FleetOffice>? {
        if(character==1001 && country==CountryId(2))return OriginalStartingOffice.REINHARD_FLEET_2 to FleetOffice.EMPIRE_AIDE
        return office?.takeIf {it.accepts(character.toLong(),country,rank)}?.let {it to it.role}
    }

    internal fun vacantFleet(fleet:OutfitStock,stocks:PlanetStocks,galaxy:Galaxy,players:Map<String,Navigator>):FleetOffice? {
        val (site,role)=berth() ?: return null
        if(players[account]?.let {it.generation==stocks.generation &&
                (it.characterId==character || !BattleDeath.deceased(stocks,it.characterId))}==true ||
            players.values.any {it.generation==stocks.generation && it.characterId==character} ||
            BattleDeath.deceased(stocks,character) || stocks.outfits.values.any {character in it.members()})return null
        return role.takeIf {fleet.id==site.fleetId && fleet.country==site.country && fleet.kind==OutfitKind.FLEET &&
            fleet.number==site.fleetNumber && fleet.baseId==site.baseId && fleet.positionIn(galaxy).baseId==site.baseId &&
            role.holders(fleet).isEmpty() && CoupActions.outfitCamp(stocks,fleet)==0 &&
            stocks.bases[site.baseId]?.let {it.country==site.country && it.camp==0}==true}
    }
}

/** Editable starting data for a lottery candidate. Its ID and creation side are stable. */
data class OriginalCandidateProfile(
    val surname:String,val givenName:String,val face:Int,val rank:Int,val abilities:List<Int>,val peerage:Peerage?=null,
    val portraitKey:String?=null,val startingOffice:OriginalStartingOffice?=null
) {
    init {
        require(listOf(surname,givenName).all { name ->
            name.isNotBlank() && name.length<=12 && name.none {it.isISOControl() || it.isSurrogate()}
        })
        require(rank in 2..20 && abilities.size==8 && abilities.all {it in 0..255})
        require(portraitKey==null || CountryImageStore.validKey(portraitKey))
    }

    val creationPower get()=when(face) {
        in empireMaleFaces,in empireFemaleFaces -> 2
        in allianceMaleFaces,in allianceFemaleFaces -> 3
        else -> 0
    }
    val gender get()=if(face in empireFemaleFaces || face in allianceFemaleFaces)1 else 0
    fun matchesCreationPower(power:Int)=power in 2..3 && creationPower==power && (power!=3 || rank!=3)

    companion object {
        // CD tcf.hed. Original female faces are separate slots in o.tcf.
        private val empireMaleFaces=1..158
        private val empireFemaleFaces=10001..10006
        private val allianceMaleFaces=100001..100108
        private val allianceFemaleFaces=110001..110004

        fun faceNumbers(power:Int,gender:Int)=when(power) {
            2 -> if(gender==0)empireMaleFaces else empireFemaleFaces
            3 -> if(gender==0)allianceMaleFaces else allianceFemaleFaces
            else -> IntRange.EMPTY
        }
    }
}

data class OriginalCandidateDefinition(val id:Long,val creationPower:Int,val profile:OriginalCandidateProfile)
data class OriginalCandidateEdit(val id:Long,val revision:Long,val profile:OriginalCandidateProfile,val country:CountryId?=null)
data class CandidatePortrait(val revision:Long,val rgba:ByteArray) {
    init { require(revision>0 && rgba.size==4096) }
}

interface OriginalCandidateStore {
    val supportsEditing:Boolean get()=true
    val supportsFemaleFaces:Boolean get()=true
    val supportsPortraits:Boolean get()=false
    val supportsStartingOffice:Boolean get()=false
    val supportsAllianceStartingOffice:Boolean get()=false
    val supportsStadenStartingOffice:Boolean get()=false
    val supportsPaettaStartingOffice:Boolean get()=false
    val supportsFogelStartingOffice:Boolean get()=false
    val supportsMerkatzStartingOffice:Boolean get()=false
    fun load():List<OriginalCandidateEdit>
    fun loadPortraits():Map<String,CandidatePortrait> = emptyMap()
    fun save(id:Long,expected:Long,profile:OriginalCandidateProfile,country:CountryId?=null):OriginalCandidateEdit?
}

class PublishedOriginalCandidates(definitions:List<OriginalCandidateDefinition> = emptyList(),val canGrantPeerage:Boolean=true) {
    val defaults=definitions.associateBy {it.id}
    @Volatile private var edits=emptyMap<Long,OriginalCandidateEdit>()
    @Volatile private var portraits=emptyMap<String,CandidatePortrait>()

    fun current()=edits
    fun portrait(id:Long)=edits[id]?.profile?.portraitKey?.let {portraits[it]}
    fun publishPortraits(values:Map<String,CandidatePortrait>) { portraits=values.toMap() }
    fun accepts(id:Long,profile:OriginalCandidateProfile,country:CountryId?=null):Boolean {
        val existing=edits
        val side=defaults[id]?.creationPower ?: existing[id]?.profile?.creationPower
        if(id !in defaults && (id !in NEW_IDS || country==null))return false
        if(profile.peerage!=null && country==null)return false
        val bound=existing[id]?.profile?.startingOffice
        if(bound!=null && profile.startingOffice!=bound)return false
        val office=profile.startingOffice
        if(office!=null && (id in defaults || !office.accepts(id,country,profile.rank) ||
            existing.values.any {it.id!=id && it.profile.startingOffice==office}))return false
        return profile.matchesCreationPower(side ?: profile.creationPower)
    }

    @Synchronized fun publish(rows:List<OriginalCandidateEdit>) {
        require(rows.all {it.revision>0} && rows.map {it.id}.distinct().size==rows.size)
        val updates=rows.filter {row -> row.revision>(edits[row.id]?.revision ?: 0)}
        require(updates.all {accepts(it.id,it.profile,it.country)})
        val next=edits+updates.associateBy {it.id}
        val offices=next.values.mapNotNull {it.profile.startingOffice}
        require(offices.distinct().size==offices.size)
        edits=next
    }

    companion object {
        val NEW_IDS=1003L..9999L
        // Leave packet space for a shared built-in candidate and five already accepted preferences.
        const val SCOPED_LIMIT=94
    }
}

/** The native peerage cards use the imperial display bank. The actual country's family must also support them. */
fun CountryCatalog.supportsNativePeerage(country:CountryId):Boolean =
    countries[country.value]?.let {it.nativePower==2 && families.getValue(it.family).peerage=="empire"}==true
