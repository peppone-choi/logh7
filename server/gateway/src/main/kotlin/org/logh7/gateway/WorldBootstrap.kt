package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.WorldMessages
import org.logh7.protocol.LoginMessages
import org.logh7.engine.UnitSpecifications
import org.logh7.engine.FleetOffice
import org.logh7.engine.members
import org.logh7.engine.positionIn
import org.logh7.protocol.OutfitMessages
import org.logh7.protocol.OutfitPartyMessages
import org.logh7.protocol.BaseAffiliationMessages
import org.logh7.protocol.CountryImageMessages
import org.logh7.protocol.LogisticsMessages

class WorldBootstrap(val galaxy: Galaxy,
    private val configuredCountryCatalog:org.logh7.engine.CountryCatalog?=null,
    private val facilityDefinitions:()->org.logh7.engine.FacilityDefinitionSnapshot={org.logh7.engine.FacilityDefinitionDefaults.snapshot()},
    private val countryImages:()->org.logh7.engine.CountryImageSnapshot={org.logh7.engine.CountryImageSnapshot(0,emptyMap())}) {
    private val countryCatalog get()=configuredCountryCatalog ?: org.logh7.engine.CountryCatalogLoader.current()
    @Volatile private var countryAppearances:List<org.logh7.engine.CountryAppearance> = emptyList()
    @Synchronized fun publishCountryAppearances(values:List<org.logh7.engine.CountryAppearance>) {
        require(values.map {it.countryId}.distinct().size==values.size)
        val previous=countryAppearances.associateBy {it.countryId}
        // A polling read started before a web save may finish after its newer snapshot.
        countryAppearances=values.map {value->previous[value.countryId]?.takeIf {it.revision>value.revision} ?: value}
    }
    /** Lobby catalogue inputs (mode 7), each read once per request: definitions with their revision, and colors. */
    internal fun lobbyCountryDefinitions():Pair<org.logh7.engine.CountryCatalog,Long> {
        val snapshot=org.logh7.engine.CountryCatalogLoader.snapshot()
        return (configuredCountryCatalog ?: snapshot?.catalog ?: countryCatalog) to (if(configuredCountryCatalog==null)snapshot?.revision ?: 0 else 0)
    }
    internal fun publishedCountryAppearances():List<org.logh7.engine.CountryAppearance> = countryAppearances
    /** Public replies use the native presentation byte without granting country membership. */
    private fun viewerDisplay(countryId:Int?,nativePower:Int)=countryId?.let {
        countryCatalog.nativePower(org.logh7.engine.Allegiance.Country(org.logh7.engine.CountryId(it)))
    } ?: nativePower
    fun countryColors(query:OutfitPartyMessages.Query,countryId:Int?,nativePower:Int=0):ByteArray {
        val viewerPower=viewerDisplay(countryId,nativePower)
        val appearance=countryAppearances.firstOrNull {it.countryId==query.outfit}
        val colors=appearance?.colors ?: org.logh7.engine.CountryColors()
        val fields=listOf(colors.primary,colors.secondary,colors.map,colors.fleet).map {org.logh7.engine.CountryColors.hex(it).removePrefix("#")}
        return LoginMessages.sessionMessage(0x032f,org.logh7.protocol.CountryColorMessages.reply(query,viewerPower,fields,appearance?.revision ?: 0))
    }
    fun countryMetadata(query:OutfitPartyMessages.Query,countryId:Int?,nativePower:Int=0):ByteArray {
        val viewerPower=viewerDisplay(countryId,nativePower)
        val snapshot=org.logh7.engine.CountryCatalogLoader.snapshot()
        val catalog=configuredCountryCatalog ?: snapshot?.catalog ?: countryCatalog
        val country=catalog.countries[query.outfit];val calendar=catalog.calendar(query.outfit)
        val emblem=nativeEmblem(country?.assets, countryImages().countries[query.outfit]?.nativeEmblem)
        return LoginMessages.sessionMessage(0x032f,org.logh7.protocol.CountryMetadataMessages.reply(query,viewerPower,
            country?.name.orEmpty(),calendar?.name.orEmpty(),calendar?.yearOffset ?: 0,if(configuredCountryCatalog==null)snapshot?.revision ?: 0 else 0,emblem))
    }
    fun countryImageFrames(query:OutfitPartyMessages.Query):List<ByteArray> {
        val snapshot=countryImages();val images=snapshot.countries[query.outfit]
        return CountryImageMessages.replies(query,snapshot.revision,images?.emblem,images?.mapIcon)
    }
    internal fun lobbyCountryEmblem(request: CountryImageMessages.LobbyRequest): List<ByteArray> {
        val (catalog, revision) = lobbyCountryDefinitions()
        val country = catalog.countries[request.query.outfit]
        val snapshot = countryImages()
        val images = country?.let { snapshot.countries[it.id] }
        // A definition refresh can arrive before its pixels. Do not mark older pixels current.
        val imageRevision = snapshot.revision.takeIf { it > 0 } ?: revision
        return CountryImageMessages.lobbyReply(request, imageRevision,
            nativeEmblem(country?.assets, images?.nativeEmblem), images?.emblem ?: images?.mapIcon)
    }

    private fun nativeEmblem(assets: String?, uploadedSlot: Int?): Int = when (assets) {
        "empire" -> 2
        "alliance" -> 3
        else -> uploadedSlot ?: 0
    }
    /** The authenticated viewer's real membership, including explicit unaffiliated/deleted definitions. */
    fun countryIdentity(query:OutfitPartyMessages.Query,countryId:Int?,nativePower:Int,portrait:Boolean=false,
        spotPortrait:Boolean=false,wounded:Boolean=false):ByteArray {
        val (catalog,revision)=lobbyCountryDefinitions()
        val country=countryId?.let {catalog.countries[it]};val calendar=country?.let {catalog.calendar(it.id)}
        return LoginMessages.sessionMessage(0x032f,org.logh7.protocol.CountryIdentityMessages.reply(query,countryId ?: 0,nativePower,
            country?.name ?: if(countryId==null)"무소속" else "미등록 국가",calendar?.name ?: "우주력",calendar?.yearOffset ?: 0,
            revision,country!=null,portrait,spotPortrait,wounded))
    }
    private val officeCards=org.logh7.engine.OrganizationOffices.all.mapValues {(_,role)->
        val commands=buildList {
            addAll(WorldMessages.commandsForCard(role.card).filter {it !in 25..26})
            if(org.logh7.engine.OutfitFormationPolicy.kinds(role.card).isNotEmpty())addAll(listOf(25,26))
            if(role.card in org.logh7.engine.CivicActions.commandingCards)addAll(listOf(52,54,56))
            when(role.card) {
                8,136->addAll(listOf(16,17,18))
                // U-25 (design-arrest-judgment.md): list/grant/arrest and judgment by duty (CM group 6).
                7,10,137->addAll(listOf(69,70,71,19,68))
                32,157->addAll(listOf(69,70,71,68,73,74))
                158->addAll(listOf(68,73,74))
                // U-17 工作 (design-espionage.md): intelligence cards also run the operations.
                20,58,162,194->addAll(listOf(68,73,74,75,76,77,78,79,80,81))
                // 後継指名 (SuccessionActions) on the emperor card.
                1->addAll(listOf(0,1,2,3,8,9,233))
                17,135->addAll(listOf(0,1,2,4))
                19,160->addAll(listOf(0,1,2))
                24,25,26,147,148,149->addAll(listOf(22,23))
                28,29,30,169,170,171->add(24)
            }
            // U-10 査閲 on the police/intelligence cards that search.
            if(role.card in org.logh7.engine.Justice.searchCards)add(72)
            // 国家目標 on the head of state and the prime minister.
            if(role.card in org.logh7.engine.GoalActions.nationalCards)add(15)
            if(role.card in org.logh7.engine.TradeActions.diplomacyCards)add(20)
            // U-08 production on the governor and capital administrator cards.
            if(role.card in setOf(54,190,192))addAll(org.logh7.engine.ProductionActions.commands.sorted())
            if(role.card==160)add(19)
            if(org.logh7.engine.OrganizationOffices.all.values.any {it.authority==role.card}) {add(5);add(6)}
            add(7)
        }.distinct()
        WorldMessages.CardDefinition(role.card,role.power,role.camp,role.institution,role.authority ?: 0,
            role.ranks.max(),role.ranks.min(),commands)
    }.plus(((60..66).toList()+(124..130).toList()).associateWith {card ->
        WorldMessages.CardDefinition(card,2,if(card>=124)1 else 0,if(card in setOf(66,130))16 else 0,1,20,0,
            // 封土 cards host 狩猟 and the lord's 統治目標.
            if(card in setOf(66,130))listOf(11,21)+org.logh7.engine.ProductionActions.commands.sorted() else emptyList())
    })
    /** Organization-linked commands; personal commands and entry permission remain separate. */
    val facilityCommands=org.logh7.engine.FacilityCommandCatalog {kind ->
        val ids=org.logh7.engine.OrganizationOffices.all.values.filter {role ->
            // Base-office cards target a base ID; their physical office is the administration or defense facility.
            val physical=if(role.scope in setOf(org.logh7.engine.OfficeScope.PLANET,org.logh7.engine.OfficeScope.CAPITAL,
                org.logh7.engine.OfficeScope.FORTRESS))if(role.canonical in setOf(54,190,192))0 else 1 else role.facility
            physical==kind
        }
            .flatMap {officeCards.getValue(it.card).commands}.distinct().sorted()
        if(ids.isEmpty())listOf("직접 연결된 직무 명령 없음. 개인·부대 명령은 해당 직무 카드에서 확인하세요.")
        else ids.map(FacilityCommandNames::name)
    }
    fun facilityGuide(query:OutfitPartyMessages.Query,countryId:Int?,stocks:org.logh7.engine.PlanetStocks,nativePower:Int=0):ByteArray {
        val viewerPower=viewerDisplay(countryId,nativePower)
        val base=query.outfit/100
        val snapshot=facilityDefinitions()
        if(base !in stocks.bases || galaxy.facilitiesAt(base,stocks,countryCatalog).none {it.id==query.outfit})
            return LoginMessages.sessionMessage(0x032f,org.logh7.protocol.FacilityGuideMessages.reply(query,viewerPower,snapshot.revision,
                "시설 정보 없음","시설 구성이 변경되었습니다. 시설 목록을 새로고침해 주세요.","",""))
        val commands=facilityCommands.commands(org.logh7.engine.FacilityDefinitionSnapshot.kindOf(query.outfit)).joinToString(" · ")
        require(commands.length<=192) {"Facility command guide exceeds the native page capacity"}
        return LoginMessages.sessionMessage(0x032f,org.logh7.protocol.FacilityGuideMessages.reply(query,viewerPower,snapshot.revision,
            snapshot.displayName(query.outfit),snapshot.purpose(query.outfit),snapshot.usage(query.outfit),commands))
    }
    private val baseOffices=BaseOfficeCards(galaxy)
    private val logisticsOffices get()=org.logh7.engine.LogisticsOfficePolicy(galaxy,countryCatalog)
    /** Stored base offices, never inferred from the viewer or a stationed outfit. The public holder lookup
     * (034e/034f, e.g. a planet's governor on its detail page) carries only the card, so it lists the holders
     * of every registered country whose office family uses that card; each holding keeps its own target seat.
     * A country missing from the catalogue contributes nothing. [countryOf] gives a holder's actual country
     * (current profile), which must be the holding country; the record keeps the card family's code. */
    fun cardCharacters(card: Int, stocks: org.logh7.engine.PlanetStocks,
        characterById: (Int) -> org.logh7.protocol.CharacterMessages.Generate?): ByteArray =
        // Compatibility for callers with original-country records only; additional countries need explicit identity.
        cardCharacters(card,stocks,{id->characterById(id)?.power?.takeIf {it in 2..3}?.let {org.logh7.engine.CountryId(it)}},characterById)
    fun cardCharacters(card: Int, stocks: org.logh7.engine.PlanetStocks,
        countryOf: (Int) -> org.logh7.engine.CountryId?,
        characterById: (Int) -> org.logh7.protocol.CharacterMessages.Generate?, page:Int?=null): ByteArray {
        require(page==null || page in 0..127)
        val role=org.logh7.engine.OrganizationOffices.of(card)
        require(role!=null || org.logh7.protocol.OfficeCardKinds.isPeerage(card) || org.logh7.protocol.OfficeCardKinds.isFief(card))
        val countries=countryCatalog
        val policy=org.logh7.engine.OrganizationalOfficePolicy(galaxy,countries)
        val holdings:List<Triple<Int,BootstrapMessages.OfficeCard,org.logh7.engine.CountryId?>> = if(role!=null)
            countries.countries.keys.sorted().map {org.logh7.engine.CountryId(it)}.flatMap {country ->
                val actual=policy.role(card,country) ?: return@flatMap emptyList()
                policy.holders(stocks,card,country).map {holding ->Triple(holding.character,
                    BootstrapMessages.OfficeCard(card,policy.target(actual,holding.seat,stocks)),country)}
            } else if(card in 60..65)
            stocks.strategic.people.filterValues {it.peerage?.ordinal==card-60}.keys.map {Triple(it,BootstrapMessages.OfficeCard(card,0),null)}
        else if(card==66)stocks.strategic.fiefs.map {(base,owner)->Triple(owner,BootstrapMessages.OfficeCard(66,base),null)} else emptyList()
        val appointed=holdings.groupBy({it.first},{it.second}).toSortedMap()
        val holderCountries=holdings.groupBy({it.first},{it.third}).mapValues {(_,c)->c.distinct()}
        val pageSize=org.logh7.protocol.CardCharacterMessages.PAGE_SIZE
        if(page==null && appointed.size>pageSize) return org.logh7.protocol.CardCharacterMessages.response(card,emptyList(),failed=true)
        val selected=appointed.entries.drop((page ?: 0)*pageSize).take(pageSize)
        require(selected.all { it.value.size<=14 }) { "Office cards exceed CD capacity" }
        val records=selected.map { (id,offices) ->
            val character=checkNotNull(characterById(id)) { "Appointed character is missing" }
            val country=holderCountries.getValue(id).single()
            if(country==null) require(character.power==2) { "Character faction differs from appointment" } // empire peerage/fief
            else {
                // The record's office cards carry the card family's code (empire 2 / alliance 3), so the wire
                // still needs that code; the current actual country must be the holding country itself.
                require(character.power==checkNotNull(role).power) { "Character faction differs from appointment" }
                require(countryOf(id)==country) { "Character country differs from appointment" }
            }
            BootstrapMessages.character(character,0,0,offices=offices.sortedBy { it.target })
        }
        return org.logh7.protocol.CardCharacterMessages.response(card,records)
    }
    fun outfitParty(query: OutfitPartyMessages.Query, countryId:Int, stocks: org.logh7.engine.PlanetStocks,
        editor: Int = 0, characterById: (Int) -> org.logh7.protocol.CharacterMessages.Generate? = { null }): ByteArray {
        require(query.mode in 0..1) { "Unsupported outfit roster query mode" }
        val country=org.logh7.engine.CountryId(countryId)
        val display=countryCatalog.nativePower(org.logh7.engine.Allegiance.Country(country))
        val outfit = stocks.outfits[query.outfit] ?: return LoginMessages.sessionMessage(0x032f,
            OutfitPartyMessages.party(query,display,0,0,emptyList(),emptyList(),0,0))
        val position = outfit.positionIn(galaxy)
        require(outfit.country == country && (query.base == 0 || query.base == if(query.mode==1)outfit.baseId else position.baseId)) { "Outfit identity mismatch" }
        val owner=stocks.bases.getValue(outfit.baseId)
        val allocator=editor>0 && logisticsOffices.permits(stocks,editor,country,outfit.kind) &&
            owner.belongsTo(country) && owner.camp==0
        require(query.mode == 0 || owner.belongsTo(country) && owner.camp == 0 && editor > 0 &&
            // The native allocation editor (00585150) sends the selected depot base, not 0.
            (outfit.commanderId == editor && query.base == outfit.baseId && position.baseId == outfit.baseId ||
                allocator && (query.base == 0 || query.base == outfit.baseId))) {
            "Only the stationed commander or authorized allocator may open the editor"
        }
        val members = outfit.members().map {id ->
            val commander = checkNotNull(characterById(id)) { "Outfit member is missing" }
            require(commander.id == id.toLong() && commander.power == display) { "Outfit member identity mismatch" }
            // Same kind (0), rank and display surname as the current 0323 character record.
            OutfitPartyMessages.Member(id,0,commander.rank,commander.surname)
        }
        val units = checkNotNull(outfit.activeShips) { "Active ship identities are not initialized" }
        val groups = units.values.groupBy { it.stack.kind }.toSortedMap().flatMap { (kind, ships) ->
            ships.sortedBy { it.id }.chunked(70).map { group ->
                OutfitPartyMessages.Ships(kind,group.sumOf {it.boats},group.map { it.id })
            }
        }
        val troops = outfit.units.troops.entries.sortedWith(compareBy({ it.key.kind },{ it.key.level }))
            .map { OutfitPartyMessages.Troops(1000+it.key.kind,it.key.level,it.value) }
        val maximum = org.logh7.engine.OutfitSupplies.visibleCapacity(outfit)
        return LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query.copy(base=if(query.mode==1)outfit.baseId else position.baseId),display,
            outfit.kind.wire,outfit.number,groups,troops,outfit.supplies,maximum,members))
    }
    /** Explicit revival query mode 2: original 0331 rows, at most 70 ground identities after the cursor.
     * No command or CP mutation; actual saved carriers, remote bases and unresolved legacy locations survive. */
    fun outfitGround(query:OutfitPartyMessages.Query,countryId:Int,stocks:org.logh7.engine.PlanetStocks):List<ByteArray> {
        require(query.mode==2 && query.base in 0..999999)
        // Command access by the actual country; the wire header carries its display code only.
        val country=org.logh7.engine.CountryId(countryId)
        val power=countryCatalog.nativePower(org.logh7.engine.Allegiance.Country(country))
        val outfit=stocks.outfits[query.outfit] ?: return listOf(LoginMessages.sessionMessage(0x032f,
            OutfitPartyMessages.party(query,power,0,0,emptyList(),emptyList(),0,0)),
            LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(emptyList())))
        require(outfit.country==country) {"Ground location access denied"}
        val position=outfit.positionIn(galaxy)
        val rows=outfit.activeGround?.values.orEmpty().filter {it.id>query.base}.sortedBy {it.id}.take(70).map {u->
            val grid=if(u.base>0)galaxy.bases.single {it.id==u.base}.grid else position.grid
            OutfitPartyMessages.UnitInformation(u.id,1000+u.stack.kind,grid,outfit.id,u.base,carrier=u.carrier,
                mode=if(u.carrier>0)10 else if(u.base>0)if(u.origin>0)9 else 4 else 0,rebellion=stocks.social.coup.loyalty[outfit.id] ?: 0)
        }
        // Echo the extension identity/cursor before 0331, including an empty page. This
        // prevents an unrelated or delayed native unit reply from satisfying the page.
        return listOf(LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query,power,outfit.kind.wire,
            outfit.number,emptyList(),emptyList(),0,0)),LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(rows)))
    }
    /** Reconstructed ground battle window (design-ground-battle-window.md), query mode 3: both sides' landed troops on
     * one base as 0331 rows. Kind 1000+k selects the artwork, while the extension's loyalty byte identifies
     * friendly/foreign troops. Only a viewer in tactics on that grid may read it. */
    fun outfitBattle(query:OutfitPartyMessages.Query,countryId:Int?,stocks:org.logh7.engine.PlanetStocks,viewerGrid:Int?,nativePower:Int=0,
        characterCountryById:(Int)->org.logh7.engine.CountryId? = {null},
        characterById:(Int)->org.logh7.protocol.CharacterMessages.Generate? = {null}):List<ByteArray> {
        require(query.mode==3 && query.outfit==0)
        val country=countryId?.let {org.logh7.engine.CountryId(it)}
        val countries=countryCatalog
        val power=viewerDisplay(countryId,nativePower)
        val registered=countries.countries.keys
        val base=galaxy.bases.singleOrNull {it.id==query.base}
        val fighting=base?.let {org.logh7.engine.GroundBattleView.fighting(stocks,it)}.orEmpty()
        // Public strength: every registered country's troops on this base (kind 1000+k keeps the model's own
        // faction). Private command (commander) only for the viewer's actual country. Records of a country that is
        // not in the catalogue are left out rather than shown as the empire or the alliance.
        val entries=if(base==null || viewerGrid!=base.grid)emptyList() else stocks.outfits.values.filter {it.country.value in registered}.flatMap {o->
            // Only troops that may fight here (p.51: no 装甲兵 on special planets/fortresses).
            o.activeGround?.values.orEmpty().filter {it.base==base.id && org.logh7.engine.GroundBattleRules.admitsCrewType(base,
                UnitSpecifications.crews.single {c->c.kind==it.stack.kind}.type)}.map {u->
                val commander=if(o.country==country)org.logh7.engine.GroundCommandAuthority.commander(o,u,stocks,galaxy) else 0
                o.country to OutfitPartyMessages.UnitInformation(u.id,1000+u.stack.kind,base.grid,o.id,u.base,
                    // destroyed=1 marks a reserve beyond the side's 30 fighting units (window display only).
                    supplies=stocks.tacticalCommands.groundTargets[u.id] ?: 0,damaged=u.strength,destroyed=if(u.id in fighting)0 else 1,
                    morale=stocks.tacticalCommands.morale[u.id] ?: 100,
                    rebellion=if(o.country==country)1 else 2,
                    commander=commander.takeIf {it>0 && characterCountryById(it)==country} ?: 0,
                    mode=if(u.origin>0)9 else 4)}
        }
        val allRows=entries.map {it.second}
        val rows=if(allRows.size<=70) allRows.sortedBy {it.id} else {
            val (fighters,reserves)=entries.sortedBy {it.second.id}.partition {it.second.destroyed==0}
            val (friendly,foreign)=fighters.partition {it.first==country}
            // Keep the viewer's commands usable even if three or more countries fill the native packet.
            val targetIds=friendly.map {it.second.supplies}.toSet()
            val (targets,others)=foreign.partition {it.second.id in targetIds}
            val opponents=others.groupBy {it.first}.values
            val balanced=(0 until org.logh7.engine.GroundBattleRules.BOX_CAPACITY_PER_FACTION).flatMap {index->
                opponents.mapNotNull {it.getOrNull(index)}
            }
            (friendly+targets+balanced+reserves).take(70).map {it.second}.sortedBy {it.id}
        }
        val contested=entries.filter {it.second.destroyed==0}.map {it.first}.toSet().size>1
        // The mode-3 header's unused number carries a version bit, battle state, and roster truncation.
        val battleFlags=1 or (if(contested)2 else 0) or (if(allRows.size>rows.size)4 else 0)
        val commanders=rows.map {it.commander}.filter {it>0}.distinct().sorted().take(10).mapNotNull {id->
            characterById(id)?.takeIf {it.id==id.toLong() && it.power==power}?.let {
                OutfitPartyMessages.Member(id,0,it.rank,it.surname)}}
        return listOf(LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query,power,0,battleFlags,emptyList(),emptyList(),0,0,commanders)),
            LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(rows)))
    }
    fun outfitUnit(id: Int, countryId: Int, stocks: org.logh7.engine.PlanetStocks, base: Int = 0): ByteArray {
        val country = org.logh7.engine.CountryId(countryId)
        require(country.value in countryCatalog.countries) { "Unregistered country" }
        val outfit = stocks.outfits.values.singleOrNull { owner -> owner.activeShips?.values?.any { it.id == id } == true }
        val units = if (outfit == null) emptyList() else {
            val position = outfit.positionIn(galaxy)
            require(outfit.country == country && (base == 0 || base == position.baseId)) { "Outfit unit access denied" }
            val unit = outfit.activeShips!!.values.single { it.id == id }
            listOf(OutfitPartyMessages.UnitInformation(id,unit.stack.kind,position.grid,outfit.id,position.baseId,
                org.logh7.engine.OutfitSupplies.distribution(outfit).getValue(id),unit.unavailable,
                UnitSpecifications.ships.single {it.kind==unit.stack.kind}.number-unit.boats,
                rebellion=stocks.social.coup.loyalty[outfit.id] ?: 0))
        }
        return LoginMessages.sessionMessage(0x0331,OutfitPartyMessages.units(units))
    }
    fun outfits(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks): ByteArray {
        val countries=countryCatalog
        return LoginMessages.sessionMessage(0x032b,
        OutfitMessages.information(ids.distinct().mapNotNull(stocks.outfits::get).map {
            OutfitMessages.Definition(it.id,it.kind.wire,countries.nativePower(org.logh7.engine.Allegiance.Country(it.country)),org.logh7.engine.CoupActions.outfitCamp(stocks,it),it.number)
        }+ids.distinct().filter {it>org.logh7.engine.PirateActions.OUTFIT_BASE}.mapNotNull {id->
            // U-32a: a pirate fleet is an outfit of power 4 (海賊).
            stocks.social.pirates.fleets[id-org.logh7.engine.PirateActions.OUTFIT_BASE]?.let {f->
                OutfitMessages.Definition(id,0,org.logh7.engine.PirateActions.POWER,0,f.id%256)}}))
    }
    fun gridOutfits(query: OutfitMessages.GridQuery, stocks: org.logh7.engine.PlanetStocks,
        country:org.logh7.engine.CountryId?=null): ByteArray {
        val countries=countryCatalog
        return LoginMessages.sessionMessage(0x032d,OutfitMessages.grid(stocks.outfits.values.filter { outfit ->
            (query.grid == -1 || outfit.positionIn(galaxy).grid == query.grid) &&
                (if(country!=null)outfit.country==country else query.power == 255 || countries.nativePower(org.logh7.engine.Allegiance.Country(outfit.country)) == query.power) &&
                (query.camp == 255 || query.camp == org.logh7.engine.CoupActions.outfitCamp(stocks,outfit))
        }.sortedBy { it.id }.map { OutfitMessages.Entry(it.id,it.kind.wire,countries.nativePower(org.logh7.engine.Allegiance.Country(it.country)),
            org.logh7.engine.CoupActions.outfitCamp(stocks,it),it.number,it.supplies) }))
    }
    private fun warehouseShips(values: Map<org.logh7.engine.UnitStack,Int>?,
        spare: Map<org.logh7.engine.UnitStack,Int> = emptyMap()) = (values.orEmpty().keys+spare.keys).map {it.kind}.distinct().sorted().map { kind ->
            val stacks=values.orEmpty().entries.filter {it.key.kind==kind}
            val count = stacks.sumOf { it.value.toLong() }
            val spec = UnitSpecifications.ships.single { it.kind == kind }
            val boats = Math.multiplyExact(count,spec.number.toLong())+spare.filterKeys {it.kind==kind}.values.sumOf {it.toLong()}
            val slots=(boats+spec.number-1)/spec.number
            require(slots in 1..255 && boats in 1..65535) { "Ship stock exceeds native warehouse capacity" }
            WorldMessages.WarehouseShips(kind,slots.toInt(),boats.toInt())
        }
    fun assignmentInventory(units: org.logh7.engine.UnitInventory,
        spare: org.logh7.engine.SpareShipBoats = org.logh7.engine.SpareShipBoats()) = LogisticsMessages.Inventory(
        warehouseShips(units.ships,spare.values).map { LogisticsMessages.ShipStock(it.kind,it.units,it.boats) },
        units.troops.entries.sortedWith(compareBy({ it.key.kind },{ it.key.level }))
            .map { LogisticsMessages.TroopStock(1000+it.key.kind,it.key.level,it.value) })
    fun outfitWarehouse(base: Int, outfitId: Int, character: Int, countryId:Int?, stocks: org.logh7.engine.PlanetStocks,
        reportUnavailable: Boolean = false): ByteArray {
        fun unavailable(reason: String): ByteArray {
            require(reportUnavailable) { reason }
            return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base,0,0,0,outfit=outfitId,unavailable=true))
        }
        val outfit = stocks.outfits[outfitId] ?: return LoginMessages.sessionMessage(0x0327,
            WorldMessages.warehouse(base,0,0,0,outfit=outfitId,unavailable=reportUnavailable))
        val country=countryId?.let { org.logh7.engine.CountryId(it) }
        if (country==null || outfit.baseId!=base || outfit.country!=country) return unavailable("Outfit warehouse identity mismatch")
        val owner = stocks.bases[base]
        if (owner==null || !owner.belongsTo(country) || owner.camp!=0) return unavailable("Outfit warehouse base access denied")
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        val commandsSharedWarehouse = stocks.outfits.values.any { it.warehouseId == warehouse.id && it.commanderId == character }
        if (character<=0 || (!commandsSharedWarehouse && !logisticsOffices.permits(stocks,character,country,outfit.kind)))
            return unavailable("Outfit warehouse access denied")
        fun entries(values: Map<org.logh7.engine.UnitStack,Int>) = values.entries
            .sortedWith(compareBy({ it.key.kind },{ it.key.level })).map { WorldMessages.Stock(it.key.kind,it.key.level,it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base,warehouse.supplies,warehouse.food,warehouse.mineral,
            warehouseShips(warehouse.units.ships,warehouse.spareShips.values),entries(warehouse.units.troops),outfitId))
    }
    fun warehouse(base: Int, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray {
        val state = requireNotNull(galaxy.conditions[base]) { "Unknown warehouse base" }
        val stored = stocks?.let { checkNotNull(it.bases[base]) }
        fun entries(values: Map<org.logh7.engine.UnitStack, Int>?) = values.orEmpty().entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.level })).map { WorldMessages.Stock(it.key.kind, it.key.level, it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base, stored?.supplies ?: state.supplies,
            stored?.food ?: state.food, stored?.mineral ?: 100_000, warehouseShips(stored?.units?.ships,stored?.spareShips?.values.orEmpty()), entries(stored?.units?.troops)))
    }
    internal fun baseWire(base: org.logh7.engine.GalaxyBase) = WorldMessages.Base(base.id, base.grid,
        base.model, base.kind, KoreanDisplayNames.base(base.name), base.physicalClass, base.radius, base.cycle, base.direction, base.angle, base.diameter)
    fun initialize(grid: Int, directory: Boolean = false, stocks: org.logh7.engine.PlanetStocks? = null): List<ByteArray> {
        val ids = galaxy.bases.filter { it.grid == grid }.map { it.id }
        // 004b76e0 builds the native standing-outfit cache after initialization
        // completes. A later standalone 032b query only fills its detail slot.
        return (if (directory) galaxy.bases.chunked(200).map { chunk ->
            LoginMessages.sessionMessage(0x1204, WorldMessages.simpleBases(chunk.map(::baseWire)))
        } else emptyList()) + (stocks?.let { state -> state.outfits.values
            .filter { it.positionIn(galaxy).grid == grid }.sortedBy { it.id }.map { it.id }
            .plus(state.social.pirates.fleets.values.filter { it.grid == grid }.map { org.logh7.engine.PirateActions.OUTFIT_BASE + it.id })
            .chunked(100).ifEmpty { listOf(emptyList()) }.map { outfits(it,state) } }.orEmpty()) +
            // Ownership is public map state. The original 031f cache holds only four
            // records; the compatibility map keeps each decoded base independently.
            // Resend all current owners on login, not the seed's original system power.
            (if (directory) galaxy.bases.map { it.id }.chunked(4).map { conditions(it,stocks) }
                else emptyList()) +
            // Restore the native four-record current-grid cache after the map snapshot.
            listOf(conditions(ids,stocks), places(ids, stocks))
    }
    fun conditions(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray {
        val countryCatalog=this.countryCatalog
        return LoginMessages.sessionMessage(0x031f,WorldMessages.conditions(ids.mapNotNull { galaxy.conditions[it] }.map {
            val stored = stocks?.let { state -> checkNotNull(state.bases[it.baseId]) }
            val people = stored?.economy
            WorldMessages.Condition(it.baseId, it.grid, countryCatalog.nativePower(stored?.owner ?: galaxy.initialOwners.getValue(it.baseId)), stored?.camp ?: it.camp,
                people?.population ?: it.population, people?.adults ?: it.adultPopulation,
                stored?.taxRate ?: it.tax, people?.approval ?: it.approval, people?.peace ?: it.peace, it.atmosphere, it.habitability,
                stored?.supplies ?: it.supplies, stored?.food ?: it.food, it.living)
        }))
    }
    /**
     * Public base affiliation snapshot (032f mode 12): every public base once, by wire base ID, with the actual
     * owner country (0 for no country) next to the same display power/camp projection 031f uses. No outfit or
     * personal data. An unregistered country fails exactly like [conditions] does.
     */
    internal fun baseAffiliationOwners(stocks: org.logh7.engine.PlanetStocks? = null): List<BaseAffiliationMessages.Owner> {
        val countryCatalog=this.countryCatalog
        val owners=galaxy.conditions.values.sortedBy { it.baseId }.map {
            val stored = stocks?.let { state -> checkNotNull(state.bases[it.baseId]) }
            val owner = stored?.owner ?: galaxy.initialOwners.getValue(it.baseId)
            BaseAffiliationMessages.Owner(it.baseId, owner.country?.value ?: 0, countryCatalog.nativePower(owner), stored?.camp ?: it.camp)
        }
        require(owners.size <= 350) { "Too many public bases" }
        return owners
    }
    /**
     * Fleet affiliations the authenticated [viewer] may already see: every outfit on the viewer's current grid
     * (any country) plus the viewer's own country's outfits anywhere, and pirate fleets on the current grid only.
     * Foreign off-grid outfits are left out, identical to "no such outfit". Actual country is separate from the
     * native power/camp display bytes; an unregistered owner fails like 031f.
     */
    internal fun outfitAffiliationOwners(stocks: org.logh7.engine.PlanetStocks, viewer: org.logh7.engine.Navigator):
        List<org.logh7.protocol.OutfitAffiliationMessages.Owner> {
        require(stocks.generation == viewer.generation) { "Stale generation" }
        val countries=countryCatalog
        val own=viewer.country
        val outfits=stocks.outfits.values.filter { it.positionIn(galaxy).grid == viewer.grid || (own != null && it.country == own) }
            .map { org.logh7.protocol.OutfitAffiliationMessages.Owner(it.id, it.country.value,
                countries.nativePower(org.logh7.engine.Allegiance.Country(it.country)), org.logh7.engine.CoupActions.outfitCamp(stocks, it)) }
        val pirates=stocks.social.pirates.fleets.values.filter { it.grid == viewer.grid }.map {
            org.logh7.protocol.OutfitAffiliationMessages.Owner(org.logh7.engine.PirateActions.OUTFIT_BASE + it.id, 0,
                org.logh7.engine.PirateActions.POWER, 0) }
        return outfits + pirates
    }
    fun outfitAffiliations(query: OutfitPartyMessages.Query, stocks: org.logh7.engine.PlanetStocks,
        viewer: org.logh7.engine.Navigator): List<ByteArray> {
        require(query.mode == org.logh7.protocol.OutfitAffiliationMessages.MODE && query.outfit in 1..65535 &&
            query.base>=0) { "Invalid outfit affiliation query" }
        // The grid is the viewer's authenticated position, never a client-chosen one.
        return org.logh7.protocol.OutfitAffiliationMessages.replies(query, viewer.grid, stocks.generation,
            outfitAffiliationOwners(stocks, viewer)).map { LoginMessages.sessionMessage(0x032f, it) }
    }
    fun baseAffiliations(query: org.logh7.protocol.OutfitPartyMessages.Query, stocks: org.logh7.engine.PlanetStocks? = null): List<ByteArray> {
        require(query.mode == BaseAffiliationMessages.MODE && query.outfit == 0 && query.base in 1..65535) { "Invalid affiliation query" }
        return BaseAffiliationMessages.replies(query, baseAffiliationOwners(stocks)).map { LoginMessages.sessionMessage(0x032f, it) }
    }
    fun places(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray = LoginMessages.sessionMessage(0x0321,
        WorldMessages.institutions(ids.mapNotNull { id -> galaxy.bases.find { it.id == id } }.map { base ->
            WorldMessages.BaseInstitutions(base.id, galaxy.facilitiesAt(base.id, stocks, countryCatalog).map { facility ->
                WorldMessages.Institution(facility.kind, facility.id, facility.spots.map {
                    WorldMessages.Spot(it.kind, it.id, it.image)
                })
            })
        }))
    val catalogues: Map<Int, ByteArray> = mapOf(
        // Row 89 renders a black hole (004d40bc: type 3, fixedstar 8 -> model 7) under the
        // "impassable grid" name; it is excluded from the 3..88 star-system index.
        0x0312 to WorldMessages.types(List(89) { kind ->
            Triple(kind, if (kind >= 3) 3 else kind, galaxy.systems.find { it.kind == kind }?.star ?: 4)
        } + Triple(org.logh7.engine.Galaxy.IMPASSABLE_CELL, 3, 8)),
        0x0314 to WorldMessages.grid(galaxy.cells),
        0x031c to WorldMessages.bases(galaxy.bases.map(::baseWire)),
        0x0306 to WorldMessages.captainCommands(officeCards.mapValues {it.value.commands}),
        0x0304 to WorldMessages.cards(officeCards),
        0x0308 to WorldMessages.powerDirection(),
        0x0310 to WorldMessages.armaments(org.logh7.engine.TacticalArmaments.profiles),
        0x030a to WorldMessages.ships(UnitSpecifications.ships.map { ship ->
            val beam = org.logh7.engine.TacticalArmaments.mount(ship, org.logh7.engine.TacticalWeapon.BEAM)
            val gun = org.logh7.engine.TacticalArmaments.mount(ship, org.logh7.engine.TacticalWeapon.GUN)
            val missile = org.logh7.engine.TacticalArmaments.mount(ship, org.logh7.engine.TacticalWeapon.MISSILE)
            // 004c5d50/00533980 use native +0x21e as the shared 0..19
            // class index, not the provisional hull-role IDs in ship.type.
            WorldMessages.Ship(ship.kind, ship.category, ship.model, KoreanDisplayNames.catalogue(ship.name),
                ship.number("出力:出力"), ship.number("索敵範囲（万km）:索敵範囲（万km）").toFloat(),
                ship.number("最高速度（km/1G秒）:最高速度（km/1G秒）").toFloat(),
                ship.number("装甲:前"), ship.number("装甲:側"), ship.number("装甲:後"),
                ship.number("シールド（旗艦のみ）:防護値"), ship.number("シールド（旗艦のみ）:容量"),
                ship.number("ビーム兵装破壊力:ビーム兵装破壊力"),
                ship.number("ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資"),
                ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資"),
                ship.number("対空兵装破壊力:対空兵装破壊力"), ship.number("修理消費物資(１隻):修理消費物資(１隻)"),
                category = ship.category, number = ship.number, term = ship.number("建造工期:建造工期"),
                crew = ship.number("必要乗組員ユニット数:必要乗組員ユニット数"),
                missileConsumption = ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資", 1),
                fighterNumber = ship.number("戦闘艇/雷撃艇搭載数:戦闘艇/雷撃艇搭載数"),
                cargo = ship.number("物資搭載量:物資搭載量"),
                beamArms = beam.profile, beamAngles = beam.angles, gunArms = gun.profile, gunAngles = gun.angles,
                missileArms = missile.profile, missileAngles = missile.angles,
                antiaircraftArms = if (ship.number("対空兵装破壊力:対空兵装破壊力") > 0) org.logh7.engine.TacticalArmaments.ANTIAIR_NAME else 255,
                commandRange=if(ship.number==1)org.logh7.engine.TacticalSupportRules.COMMAND_RANGE else 0f)
        }),
        0x030c to WorldMessages.crews(UnitSpecifications.crews.map { crew ->
            WorldMessages.Crew(crew.kind, crew.category, crew.category, crew.manual.training,
                crew.manual.groundAttack ?: 0, crew.manual.groundDefense ?: 0)
        }),
    )
}
