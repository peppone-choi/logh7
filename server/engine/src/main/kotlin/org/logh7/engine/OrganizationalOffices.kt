package org.logh7.engine

import org.logh7.engine.worldseed.OrganizationSeedLoader
import org.logh7.engine.worldseed.OrganizationPost
import org.logh7.engine.worldseed.SeedFaction

enum class OfficeScope { NATIONAL,FORTRESS,PLANET,CAPITAL,OUTFIT }
data class OrganizationalOffice(val card:Int,val canonical:Int,val power:Int,val camp:Int,
    val manual:OrganizationPost,val institution:Int,val facility:Int,val scope:OfficeScope,val authority:Int?,
    val country:CountryId=CountryId(power)) {
    val seats get()=manual.seats
    val ranks:Set<Int> get() {
        fun rank(name:String)=rankNames.indexOf(name).also {require(it>=0){"Unknown manual rank $name"}}
        if(manual.minRank=="-")return setOf(0)
        if('/' in manual.minRank)return manual.minRank.split('/').map(::rank).toSet()
        val low=rank(manual.minRank);val high=rank(manual.maxRank)
        return (high..low).filter {power!=3 || it!=3}.toSet()
    }
    fun eligible(actorRank:Int,targetRank:Int)=targetRank in ranks &&
        (targetRank==1 && actorRank==1 || actorRank<targetRank)
    companion object {
        private val rankNames=listOf("皇帝","政治家","元帥","上級大将","大将","中将","少将","准将","大佐","中佐","少佐",
            "大尉","中尉","少尉","准尉","曹長","軍曹","伍長","上等兵","一等兵","二等兵")
    }
}
data class OfficeHolding(val card:Int,val seat:Int,val character:Int,
    val country:CountryId=CountryId(requireNotNull(OrganizationOffices.of(card)).power)) {
    init {require(card in 1..256 && seat>0 && character>0)}
}

/** Exact appointed_by edges are read from the reviewed 121-row manual source. Empty edges
 * stay empty: elections, succession and missing appointers are never replaced by rank checks.
 * Rebel aliases retain the matching office edge as an explicit restoration policy; the two
 * rebel leaders have no ordinary appointment edge. Author: Peppone Choi. */
object OrganizationOffices {
    private val institutions=listOf("皇宮","内閣","駐フェザーン弁務官事務所","軍務省","統帥本部","宇宙艦隊司令部",
        "憲兵本部","装甲擲弾兵総監部","科学技術総監部","帝国軍士官学校","同盟軍士官学校","艦隊","輸送艦隊",
        "巡察隊","地上部隊","要塞","首都惑星を除く各惑星","首都惑星","統合偵察局","連合司令部","最高評議会",
        "統合作戦本部","後方勤務本部","国防委員会","戦略作戦局")
    private val facilities=mapOf(0 to 22,1 to 38,2 to 28,3 to 31,4 to 37,5 to 29,6 to 32,7 to 35,8 to 30,
        9 to 20,10 to 20,20 to 24,21 to 36,22 to 33,23 to 34)
    private val source=OrganizationSeedLoader.load().posts
    private val standard:Map<Int,OrganizationPost> = buildMap {
        val empire=source.filter {it.faction==SeedFaction.EMPIRE};val alliance=source.filter {it.faction==SeedFaction.ALLIANCE}
        require(empire.size==58 && alliance.size==63)
        empire.forEachIndexed {i,p->put(i+1,p)};alliance.forEachIndexed {i,p->put(i+132,p)}
    }
    /** The original 261-slot catalogue includes personal/peerage/special cards and rebel
     * aliases. They are not 261 appointable manual offices. */
    private val aliases:Map<Int,Int> = buildMap {
        for(card in 70..80)put(card,card-67)
        for(card in 81..122)put(card,card-64)
        for(card in 199..208)put(card,card-65)
        for(card in 209..256)put(card,card-62)
        put(68,1);put(69,2);put(197,132);put(198,133)
    }
    val all:Map<Int,OrganizationalOffice> = (standard.keys+aliases.keys).associateWith {card ->
        val canonical=aliases[card] ?: card;val p=standard.getValue(canonical)
        val power=if(p.faction==SeedFaction.EMPIRE)2 else 3;val camp=if(card in aliases)1 else 0
        val institution=institutions.indexOf(p.department).also {require(it>=0)}
        val authority=if(card in setOf(68,69,197,198) || p.appointedBy.isEmpty())null else {
            val name=p.appointedBy.substringAfterLast('・')
            val canonicalAuthority=standard.entries.single {it.value.faction==p.faction && it.value.post==name}.key
            if(camp==0)canonicalAuthority else aliases.entries.singleOrNull {it.value==canonicalAuthority}?.key
        }
        val scope=when(institution) {11,12,13,14->OfficeScope.OUTFIT;15->OfficeScope.FORTRESS;
            16->OfficeScope.PLANET;17->OfficeScope.CAPITAL;else->OfficeScope.NATIONAL}
        OrganizationalOffice(card,canonical,power,camp,p,institution,facilities[institution] ?: -1,scope,authority)
    }
    fun of(card:Int)=all[card]
    /** Select a rule system by its configured key, never by the country's display code. */
    fun system(country:CountryId,countries:CountryCatalog):Int? {
        val definition=countries.countries[country.value] ?: return null
        return when(countries.families.getValue(definition.family).offices) {"empire"->2;"alliance"->3;else->null}
    }
    fun forCountry(country:CountryId,countries:CountryCatalog=CountryCatalogLoader.current()):List<OrganizationalOffice> {
        val system=system(country,countries) ?: return emptyList()
        return all.values.filter {it.power==system}.map {it.copy(country=country)}
    }
    fun of(card:Int,country:CountryId,countries:CountryCatalog=CountryCatalogLoader.current())=
        all[card]?.takeIf {it.power==system(country,countries)}?.copy(country=country)
}

/** A single authoritative view across old concrete office fields and newly recovered seats. */
class OrganizationalOfficePolicy(private val galaxy:Galaxy,private val configuredCountries:CountryCatalog?=null) {
    private val countries get()=configuredCountries ?: CountryCatalogLoader.current()
    fun role(card:Int,country:CountryId)=OrganizationOffices.of(card,country,countries)
    fun capital(stocks:PlanetStocks,country:CountryId):Int? {
        val seat=stocks.strategic.capitals[country] ?: galaxy.bases.singleOrNull {Capital.of(it)?.power==country.value}?.id ?: return null
        return seat.takeIf {stocks.bases[it]?.let {b->b.belongsTo(country) && b.camp==0}==true}
    }
    fun active(stocks:PlanetStocks,role:OrganizationalOffice,seat:Int):Boolean {
        val baseId=if(role.scope==OfficeScope.OUTFIT)stocks.outfits[seat]?.baseId ?: return false else seat
        val base=stocks.bases[baseId] ?: return false
        val seed=galaxy.bases.singleOrNull {it.id==baseId} ?: return false
        val country=role.country
        if(!base.belongsTo(country) || base.camp!=role.camp)return false
        return when(role.scope) {
            OfficeScope.OUTFIT -> stocks.outfits[seat]?.let {it.country==country && it.kind==when(role.institution) {
                11->OutfitKind.FLEET;12->OutfitKind.TRANSPORT;13->OutfitKind.PATROL;else->OutfitKind.GROUND}}==true
            OfficeScope.FORTRESS -> seed.kind==1
            OfficeScope.PLANET -> seed.kind==0 && Capital.of(seed)==null && baseId !in stocks.strategic.capitals.values
            OfficeScope.CAPITAL,OfficeScope.NATIONAL -> if(role.camp==1)true else capital(stocks,country)==baseId
        }
    }
    fun holders(stocks:PlanetStocks,card:Int):List<OfficeHolding> {
        val legacy=OrganizationOffices.of(card) ?: return emptyList()
        return holders(stocks,card,CountryId(legacy.power))
    }
    fun holders(stocks:PlanetStocks,card:Int,country:CountryId):List<OfficeHolding> {
        val role=role(card,country) ?: return emptyList()
        val old=if(role.camp!=0)emptyList() else buildList {
            if(country==CountryId(role.power))NationalOffice.of(card)?.let {office ->stocks.nationalOffices[office]?.let {character ->
                capital(stocks,country)?.let {add(OfficeHolding(card,it,character,country))}}}
            if(country==CountryId(role.power))LogisticsOffice.of(card)?.let {office ->
                val base=capital(stocks,country)
                if(base!=null)stocks.logisticsOfficers.filterValues {it==office}.keys.forEach {add(OfficeHolding(card,base,it,country))}
            }
            val fleet=FleetOffice.of(card)
            if(fleet!=null)stocks.outfits.values.filter {it.country==country && it.kind==OutfitKind.FLEET}.forEach {o->
                fleet.holders(o).forEach {add(OfficeHolding(card,o.id,it,country))}}
            val commanding=when(card){44,180->OutfitKind.TRANSPORT;47,183->OutfitKind.PATROL;50,186->OutfitKind.GROUND;else->null}
            if(commanding!=null)stocks.outfits.values.filter {it.country==country && it.kind==commanding && it.commanderId>0}
                .forEach {add(OfficeHolding(card,it.id,it.commanderId,country))}
            if(country==CountryId(role.power) && role.scope in setOf(OfficeScope.FORTRESS,OfficeScope.PLANET,OfficeScope.CAPITAL))
                stocks.bases.values.forEach {b->BaseOfficePolicy(galaxy).holders(b)[card]?.let {add(OfficeHolding(card,b.baseId,it))}}
        }
        return (old+stocks.strategic.offices.filter {it.card==card && it.country==country}).distinct().filter {active(stocks,role,it.seat)}
    }
    fun all(stocks:PlanetStocks)=countries.countries.keys.flatMap {id->
        val country=CountryId(id);OrganizationOffices.forCountry(country,countries).flatMap {holders(stocks,it.card,country)}}
    fun held(stocks:PlanetStocks,character:Int)=all(stocks).filter {it.character==character}
    fun cardCount(stocks:PlanetStocks,character:Int)=held(stocks,character).size+
        (if(stocks.strategic.people[character]?.peerage!=null)1 else 0)+stocks.strategic.fiefs.values.count {it==character}
    fun canTakeAnySeat(stocks: PlanetStocks, role: OrganizationalOffice, character: Int, seats: Collection<Int>): Boolean {
        // Native resignation names the card kind without a seat.
        if (held(stocks, character).any { it.card == role.card }) return false
        return seats.any { role.scope != OfficeScope.OUTFIT || character !in stocks.outfits.getValue(it).members() }
    }
    fun authorized(stocks:PlanetStocks,role:OrganizationalOffice,seat:Int,character:Int,rank:Int?=null):Boolean {
        if(!active(stocks,role,seat))return false
        val authority=role.authority?.let {this.role(it,role.country)} ?: return false
        if(rank!=null && rank !in authority.ranks && !(authority.canonical==2 && rank==0)) {
            // Native card 5 describes a chancellor who concurrently holds card 1 or card 6.
            val concurrentCard=when(rank) {0->1;1->6;else->null}
            if(authority.card!=5 || concurrentCard==null ||
                holders(stocks,concurrentCard,role.country).none {it.character==character})return false
        }
        return holders(stocks,authority.card,role.country).any {it.character==character &&
            // Local commanders/governors appoint only within their own outfit/base.
            (authority.scope==OfficeScope.NATIONAL || it.seat==seat)}
    }
    fun available(stocks:PlanetStocks,character:Int,power:Int,rank:Int):Map<Int,List<Int>> =
        (if(power>0)OrganizationOffices.forCountry(CountryId(power),countries) else emptyList())
        .filter {it.authority!=null}.associate {role ->
            val seats=if(role.scope==OfficeScope.OUTFIT)stocks.outfits.keys else stocks.bases.keys
            role.card to seats.filter {seat->authorized(stocks,role,seat,character,rank) && holders(stocks,role.card,role.country).count {it.seat==seat}<role.seats}
        }.filterValues {it.isNotEmpty()}
    fun target(role:OrganizationalOffice,seat:Int,stocks:PlanetStocks?=null):Int = if(role.scope==OfficeScope.NATIONAL && role.facility>=0)
        galaxy.facilitiesAt(seat,stocks,countries).singleOrNull {it.kind==role.facility}?.id ?: seat else seat
    fun set(stocks:PlanetStocks,holding:OfficeHolding,remove:Boolean=false):PlanetStocks {
        if(!remove) {
            val appointed=store(stocks,holding,false)
            val outfit=appointed.outfits[holding.seat] ?: return appointed
            if(stocks.outfits[holding.seat]?.commanderId!=0 || outfit.commanderId!=holding.character ||
                outfit.kind==OutfitKind.GROUND)return appointed
            // A retreat detachment keeps temporary command until its vacant commander seat is filled.
            val members=outfit.members()
            val authority=appointed.tacticalCommands.authority
            val released=outfit.activeShips?.values.orEmpty().filter {unit->
                authority[unit.id]?.let {it !in members}==true
            }.map {it.id}
            return if(released.isEmpty())appointed else appointed.copy(tacticalCommands=appointed.tacticalCommands.copy(
                authority=authority-released))
        }
        // A seat may sit in its concrete field or in the general seat list; removal clears both.
        val cleared=store(stocks,holding,true)
        val replacement=if(holding in cleared.strategic.offices)
            cleared.copy(strategic=cleared.strategic.copy(offices=cleared.strategic.offices-holding)) else cleared
        if(role(holding.card,holding.country)?.scope!=OfficeScope.OUTFIT)return replacement
        val outfit=replacement.outfits[holding.seat] ?: return replacement
        if(holding.character in outfit.members())return replacement
        val released=outfit.activeShips?.values.orEmpty().filter {
            replacement.tacticalCommands.authority[it.id]==holding.character
        }.map {it.id}
        return replacement.copy(tacticalCommands=replacement.tacticalCommands.copy(
            authority=replacement.tacticalCommands.authority-released))
    }
    private fun store(stocks:PlanetStocks,holding:OfficeHolding,remove:Boolean):PlanetStocks {
        val role=checkNotNull(role(holding.card,holding.country));val card=holding.card
        require(active(stocks,role,holding.seat))
        if(role.camp==0) {
            if(holding.country==CountryId(role.power))NationalOffice.of(card)?.let {return stocks.copy(nationalOffices=if(remove)stocks.nationalOffices-it else stocks.nationalOffices+(it to holding.character))}
            if(holding.country==CountryId(role.power))LogisticsOffice.of(card)?.let {office->
                val existing=stocks.logisticsOfficers[holding.character]
                // The legacy map stores one logistics role per person. Keep additional
                // simultaneous cards in the general seat list instead of replacing a job.
                if(existing==office || !remove && existing==null)return stocks.copy(logisticsOfficers=
                    if(remove)stocks.logisticsOfficers-holding.character else stocks.logisticsOfficers+(holding.character to office))
            }
            FleetOffice.of(card)?.let {r->val o=stocks.outfits.getValue(holding.seat)
                return stocks.copy(outfits=stocks.outfits+(o.id to if(remove)r.remove(o,holding.character) else r.assign(o,holding.character)))}
            if(card in setOf(44,47,50,180,183,186)) {
                val o=stocks.outfits.getValue(holding.seat)
                return stocks.copy(outfits=stocks.outfits+(o.id to o.copy(commanderId=if(remove)0 else holding.character)))
            }
            // Capital-only fields (56/57/192/193) exist on the original capital alone (BaseOfficePolicy);
            // a moved capital keeps them in the general seat list, which holders() already reads.
            val legacyCapitalSeat=card !in setOf(56,57,192,193) ||
                galaxy.bases.singleOrNull {it.id==holding.seat}?.let(Capital::of)?.power==role.power
            if(holding.country==CountryId(role.power) && legacyCapitalSeat && card in setOf(52,54,55,56,57,188,190,191,192,193)) {
                val b=stocks.bases.getValue(holding.seat)
                if(remove && role.scope==OfficeScope.CAPITAL && BaseOfficePolicy(galaxy).holders(b)[card]!=holding.character)
                    return stocks
                val id=if(remove)0 else holding.character
                val next=when(card){54,190->b.copy(governorId=id);52,55,188,191->b.copy(garrisonCommanderId=id)
                    56,193->b.copy(capitalDefenseCommanderId=id);57->b.copy(guardInspectorId=id);else->b.copy(capitalAdministratorId=id)}
                return stocks.copy(bases=stocks.bases+(b.baseId to next))
            }
        }
        return stocks.copy(strategic=stocks.strategic.copy(offices=if(remove)stocks.strategic.offices-holding
            else stocks.strategic.offices+holding))
    }
}
