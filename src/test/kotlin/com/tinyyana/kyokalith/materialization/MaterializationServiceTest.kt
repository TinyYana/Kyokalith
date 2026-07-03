package com.tinyyana.kyokalith.materialization

import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MaterializationServiceTest {

    @Test
    fun `native ore base material is inferred conservatively`() {
        assertEquals(Material.STONE, MaterializationService.nativeOreBase(Material.DIAMOND_ORE))
        assertEquals(Material.DEEPSLATE, MaterializationService.nativeOreBase(Material.DEEPSLATE_DIAMOND_ORE))
        assertEquals(Material.NETHERRACK, MaterializationService.nativeOreBase(Material.NETHER_QUARTZ_ORE))
        assertEquals(Material.NETHERRACK, MaterializationService.nativeOreBase(Material.ANCIENT_DEBRIS))
        assertNull(MaterializationService.nativeOreBase(Material.AMETHYST_BLOCK))
    }
}
