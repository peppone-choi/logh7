package org.logh7.engine

import org.logh7.engine.strategy.*

/** Explicit G-hour policy shared by the two live captain jobs. */
internal object NavigationWork {
    const val FUEL = "operation/燃料補給"
    const val DOCK = "operation/星系内航行"
    const val CAPTAIN = "captain"
    private val catalog by lazy { StrategyCatalogLoader.load().catalog }
    private val permissions = CardPermissions(mapOf(CAPTAIN to setOf(FUEL, DOCK)))
    fun pipeline(player: Navigator, policy: NavigationPolicy, fuelHours: Long = 48) = StrategyPipeline(catalog, permissions,
        CommandResolver { command -> CommandResolution(
            if (command.id == FUEL) policy.fuelSupplyCost.toLong() else 160, CpPool.MCP,
            8 * 3600_000L, if (command.id == FUEL) fuelHours * 3600_000L else 0, 3600_000L) },
        setOf(FUEL, DOCK), CpBalance(player.pcp.toLong(), player.mcp.toLong()), player.strategy ?: StrategyCheckpoint())
}
