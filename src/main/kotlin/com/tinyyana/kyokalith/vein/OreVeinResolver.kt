package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreDefinition
import com.tinyyana.kyokalith.ore.OreRegistry
import java.util.Collections
import kotlin.math.max

data class OreResult(
    val oreType: String,
    val material: String,
    val veinId: String,
    /** 決算贏得這個座標的礦種 priority(見 OreDefinition.priority),供 /kyo inspect 顯示仲裁依據。 */
    val priority: Int,
)

data class VeinPosition(val x: Int, val y: Int, val z: Int)

/** 一顆 veinId 的完整、嚴格有界形狀。 */
data class VeinShape(val positions: Set<VeinPosition>) {
    val blockCount: Int get() = positions.size
    fun contains(x: Int, y: Int, z: Int): Boolean = VeinPosition(x, y, z) in positions
}

/** [resolve] 的完整版本,額外附上贏得該座標的完整礦脈形狀。 */
data class ResolvedVein(val result: OreResult, val shape: VeinShape)

/**
 * 決定性礦脈函數,只做純計算;不讀寫世界、不碰資料庫。
 */
class OreVeinResolver(
    private val salt: String,
    private val registry: OreRegistry,
) {
    /** 裸露原生礦只提供入口；後方延續大小與走向仍由私有 salt 決定，不沿用可由世界 seed 預測的形狀。 */
    fun worldgenContinuationSize(world: String, epoch: Int, oreType: String, origin: VeinPosition): Int {
        val ore = requireNotNull(registry[oreType]) { "unknown ore type: $oreType" }
        val seed = stableHash64("$salt|worldgen|$world|$epoch|$oreType|${origin.x}|${origin.y}|${origin.z}")
        return ore.veinSizeMin + positiveMod(seed, ore.veinSizeMax - ore.veinSizeMin + 1)
    }

    fun worldgenContinuationRank(
        world: String,
        epoch: Int,
        oreType: String,
        origin: VeinPosition,
        position: VeinPosition,
    ): Long = stableHash64(
        "$salt|worldgen-rank|$world|$epoch|$oreType|${origin.x}|${origin.y}|${origin.z}|" +
            "${position.x}|${position.y}|${position.z}",
    )

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

    /** 只回傳最終贏得該座標的礦物結果;跨礦種仲裁見 [resolveDetailed]。 */
    fun resolve(
        world: String,
        epoch: Int,
        x: Int,
        y: Int,
        z: Int,
        baseMaterial: String,
        dimension: String = "NORMAL",
    ): OreResult? = resolveDetailed(world, epoch, x, y, z, baseMaterial, dimension)?.result

    /**
     * 決算並附上贏得該座標的完整礦脈形狀，讓 MaterializationService 在首次命中時
     * 一次鎖定完整 veinId，而不是靠移動視窗逐段推進。
     *
     * 跨礦種重疊時,依 [OreDefinition.priority] 由大到小排序決定贏家;priority 相同才退回
     * veinId 字典序(僅用於讓結果穩定可重現,無維運意義)。
     */
    fun resolveDetailed(
        world: String,
        epoch: Int,
        x: Int,
        y: Int,
        z: Int,
        baseMaterial: String,
        dimension: String = "NORMAL",
    ): ResolvedVein? {
        val hits = registry.enabled().mapNotNull { ore ->
            if (ore.dimension != dimension) return@mapNotNull null
            val material = materialForBase(ore, baseMaterial) ?: return@mapNotNull null
            if (y !in ore.yMin..ore.yMax) return@mapNotNull null
            hitForOre(world, epoch, x, y, z, baseMaterial, ore)?.let { hit ->
                hit.copy(result = hit.result.copy(material = material))
            }
        }
        return hits
            .sortedWith(compareByDescending<OreHit> { it.result.priority }.thenBy { it.result.veinId })
            .firstOrNull()
            ?.let { ResolvedVein(it.result, it.shape) }
    }

    private data class OreHit(val result: OreResult, val shape: VeinShape)

    private fun hitForOre(
        world: String,
        epoch: Int,
        x: Int,
        y: Int,
        z: Int,
        baseMaterial: String,
        ore: OreDefinition,
    ): OreHit? {
        val cellX = floorCell(x)
        val cellY = floorCell(y)
        val cellZ = floorCell(z)
        val candidate = candidateFromCell(world, epoch, cellX, cellY, cellZ, ore)
        if (!candidate.contains(x, y, z) ||
            !isAcceptedSameOre(world, epoch, cellX, cellY, cellZ, ore, candidate) ||
            !winsCrossOreArbitration(world, epoch, cellX, cellY, cellZ, baseMaterial, ore, candidate)
        ) {
            return null
        }
        return OreHit(
            OreResult(ore.oreType, "", candidate.id, ore.priority),
            VeinShape(candidate.positions),
        )
    }

    /**
     * 每個 cell 最多一顆候選。相鄰 cell 的同礦種形狀若六面相連,只保留 veinId 較小者;
     * 因此任何最終同礦種連通元件都只可能包含一個 veinId。
     */
    private fun isAcceptedSameOre(
        world: String,
        epoch: Int,
        cellX: Int,
        cellY: Int,
        cellZ: Int,
        ore: OreDefinition,
        candidate: CandidateVein,
    ): Boolean =
        CELL_NEIGHBORS.none { (dx, dy, dz) ->
            val other = candidateFromCell(world, epoch, cellX + dx, cellY + dy, cellZ + dz, ore)
            other.id < candidate.id && candidate.touches(other)
        }

    /**
     * 跨礦種 priority 以整顆形狀為單位仲裁。若只覆蓋重疊座標，原本連通的低 priority
     * 礦脈可能被削成玩家只挖到 1–2 格的殘片；整顆淘汰可保證每個存活 veinId 仍完整連通。
     * 所有形狀都限制在自己的 cell，因此只需比較同一個 cell 的其他礦種候選。
     */
    private fun winsCrossOreArbitration(
        world: String,
        epoch: Int,
        cellX: Int,
        cellY: Int,
        cellZ: Int,
        baseMaterial: String,
        ore: OreDefinition,
        candidate: CandidateVein,
    ): Boolean {
        val contenders = registry.enabled()
            .asSequence()
            .filter { it.dimension == ore.dimension }
            .filter { materialForBase(it, baseMaterial) != null }
            .map { otherOre ->
                otherOre to candidateFromCell(world, epoch, cellX, cellY, cellZ, otherOre)
            }
            .filter { (otherOre, other) ->
                other.positions.isNotEmpty() &&
                    isAcceptedSameOre(world, epoch, cellX, cellY, cellZ, otherOre, other)
            }
            .sortedWith(
                compareByDescending<Pair<OreDefinition, CandidateVein>> { it.first.priority }
                    .thenBy { it.second.id },
            )

        val accepted = ArrayList<CandidateVein>()
        contenders.forEach { (otherOre, other) ->
            if (accepted.any(other::overlaps)) return@forEach
            if (otherOre.oreType == ore.oreType && other.id == candidate.id) return true
            accepted += other
        }
        return false
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
        val id = seed.toULong().toString(16)

        val cellMinY = cellY * CELL_SIZE
        val allowedMinY = max(cellMinY, ore.yMin)
        val allowedMaxY = minOf(cellMinY + CELL_SIZE - 1, ore.yMax)
        if (allowedMinY > allowedMaxY) return CandidateVein(emptySet(), id)

        val origin = VeinPosition(
            cellX * CELL_SIZE + positiveMod(mix(seed, 2), CELL_SIZE),
            allowedMinY + positiveMod(mix(seed, 3), allowedMaxY - allowedMinY + 1),
            cellZ * CELL_SIZE + positiveMod(mix(seed, 4), CELL_SIZE),
        )
        val cellFill = (allowedMaxY - allowedMinY + 1).toDouble() / CELL_SIZE
        val active = unit(seed) <
            (ore.cellChance * ore.density * yWeight(origin.y, ore) * cellFill).coerceIn(0.0, 1.0)
        if (!active) return CandidateVein(emptySet(), id)

        val size = ore.veinSizeMin + positiveMod(mix(seed, 1), ore.veinSizeMax - ore.veinSizeMin + 1)
        return CandidateVein(growShape(seed, origin, size, cellX, cellZ, allowedMinY, allowedMaxY), id)
    }

    /** 決定性 frontier growth:每次只從既有方塊的六面鄰居擴張,所以形狀永遠連通且恰為 size 格。 */
    private fun growShape(
        seed: Long,
        origin: VeinPosition,
        size: Int,
        cellX: Int,
        cellZ: Int,
        allowedMinY: Int,
        allowedMaxY: Int,
    ): Set<VeinPosition> {
        val cellMinX = cellX * CELL_SIZE
        val cellMinZ = cellZ * CELL_SIZE
        val selected = linkedSetOf(origin)
        val frontier = HashSet<VeinPosition>()

        fun addFrontier(position: VeinPosition) {
            BLOCK_NEIGHBORS.forEach { (dx, dy, dz) ->
                val next = VeinPosition(position.x + dx, position.y + dy, position.z + dz)
                if (next.x !in cellMinX until cellMinX + CELL_SIZE ||
                    next.y !in allowedMinY..allowedMaxY ||
                    next.z !in cellMinZ until cellMinZ + CELL_SIZE ||
                    next in selected
                ) {
                    return@forEach
                }
                frontier += next
            }
        }

        addFrontier(origin)
        while (selected.size < size) {
            val next = frontier.minWithOrNull(
                compareBy<VeinPosition> { mix(seed, packedLocal(it.x, it.y, it.z)) }
                    .thenBy { it.x }
                    .thenBy { it.y }
                    .thenBy { it.z },
            ) ?: error("vein_size=$size 超過單一 cell 可生成的形狀容量")
            frontier.remove(next)
            selected += next
            addFrontier(next)
        }
        return selected
    }

    /** bundled ores 可用分段線性曲線表達雙峰/平台/均勻分布;舊 config 仍退回單峰三角形。 */
    private fun yWeight(y: Int, ore: OreDefinition): Double {
        val points = ore.yWeightPoints
        if (points.isNotEmpty()) {
            if (y <= points.first().y) return points.first().weight
            if (y >= points.last().y) return points.last().weight
            val rightIndex = points.indexOfFirst { it.y >= y }
            val left = points[rightIndex - 1]
            val right = points[rightIndex]
            val progress = (y - left.y).toDouble() / (right.y - left.y)
            return left.weight + (right.weight - left.weight) * progress
        }
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
        val positions: Set<VeinPosition>,
        val id: String,
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean = VeinPosition(x, y, z) in positions

        fun touches(other: CandidateVein): Boolean =
            positions.any { position ->
                BLOCK_NEIGHBORS.any { (dx, dy, dz) ->
                    VeinPosition(position.x + dx, position.y + dy, position.z + dz) in other.positions
                }
            }

        fun overlaps(other: CandidateVein): Boolean = positions.any(other.positions::contains)
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
        /**
         * 8³ cell 讓小礦脈能更常出現；若維持 16³，即使 cell_chance=1，沿直線挖掘仍會
         * 因候選中心太疏而出現極長空窗。8 可整除 chunk 寬度，跨 chunk/負座標語意不變。
         */
        private const val CELL_SIZE = 8
        // Shapes carry up to 32 positions; keep the old cache from multiplying that object graph without bound.
        private const val CELL_CACHE_MAX_ENTRIES = 20_000

        private val CELL_NEIGHBORS = listOf(
            Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0),
            Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
        )
        private val BLOCK_NEIGHBORS = CELL_NEIGHBORS

        private fun floorCell(value: Int): Int = Math.floorDiv(value, CELL_SIZE)

        private fun positiveMod(value: Long, modulus: Int): Int = Math.floorMod(value, modulus.toLong()).toInt()

        private fun packedLocal(x: Int, y: Int, z: Int): Long =
            ((Math.floorMod(x, CELL_SIZE) shl 8) or
                (Math.floorMod(y, CELL_SIZE) shl 4) or
                Math.floorMod(z, CELL_SIZE)).toLong() + 5

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
