package com.tinyyana.kyokalith.ore

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OreRegistryTest {

    private fun config(): YamlConfiguration {
        val config = YamlConfiguration()
        config.set("ores.diamond.enabled", true)
        config.set("ores.diamond.materials.stone", "DIAMOND_ORE")
        config.set("ores.diamond.materials.deepslate", "DEEPSLATE_DIAMOND_ORE")
        config.set("ores.diamond.y_min", -64)
        config.set("ores.diamond.y_max", 16)
        config.set("ores.diamond.preferred_y", -59)
        config.set("ores.diamond.vein_size_min", 1)
        config.set("ores.diamond.vein_size_max", 8)
        config.set("ores.diamond.cell_chance", 0.018)
        config.set("ores.ancient_debris.enabled", false)
        config.set("ores.ancient_debris.materials.stone", "ANCIENT_DEBRIS")
        config.set("ores.ancient_debris.y_min", 8)
        config.set("ores.ancient_debris.y_max", 119)
        config.set("ores.ancient_debris.preferred_y", 15)
        config.set("ores.ancient_debris.cell_chance", 0.004)
        return config
    }

    @Test
    fun `loads enabled and disabled ores`() {
        val registry = OreRegistry.load(config().getConfigurationSection("ores")).getOrThrow()

        assertEquals(2, registry.all().size)
        assertEquals(1, registry.enabled().size)
        assertTrue(registry.enabled().any { it.oreType == "diamond" })
        assertFalse(registry["ancient_debris"]!!.enabled)
    }

    @Test
    fun `enabled ore material set excludes disabled ores`() {
        val registry = OreRegistry.load(config().getConfigurationSection("ores")).getOrThrow()

        assertTrue(registry.isOreMaterial("ANCIENT_DEBRIS"))
        assertFalse(registry.isEnabledOreMaterial("ANCIENT_DEBRIS"))
        assertTrue(registry.isEnabledOreMaterial("DIAMOND_ORE"))
        assertTrue(registry.isEnabledOreMaterial("DEEPSLATE_DIAMOND_ORE"))
        assertFalse(registry.isEnabledOreMaterial("STONE"))
    }

    @Test
    fun `oreTypeForEnabledMaterial finds enabled ores only`() {
        val registry = OreRegistry.load(config().getConfigurationSection("ores")).getOrThrow()

        assertEquals("diamond", registry.oreTypeForEnabledMaterial("DIAMOND_ORE"))
        assertEquals("diamond", registry.oreTypeForEnabledMaterial("DEEPSLATE_DIAMOND_ORE"))
        assertEquals(null, registry.oreTypeForEnabledMaterial("ANCIENT_DEBRIS")) // disabled ore type
        assertEquals(null, registry.oreTypeForEnabledMaterial("STONE")) // not an ore material at all
    }

    @Test
    fun `rejects ore with no material and no y range`() {
        val config = YamlConfiguration()
        config.set("ores.broken.enabled", true)
        config.set("ores.broken.y_min", 10)
        config.set("ores.broken.y_max", 5)
        config.set("ores.broken.cell_chance", 0.01)

        val result = OreRegistry.load(config.getConfigurationSection("ores"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `missing ores section fails`() {
        val result = OreRegistry.load(null)
        assertTrue(result.isFailure)
    }
}
