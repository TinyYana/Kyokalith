package com.tinyyana.kyokalith.materialization

import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterializationServiceTest {

    @Test
    fun `native ore base material is inferred conservatively`() {
        assertEquals(Material.STONE, MaterializationService.nativeOreBase(Material.DIAMOND_ORE))
        assertEquals(Material.DEEPSLATE, MaterializationService.nativeOreBase(Material.DEEPSLATE_DIAMOND_ORE))
        assertEquals(Material.NETHERRACK, MaterializationService.nativeOreBase(Material.NETHER_QUARTZ_ORE))
        assertEquals(Material.NETHERRACK, MaterializationService.nativeOreBase(Material.ANCIENT_DEBRIS))
        assertNull(MaterializationService.nativeOreBase(Material.AMETHYST_BLOCK))
    }

    private val solidNeighbor = NeighborExposure(inRemovedKeys = false, liveTransparent = false)

    /**
     * 回歸守門:把決算搬到事件當下(同一 tick、移除還沒真的套用到世界資料)執行時,
     * 唯一透明的鄰居 liveTransparent 會是 false(世界資料還沒變成空氣),但它屬於本次
     * removedKeys——如果這裡誤判成「不是首次曝露」,誘餌就永遠不會決算,對應「魚骨挖礦
     * 有時候會看到礦物從面前消失」的回報:玩家會先看到用戶端快取畫出的誘餌原貌,
     * 決算又遲遲不追上來把它修正掉。
     */
    @Test
    fun `neighbor pending removal counts as newly exposed even before world state updates`() {
        val neighbors = listOf(
            NeighborExposure(inRemovedKeys = true, liveTransparent = false),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertTrue(MaterializationService.isNewlyExposed(neighbors))
    }

    /** 移除已生效(next-tick fallback 路徑)時,判定結果必須與「移除前」呼叫一致。 */
    @Test
    fun `neighbor pending removal counts as newly exposed after world state updates too`() {
        val neighbors = listOf(
            NeighborExposure(inRemovedKeys = true, liveTransparent = true),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertTrue(MaterializationService.isNewlyExposed(neighbors))
    }

    /**
     * 已經有一個透明面不屬於本次事件(世界生成就曝露,或先前事件已經決算過):
     * 一律當作「事件前就看得到」,不能再被改動,即使本次事件也移除了另一個鄰居。
     */
    @Test
    fun `pre-existing exposure outside this event blocks re-resolution`() {
        val neighbors = listOf(
            NeighborExposure(inRemovedKeys = true, liveTransparent = true),
            NeighborExposure(inRemovedKeys = false, liveTransparent = true),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertFalse(MaterializationService.isNewlyExposed(neighbors))
    }

    @Test
    fun `no transparent neighbor at all is not newly exposed`() {
        val neighbors = List(6) { solidNeighbor }
        assertFalse(MaterializationService.isNewlyExposed(neighbors))
    }

    /**
     * 5×5×5 局部鄰域預決算的硬上限:不管礦脈候選球之後被調得多大(OreVeinResolver 的
     * MAX_VEIN_RADIUS 是獨立的上限),單一首次曝露事件一次鎖定的鄰域工作量永遠是這 124 個
     * 額外座標,不會變成掃整顆礦脈或整個 chunk。
     */
    @Test
    fun `neighborhood offsets are a fixed 124-entry window regardless of vein size`() {
        val offsets = MaterializationService.neighborhoodOffsets()

        assertEquals(124, offsets.size, "5x5x5 扣掉原點應該正好是 124 個座標")
        assertTrue(offsets.none { (dx, dy, dz) -> dx == 0 && dy == 0 && dz == 0 }, "觸發座標本身不該出現在鄰域清單裡")
        assertTrue(offsets.all { (dx, dy, dz) -> dx in -2..2 && dy in -2..2 && dz in -2..2 }, "每個偏移量都必須落在半徑 2 以內")
        assertEquals(offsets.size, offsets.toSet().size, "偏移量不應該重複")
    }
}
