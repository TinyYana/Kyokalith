package com.tinyyana.kyokalith.mining

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.materialization.MaterializationService
import org.bukkit.block.Block

class OreEligibilityService(private val plugin: KyokalithPlugin) {

    fun find(block: Block): EligibleOreBlock? {
        val materialName = block.type.name
        if (!plugin.oreRegistry.isOreMaterial(materialName)) return null

        val placed = plugin.eligiblePlacedOreStore.find(block.world.name, block.x, block.y, block.z)
        if (placed != null) {
            return EligibleOreBlock(EligibilitySource.PLACED_BLOCK, placed.oreType, placed.oreMaterial, placed.epoch)
        }

        val base = MaterializationService.nativeOreBase(block.type) ?: return null
        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        val epoch = plugin.chunkEpochStore.get(coord)
        val epoched = EpochedChunk(coord.world, coord.cx, coord.cz, epoch)
        val local = LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))
        if (plugin.dirtyPositionStore.isDirty(epoched, local)) return null

        val result = plugin.oreVeinResolver.resolve(
            block.world.name,
            epoch,
            block.x,
            block.y,
            block.z,
            base.name,
            block.world.environment.name,
        ) ?: return null
        if (result.material != materialName) return null
        return EligibleOreBlock(EligibilitySource.NATURAL_BLOCK, result.oreType, result.material, epoch)
    }
}
