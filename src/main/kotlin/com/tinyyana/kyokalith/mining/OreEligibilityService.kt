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
        if (!plugin.oreRegistry.isEnabledOreMaterial(materialName)) return null

        val placed = plugin.eligiblePlacedOreStore.find(block.world.name, block.x, block.y, block.z)
        if (placed != null) {
            return EligibleOreBlock(EligibilitySource.PLACED_BLOCK, placed.oreType, placed.oreMaterial, placed.epoch)
        }

        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        val epoch = plugin.chunkEpochStore.get(coord)
        val epoched = EpochedChunk(coord.world, coord.cx, coord.cz, epoch)
        val local = LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))
        if (plugin.dirtyPositionStore.isDirty(epoched, local)) return null

        val base = MaterializationService.nativeOreBase(block.type)
        val result = base?.let {
            plugin.oreVeinResolver.resolve(
                block.world.name,
                epoch,
                block.x,
                block.y,
                block.z,
                it.name,
                block.world.environment.name,
            )
        }
        if (result != null && result.material == materialName) {
            return EligibleOreBlock(EligibilitySource.NATURAL_BLOCK, result.oreType, result.material, epoch)
        }

        // 2026-07-16 拍板:世界生成就曝露的原生礦(從沒經過首次曝露決算,f 自然對不上礦種)
        // 對 xray 零情報價值,一樣是真礦,只是不是 Kyokalith 決算產出的——也給 eligible,
        // 否則玩家挖既有洞穴/礦脈幾乎骰不到檢定(見 docs/KYOKALITH_SPEC.md §11.2)。
        val oreType = plugin.oreRegistry.oreTypeForEnabledMaterial(materialName) ?: return null
        return EligibleOreBlock(EligibilitySource.WORLDGEN_EXPOSED, oreType, materialName, epoch)
    }
}
