package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

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
     * removed:同一事件中消失(或被活塞搬離)的天然方塊,呼叫時機是移除生效之後,
     * 這些座標當下已是空氣/流體。dirty 的 removed 座標(玩家放置過)不觸發決算:
     * 它蓋住的東西在被蓋住之前必然已經曝露過,再挖開不能改變它——這就是
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

    private fun newlyExposed(block: Block, removedKeys: Set<PosKey>): Boolean {
        var opened = 0
        NEIGHBORS.forEach { face ->
            val neighbor = block.getRelative(face)
            if (neighbor.type in EXPOSURE_BLOCKS) {
                if (PosKey(neighbor.x, neighbor.y, neighbor.z) !in removedKeys) return false
                opened++
            }
        }
        return opened > 0
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
    }
}
