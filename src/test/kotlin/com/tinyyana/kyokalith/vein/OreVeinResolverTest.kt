package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreRegistry
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OreVeinResolverTest {

    private fun registry(): OreRegistry {
        val config = YamlConfiguration()
        config.set("ores.test.enabled", true)
        config.set("ores.test.materials.stone", "IRON_ORE")
        config.set("ores.test.materials.deepslate", "DEEPSLATE_IRON_ORE")
        config.set("ores.test.y_min", -64)
        config.set("ores.test.y_max", 320)
        config.set("ores.test.preferred_y", 0)
        config.set("ores.test.density", 1.0)
        config.set("ores.test.vein_size_min", 32)
        config.set("ores.test.vein_size_max", 32)
        config.set("ores.test.cell_chance", 1.0)
        return OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow()
    }

    /**
     * 有界 voxel shape 只佔 cell 一小部分,不能再靠超大 vein_size 保證固定座標命中。
     * 改成在多個 cell 內掃描找出第一個真實命中座標,
     * 命中一定存在(cell_chance=1.0、density=1.0、preferred_y=0 使 y=0 附近 weight≈1)。
     */
    private fun findHitCoordinate(
        resolver: OreVeinResolver,
        epoch: Int = 0,
        baseMaterial: String = "STONE",
        dimension: String = "NORMAL",
    ): Triple<Int, Int, Int> {
        // "cell 是否啟用" 由 cell 座標的雜湊決定,不是每個 cell 都會 active(cell_chance 只是機率上限)。
        // 掃描範圍涵蓋多個相鄰 cell(而不只是一個),確保至少有一個 active cell 可以命中。
        for (x in 0 until 32) {
            for (y in -32 until 32) {
                for (z in 0 until 32) {
                    if (resolver.resolve("world", epoch, x, y, z, baseMaterial, dimension) != null) {
                        return Triple(x, y, z)
                    }
                }
            }
        }
        error("測試設定下掃描範圍內找不到任何命中,vein 幾何或測試假設可能不成立")
    }

    @Test
    fun `same coordinate resolves deterministically`() {
        val resolver = OreVeinResolver("salt", registry())
        val (x, y, z) = findHitCoordinate(resolver)

        val first = resolver.resolve("world", 0, x, y, z, "STONE")
        val second = resolver.resolve("world", 0, x, y, z, "STONE")

        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `worldgen continuation order is deterministic and salt protected`() {
        val origin = VeinPosition(-1, 16, 0)
        val positions = listOf(
            VeinPosition(0, 16, 0),
            VeinPosition(-2, 16, 0),
            VeinPosition(-1, 17, 0),
            VeinPosition(-1, 16, 1),
        )
        val first = OreVeinResolver("private-salt-a", registry())
        val second = OreVeinResolver("private-salt-b", registry())
        fun ranks(resolver: OreVeinResolver) = positions.map {
            resolver.worldgenContinuationRank("world", 0, "test", origin, it)
        }

        assertEquals(ranks(first), ranks(first))
        assertNotEquals(ranks(first), ranks(second), "world seed 相同但 salt 不同時，裸礦後方走向必須不同")
        assertEquals(32, first.worldgenContinuationSize("world", 0, "test", origin))
    }

    @Test
    fun `epoch participates in the vein function`() {
        val resolver = OreVeinResolver("salt", registry())

        val (x0, y0, z0) = findHitCoordinate(resolver, epoch = 0)
        val (x1, y1, z1) = findHitCoordinate(resolver, epoch = 1)
        val epoch0 = resolver.resolve("world", 0, x0, y0, z0, "STONE")
        val epoch1 = resolver.resolve("world", 1, x1, y1, z1, "STONE")

        assertNotEquals(epoch0?.veinId, epoch1?.veinId)
    }

    @Test
    fun `base material controls ore material`() {
        val resolver = OreVeinResolver("salt", registry())
        val (x, y, z) = findHitCoordinate(resolver)

        assertEquals("IRON_ORE", resolver.resolve("world", 0, x, y, z, "STONE")?.material)
        assertEquals("DEEPSLATE_IRON_ORE", resolver.resolve("world", 0, x, y, z, "DEEPSLATE")?.material)
        assertNull(resolver.resolve("world", 0, x, y, z, "DIRT"))
    }

    @Test
    fun `ore never resolves outside its y range even with full density`() {
        val resolver = OreVeinResolver("salt", registry())

        // y_max in registry() is 320; 400 is outside y_min..y_max entirely.
        assertNull(resolver.resolve("world", 0, 10, 400, 30, "STONE"))
    }

    @Test
    fun `y farthest from preferred_y never resolves even at full cell chance`() {
        // preferred_y = y_min puts the triangular weight's zero point at y_max; the weight
        // is only exactly 0 right at that edge, but with a huge range the neighbouring cell's
        // residual weight rounds down to a probability far below what any test run could hit.
        val config = YamlConfiguration()
        config.set("ores.test.enabled", true)
        config.set("ores.test.materials.stone", "IRON_ORE")
        config.set("ores.test.y_min", -2_000_000)
        config.set("ores.test.y_max", 2_000_000)
        config.set("ores.test.preferred_y", -2_000_000)
        config.set("ores.test.density", 1.0)
        config.set("ores.test.vein_size_min", 1)
        config.set("ores.test.vein_size_max", 32)
        config.set("ores.test.cell_chance", 1.0)
        val resolver = OreVeinResolver("salt", OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow())

        repeat(20) { epoch ->
            assertNull(resolver.resolve("world", epoch, epoch * 16, 2_000_000, epoch * 16, "STONE"))
        }
    }

    @Test
    fun `nether-only ore never resolves when queried from the overworld`() {
        val config = YamlConfiguration()
        config.set("ores.nether_quartz.enabled", true)
        config.set("ores.nether_quartz.dimension", "NETHER")
        config.set("ores.nether_quartz.materials.stone", "NETHER_QUARTZ_ORE")
        config.set("ores.nether_quartz.y_min", -64)
        config.set("ores.nether_quartz.y_max", 320)
        config.set("ores.nether_quartz.preferred_y", 0)
        config.set("ores.nether_quartz.density", 1.0)
        config.set("ores.nether_quartz.vein_size_min", 32)
        config.set("ores.nether_quartz.vein_size_max", 32)
        config.set("ores.nether_quartz.cell_chance", 1.0)
        val resolver = OreVeinResolver("salt", OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow())
        val (x, y, z) = findHitCoordinate(resolver, dimension = "NETHER")

        assertNull(resolver.resolve("world", 0, x, y, z, "STONE", dimension = "NORMAL"))
        assertNotNull(resolver.resolve("world", 0, x, y, z, "STONE", dimension = "NETHER"))
    }

    /**
     * 覆蓋跨礦種重疊時的原子仲裁:兩個礦種都用 cell_chance=1.0 與 size=32,掃描找出
     * 形狀重疊的 cell。priority 較高者保留完整形狀；低 priority 候選整顆淘汰，
     * 不可在重疊邊緣留下只有 1–2 格的殘脈。
     */
    @Test
    fun `higher priority ore wins an overlap and the winner is reproducible`() {
        fun singleOreRegistry(oreType: String, material: String, priority: Int): OreRegistry {
            val config = YamlConfiguration()
            config.set("ores.$oreType.enabled", true)
            config.set("ores.$oreType.materials.stone", material)
            config.set("ores.$oreType.y_min", -64)
            config.set("ores.$oreType.y_max", 64)
            config.set("ores.$oreType.preferred_y", 0)
            config.set("ores.$oreType.density", 1.0)
            config.set("ores.$oreType.vein_size_min", 32)
            config.set("ores.$oreType.vein_size_max", 32)
            config.set("ores.$oreType.cell_chance", 1.0)
            config.set("ores.$oreType.priority", priority)
            return OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow()
        }

        val commonOnly = OreVeinResolver("salt", singleOreRegistry("common", "IRON_ORE", priority = 10))
        val rareOnly = OreVeinResolver("salt", singleOreRegistry("rare", "DIAMOND_ORE", priority = 90))

        val combinedConfig = YamlConfiguration()
        combinedConfig.set("ores.common.enabled", true)
        combinedConfig.set("ores.common.materials.stone", "IRON_ORE")
        combinedConfig.set("ores.common.y_min", -64)
        combinedConfig.set("ores.common.y_max", 64)
        combinedConfig.set("ores.common.preferred_y", 0)
        combinedConfig.set("ores.common.density", 1.0)
        combinedConfig.set("ores.common.vein_size_min", 32)
        combinedConfig.set("ores.common.vein_size_max", 32)
        combinedConfig.set("ores.common.cell_chance", 1.0)
        combinedConfig.set("ores.common.priority", 10)
        combinedConfig.set("ores.rare.enabled", true)
        combinedConfig.set("ores.rare.materials.stone", "DIAMOND_ORE")
        combinedConfig.set("ores.rare.y_min", -64)
        combinedConfig.set("ores.rare.y_max", 64)
        combinedConfig.set("ores.rare.preferred_y", 0)
        combinedConfig.set("ores.rare.density", 1.0)
        combinedConfig.set("ores.rare.vein_size_min", 32)
        combinedConfig.set("ores.rare.vein_size_max", 32)
        combinedConfig.set("ores.rare.cell_chance", 1.0)
        combinedConfig.set("ores.rare.priority", 90)
        val combined = OreVeinResolver("salt", OreRegistry.load(combinedConfig.getConfigurationSection("ores")).getOrThrow())

        var overlap: Triple<Int, Int, Int>? = null
        outer@ for (x in 0 until 96) {
            for (y in -32 until 32) {
                for (z in 0 until 96) {
                    if (commonOnly.resolve("world", 0, x, y, z, "STONE") != null &&
                        rareOnly.resolve("world", 0, x, y, z, "STONE") != null
                    ) {
                        overlap = Triple(x, y, z)
                        break@outer
                    }
                }
            }
        }
        val (x, y, z) = overlap ?: error("測試設定下掃描範圍內找不到兩種礦候選形狀重疊的座標")

        val first = combined.resolve("world", 0, x, y, z, "STONE")
        val second = combined.resolve("world", 0, x, y, z, "STONE")
        assertNotNull(first)
        assertEquals("rare", first.oreType, "priority 較高(90 > 10)的礦種應該贏")
        assertEquals(90, first.priority)
        assertEquals(first, second, "重複呼叫必須得到一模一樣的贏家,不能忽勝忽敗")

        val commonShape = commonOnly.resolveDetailed("world", 0, x, y, z, "STONE")!!.shape
        assertTrue(
            commonShape.positions.none { pos ->
                combined.resolve("world", 0, pos.x, pos.y, pos.z, "STONE")?.oreType == "common"
            },
            "跨礦種形狀重疊後，低 priority 候選必須整顆淘汰，不能留下殘脈",
        )
        val rareShape = rareOnly.resolveDetailed("world", 0, x, y, z, "STONE")!!.shape
        assertTrue(
            rareShape.positions.all { pos ->
                combined.resolve("world", 0, pos.x, pos.y, pos.z, "STONE")?.oreType == "rare"
            },
            "獲勝礦脈必須保留完整連通形狀",
        )
    }

    @Test
    fun `priority contender without the queried base material cannot suppress a valid ore`() {
        fun registry(includeCommon: Boolean, includeRare: Boolean): OreRegistry {
            val config = YamlConfiguration()
            if (includeCommon) {
                config.set("ores.common.enabled", true)
                config.set("ores.common.materials.stone", "IRON_ORE")
                config.set("ores.common.y_min", -64)
                config.set("ores.common.y_max", 64)
                config.set("ores.common.preferred_y", 0)
                config.set("ores.common.density", 1.0)
                config.set("ores.common.vein_size_min", 32)
                config.set("ores.common.vein_size_max", 32)
                config.set("ores.common.cell_chance", 1.0)
                config.set("ores.common.priority", 10)
            }
            if (includeRare) {
                config.set("ores.rare.enabled", true)
                config.set("ores.rare.materials.deepslate", "DEEPSLATE_DIAMOND_ORE")
                config.set("ores.rare.y_min", -64)
                config.set("ores.rare.y_max", 64)
                config.set("ores.rare.preferred_y", 0)
                config.set("ores.rare.density", 1.0)
                config.set("ores.rare.vein_size_min", 32)
                config.set("ores.rare.vein_size_max", 32)
                config.set("ores.rare.cell_chance", 1.0)
                config.set("ores.rare.priority", 90)
            }
            return OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow()
        }

        val commonOnly = OreVeinResolver("material-priority", registry(includeCommon = true, includeRare = false))
        val rareOnly = OreVeinResolver("material-priority", registry(includeCommon = false, includeRare = true))
        val combined = OreVeinResolver("material-priority", registry(includeCommon = true, includeRare = true))
        var overlap: Triple<Int, Int, Int>? = null
        outer@ for (x in 0 until 96) {
            for (y in -32 until 32) {
                for (z in 0 until 96) {
                    if (commonOnly.resolve("world", 0, x, y, z, "STONE") != null &&
                        rareOnly.resolve("world", 0, x, y, z, "DEEPSLATE") != null
                    ) {
                        overlap = Triple(x, y, z)
                        break@outer
                    }
                }
            }
        }
        val (x, y, z) = overlap ?: error("測試範圍內找不到 stone/deepslate 候選重疊")

        assertEquals("common", combined.resolve("world", 0, x, y, z, "STONE")?.oreType)
        assertEquals("rare", combined.resolve("world", 0, x, y, z, "DEEPSLATE")?.oreType)
    }

    /**
     * OreVeinResolver.resolveDetailed 附帶的 [VeinShape] 讓呼叫端(MaterializationService)可以
     * 判斷「另一個座標是否屬於同一顆礦脈」——這裡驗證:同一個 shape 內的不同座標,各自
     * 獨立呼叫 resolveDetailed,得到的都是同一個 veinId(同一顆礦脈),且多次呼叫結果一致。
     */
    @Test
    fun `distinct coordinates within the same shape resolve to the same vein consistently`() {
        val resolver = OreVeinResolver("salt", registry())
        val (x, y, z) = findHitCoordinate(resolver)
        val trigger = resolver.resolveDetailed("world", 0, x, y, z, "STONE") ?: error("預期命中卻沒有結果")

        assertEquals(32, trigger.shape.blockCount)
        trigger.shape.positions.take(8).forEach { position ->
            val first = resolver.resolveDetailed("world", 0, position.x, position.y, position.z, "STONE")
            val second = resolver.resolveDetailed("world", 0, position.x, position.y, position.z, "STONE")
            assertNotNull(first, "$position 落在同一個 shape 內,理論上必定命中")
            assertEquals(trigger.result.veinId, first.result.veinId, "同一個候選形狀內的座標必須是同一個 veinId")
            assertEquals(first, second, "同座標重複決算必須完全一致")
        }
    }

    @Test
    fun `one veinId never exceeds configured size and size changes the actual shape`() {
        fun resolver(size: Int): OreVeinResolver {
            val config = YamlConfiguration()
            config.set("ores.test.enabled", true)
            config.set("ores.test.materials.stone", "IRON_ORE")
            config.set("ores.test.y_min", -64)
            config.set("ores.test.y_max", 64)
            config.set("ores.test.preferred_y", 0)
            config.set("ores.test.density", 1.0)
            config.set("ores.test.vein_size_min", size)
            config.set("ores.test.vein_size_max", size)
            config.set("ores.test.cell_chance", 1.0)
            return OreVeinResolver("size-contract", OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow())
        }

        listOf(1, 8, 14, 32).forEach { size ->
            val current = resolver(size)
            val (x, y, z) = findHitCoordinate(current)
            val shape = current.resolveDetailed("world", 0, x, y, z, "STONE")!!.shape
            assertEquals(size, shape.blockCount, "vein_size=$size 應直接等於單一 veinId 的實際方塊數")
        }
    }

    @Test
    fun `negative coordinates and chunk edges are deterministic without joining adjacent cells`() {
        val resolver = OreVeinResolver("negative-and-boundary", registry())
        val probes = listOf(-33, -32, -17, -16, -1, 0, 15, 16, 31, 32)
        probes.forEach { x ->
            probes.forEach { z ->
                val first = resolver.resolve("world", 0, x, 0, z, "STONE")
                val second = resolver.resolve("world", 0, x, 0, z, "STONE")
                assertEquals(first, second, "($x,0,$z) 在負座標或 chunk 邊界必須穩定")
            }
        }

        val hits = HashMap<VeinPosition, String>()
        for (x in -32 until 32) for (y in -16 until 16) for (z in -32 until 32) {
            resolver.resolve("world", 0, x, y, z, "STONE")?.let { hits[VeinPosition(x, y, z)] = it.veinId }
        }
        hits.forEach { (position, veinId) ->
            listOf(
                VeinPosition(position.x + 1, position.y, position.z),
                VeinPosition(position.x - 1, position.y, position.z),
                VeinPosition(position.x, position.y + 1, position.z),
                VeinPosition(position.x, position.y - 1, position.z),
                VeinPosition(position.x, position.y, position.z + 1),
                VeinPosition(position.x, position.y, position.z - 1),
            ).forEach { neighbor ->
                val other = hits[neighbor]
                assertTrue(other == null || other == veinId, "相鄰 cell 的不同 veinId 不得六面連通:$veinId / $other")
            }
        }
    }

    @Test
    fun `deepslate does not fall back to stone material`() {
        val config = YamlConfiguration()
        config.set("ores.nether_quartz.enabled", true)
        config.set("ores.nether_quartz.materials.stone", "NETHER_QUARTZ_ORE")
        config.set("ores.nether_quartz.y_min", -64)
        config.set("ores.nether_quartz.y_max", 320)
        config.set("ores.nether_quartz.preferred_y", 0)
        config.set("ores.nether_quartz.density", 1.0)
        config.set("ores.nether_quartz.vein_size_min", 32)
        config.set("ores.nether_quartz.vein_size_max", 32)
        config.set("ores.nether_quartz.cell_chance", 1.0)
        val resolver = OreVeinResolver("salt", OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow())
        val (x, y, z) = findHitCoordinate(resolver, baseMaterial = "NETHERRACK")

        assertNull(resolver.resolve("world", 0, x, y, z, "DEEPSLATE"))
        assertEquals("NETHER_QUARTZ_ORE", resolver.resolve("world", 0, x, y, z, "NETHERRACK")?.material)
    }
}
