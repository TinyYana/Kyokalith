package com.tinyyana.kyokalith.integration

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.schedule.Schedulers
import org.bukkit.Chunk
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

class NatureReviveBridge(private val plugin: KyokalithPlugin) {
    fun register(): Boolean {
        val eventClass = runCatching {
            Class.forName("engineer.skyouo.plugins.naturerevive.spigot.events.ChunkRegenEvent")
                .asSubclass(Event::class.java)
        }.getOrNull() ?: return false

        val listener = object : Listener {}
        plugin.server.pluginManager.registerEvent(
            eventClass,
            listener,
            EventPriority.MONITOR,
            EventExecutor { _, event -> handle(event) },
            plugin,
            true,
        )
        return true
    }

    private fun handle(event: Event) {
        val chunk = runCatching { event.javaClass.getMethod("getChunk").invoke(event) as? Chunk }.getOrNull()
            ?: return
        Schedulers.atRegion(plugin, chunk.world, chunk.x, chunk.z) { handleRegeneratedChunk(chunk) }
    }

    private fun handleRegeneratedChunk(chunk: Chunk) {
        val coord = ChunkCoord(chunk.world.name, chunk.x, chunk.z)
        val oldEpoch = plugin.chunkEpochStore.get(coord)
        plugin.suspendedChunkStore.suspend(coord, "NatureRevive regeneration")
        var completed = false
        try {
            val nextEpoch = plugin.chunkEpochStore.increment(coord)
            plugin.dirtyPositionStore.clearEpoch(EpochedChunk(coord.world, coord.cx, coord.cz, oldEpoch))
            plugin.eligiblePlacedOreStore.removeInChunk(coord.world, coord.cx, coord.cz)
            plugin.logger.fine("NatureRevive regenerated ${coord.world} ${coord.cx},${coord.cz}; epoch $oldEpoch->$nextEpoch")
            completed = true
        } catch (e: Exception) {
            plugin.logger.severe("NatureRevive bridge failed for ${coord.world} ${coord.cx},${coord.cz}; chunk remains suspended: ${e.message}")
            return
        } finally {
            if (completed) plugin.suspendedChunkStore.resume(coord)
        }
        // 再生後不需要重掃:regen 會放回原版礦(= 新一輪誘餌),epoch+1 已讓 f 重洗
    }
}
