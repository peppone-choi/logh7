package org.logh7.engine

/** NPC cabinet and production-office appointments at the monthly review (evidence:guess). */
internal object NpcGovernmentAppointments {
    private const val EMPIRE_GOVERNOR = 54
    private const val ALLIANCE_STATE_CHAIR = 134
    private const val ALLIANCE_RESOURCES_CHAIR = 139
    private const val ALLIANCE_GOVERNOR = 190
    private const val ALLIANCE_CAPITAL_ADMINISTRATOR = 192
    private const val EMPIRE_GROUND_COMMANDER = 50
    private val empireFormation = InitialGovernment.EMPIRE_AUXILIARY_FORMATION
    val refreshedCards = setOf(EMPIRE_GOVERNOR, ALLIANCE_STATE_CHAIR, ALLIANCE_GOVERNOR, ALLIANCE_CAPITAL_ADMINISTRATOR,
        InitialGovernment.EMPIRE_MILITARY_MINISTER, 22, empireFormation,
        InitialGovernment.EMPIRE_GRENADIER_INSPECTOR, EMPIRE_GROUND_COMMANDER)

    fun settle(galaxy: Galaxy, stocks: PlanetStocks, identities: List<StrategicIdentity>,
        players: Collection<Navigator>, eliminated: Set<CountryId>): PlanetStocks {
        val npc = InitialGovernment.roster(stocks).associateBy { it.id }
        if (npc.isEmpty() || players.isEmpty()) return stocks
        val catalog = CountryCatalogLoader.current()
        val policy = OrganizationalOfficePolicy(galaxy, catalog)
        val registered = identities.associateBy { it.id }
        val candidates = players.filter { player ->
            val country = player.country
            player.generation == stocks.generation && country != null && country !in eliminated &&
                registered[player.characterId]?.country == country && player.characterId !in npc &&
                player.baseId > 0 && !player.cpPaused && !player.navigationPending
        }.sortedWith(compareByDescending<Navigator> { TacticalPersonnel.merit(stocks, it.characterId) }.thenBy { it.characterId })

        fun available(s: PlanetStocks, id: Int, country: CountryId): Boolean =
            !BattleDeath.deceased(s, id) && !s.social.justice.detained(id) && !CoupActions.rebel(s, id, country)

        fun canFill(s: PlanetStocks, role: OrganizationalOffice, seat: Int, id: Int, fallbackRank: Int): Boolean =
            available(s, id, role.country) && policy.active(s, role, seat) &&
                ProposalActions.rank(s, id, role.country, fallbackRank, catalog).let { rank ->
                    policy.officeRank(s, role, id, rank) in role.ranks
                } && policy.cardCount(s, id) < 14 && policy.canTakeAnySeat(s, role, id, listOf(seat)) &&
                policy.holders(s, role.card, role.country).count { it.seat == seat } < role.seats

        fun appoint(s: PlanetStocks, role: OrganizationalOffice, seat: Int, id: Int, fallbackRank: Int): PlanetStocks {
            if (!canFill(s, role, seat, id, fallbackRank)) return s
            val authority = role.authority ?: return s
            val country = role.country
            val rank = ProposalActions.rank(s, id, country, fallbackRank, catalog)
            val appointer = policy.holders(s, authority, country).any { holder ->
                val official = npc[holder.character]?.takeIf { it.country == country } ?: return@any false
                val actorRank = ProposalActions.rank(s, official.id, country, official.rank, catalog)
                available(s, official.id, country) && policy.authorized(s, role, seat, official.id, actorRank) &&
                    policy.eligible(s, role, official.id, actorRank, id, rank)
            }
            return if (appointer) policy.set(s, OfficeHolding(role.card, seat, id, country)) else s
        }

        var next = stocks
        for (player in candidates) {
            val id = player.characterId
            val country = checkNotNull(player.country)
            val rank = registered.getValue(id).rank
            if (policy.held(next, id).any { it.card in GoalActions.governanceCards } ||
                id in next.strategic.fiefs.values) continue
            val capital = policy.capital(next, country) ?: continue
            val card = when (country) {
                Power.EMPIRE.country -> EMPIRE_GOVERNOR
                Power.ALLIANCE.country -> if (player.baseId == capital) ALLIANCE_CAPITAL_ADMINISTRATOR else ALLIANCE_GOVERNOR
                else -> continue
            }
            val role = policy.role(card, country) ?: continue
            if (country == Power.ALLIANCE.country && canFill(next, role, player.baseId, id, rank) &&
                policy.holders(next, ALLIANCE_STATE_CHAIR, country).isEmpty() &&
                policy.holders(next, PoliticianLadder.CHAIRMAN_CARD, country).any { it.character == InitialGovernment.ALLIANCE_CHAIR } &&
                policy.holders(next, ALLIANCE_RESOURCES_CHAIR, country).any { it.character == InitialGovernment.ALLIANCE_RESOURCES_CHAIR }) {
                val stateChair = policy.role(ALLIANCE_STATE_CHAIR, country)
                if (stateChair != null) next = appoint(next, stateChair, capital, InitialGovernment.ALLIANCE_RESOURCES_CHAIR, 1)
            }
            next = appoint(next, role, player.baseId, id, rank)
        }
        val empire = Power.EMPIRE.country
        val formation = policy.role(empireFormation, empire)
        val capital = policy.capital(next, empire)
        if (formation != null && capital != null && LogisticsOfficePolicy(galaxy, catalog).seat(LogisticsOffice.EMPIRE_SECOND, next) != null) {
            for (player in candidates) {
                if (player.country != empire || player.baseId == capital || player.mode !in 1..4 ||
                    next.bases[player.baseId]?.let { it.belongsTo(empire) && it.camp == 0 } != true ||
                    OutfitFormationPolicy.available(next, player.baseId, catalog.shipStyle(empire), OutfitKind.GROUND)
                        .none { (code, count) -> count > 0 && OutfitFormationPolicy.stack(code).first }) continue
                val appointed = appoint(next, formation, capital, player.characterId, registered.getValue(player.characterId).rank)
                if (appointed !== next) {
                    next = appointed
                    break
                }
            }
        }
        val groundCommand = policy.role(EMPIRE_GROUND_COMMANDER, empire)
        if (groundCommand != null && policy.holders(next, InitialGovernment.EMPIRE_GRENADIER_INSPECTOR, empire).isNotEmpty()) {
            val vacancies = next.outfits.values.filter { it.country == empire && it.kind == OutfitKind.GROUND &&
                it.commanderId == 0 && it.activeGround?.values.orEmpty().isNotEmpty() }
                .groupBy { it.positionIn(galaxy) }
            for (player in candidates) {
                if (player.country != empire || player.mode !in 1..4 ||
                    next.bases[player.baseId]?.let { it.belongsTo(empire) && it.camp == 0 } != true) continue
                val rank = registered.getValue(player.characterId).rank
                for (outfit in vacancies[OutfitPosition(player.grid, player.baseId)].orEmpty().sortedBy { it.id }) {
                    val appointed = appoint(next, groundCommand, outfit.id, player.characterId, rank)
                    if (appointed !== next) {
                        next = appointed
                        break
                    }
                }
            }
        }
        return next
    }
}
