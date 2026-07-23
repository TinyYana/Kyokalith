package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

/** 候選方塊的一個鄰居面:是否屬於本次事件的 removedKeys、是否目前就已經是透明方塊。 */
data class NeighborExposure(val inRemovedKeys: Boolean, val liveTransparent: Boolean)

/**
 * 曝露時決算(誘餌模型)。
 *
 * 原版世界生成的礦物保留在世界資料中當「誘餌」:透視看得到,但完全埋藏的誘餌在
 * 首次曝露的那一刻才由決定性礦脈函數決算——f 命中換成真礦,未命中的誘餌換回基底石。
 * 已曝露過的方塊(世界生成就露出的洞穴壁、先前決算過的斷面)永不改動,因此正常
 * 玩家看得到的礦全部是真的,誘餌只會騙到隔著實心方塊偷看的透視。
 *
 * 全程沒有 chunk 掃描、沒有排程任務、沒有 ChunkLoadEvent 處理;只在主執行緒由
 * 方塊消失事件觸發,每次事件的成本上限是「消失方塊數 × 6 鄰居」的常數工作。
 */
class MaterializationService(private val plugin: KyokalithPlugin) {

    /**
     * removed:同一事件中消失(或被活塞搬離)的天然方塊。呼叫時機可以是移除「生效之前」
     * (事件當下、由 MONITOR 優先權的 listener 同一 tick 呼叫,見 MaterializationListener)
     * 或生效之後(next-tick fallback);newlyExposed 只靠 removedKeys 本身判定「即將透明」,
     * 不需要世界資料已經反映移除,兩種呼叫時機結果一致。dirty 的 removed 座標(玩家放置過)
     * 不觸發決算:它蓋住的東西在被蓋住之前必然已經曝露過,再挖開不能改變它——這就是
     * 「玩家把看到的礦蓋起來、之後再挖開,礦不會消失」的保護。
     */
    fun resolveRemoved(removed: Collection<Block>) {
        if (removed.isEmpty()) return
        val removedKeys = removed.mapTo(HashSet()) { PosKey(it.x, it.y, it.z) }
        val visited = HashSet<PosKey>()
        removed.forEach { origin ->
            if (isDirty(origin)) return@forEach
            NEIGHBORS.forEach { face ->
                val neighbor = origin.getRelative(face)
                val key = PosKey(neighbor.x, neighbor.y, neighbor.z)
                if (key in removedKeys || !visited.add(key)) return@forEach
                resolveIfNewlyExposed(neighbor, removedKeys)
            }
        }
    }

    /** 玩家放置/機制生成的座標永不實體化,之後挖開它也不觸發鄰居決算(§10)。 */
    fun markDirty(block: Block) {
        plugin.dirtyPositionStore.markDirty(epochedChunk(block), localPos(block))
    }

    /**
     * 只決算「本次事件才首次曝露」的方塊:它的每一個透明面都必須指向本次 removed
     * 集合。已有其他透明面 = 事件前就看得到(世界生成曝露或先前決算過),一律不動,
     * 避免可見牆面憑空長礦或真礦被抹掉。
     */
    private fun resolveIfNewlyExposed(block: Block, removedKeys: Set<PosKey>) {
        val current = block.type
        val decoyBase = if (current in BASE_BLOCKS) null else {
            if (!plugin.oreRegistry.isEnabledOreMaterial(current.name)) return
            nativeOreBase(current) ?: return
        }
        val base = decoyBase ?: current
        if (!newlyExposed(block, removedKeys)) return

        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        if (plugin.suspendedChunkStore.isSuspended(coord)) return
        if (isDirty(block)) return

        val epoch = plugin.chunkEpochStore.get(coord)
        val resolved = plugin.oreVeinResolver.resolve(
            block.world.name,
            epoch,
            block.x,
            block.y,
            block.z,
            base.name,
            block.world.environment.name,
        )?.material?.let { Material.matchMaterial(it) }
        // 石頭且 f 未命中 → 維持原樣;誘餌礦且 f 未命中 → 換回基底石
        val target = resolved ?: (decoyBase ?: return)
        if (target != current) block.setType(target, false)
    }

    /**
     * 讀鄰居目前的即時方塊狀態,轉成與呼叫時機無關的 [NeighborExposure],交給純函數
     * [isNewlyExposed] 判定。liveTransparent 讀的是「呼叫當下」的世界狀態——若在移除生效前
     * 呼叫(同一 tick 同步執行),origin 位置的 liveTransparent 會是 false,但 inRemovedKeys
     * 為 true 仍會被算成「即將透明」,判定結果與等到下一 tick 呼叫時完全一致。
     */
    private fun newlyExposed(block: Block, removedKeys: Set<PosKey>): Boolean {
        val neighbors = NEIGHBORS.map { face ->
            val neighbor = block.getRelative(face)
            val key = PosKey(neighbor.x, neighbor.y, neighbor.z)
            NeighborExposure(inRemovedKeys = key in removedKeys, liveTransparent = neighbor.type in EXPOSURE_BLOCKS)
        }
        return isNewlyExposed(neighbors)
    }

    private fun isDirty(block: Block): Boolean =
        plugin.dirtyPositionStore.isDirty(epochedChunk(block), localPos(block))

    private fun epochedChunk(block: Block): EpochedChunk {
        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        return EpochedChunk(coord.world, coord.cx, coord.cz, plugin.chunkEpochStore.get(coord))
    }

    private fun localPos(block: Block): LocalPos =
        LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))

    private data class PosKey(val x: Int, val y: Int, val z: Int)

    companion object {
        val BASE_BLOCKS: Set<Material> = setOf(Material.STONE, Material.DEEPSLATE, Material.NETHERRACK)

        private val EXPOSURE_BLOCKS: Set<Material> = setOf(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.WATER,
            Material.LAVA,
        )

        private val NEIGHBORS = listOf(
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
        )

        fun nativeOreBase(material: Material): Material? {
            val name = material.name
            return when {
                name.startsWith("DEEPSLATE_") -> Material.DEEPSLATE
                name.startsWith("NETHER_") || name == "ANCIENT_DEBRIS" -> Material.NETHERRACK
                name.endsWith("_ORE") -> Material.STONE
                else -> null
            }
        }

        /**
         * 純判定,不碰 Bukkit Block、不依賴呼叫時機:6 個鄰居中,只要有任一透明鄰居
         * 不屬於本次事件(inRemovedKeys=false 卻 liveTransparent=true),代表事件前就已經
         * 曝露過,一律不動,回傳 false。屬於本次事件的鄰居一律當作「即將/已經透明」計入
         * opened,不需要 liveTransparent 為真——這讓決算可以在移除實際生效「之前」
         * (事件當下、同一 tick)安全呼叫,而不必等到下一 tick 才能看見「移除已生效」的世界
         * 狀態,消除兩者之間的可見閃爍窗口(誘餌原貌被畫出來、下一 tick 才修正成真礦或石頭)。
         */
        fun isNewlyExposed(neighbors: List<NeighborExposure>): Boolean {
            var opened = 0
            neighbors.forEach { neighbor ->
                if (neighbor.inRemovedKeys || neighbor.liveTransparent) {
                    if (!neighbor.inRemovedKeys) return false
                    opened++
                }
            }
            return opened > 0
        }
    }
}
