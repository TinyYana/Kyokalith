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
    fun `resolution rate drops far from preferred_y compared to at preferred_y`() {
        val config = YamlConfiguration()
        config.set("ores.test.enabled", true)
        config.set("ores.test.materials.stone", "IRON_ORE")
        config.set("ores.test.y_min", -256)
        config.set("ores.test.y_max", 256)
        config.set("ores.test.preferred_y", 0)
        config.set("ores.test.density", 1.0)
        config.set("ores.test.vein_size_min", 1)
        config.set("ores.test.vein_size_max", 1)
        config.set("ores.test.cell_chance", 1.0)
        val resolver = OreVeinResolver("salt", OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow())

        val hitsAtPeak = (0 until 200).count { resolver.resolve("world", it, it * 16, 0, it * 16, "STONE") != null }
        val hitsFarFromPeak = (0 until 200).count { resolver.resolve("world", it, it * 16, 250, it * 16, "STONE") != null }

        assertTrue(hitsAtPeak > hitsFarFromPeak)
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
