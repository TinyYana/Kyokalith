package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.persistence.PersistentDataType

/**
 * 單點曝露檢查與實體化。只在主執行緒呼叫;不做全區掃描。
 */
class MaterializationService(private val plugin: KyokalithPlugin) {
    private val activeScans = mutableSetOf<String>()
    private val scannedKey = NamespacedKey(plugin, "chunk_scanned")

    /**
     * 一個 chunk 只需完整掃描一次(生成期掃描或既有 chunk strip 遷移)。
     * 沒有這個標記,chunk 每次 unload/reload(玩家移動造成的正常行為)都會重新全高度掃描,
     * 不但违反 §9.5「禁止定期全區掃描」,還會把已經實體化的 Kyokalith 礦重新 strip 回石頭。
     */
    fun isScanned(chunk: Chunk): Boolean =
        chunk.persistentDataContainer.get(scannedKey, PersistentDataType.BYTE) == 1.toByte()

    private fun markScanned(chunk: Chunk) {
        chunk.persistentDataContainer.set(scannedKey, PersistentDataType.BYTE, 1)
    }

    /** NatureRevive 再生後該 chunk 需要重新掃描一次,見 §13.2。 */
    fun clearScanned(chunk: Chunk) {
        chunk.persistentDataContainer.remove(scannedKey)
    }

    fun tryMaterialize(block: Block): Boolean {
        val base = block.type
        if (base !in BASE_BLOCKS) return false
        if (plugin.oreRegistry.isOreMaterial(base.name)) return false
        if (!isExposed(block)) return false

        val coord = ChunkCoord(block.world.name, block.x.floorChunk(), block.z.floorChunk())
        if (plugin.suspendedChunkStore.isSuspended(coord)) return false

        val epoch = plugin.chunkEpochStore.get(coord)
        val epoched = EpochedChunk(coord.world, coord.cx, coord.cz, epoch)
        val local = LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))
        if (plugin.dirtyPositionStore.isDirty(epoched, local)) return false

        val result = plugin.oreVeinResolver.resolve(block.world.name, epoch, block.x, block.y, block.z, base.name)
            ?: return false
        val material = Material.matchMaterial(result.material) ?: return false
        block.setType(material, false)
        return true
    }

    fun markDirty(block: Block) {
        if (block.type !in BASE_BLOCKS && !plugin.oreRegistry.isOreMaterial(block.type.name)) return
        val coord = ChunkCoord(block.world.name, block.x.floorChunk(), block.z.floorChunk())
        val epoch = plugin.chunkEpochStore.get(coord)
        plugin.dirtyPositionStore.markDirty(
            EpochedChunk(coord.world, coord.cx, coord.cz, epoch),
            LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16)),
        )
    }

    fun checkNeighbors(block: Block) {
        NEIGHBORS.forEach { tryMaterialize(block.getRelative(it)) }
    }

    fun scanGeneratedChunk(chunk: Chunk) {
        scanChunkBatched(chunk, materialize = true, scanNeighborShells = true)
    }

    fun stripExistingChunk(chunk: Chunk) {
        scanChunkBatched(chunk, materialize = false, scanNeighborShells = false)
    }

    fun stripLoadedChunks() {
        if (!plugin.config.getBoolean("materialization.strip_existing_chunks", true)) return
        plugin.server.worlds.forEach { world ->
            world.loadedChunks.forEach { if (!isScanned(it)) stripExistingChunk(it) }
        }
    }

    fun stripNativeOre(block: Block): Boolean {
        if (!plugin.oreRegistry.isOreMaterial(block.type.name)) return false
        val base = nativeOreBase(block.type) ?: return false
        block.setType(base, false)
        return true
    }

    private fun scanChunkBatched(chunk: Chunk, materialize: Boolean, scanNeighborShells: Boolean) {
        if (isScanned(chunk)) return
        val world = chunk.world
        val scanKey = "${world.uid}:${chunk.x}:${chunk.z}:$materialize:$scanNeighborShells"
        if (!activeScans.add(scanKey)) return

        val minY = world.minHeight
        val maxY = world.maxHeight
        val bodyTotal = 16 * 16 * (maxY - minY)
        val shells = if (scanNeighborShells) loadedShells(chunk) else emptyList()
        val shellSize = 16 * (maxY - minY)
        val blockBudget = plugin.config.getInt("materialization.scan_blocks_per_tick", 1024).coerceAtLeast(256)
        var bodyIndex = 0
        var shellIndex = 0
        var taskId = -1

        taskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (!world.isChunkLoaded(chunk.x, chunk.z)) {
                activeScans.remove(scanKey)
                plugin.server.scheduler.cancelTask(taskId)
                return@Runnable
            }

            var processed = 0
            while (processed < blockBudget && bodyIndex < bodyTotal) {
                val y = minY + bodyIndex / 256
                val inLayer = bodyIndex % 256
                val x = inLayer % 16
                val z = inLayer / 16
                processScannedBlock(chunk.getBlock(x, y, z), materialize)
                bodyIndex++
                processed++
            }

            val shellTotal = shells.size * shellSize
            while (processed < blockBudget && bodyIndex >= bodyTotal && shellIndex < shellTotal) {
                blockForShell(shells[shellIndex / shellSize], shellIndex % shellSize, minY)
                    ?.let { tryMaterialize(it) }
                shellIndex++
                processed++
            }

            if (bodyIndex >= bodyTotal && shellIndex >= shellTotal) {
                markScanned(chunk)
                activeScans.remove(scanKey)
                plugin.server.scheduler.cancelTask(taskId)
            }
        }, 1L, 1L)
    }

    private fun processScannedBlock(block: Block, materialize: Boolean) {
        stripNativeOre(block)
        if (materialize) tryMaterialize(block)
    }

    private fun loadedShells(chunk: Chunk): List<ShellScan> {
        val world = chunk.world
        return listOfNotNull(
            shellIfLoaded(world, chunk.x - 1, chunk.z, shellX = 15, shellZ = null),
            shellIfLoaded(world, chunk.x + 1, chunk.z, shellX = 0, shellZ = null),
            shellIfLoaded(world, chunk.x, chunk.z - 1, shellX = null, shellZ = 15),
            shellIfLoaded(world, chunk.x, chunk.z + 1, shellX = null, shellZ = 0),
        )
    }

    private fun shellIfLoaded(
        world: World,
        chunkX: Int,
        chunkZ: Int,
        shellX: Int?,
        shellZ: Int?,
    ): ShellScan? {
        if (!world.isChunkLoaded(chunkX, chunkZ)) return null
        return ShellScan(world.getChunkAt(chunkX, chunkZ), shellX, shellZ)
    }

    private fun blockForShell(shell: ShellScan, index: Int, minY: Int): Block? {
        val chunk = shell.chunk
        if (!chunk.world.isChunkLoaded(chunk.x, chunk.z)) return null
        val y = minY + index / 16
        val offset = index % 16
        return if (shell.shellX != null) {
            chunk.getBlock(shell.shellX, y, offset)
        } else {
            chunk.getBlock(offset, y, shell.shellZ!!)
        }
    }

    private fun isExposed(block: Block): Boolean =
        NEIGHBORS.any { block.getRelative(it).type in EXPOSURE_BLOCKS }

    private fun Int.floorChunk(): Int = Math.floorDiv(this, 16)

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

    private data class ShellScan(
        val chunk: Chunk,
        val shellX: Int?,
        val shellZ: Int?,
    )
}
