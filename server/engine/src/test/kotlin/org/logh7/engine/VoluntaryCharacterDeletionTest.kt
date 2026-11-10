package org.logh7.engine

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class VoluntaryCharacterDeletionTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)), listOf(
        GalaxyBase(1049, 2487, 80, 0, Capital.EMPIRE.baseName, 0, 20f, 86400, 0, 0f, 2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
    private val stocks = PlanetStocks(1, 0, mapOf(1049 to PlanetStock(1049, 2, 0, 0, 0, 0)))
    private val player = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000,
        baseId = 1049, mode = 1, spotId = 10493414, spotOwner = 10000)
    private val countries = CountryCatalogLoader.current()

    @Test fun deletionRequiresAQualifiedGeneratedCharacterInTheirOwnRoom() {
        fun result(p: Navigator = player, rank: Int = 8, s: PlanetStocks = stocks) =
            VoluntaryCharacterDeletion.eligibility(galaxy, s, p, rank, countries)
        assertNull(result())
        assertEquals(CharacterDeletionResult.INELIGIBLE, result(rank = 7))
        assertEquals(CharacterDeletionResult.INELIGIBLE, result(player.copy(characterId = 1001, spotOwner = 1001)))
        assertEquals(CharacterDeletionResult.INELIGIBLE, result(player.copy(spotOwner = 10001)))
        assertEquals(CharacterDeletionResult.INELIGIBLE, result(player.copy(spotId = 10493402)))
        assertEquals(CharacterDeletionResult.BUSY, result(player.copy(cpPaused = true)))
        val promoted = stocks.copy(strategic = StrategicState(people = mapOf(10000 to StrategicPerson(rank = 7))))
        assertEquals(CharacterDeletionResult.INELIGIBLE, result(s = promoted))
    }

    @Test fun deletionReleasesAuthorityAndRecordsTheIdWithoutACasualty() {
        val assigned = stocks.copy(
            bases = stocks.bases.mapValues { (_, base) -> base.copy(governorId = 10000) },
            strategic = StrategicState(people = mapOf(10000 to StrategicPerson(rank = 8)),
                fiefs = mapOf(1049 to 10000)))
        val after = VoluntaryCharacterDeletion.remove(assigned, "pilot", 10000)
        assertEquals(0, after.bases.getValue(1049).governorId)
        assertTrue(after.strategic.fiefs.isEmpty())
        assertTrue(after.strategic.people.isEmpty())
        assertFalse(BattleDeath.deceased(after, 10000))
        assertEquals(mapOf("pilot" to setOf(10000)), after.deletedCharacters)
    }

    @Test fun residenceEntryRequiresTheAuthenticatedOwnerAndEnablesDeletion() {
        val room = galaxy.facilitiesAt(player.baseId, stocks).single { it.kind == 16 }.spots.single { it.kind == 12 }
        val session = SessionSimulation(SessionRules.load()).also {
            it.join("pilot", Power.EMPIRE, false, "link")
        }.exportState()
        val initial = player.copy(cpRecoveryAt = session.gameMillis / 1000)
        var saved = mapOf("pilot" to initial)
        val navigation = Navigation(galaxy, object : NavigationStore {
            override fun load() = saved
            override fun save(players: Map<String, Navigator>) { saved = players }
        }, NavigationPolicy())
        fun enter(owner: Int) = navigation.apply(
            NavigationRequest("pilot", player.characterId, Power.EMPIRE, "link", 1, place = PlaceMove.Spot(room.id, owner)),
            session, stocks)
        assertEquals(NavigationRejection.ACCESS, enter(10001).rejection)
        assertEquals(initial, saved["pilot"])
        val entered = assertNotNull(enter(0).player)
        assertEquals(room.id, entered.spotId)
        assertEquals(player.characterId, entered.spotOwner)
        assertEquals(990, entered.pcp)
        assertEquals(NavigationRejection.SPOT, enter(0).rejection)
        assertNull(VoluntaryCharacterDeletion.eligibility(galaxy, stocks, entered, 8, countries))
    }

    @Test fun worldCommitDeletesOnceOrKeepsTheWholeCharacterOnFailure() = runBlocking {
        for (fail in listOf(false, true)) {
            val startingPlayers = mapOf("pilot" to player)
            val startingStocks = stocks.copy(deploymentInitialized = true)
            var savedPlayers = startingPlayers
            var savedStocks = startingStocks
            var attempts = 0
            val engine = WorldEngine(this, ticking = false, galaxy = galaxy,
                navigationStore = object : NavigationStore {
                    override fun load() = savedPlayers
                    override fun save(players: Map<String, Navigator>) { savedPlayers = players }
                },
                stockStore = object : PlanetStockStore {
                    override fun load() = savedStocks
                    override fun save(stocks: PlanetStocks) { savedStocks = stocks }
                },
                worldStateCommitter = WorldStateCommitter { players, inventory ->
                    attempts++
                    if (fail) throw IOException("test storage failure")
                    savedPlayers = players
                    savedStocks = checkNotNull(inventory)
                }, npcPolicy = NpcTacticalPolicy(enabled = false))
            try {
                suspend fun join(id: Int, original: Boolean = false): Admission {
                    val result = CompletableDeferred<Admission>()
                    engine.submit(WorldCommand.Join("pilot", CountryId(2), original, result, "pilot-link", 1,
                        characterId = id))
                    return result.await()
                }
                suspend fun delete(): CharacterDeletionResult {
                    val result = CompletableDeferred<CharacterDeletionResult>()
                    engine.submit(WorldCommand.DeleteGeneratedCharacter("pilot", 1, player.characterId, 8, result))
                    return result.await()
                }
                assertEquals(Admission.ACCEPTED, join(player.characterId))
                assertEquals(CharacterDeletionResult.BUSY, delete())
                assertEquals(0, attempts)
                engine.submit(WorldCommand.Disconnect("pilot", "pilot-link"))
                if (fail) {
                    assertFailsWith<IOException> { delete() }
                    assertEquals(startingPlayers, savedPlayers)
                    assertTrue(savedStocks.deletedCharacters.isEmpty())
                    assertEquals(player, engine.snapshot().navigators["pilot"])
                    assertEquals(EngineHealth.STORAGE_FAILED, engine.health())
                } else {
                    assertEquals(CharacterDeletionResult.DELETED, delete())
                    assertEquals(CharacterDeletionResult.DELETED, delete())
                    assertTrue(savedPlayers.isEmpty())
                    assertEquals(setOf(player.characterId), savedStocks.deletedCharacters["pilot"])
                    assertEquals(Admission.IDENTITY_CHANGED, join(player.characterId))
                    assertEquals(Admission.ACCEPTED, join(1001, original = true))
                }
                assertEquals(1, attempts)
            } finally {
                engine.drainAndClose()
            }
        }
    }
}
