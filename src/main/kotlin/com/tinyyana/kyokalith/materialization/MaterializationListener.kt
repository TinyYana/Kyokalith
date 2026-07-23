package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.schedule.Schedulers
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
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
 *
 * 決算優先在事件當下、同一 tick 內(用 [Schedulers.atRegionNow])執行:目前執行緒已經
 * 擁有目標座標(Folia 上的 region owner、或 Spigot/Paper 的主執行緒——事件觸發時一律成立)
 * 就直接跑,只有跨 region 才退回下一 tick。這條路徑之所以安全,是因為
 * MaterializationService.isNewlyExposed 只靠事件本身的 removedKeys 判定「即將透明」,
 * 不需要世界資料已經反映移除;拖到下一 tick 才決算,會讓誘餌真面目先被畫給客戶端看一次
 * (世界生成的埋藏誘餌本來就在 chunk 封包裡,移除擋住它的方塊後,用戶端會馬上依快取資料
 * 算面剔除、直接顯示誘餌原貌),下一 tick 才修正成真礦或石頭時就會被玩家看到「礦物消失/
 * 變成別的礦」的閃爍——這正是「魚骨挖礦看到礦物從面前消失」回報的根因。
 *
 * 所有會觸發 resolveRemoved 的 handler 都掛在 [org.bukkit.event.EventPriority.MONITOR]:
 * 同一 tick 執行代表我們是在事件的移除**生效前**讀取 removedKeys,所以必須確定沒有其他
 * 插件會在我們之後才取消事件——MONITOR 保證輪到我們時 isCancelled() 已是最終結果,
 * `ignoreCancelled = true` 才能可靠地擋掉「插件事後取消,但我們已經誤決算鄰居」的情況。
 */
class MaterializationListener(
    private val plugin: KyokalithPlugin,
    private val materialization: MaterializationService,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        resolveLater(listOf(event.block))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        // 所有玩家放置都標 dirty:放置的位置永不再進決算(f 是確定性的,防的不是重骰,
        // 是「已被玩家看過/蓋過的方塊不該再被改動」),也讓「挖開自己放的方塊」不觸發鄰居決算,
        // 這是蓋住可見礦再挖開時礦不消失的保護的一半(另一半在 resolveRemoved 的 dirty 閘)
        materialization.markDirty(event.blockPlaced)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (event.newState.type in MaterializationService.BASE_BLOCKS) {
            markDirtyLater(event.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.to.isAir) {
            resolveLater(listOf(event.block))
        } else if (event.to in MaterializationService.BASE_BLOCKS) {
            markDirtyLater(event.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        resolveLater(event.blockList().toList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        resolveLater(event.blockList().toList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        resolveLater(listOf(event.block))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        onPistonMove(event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
        atFirstBlockRegion(moved) {
            destinations.forEach { materialization.markDirty(it) }
            materialization.resolveRemoved(moved)
        }
    }

    /** 名稱沿用舊稱,但實際上優先同一 tick 執行,見 atFirstBlockRegion。 */
    private fun resolveLater(removed: List<Block>) {
        if (removed.isEmpty()) return
        atFirstBlockRegion(removed) { materialization.resolveRemoved(removed) }
    }

    /** 這裡沒有「即將透明」的 removedKeys 可以信任(只是標 dirty),維持原本的下一 tick 語意即可。 */
    private fun markDirtyLater(block: Block) {
        Schedulers.atRegion(plugin, block.location) { materialization.markDirty(block) }
    }

    /**
     * 目前執行緒已經擁有 blocks.first() 的座標(事件觸發時的常態)就同一 tick 內直接跑,
     * 避免下一 tick 才決算造成的可見閃爍;只有跨 region 的少見情形才退回下一 tick。
     */
    private fun atFirstBlockRegion(blocks: List<Block>, task: () -> Unit) {
        Schedulers.atRegionNow(plugin, blocks.first().location) { task() }
    }
}
