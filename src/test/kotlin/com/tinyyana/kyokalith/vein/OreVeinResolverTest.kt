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

    @Test
    fun `same coordinate resolves deterministically`() {
        val resolver = OreVeinResolver("salt", registry())

        val first = resolver.resolve("world", 0, 10, 20, 30, "STONE")
        val second = resolver.resolve("world", 0, 10, 20, 30, "STONE")

        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `epoch participates in the vein function`() {
        val resolver = OreVeinResolver("salt", registry())

        val epoch0 = resolver.resolve("world", 0, 10, 20, 30, "STONE")
        val epoch1 = resolver.resolve("world", 1, 10, 20, 30, "STONE")

        assertNotEquals(epoch0?.veinId, epoch1?.veinId)
    }

    @Test
    fun `base material controls ore material`() {
        val resolver = OreVeinResolver("salt", registry())

        assertEquals("IRON_ORE", resolver.resolve("world", 0, 10, 20, 30, "STONE")?.material)
        assertEquals("DEEPSLATE_IRON_ORE", resolver.resolve("world", 0, 10, 20, 30, "DEEPSLATE")?.material)
        assertNull(resolver.resolve("world", 0, 10, 20, 30, "DIRT"))
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

        assertNull(resolver.resolve("world", 0, 10, 20, 30, "DEEPSLATE"))
        assertEquals("NETHER_QUARTZ_ORE", resolver.resolve("world", 0, 10, 20, 30, "NETHERRACK")?.material)
    }
}
