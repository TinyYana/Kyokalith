package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.vein.MaterializedVein
import com.tinyyana.kyokalith.vein.ResolvedVein
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
     *
     * 決算前先查 [MaterializedVeinStore][com.tinyyana.kyokalith.vein.MaterializedVeinStore]
     * 有沒有這個座標已鎖定的結果——有就直接套用(不重算、不重寫);沒有才呼叫礦脈函數即時
     * 決算,見 [resolveAndLock]。**這裡的持久化只決定「下次曝露到這個座標時該長什麼」,
     * 對還沒曝露的座標本身永遠不呼叫 `setType`——只有正在處理的 [block](本次事件當下才
     * 真正首次曝露的那一個)才會被 setType,鄰域鎖定的其他座標只寫進資料庫/記憶體快取,
     * 物理世界方塊維持原狀不變,直到它們自己未來真正首次曝露的那一刻。**
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
        val epoched = EpochedChunk(coord.world, coord.cx, coord.cz, epoch)
        val local = localPos(block)

        val lockedMaterial = plugin.materializedVeinStore.find(epoched, local)?.material
        val target = (
            if (lockedMaterial != null) {
                Material.matchMaterial(lockedMaterial)
            } else {
                resolveAndLock(block, base, decoyBase, epoched)
            }
            ) ?: return
        if (target != current) block.setType(target, false)
    }

    /**
     * 沒有鎖定紀錄的座標:呼叫礦脈函數即時決算。
     *
     * miss(候選球不存在,f 對這個座標沒有任何礦種命中)不寫入 materialized_positions——
     * 世界方塊狀態本身已經是永久記錄(`isNewlyExposed` 保證同一座標不會被決算第二次),
     * 補一筆 miss 記錄不會多一層保障,卻會讓每次挖空石都變成一次同步 SQLite 寫入,直接
     * 打破 §15.1「熱路徑無 DB I/O」的紅線(這系統裡大多數的挖掘都是 miss)。
     *
     * hit 則鎖定觸發座標與其 5×5×5 局部鄰域內、屬於同一顆候選球(用 [ResolvedVein.ball]
     * 的 contains 判斷)、尚未曝露且不與其他決算表衝突的座標,一次性批次寫入(單一 SQL
     * transaction,見 [MaterializedVeinStore.upsertAll][com.tinyyana.kyokalith.vein.MaterializedVeinStore.upsertAll])。
     * 寫入失敗時整批放棄鄰域鎖定,只試著單獨鎖定觸發座標本身;再失敗就保守停手,回傳 null
     * 讓呼叫端完全不改動方塊(KYOKALITH_SPEC.md §9.4:決算了卻沒記錄成功,會破壞「不得
     * 再次決算」的保證,所以寧可這次不套用,也不能決算後不落地)。
     *
     * 注意:批次裡的每一筆(包含鄰域內判定為 miss 的座標)都只寫入資料庫/記憶體快取,
     * 不會對任何尚未曝露的鄰居呼叫 `setType`——回傳值只給觸發座標 [block] 本身使用。
     */
    private fun resolveAndLock(block: Block, base: Material, decoyBase: Material?, epoched: EpochedChunk): Material? {
        val world = block.world
        val detailed = plugin.oreVeinResolver.resolveDetailed(
            world.name, epoched.epoch, block.x, block.y, block.z, base.name, world.environment.name,
        ) ?: return decoyBase ?: base // miss:不寫資料庫,直接回傳基底材質

        val triggerMaterial = Material.matchMaterial(detailed.result.material) ?: return decoyBase ?: base
        val triggerLocal = localPos(block)
        val entries = LinkedHashMap<LocalPos, MaterializedVein>()
        entries[triggerLocal] = MaterializedVein(detailed.result.oreType, detailed.result.veinId, detailed.result.material)
        entries.putAll(collectNeighborhood(block, detailed, epoched))

        val locked = plugin.materializedVeinStore.upsertAll(epoched, entries) ||
            plugin.materializedVeinStore.upsertAll(epoched, mapOf(triggerLocal to entries.getValue(triggerLocal)))
        return if (locked) triggerMaterial else null
    }

    /**
     * 觸發座標的 5×5×5 局部鄰域(半徑 2,至多 124 個額外座標,常數上限——不掃整顆礦脈的
     * 理論延伸範圍,也不掃整個 chunk)。只鎖定同時滿足下列全部條件的鄰居:
     *
     * 1. 落在贏得觸發座標的那顆候選球([ResolvedVein.ball])範圍內——「同一顆礦脈」。
     * 2. 與觸發座標同一個 chunk(半徑 2 理論上可能跨 chunk 邊界;跨界的部分留給該
     *    chunk 自己未來的首次曝露事件處理,不在這裡展開成多個 chunk/epoch 的批次)。
     * 3. 在世界高度範圍內、目前是可決算材質(base block 或已啟用礦種的誘餌材質)。
     * 4. 未曾曝露過,包含世界生成當下就曝露的情況(六個面目前都不透明)。
     * 5. 不在 dirty positions、不是已知的 placed eligible ore、還沒被鎖定過。
     *
     * 通過的鄰居各自呼叫一次 `resolve()`(不是直接沿用觸發座標的結果)——鄰居自己的
     * base 材質、Y 邊界、跨礦種優先序都可能與觸發座標不同,必須獨立決算,球體 contains
     * 只是用來限定「值得鎖定」的範圍,不是「answer 一定相同」的捷徑。
     */
    private fun collectNeighborhood(
        origin: Block,
        detailed: ResolvedVein,
        epoched: EpochedChunk,
    ): Map<LocalPos, MaterializedVein> {
        val world = origin.world
        val originCoord = ChunkCoord(world.name, Math.floorDiv(origin.x, 16), Math.floorDiv(origin.z, 16))
        val result = LinkedHashMap<LocalPos, MaterializedVein>()
        for ((dx, dy, dz) in neighborhoodOffsets()) {
            val nx = origin.x + dx
            val ny = origin.y + dy
            val nz = origin.z + dz
            if (!detailed.ball.contains(nx, ny, nz)) continue
            if (ny < world.minHeight || ny >= world.maxHeight) continue
            val nCoord = ChunkCoord(world.name, Math.floorDiv(nx, 16), Math.floorDiv(nz, 16))
            if (nCoord != originCoord) continue // 跨 chunk 留給該 chunk 未來自己的首次曝露事件

            val nLocal = LocalPos(Math.floorMod(nx, 16), ny, Math.floorMod(nz, 16))
            if (plugin.materializedVeinStore.find(epoched, nLocal) != null) continue // 已鎖定過
            if (plugin.dirtyPositionStore.isDirty(epoched, nLocal)) continue
            if (plugin.eligiblePlacedOreStore.find(world.name, nx, ny, nz) != null) continue

            val nBlock = world.getBlockAt(nx, ny, nz)
            if (hasAnyExposedFace(nBlock)) continue // 已曝露過(含世界生成當下),不可改動

            val nCurrent = nBlock.type
            val nDecoyBase = if (nCurrent in BASE_BLOCKS) null else {
                if (!plugin.oreRegistry.isEnabledOreMaterial(nCurrent.name)) continue
                nativeOreBase(nCurrent) ?: continue
            }
            val nBase = nDecoyBase ?: nCurrent
            val nResolved = plugin.oreVeinResolver.resolve(
                world.name, epoched.epoch, nx, ny, nz, nBase.name, world.environment.name,
            )
            val nMaterial = nResolved?.material ?: nBase.name
            result[nLocal] = MaterializedVein(nResolved?.oreType, nResolved?.veinId, nMaterial)
        }
        return result
    }

    /** 目前世界狀態下,這個座標是否已經有任一面透明(=事件前就看得到,不可再改動)。 */
    private fun hasAnyExposedFace(block: Block): Boolean =
        NEIGHBORS.any { face -> block.getRelative(face).type in EXPOSURE_BLOCKS }

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
        /**
         * 5×5×5 局部鄰域預決算的半徑上限,與 [com.tinyyana.kyokalith.vein.OreVeinResolver] 的
         * 礦脈候選球半徑上限（`MAX_VEIN_RADIUS`）**故意分開、互不牽動**:即使礦脈本身的候選球
         * 半徑之後再調整,單一首次曝露事件一次鎖定的鄰域工作量永遠是這個常數(至多 124 個
         * 額外座標),不會因為礦脈變大就跟著掃描更大範圍——超出這個視窗的礦脈其餘部分,
         * 留給玩家未來自然挖到時的下一次首次曝露事件處理。
         */
        private const val NEIGHBORHOOD_RADIUS = 2

        /**
         * 5×5×5 局部鄰域的相對座標偏移(不含 0,0,0 本身),純函數、不碰 Bukkit,方便單元測試
         * 直接驗證「至多 124 個額外座標」這個硬上限,不需要 MockBukkit 或真正的 World/Block。
         */
        fun neighborhoodOffsets(): List<Triple<Int, Int, Int>> = buildList {
            for (dx in -NEIGHBORHOOD_RADIUS..NEIGHBORHOOD_RADIUS) {
                for (dy in -NEIGHBORHOOD_RADIUS..NEIGHBORHOOD_RADIUS) {
                    for (dz in -NEIGHBORHOOD_RADIUS..NEIGHBORHOOD_RADIUS) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        add(Triple(dx, dy, dz))
                    }
                }
            }
        }

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
