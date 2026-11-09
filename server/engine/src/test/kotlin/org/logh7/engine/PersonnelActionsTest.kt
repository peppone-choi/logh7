package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 실제 인사 결과와 래더/정원/직무 상실/봉토 상태를 검사한다. 작성: Peppone Choi. */
class PersonnelActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"Estate",0,30f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private val p=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val identities=listOf(StrategicIdentity(10000,2,2,0,List(8){50}),
        StrategicIdentity(10001,2,9,0,List(8){50}),StrategicIdentity(10002,2,9,0,List(8){50}))
    private fun stocks(card:Int=19):PlanetStocks {
        val initial=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1050 to PlanetStock(1050,2,0,100,200,300)),
            strategic=StrategicState(people=mapOf(10001 to StrategicPerson(merit=30),10002 to StrategicPerson(merit=10)),
                offices=listOf(OfficeHolding(4,1049,10001))))
        return policy.set(initial,OfficeHolding(card,1049,10000))
    }
    private fun request(command:Int,target:Int=10001,card:Int=19)=StrategyActionRequest("actor","c",10000,Power.EMPIRE,
        1,command,card,2,target=target,identities=identities)
    private fun act(r:StrategyActionRequest,s:PlanetStocks=stocks())=StrategyActions.evaluate(galaxy,r,session,p,s)
    @Test fun foreignAndUnattachedCharactersCannotReceiveDomesticPromotionsOrDistortTheLadder() {
        for(country in listOf(CountryId(1),CountryId(4),CountryId(42),null)) {
            val ids=identities.map {if(it.id==10001)it.copy(country=country) else it}
            val s=stocks()
            val denied=act(request(0).copy(identities=ids),s)
            assertEquals(StrategyActionRejection.IDENTITY,denied.rejection)
            assertEquals(s,denied.stocks);assertEquals(p,denied.player)
            assertNull(act(request(0,10002).copy(identities=ids),s).rejection,"foreign merit must not top our ladder")
        }
    }
    @Test fun deceasedTargetsAreRejectedAndDoNotLeadThePromotionLadder() {
        val original=stocks()
        val people=original.strategic.people
        val state=original.copy(strategic=original.strategic.copy(people=people+
            (10001 to people.getValue(10001).copy(deceasedAt=1))))
        val denied=act(request(0),state)
        assertEquals(StrategyActionRejection.IDENTITY,denied.rejection)
        assertEquals(state,denied.stocks)
        assertEquals(p,denied.player)
        val promoted=act(request(0,10002),state)
        assertNull(promoted.rejection)
        assertEquals(8,promoted.stocks?.strategic?.people?.get(10002)?.rank)
    }
    @Test fun promotionPreservesAnImperialCharactersAllianceShipStyle() {
        val target=Navigator(1,10001,CountryId(2),2487,1000,1000,1000,
            hull=FlagshipHull(93,0),shipStyle=ShipStyleId("alliance"))
        val result=act(request(0).copy(navigators=mapOf("target" to target)))
        assertNull(result.rejection)
        val promoted=assertNotNull(result.navigators["target"])
        assertEquals(CountryId(2),promoted.country);assertEquals(ShipStyleId("alliance"),promoted.shipStyle)
        assertEquals(Flagships.forType(3,2).kind,promoted.hull?.kind)
    }
    @Test fun promotionRequiresTheCurrentTopAndClearsOfficesWithMeritReset() {
        val s=stocks();val promoted=act(request(0),s)
        assertNull(promoted.rejection);assertEquals(840,promoted.player?.mcp)
        val stored=assertNotNull(promoted.stocks);val person=stored.strategic.people.getValue(10001)
        assertEquals(8,person.rank);assertEquals(0L,person.merit);assertTrue(policy.held(stored,10001).isEmpty())
        assertEquals(10L,stored.strategic.people.getValue(10002).merit)
        val denied=act(request(0,10002),s)
        assertEquals(StrategyActionRejection.RANK,denied.rejection);assertEquals(s,denied.stocks);assertEquals(p,denied.player)
    }
    @Test fun promotionRevokesTheOldFleetDelegationEvenAfterAppointmentToAnotherFleet() {
        val stack = UnitStack(ProductionUnits.definitions.first { it.ship && it.power == 2 }.kind)
        val oldDelegated = ActiveShipUnit(1100, stack)
        val oldOther = ActiveShipUnit(1101, stack)
        val detached = ActiveShipUnit(1200, stack)
        val old = OutfitStock(200, 2, OutfitKind.FLEET, 1049, 300, number = 1, commanderId = 10002, aideId = 10001,
            units = UnitInventory(mapOf(stack to 2)), activeShips = ActiveShipUnits(listOf(oldDelegated, oldOther)))
        val detachment = OutfitStock(201, 2, OutfitKind.FLEET, 1049, 301, number = 2,
            units = UnitInventory(mapOf(stack to 1)), activeShips = ActiveShipUnits(listOf(detached)))
        val new = OutfitStock(202, 2, OutfitKind.FLEET, 1049, 302, number = 3, commanderId = 10000)
        val outfits = listOf(old, detachment, new)
        val initial = stocks().copy(outfits = outfits.associateBy { it.id }, nextShipId = detached.id + 1,
            warehouses = outfits.associate { it.warehouseId to OutfitWarehouse(it.warehouseId, 2, it.kind, it.baseId, it.id) },
            tacticalCommands = TacticalCommandState(authority = mapOf(oldDelegated.id to 10001, oldOther.id to 10002, detached.id to 10001)))
        assertEquals(10001, TacticalAuthority.commander(old, oldDelegated, initial))

        val promotion = act(request(0), initial)
        assertNull(promotion.rejection)
        val promoted = assertNotNull(promotion.stocks)
        assertEquals(0, promoted.outfits.getValue(old.id).aideId)
        assertEquals(mapOf(oldOther.id to 10002, detached.id to 10001), promoted.tacticalCommands.authority)

        val appointment = GarrisonAppointmentRequest("actor", "c", 10000, Power.EMPIRE, 1, 0, 10001,
            FleetOffice.EMPIRE_AIDE.card, 5, 8, 2, outfit = new.id)
        val result = GarrisonAppointment.evaluate(galaxy, appointment, session, p, promoted)
        assertTrue(result.changed, result.rejection.toString())
        val appointed = assertNotNull(result.stocks)
        assertEquals(10001, appointed.outfits.getValue(new.id).aideId)
        assertEquals(10002, TacticalAuthority.commander(appointed.outfits.getValue(old.id), oldDelegated, appointed))
        assertEquals(10001, TacticalAuthority.commander(detachment, detached, appointed))
    }

    @Test fun promotionRaisesTheFlagshipToTheNewRankDefaultButNeverTradesItDown() {
        // U-24: rank 9 -> 8 is the cruiser band; a destroyer is replaced, a battleship is kept.
        val target=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000)
        fun promote(kind:Int)=act(request(0).copy(navigators=mapOf("target" to target.copy(hull=FlagshipHull(kind,0))))).navigators["target"]
        assertEquals(Flagships.forType(2,2).kind,assertNotNull(promote(3)).hull?.kind)
        assertNull(promote(0))
        assertNull(act(request(2,10002).copy(navigators=mapOf("target" to target.copy(characterId=10002,hull=FlagshipHull(3,0))))).navigators["target"])
    }
    @Test fun specialPromotionAndDemotionExcludeTheTopAndUseTheManualCosts() {
        val s=stocks()
        assertEquals(StrategyActionRejection.RANK,act(request(1),s).rejection)
        assertEquals(StrategyActionRejection.RANK,act(request(2),s).rejection)
        val selected=act(request(1,10002),s)
        assertNull(selected.rejection);assertEquals(360,selected.player?.mcp)
        assertEquals(8,selected.stocks?.strategic?.people?.get(10002)?.rank)
        val demoted=act(request(2,10002),s)
        assertNull(demoted.rejection);assertEquals(680,demoted.player?.mcp)
        assertEquals(10,demoted.stocks?.strategic?.people?.get(10002)?.rank)
        assertEquals(100L,demoted.stocks?.strategic?.people?.get(10002)?.merit)
        assertEquals(StrategyActionRejection.AUTHORITY,act(request(0,card=17),s).rejection)
    }
    @Test fun fullQuotaCannotBeBypassed() {
        val ids=listOf(StrategicIdentity(10000,2,2,0,List(8){50}),StrategicIdentity(10001,2,8,0,List(8){50}))+
            (20000..20079).map {StrategicIdentity(it,2,7,0,List(8){50})}
        val s=stocks(17)
        val denied=act(request(0,card=17).copy(identities=ids),s)
        assertEquals(StrategyActionRejection.MAXIMUM,denied.rejection);assertEquals(p,denied.player)
        val vacancy=s.copy(strategic=s.strategic.copy(people=s.strategic.people+(20000 to StrategicPerson(deceasedAt=1))))
        assertNull(act(request(0,card=17).copy(identities=ids),vacancy).rejection)
    }
    @Test fun sixPeeragesAreOrderedAndBaronOrHigherReceivesAnActualEstateCardOwner() {
        assertEquals(6,Peerage.entries.size)
        val ids=identities.map {if(it.id==10001)it.copy(rank=6) else it}
        val s=stocks(1)
        val grant=act(request(3,card=1).copy(value=Peerage.BARON.ordinal,identities=ids),s)
        assertNull(grant.rejection);assertEquals(Peerage.BARON,grant.stocks?.strategic?.people?.get(10001)?.peerage)
        val stored=assertNotNull(grant.stocks)
        val fief=act(request(8,card=1).copy(base=1050,identities=ids),stored)
        assertNull(fief.rejection);assertEquals(10001,fief.stocks?.strategic?.fiefs?.get(1050));assertEquals(360,fief.player?.mcp)
        val revoke=act(request(9,card=1).copy(base=1050,identities=ids),assertNotNull(fief.stocks))
        assertNull(revoke.rejection);assertTrue(assertNotNull(revoke.stocks).strategic.fiefs.isEmpty())
        val noNoble=act(request(3,card=1).copy(value=4,identities=ids.map {if(it.id==10001)it.copy(origin=1) else it}),s)
        assertEquals(StrategyActionRejection.RANK,noNoble.rejection)
    }
    @Test fun aDormantFiefUsesACardSlotButUpgradingATitleDoesNot() {
        val initial = stocks(1)
        val estates = (1051..1062).associateWith { id ->
            PlanetStock(id, 2, if (id == 1062) 1 else 0, 100, 200, 300)
        }
        val world = Galaxy(galaxy.systems, galaxy.bases + estates.keys.map { id ->
            galaxy.bases.first().copy(id = id, name = "Estate $id")
        }, galaxy.starts)
        val offices = OrganizationalOfficePolicy(world)
        val full = initial.copy(bases = initial.bases + estates, strategic = initial.strategic.copy(
            people = initial.strategic.people + (10001 to StrategicPerson(rank = 5, peerage = Peerage.BARON)),
            fiefs = estates.keys.associateWith { 10001 }))
        assertEquals(14, offices.cardCount(full, 10001))
        val ids = identities.map { if (it.id == 10001) it.copy(rank = 5) else it }
        val grant = request(8, card = 1).copy(base = 1050, identities = ids)
        fun evaluate(r: StrategyActionRequest, s: PlanetStocks) = StrategyActions.evaluate(world, r, session, p, s)
        val rejected = evaluate(grant, full)
        assertEquals(StrategyActionRejection.MAXIMUM, rejected.rejection)
        assertEquals(p, rejected.player)
        assertEquals(full, rejected.stocks)

        val upgraded = evaluate(request(3, card = 1).copy(value = Peerage.VISCOUNT.ordinal, identities = ids), full)
        assertNull(upgraded.rejection)
        val afterUpgrade = assertNotNull(upgraded.stocks)
        assertEquals(Peerage.VISCOUNT, afterUpgrade.strategic.people.getValue(10001).peerage)
        assertEquals(14, offices.cardCount(afterUpgrade, 10001))

        val room = full.copy(strategic = full.strategic.copy(fiefs = full.strategic.fiefs - 1051))
        val endowed = evaluate(grant, room)
        assertNull(endowed.rejection)
        val afterGrant = assertNotNull(endowed.stocks)
        assertEquals(10001, afterGrant.strategic.fiefs[1050])
        assertEquals(14, offices.cardCount(afterGrant, 10001))
    }

    @Test fun decorationsRequireStoredAuthorityAndMeritAndCannotBeChargedTwice() {
        val s=stocks(17).let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10001 to StrategicPerson(merit=100))))}
        val q=request(4,card=17).copy(identities=identities.map {if(it.id==10001)it.copy(rank=6) else it})
        val awarded=act(q,s);assertNull(awarded.rejection)
        assertEquals(setOf(0),awarded.stocks?.strategic?.people?.get(10001)?.decorations)
        val replay=act(q,assertNotNull(awarded.stocks))
        assertEquals(StrategyActionRejection.MAXIMUM,replay.rejection);assertEquals(p,replay.player)
    }
    @Test fun anEmpirePoliticianStaysOnItsMilitaryLadderAndIsPromotedThere() {
        // U-28 (CD manual p.37): 10001 retired from rank 9 as a politician that keeps rank 9's military ladder.
        val s=stocks().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+
            (10001 to StrategicPerson(rank=1,militaryRank=9,merit=30))))}
        val promoted=act(request(0),s);assertNull(promoted.rejection)
        val person=assertNotNull(promoted.stocks).strategic.people.getValue(10001)
        assertEquals(1,person.rank,"still a politician");assertEquals(8,person.militaryRank);assertEquals(0L,person.merit)
        assertEquals(StrategyActionRejection.RANK,act(request(0,10002),s).rejection,"the politician tops rank 9")
    }
}
