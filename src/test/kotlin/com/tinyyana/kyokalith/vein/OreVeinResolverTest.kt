package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreRegistry
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
     * MAX_VEIN_RADIUS 上限後,單一固定座標不再保證命中(半徑上限後的球體只佔 cell 一小部分),
     * 不能再靠超大 vein_size 保證命中。改成在一個 cell 內掃描找出第一個真實命中座標,
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
