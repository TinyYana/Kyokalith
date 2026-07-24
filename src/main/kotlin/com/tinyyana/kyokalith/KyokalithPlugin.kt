package com.tinyyana.kyokalith

import com.tinyyana.kyokalith.chunk.ChunkEpochStore
import com.tinyyana.kyokalith.chunk.DirtyPositionStore
import com.tinyyana.kyokalith.chunk.SuspendedChunkStore
import com.tinyyana.kyokalith.command.KyoCommand
import com.tinyyana.kyokalith.db.KyokalithDatabase
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOreStore
import com.tinyyana.kyokalith.i18n.Messages
import com.tinyyana.kyokalith.integration.NatureReviveBridge
import com.tinyyana.kyokalith.materialization.MaterializationListener
import com.tinyyana.kyokalith.materialization.MaterializationService
import com.tinyyana.kyokalith.mining.OreEligibilityService
import com.tinyyana.kyokalith.mining.OreLifecycleListener
import com.tinyyana.kyokalith.ore.OreRegistry
import com.tinyyana.kyokalith.pdc.EligibleOrePdc
import com.tinyyana.kyokalith.schedule.Schedulers
import com.tinyyana.kyokalith.vein.MaterializedVeinStore
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
    lateinit var materializedVeinStore: MaterializedVeinStore
        private set
    lateinit var materializationService: MaterializationService
        private set
    lateinit var oreEligibilityService: OreEligibilityService
        private set
    lateinit var messages: Messages
        private set
    var natureReviveBridgeActive: Boolean = false
        private set

    private var cancelDirtyFlush: (() -> Unit)? = null

    override fun onEnable() {
        mergeConfigDefaults()
        Messages.saveBundledTemplates(this)
        messages = Messages.load(this, config.getString("locale", Messages.DEFAULT_LOCALE)!!)

        OreRegistry.load(config.getConfigurationSection("ores")).fold(
            onSuccess = { oreRegistry = it },
            onFailure = { e ->
                logger.severe("Failed to load ore config, disabling plugin: ${e.message}")
                server.pluginManager.disablePlugin(this)
                return
            },
        )

        database = KyokalithDatabase(File(dataFolder, config.getString("database.file", "kyokalith.db")!!))
        runCatching { database.init() }.onFailure { e ->
            logger.severe("Database initialization failed, disabling plugin: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        chunkEpochStore = ChunkEpochStore(database)
        dirtyPositionStore = DirtyPositionStore(database, logger)
        suspendedChunkStore = SuspendedChunkStore(database)
        eligiblePlacedOreStore = EligiblePlacedOreStore(database)
        eligibleOrePdc = EligibleOrePdc(this)
        oreVeinResolver = OreVeinResolver(database.getMeta("salt") ?: error("database salt missing"), oreRegistry)
        materializedVeinStore = MaterializedVeinStore(database)
        materializationService = MaterializationService(this)
        oreEligibilityService = OreEligibilityService(this)

        val flushIntervalTicks = config.getLong("database.dirty_flush_interval_ticks", 40L)
        cancelDirtyFlush = Schedulers.globalTimer(this, flushIntervalTicks, flushIntervalTicks) {
            dirtyPositionStore.flushAll()
        }

        KyoCommand(this).let { cmd ->
            getCommand("kyokalith")?.apply {
                setExecutor(cmd)
                tabCompleter = cmd
            }
        }
        server.pluginManager.registerEvents(MaterializationListener(this, materializationService), this)
        server.pluginManager.registerEvents(OreLifecycleListener(this, oreEligibilityService), this)
        natureReviveBridgeActive = NatureReviveBridge(this).register()
        logger.info(
            "Kyokalith ${description.version} enabled (decoy model: event-driven exposure resolution, silk/placed token lifecycle, OreCheckTriggerEvent available, no chunk scanning, NatureRevive bridge: ${if (natureReviveBridgeActive) "active" else "inactive"}, scheduler: ${if (Schedulers.isFolia) "Folia regionized" else "Bukkit main thread"})",
        )
    }

    override fun onDisable() {
        cancelDirtyFlush?.invoke()
        if (::dirtyPositionStore.isInitialized) dirtyPositionStore.flushAll()
    }

    private fun mergeConfigDefaults() {
        saveDefaultConfig()
        reloadConfig()
        config.options().copyDefaults(true)
        saveConfig()
    }
}
