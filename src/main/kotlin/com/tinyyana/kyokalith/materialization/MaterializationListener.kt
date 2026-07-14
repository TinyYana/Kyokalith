package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent

/**
 * 把「天然方塊消失」的事件收斂到 MaterializationService.resolveRemoved,
 * 「方塊被放入/生成」的事件收斂到 markDirty。
 * 決算一律排到下一 tick,讓移除先生效、透明狀態反映現實。
 */
class MaterializationListener(
    private val plugin: KyokalithPlugin,
    private val materialization: MaterializationService,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        resolveLater(listOf(event.block))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        // 所有玩家放置都標 dirty:除了防重骰,也讓「挖開自己放的方塊」不觸發鄰居決算,
        // 這是蓋住可見礦再挖開時礦不消失的保護的一半(另一半在 resolveRemoved 的 dirty 閘)
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
        if (event.to.isAir) {
            resolveLater(listOf(event.block))
        } else if (event.to in MaterializationService.BASE_BLOCKS) {
            plugin.server.scheduler.runTask(plugin, Runnable { materialization.markDirty(event.block) })
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        resolveLater(event.blockList().toList())
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        resolveLater(event.blockList().toList())
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        resolveLater(listOf(event.block))
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        onPistonMove(event.blocks, event.direction)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        onPistonMove(event.blocks, event.direction)
    }

    /**
     * 活塞搬移:舊座標視為消失觸發決算(擋住「用活塞抽走覆蓋方塊、不觸發決算就看到誘餌」),
     * 新座標視為機制放置標 dirty。被其他搬移方塊回填的舊座標不透明,決算自然不會誤判。
     */
    private fun onPistonMove(moved: List<Block>, direction: BlockFace) {
        if (moved.isEmpty()) return
        val destinations = moved.map { it.getRelative(direction) }
        plugin.server.scheduler.runTask(plugin, Runnable {
            destinations.forEach { materialization.markDirty(it) }
            materialization.resolveRemoved(moved)
        })
    }

    private fun resolveLater(removed: List<Block>) {
        if (removed.isEmpty()) return
        plugin.server.scheduler.runTask(plugin, Runnable { materialization.resolveRemoved(removed) })
    }
}
