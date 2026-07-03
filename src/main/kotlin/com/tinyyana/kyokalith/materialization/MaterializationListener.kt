package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent

class MaterializationListener(
    private val plugin: KyokalithPlugin,
    private val materialization: MaterializationService,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        plugin.server.scheduler.runTask(plugin, Runnable { materialization.checkNeighbors(block) })
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        materialization.markDirty(event.blockPlaced)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (event.newState.type in MaterializationService.BASE_BLOCKS) {
            plugin.server.scheduler.runTask(plugin, Runnable { materialization.markDirty(event.block) })
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.to in MaterializationService.BASE_BLOCKS) {
            plugin.server.scheduler.runTask(plugin, Runnable { materialization.markDirty(event.block) })
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val blocks = event.blockList().toList()
        plugin.server.scheduler.runTask(plugin, Runnable {
            blocks.forEach { materialization.checkNeighbors(it) }
        })
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val blocks = event.blockList().toList()
        plugin.server.scheduler.runTask(plugin, Runnable {
            blocks.forEach { materialization.checkNeighbors(it) }
        })
    }
}
