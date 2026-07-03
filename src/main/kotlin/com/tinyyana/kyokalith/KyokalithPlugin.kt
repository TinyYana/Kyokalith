package com.tinyyana.kyokalith

import com.tinyyana.kyokalith.chunk.ChunkEpochStore
import com.tinyyana.kyokalith.chunk.DirtyPositionStore
import com.tinyyana.kyokalith.chunk.SuspendedChunkStore
import com.tinyyana.kyokalith.command.KyoCommand
import com.tinyyana.kyokalith.db.KyokalithDatabase
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOreStore
import com.tinyyana.kyokalith.integration.NatureReviveBridge
import com.tinyyana.kyokalith.materialization.ChunkScanListener
import com.tinyyana.kyokalith.materialization.MaterializationListener
import com.tinyyana.kyokalith.materialization.MaterializationService
import com.tinyyana.kyokalith.mining.OreEligibilityService
import com.tinyyana.kyokalith.mining.OreLifecycleListener
import com.tinyyana.kyokalith.ore.OreRegistry
import com.tinyyana.kyokalith.pdc.EligibleOrePdc
import com.tinyyana.kyokalith.vein.OreVeinResolver
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class KyokalithPlugin : JavaPlugin() {

    lateinit var database: KyokalithDatabase
        private set
    lateinit var oreRegistry: OreRegistry
        private set
    lateinit var chunkEpochStore: ChunkEpochStore
        private set
    lateinit var dirtyPositionStore: DirtyPositionStore
        private set
    lateinit var suspendedChunkStore: SuspendedChunkStore
        private set
    lateinit var eligiblePlacedOreStore: EligiblePlacedOreStore
        private set
    lateinit var eligibleOrePdc: EligibleOrePdc
        private set
    lateinit var oreVeinResolver: OreVeinResolver
        private set
    lateinit var materializationService: MaterializationService
        private set
    lateinit var oreEligibilityService: OreEligibilityService
        private set
    var natureReviveBridgeActive: Boolean = false
        private set

    private var dirtyFlushTaskId = -1

    override fun onEnable() {
        mergeConfigDefaults()

        OreRegistry.load(config.getConfigurationSection("ores")).fold(
            onSuccess = { oreRegistry = it },
            onFailure = { e ->
                logger.severe("礦種設定載入失敗,停用插件:${e.message}")
                server.pluginManager.disablePlugin(this)
                return
            },
        )

        database = KyokalithDatabase(File(dataFolder, config.getString("database.file", "kyokalith.db")!!))
        runCatching { database.init() }.onFailure { e ->
            logger.severe("資料庫初始化失敗,停用插件:${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        chunkEpochStore = ChunkEpochStore(database)
        dirtyPositionStore = DirtyPositionStore(database)
        suspendedChunkStore = SuspendedChunkStore(database)
        eligiblePlacedOreStore = EligiblePlacedOreStore(database)
        eligibleOrePdc = EligibleOrePdc(this)
        oreVeinResolver = OreVeinResolver(database.getMeta("salt") ?: error("database salt missing"), oreRegistry)
        materializationService = MaterializationService(this)
        oreEligibilityService = OreEligibilityService(this)

        val flushIntervalTicks = config.getLong("database.dirty_flush_interval_ticks", 40L)
        dirtyFlushTaskId = server.scheduler.scheduleSyncRepeatingTask(
            this,
            { dirtyPositionStore.flushAll() },
            flushIntervalTicks,
            flushIntervalTicks,
        )

        getCommand("kyokalith")?.setExecutor(KyoCommand(this))
        server.pluginManager.registerEvents(MaterializationListener(this, materializationService), this)
        server.pluginManager.registerEvents(ChunkScanListener(this, materializationService), this)
        server.pluginManager.registerEvents(OreLifecycleListener(this, oreEligibilityService), this)
        natureReviveBridgeActive = NatureReviveBridge(this, materializationService).register()
        materializationService.stripLoadedChunks()
        logger.info(
            "Kyokalith ${pluginMeta.version} enabled(新 chunk 掃描、曝露實體化、Silk/placed token 生命週期、OreCheckTriggerEvent 可用,NatureRevive bridge:${if (natureReviveBridgeActive) "active" else "inactive"})",
        )
    }

    override fun onDisable() {
        if (dirtyFlushTaskId != -1) server.scheduler.cancelTask(dirtyFlushTaskId)
        if (::dirtyPositionStore.isInitialized) dirtyPositionStore.flushAll()
    }

    private fun mergeConfigDefaults() {
        saveDefaultConfig()
        reloadConfig()
        config.options().copyDefaults(true)
        saveConfig()
    }
}
