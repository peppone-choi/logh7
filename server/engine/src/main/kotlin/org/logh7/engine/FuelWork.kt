package org.logh7.engine

import org.logh7.engine.strategy.*

/** First live adapter for the shared command pipeline. Time units remain an explicit local policy. */
internal object FuelWork {
    const val COMMAND = "operation/燃料補給"
    const val CAPTAIN = "captain"
    private val catalog by lazy { StrategyCatalogLoader.load().catalog }
    private val permissions = CardPermissions(mapOf(CAPTAIN to setOf(COMMAND)))
    fun pipeline(player: Navigator, policy: NavigationPolicy, hours: Long = 48) = StrategyPipeline(catalog, permissions,
        CommandResolver { CommandResolution(policy.fuelSupplyCost.toLong(), CpPool.MCP,
            8 * 3600_000L, hours * 3600_000L, 3600_000L) }, setOf(COMMAND),
        CpBalance(player.pcp.toLong(), player.mcp.toLong()), player.strategy ?: StrategyCheckpoint())
}
