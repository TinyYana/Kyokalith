package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent

class ChunkScanListener(
    private val plugin: KyokalithPlugin,
    private val materialization: MaterializationService,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        if (materialization.isScanned(chunk)) return
        if (event.isNewChunk) {
            materialization.scanGeneratedChunk(chunk)
        } else if (plugin.config.getBoolean("materialization.strip_existing_chunks", true)) {
            materialization.stripExistingChunk(chunk)
        }
    }
}
