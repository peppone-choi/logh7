package org.logh7.engine

/** Manual W pp.12-13. UNRESOLVED preserves missing political conditions instead of granting an award. */
enum class SessionGrade { DECISIVE, LIMITED, LOCAL, DEFEAT, UNRESOLVED }
data class SessionStatistics(val planets:Int=0,val fortresses:Int=0,val population:Long=0,
    val shipUnits:Long=0,val groundUnits:Long=0) {
    init {require(planets>=0 && fortresses>=0 && population>=0 && shipUnits>=0 && groundUnits>=0)}
}
data class SessionCountryStatistics(val total:SessionStatistics,val countries:Map<CountryId,SessionStatistics>)
data class CountryVictoryConditions(val noCoupAtEnd:Boolean?=null,val leaderAtEnemyCapital:Boolean?=null)
data class VictoryConditions(val noCoupAtEnd:Boolean?=null,val imperialLeaderAtEnemyCapital:Boolean?=null,
    val coupSides:Set<Power>?=null,val countries:Map<CountryId,CountryVictoryConditions> = emptyMap()) {
    fun noCoup(side:Power)=noCoup(side.country)
    fun noCoup(country:CountryId):Boolean?=if(country in countries)countries.getValue(country).noCoupAtEnd else
        noCoupAtEnd ?: Power.entries.singleOrNull {it.country==country}?.let {side->coupSides?.let {side !in it}}
    fun leader(country:CountryId):Boolean?=if(country in countries)countries.getValue(country).leaderAtEnemyCapital else
        imperialLeaderAtEnemyCapital.takeIf {country==CountryId(2)}
}
data class SessionCountryResult(val statistics:SessionStatistics,val grade:SessionGrade,
    val populationDominance:Boolean,val fleetDominance:Boolean,val leaderRequired:Boolean?)
data class SessionResult(val end:SessionEnd,val total:SessionStatistics,val countries:Map<CountryId,SessionCountryResult>,
    val conditions:VictoryConditions) {
    /** Native two-row boundary only. Never silently discard another country's result. */
    val powers:Map<Power,SessionCountryResult> get() {
        require(countries.keys==Power.entries.map {it.country}.toSet()) {"Native result rows require exactly countries 2/3"}
        return Power.entries.associateWith {countries.getValue(it.country)}
    }
}

object SessionResults {
    /** Compatibility entry point; country assessment is shared with new worlds. */
    fun assess(end:SessionEnd,total:SessionStatistics,powers:Map<Power,SessionStatistics>,
        conditions:VictoryConditions=VictoryConditions()):SessionResult {
        require(powers.keys==Power.entries.toSet())
        return assessCountries(end,total,powers.mapKeys {it.key.country},conditions)
    }

    fun assessCountries(end:SessionEnd,total:SessionStatistics,statistics:Map<CountryId,SessionStatistics>,
        conditions:VictoryConditions=VictoryConditions(),catalog:CountryCatalog=CountryCatalogLoader.current()):SessionResult {
        val ids=statistics.keys+end.affectedCountries+end.remainingCountries.orEmpty()
        require(ids.size in 2..350)
        val countries=ids.associateWith {statistics[it] ?: SessionStatistics()}
        require(countries.values.sumOf {it.population.toBigInteger()}<=total.population.toBigInteger())
        val survivors=end.remainingCountries ?: ids
        val survivor=end.remainingCountries?.singleOrNull()
        val legacy=ids==Power.entries.map {it.country}.toSet()
        val capitalWinner=if(end.reason!=EndReason.CAPITAL_CAPTURED)null else
            if(end.remainingCountries!=null)survivor else
                if(legacy && end.affectedCountries.size==1)(ids-end.affectedCountries).single() else null
        val populationLeaders=survivors.groupBy {countries.getValue(it).population}.maxByOrNull {it.key}?.value.orEmpty()
        // Preserve the original two-country tie rule. More-country ties remain unresolved.
        val populationWinner=populationLeaders.singleOrNull() ?: CountryId(3).takeIf {legacy && it in populationLeaders}
        val localWinner=when {
            end.reason==EndReason.CAPITAL_CAPTURED->null
            end.reason!=EndReason.TIME_LIMIT && end.remainingCountries!=null->survivor
            else->populationWinner
        }
        val results=countries.mapValues {(country,own)->
            val enemyShips=countries.filterKeys {it!=country}.values.sumOf {it.shipUnits.toBigInteger()}
            val population=total.population>0 && own.population.toBigInteger()*10.toBigInteger()>=total.population.toBigInteger()*9.toBigInteger()
            val fleet=own.shipUnits>0 && own.shipUnits.toBigInteger()>=enemyShips*10.toBigInteger()
            val leaderRequired=when(OrganizationOffices.system(country,catalog)) {2->true;3->false;else->null}
            val requirements=listOf(population,fleet,conditions.noCoup(country))+when(leaderRequired) {
                true->listOf(conditions.leader(country));false->emptyList();null->listOf(null)
            }
            val grade=when {
                end.reason!=EndReason.TIME_LIMIT && end.remainingCountries==emptySet<CountryId>()->SessionGrade.UNRESOLVED
                country !in survivors->SessionGrade.DEFEAT
                end.reason==EndReason.CAPITAL_CAPTURED && capitalWinner==null->SessionGrade.UNRESOLVED
                country==capitalWinner && leaderRequired==null->SessionGrade.UNRESOLVED
                country==capitalWinner->when {
                    requirements.any {it==false}->SessionGrade.LIMITED
                    requirements.any {it==null}->SessionGrade.UNRESOLVED
                    else->SessionGrade.DECISIVE
                }
                country==localWinner->SessionGrade.LOCAL
                country in populationLeaders && localWinner==null && end.reason==EndReason.TIME_LIMIT->SessionGrade.UNRESOLVED
                else->SessionGrade.DEFEAT
            }
            SessionCountryResult(own,grade,population,fleet,leaderRequired)
        }
        return SessionResult(end,total,java.util.Collections.unmodifiableMap(results),conditions.copy(
            coupSides=conditions.coupSides?.let {java.util.Collections.unmodifiableSet(it.toSet())},
            countries=java.util.Collections.unmodifiableMap(conditions.countries.toMap())))
    }

    /** W p.13: emperor or supreme commander in an enemy capital. Unknown NPC positions stay unresolved.
     * For multiple countries, any other country's registered capital is an enemy capital (revival policy). */
    fun imperialLeaderAtEnemyCapital(stocks:PlanetStocks,navigators:Collection<Navigator>,country:CountryId=CountryId(2),
        galaxy:Galaxy?=null,catalog:CountryCatalog=CountryCatalogLoader.current()):Boolean? {
        if(OrganizationOffices.system(country,catalog)!=2)return null
        val holders=if(galaxy==null)stocks.strategic.offices.filter {it.country==country && it.card in setOf(1,2,68,69)}.map {it.character}.toSet()+
            listOfNotNull(stocks.nationalOffices[NationalOffice.IMPERIAL_SUPREME_COMMANDER].takeIf {country==CountryId(2)}) else {
            val policy=OrganizationalOfficePolicy(galaxy,catalog)
            listOf(1,2,68,69).flatMap {policy.holders(stocks,it,country)}.map {it.character}.toSet()
        }
        val alive=holders.filterNot {BattleDeath.deceased(stocks,it)}.toSet()
        if(alive.isEmpty())return false
        val capitals=galaxy?.let {world->world.bases.mapNotNull {base->Capital.of(base)?.let {CountryId(it.power) to base.id}}.toMap()}.orEmpty()+stocks.strategic.capitals
        val enemyGrids=if(galaxy==null)setOf(Capital.ALLIANCE.grid).takeIf {country==CountryId(2)}.orEmpty() else
            capitals.filter {(opponent,id)->opponent!=country && id!=capitals[country]}.values.map {id->galaxy.bases.single {it.id==id}.grid}.toSet()
        if(enemyGrids.isEmpty())return null
        val located=navigators.filter {it.country==country && it.characterId in alive && it.generation==stocks.generation}
        return when {
            located.any {it.grid in enemyGrids}->true
            located.map {it.characterId}.toSet().containsAll(alive)->false
            else->null
        }
    }

    fun fromWorld(galaxy:Galaxy,snapshot:WorldSnapshot,given:VictoryConditions=VictoryConditions(),
        catalog:CountryCatalog=CountryCatalogLoader.current()):SessionResult {
        val session=checkNotNull(snapshot.session);val end=checkNotNull(session.ended)
        val stocks=checkNotNull(snapshot.planetStocks)
        val withCoup=if(given.coupSides!=null)given else given.copy(coupSides=Power.entries.filter {
            stocks.social.coup.revolting(it.country.value)}.toSet())
        val legacyConditions=if(withCoup.imperialLeaderAtEnemyCapital!=null)withCoup else
            withCoup.copy(imperialLeaderAtEnemyCapital=imperialLeaderAtEnemyCapital(stocks,snapshot.navigators.values,galaxy=galaxy,catalog=catalog))
        val statistics=statistics(galaxy,snapshot)
        val ids=statistics.countries.keys+session.countries+end.affectedCountries+end.remainingCountries.orEmpty()+
            if(session.countries.isEmpty() && end.remainingCountries==null)Power.entries.map {it.country}.toSet() else emptySet()
        val facts=ids.associateWith {country->given.countries[country] ?: CountryVictoryConditions(
            legacyConditions.noCoup(country),legacyConditions.leader(country) ?: if(OrganizationOffices.system(country,catalog)==2)
                imperialLeaderAtEnemyCapital(stocks,snapshot.navigators.values,country,galaxy,catalog) else null)}
        return assessCountries(end,statistics.total,ids.associateWith {statistics.countries[it] ?: SessionStatistics()},
            legacyConditions.copy(countries=facts),catalog)
    }

    /** Actual country ownership is retained even when multiple countries use one native display/model. */
    fun statistics(galaxy:Galaxy,snapshot:WorldSnapshot):SessionCountryStatistics {
        val session=checkNotNull(snapshot.session)
        val stocks=checkNotNull(snapshot.planetStocks)
        require(stocks.generation==session.generation && stocks.bases.keys==galaxy.bases.map {it.id}.toSet())
        data class Totals(var planets:Int=0,var fortresses:Int=0,var population:Long=0,var ships:Long=0,var ground:Long=0) {
            fun value()=SessionStatistics(planets,fortresses,population,ships,ground)
        }
        val total=Totals();val countries=linkedMapOf<CountryId,Totals>()
        fun add(country:CountryId?,planets:Int=0,fortresses:Int=0,people:Long=0,ships:Long=0,ground:Long=0) {
            for(t in listOfNotNull(total,country?.let {countries.getOrPut(it) {Totals()}})) {
                t.planets+=planets;t.fortresses+=fortresses;t.population+=people;t.ships+=ships;t.ground+=ground
            }
        }
        fun troops(units:UnitInventory)=units.troops.filterKeys {GroundUnits.isGround(it.kind)}.values.sumOf(Int::toLong)
        fun ships(units:UnitInventory,spare:SpareShipBoats)=units.ships.values.sumOf(Int::toLong)+spare.values.size
        for(base in galaxy.bases) {
            val stock=stocks.bases.getValue(base.id)
            val owner=if(stock.camp==0)stock.country else null
            add(owner,if(base.kind==0)1 else 0,if(base.kind==1)1 else 0,
                (stock.economy?.population ?: galaxy.conditions.getValue(base.id).population).toLong(),
                ships(stock.units,stock.spareShips),troops(stock.units))
        }
        for(stock in stocks.warehouses.values) add(stock.country,ships=ships(stock.units,stock.spareShips),
            ground=troops(stock.units))
        for(outfit in stocks.outfits.values) add(outfit.country,
            ships=outfit.activeShips?.values?.count {it.boats>0}?.toLong() ?: outfit.units.ships.values.sumOf(Int::toLong),
            ground=outfit.activeGround?.values?.size?.toLong() ?: troops(outfit.units))
        // A navigator always owns its personal unit; a legacy null hull is the default hull,
        // and defeat replaces rather than deletes it. Offline units remain in the world.
        for(player in snapshot.navigators.values)if(player.generation==session.generation)
            add(player.country,ships=1)
        return SessionCountryStatistics(total.value(),java.util.Collections.unmodifiableMap(countries.mapValues {it.value.value()}))
    }
}
