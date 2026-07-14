package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreDefinition
import com.tinyyana.kyokalith.ore.OreRegistry
import java.util.Collections
import kotlin.math.max

data class OreResult(
    val oreType: String,
    val material: String,
    val veinId: String,
)

/**
 * 決定性礦脈函數,只做純計算;不讀寫世界、不碰資料庫。
 */
class OreVeinResolver(
    private val salt: String,
    private val registry: OreRegistry,
) {
    /**
     * cell 幾何快取(§8.5)。key 已含 epoch,chunk 重生後舊 epoch 的 entry 自然變成死條目,
     * 靠 LRU 淘汰即可,不需要主動失效。快取遺失不影響正確性,只是重新算一次。
     */
    private val cellCache = Collections.synchronizedMap(
        object : LinkedHashMap<CellKey, CandidateVein>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CellKey, CandidateVein>): Boolean =
                size > CELL_CACHE_MAX_ENTRIES
        },
    )

    fun resolve(
        world: String,
        epoch: Int,
        x: Int,
        y: Int,
        z: Int,
        baseMaterial: String,
        dimension: String = "NORMAL",
    ): OreResult? {
        val candidates = registry.enabled().mapNotNull { ore ->
            if (ore.dimension != dimension) return@mapNotNull null
            val material = materialForBase(ore, baseMaterial) ?: return@mapNotNull null
            if (y !in ore.yMin..ore.yMax) return@mapNotNull null
            hitForOre(world, epoch, x, y, z, ore)?.let { hit ->
                hit.copy(material = material)
            }
        }
        return candidates.minByOrNull { it.veinId }
    }

    private fun hitForOre(world: String, epoch: Int, x: Int, y: Int, z: Int, ore: OreDefinition): OreResult? {
        val cellX = floorCell(x)
        val cellY = floorCell(y)
        val cellZ = floorCell(z)
        var best: OreResult? = null
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val candidate = candidateFromCell(world, epoch, cellX + dx, cellY + dy, cellZ + dz, ore)
                    if (candidate.contains(x, y, z)) {
                        val result = OreResult(ore.oreType, "", candidate.id)
                        if (best == null || result.veinId < best.veinId) best = result
                    }
                }
            }
        }
        return best
    }

    private fun candidateFromCell(
        world: String,
        epoch: Int,
        cellX: Int,
        cellY: Int,
        cellZ: Int,
        ore: OreDefinition,
    ): CandidateVein {
        val cacheKey = CellKey(world, epoch, ore.oreType, cellX, cellY, cellZ)
        cellCache[cacheKey]?.let { return it }
        val computed = computeCandidateFromCell(world, epoch, cellX, cellY, cellZ, ore)
        cellCache[cacheKey] = computed
        return computed
    }

    private fun computeCandidateFromCell(
        world: String,
        epoch: Int,
        cellX: Int,
        cellY: Int,
        cellZ: Int,
        ore: OreDefinition,
    ): CandidateVein {
        val key = "$salt|$world|$epoch|${ore.oreType}|$cellX|$cellY|$cellZ"
        val seed = stableHash64(key)
        val cellCenterY = cellY * CELL_SIZE + CELL_SIZE / 2
        val weight = yWeight(cellCenterY, ore)
        val active = unit(seed) < (ore.cellChance * ore.density * weight).coerceIn(0.0, 1.0)
        val size = ore.veinSizeMin + positiveMod(mix(seed, 1), ore.veinSizeMax - ore.veinSizeMin + 1)
        val ox = cellX * CELL_SIZE + positiveMod(mix(seed, 2), CELL_SIZE)
        val oy = cellY * CELL_SIZE + positiveMod(mix(seed, 3), CELL_SIZE)
        val oz = cellZ * CELL_SIZE + positiveMod(mix(seed, 4), CELL_SIZE)
        // 硬上限,避免 config 誤設過大的 vein_size_max 時單一 cell 產生失控大礦脈。
        val radius = max(1, size / 2).coerceAtMost(MAX_VEIN_RADIUS)
        return CandidateVein(active, ox, oy, oz, radius, seed.toULong().toString(16))
    }

    /**
     * 三角分布權重,在 preferredY 為 1.0,往 y_min/y_max 兩端線性遞減到 0——
     * 原本 cell_chance 對整個 y_min..y_max 範圍套用同一機率,範圍動輒 300+ 格,
     * 導致礦物在任何單一 Y 層的密度都遠低於原版,且完全不會像原版一樣在特定深度形成礦帶。
     */
    private fun yWeight(y: Int, ore: OreDefinition): Double {
        val half = max(max(ore.preferredY - ore.yMin, ore.yMax - ore.preferredY), 1)
        val distance = Math.abs(y - ore.preferredY)
        return (1.0 - distance.toDouble() / half).coerceIn(0.0, 1.0)
    }

    private fun materialForBase(ore: OreDefinition, baseMaterial: String): String? =
        when (baseMaterial) {
            "DEEPSLATE" -> ore.deepslateMaterial
            "STONE", "NETHERRACK" -> ore.stoneMaterial
            else -> null
        }

    private data class CandidateVein(
        val active: Boolean,
        val ox: Int,
        val oy: Int,
        val oz: Int,
        val radius: Int,
        val id: String,
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean {
            if (!active) return false
            val dx = x - ox
            val dy = y - oy
            val dz = z - oz
            return dx * dx + dy * dy + dz * dz <= radius * radius
        }
    }

    private data class CellKey(
        val world: String,
        val epoch: Int,
        val oreType: String,
        val cellX: Int,
        val cellY: Int,
        val cellZ: Int,
    )

    companion object {
        private const val CELL_SIZE = 16
        private const val CELL_CACHE_MAX_ENTRIES = 200_000

        /** 半徑上限對應球體約 33 格(dx²+dy²+dz²<=4 的整數點數),與原版鐵/鑽石礦脈 8-10 格量級同一數量級。 */
        private const val MAX_VEIN_RADIUS = 2

        private fun floorCell(value: Int): Int = Math.floorDiv(value, CELL_SIZE)

        private fun positiveMod(value: Long, modulus: Int): Int = Math.floorMod(value, modulus.toLong()).toInt()

        private fun unit(value: Long): Double = (value ushr 11).toDouble() / (1L shl 53).toDouble()

        private fun mix(seed: Long, stream: Long): Long = splitMix64(seed + stream * 0x9E3779B97F4A7C15uL.toLong())

        private fun stableHash64(text: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (ch in text) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            return splitMix64(hash)
        }

        private fun splitMix64(input: Long): Long {
            var z = input + 0x9E3779B97F4A7C15uL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }
    }
}
