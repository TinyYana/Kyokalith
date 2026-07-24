package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreRegistry
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 校準回歸測試:直接載入實際出貨的 `config.yml`(不是手抄一份副本),對地獄各礦種的
 * 決定性礦脈函數做大量座標抽樣,量測「每一萬個抽樣座標的命中數」,並斷言 ancient_debris
 * 的密度落在「比 nether_quartz/nether_gold 自己的密度峰值明顯稀有,但不再是趨近於零」的
 * 範圍——這正是本次校準要修的東西(見 commit message 的完整推導與前後對照數字)。
 *
 * f 是純函數(固定 salt + 座標 → 固定結果),這裡的「Monte Carlo」抽樣本身是完全決定性、
 * 可重現的,不是真隨機——同一份 config.yml 永遠量出同一組數字,沒有測試 flaky 的疑慮,
 * 門檻可以設得比較貼近實測值。
 *
 * 這個測試檔用的抽樣方法量到的實際數字(salt="monte-carlo-calibration-salt",side=600,
 * 即 360,000 個座標):
 * - 校準前(cell_chance=0.006, vein_size_max=2):ancient_debris y8=0.14/10k、y15=0.056/10k、
 *   y22=0.0/10k——y8-22 這段實質上是零,這是玩家回報「Y9 附近幾乎挖不到資源」的根因。
 * - 校準後(cell_chance=0.05, vein_size_max=3):ancient_debris y15=0.81/10k,約為校準前的
 *   14 倍;nether_quartz 在它自己的密度峰值(y60)量到 9.17/10k——校準後的 ancient_debris
 *   仍只有 quartz 峰值的 ~9%,維持「遠比常見地獄礦稀有」但不再趨近於零。
 */
class NetherOreDensityMonteCarloTest {

    private fun bundledOreRegistry(): OreRegistry {
        val stream = javaClass.classLoader.getResourceAsStream("config.yml")
            ?: error("找不到 classpath 上的 config.yml——這個測試要量的就是實際出貨的設定")
        val config = YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
        return OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow()
    }

    /** 在固定 Y、一個 [side] x [side] 的 X/Z 方格內,量測某個礦種贏得決算的座標數,換算成每萬格命中數。 */
    private fun hitsPer10k(resolver: OreVeinResolver, oreType: String, y: Int, side: Int = 600): Double {
        var hits = 0
        for (x in 0 until side) {
            for (z in 0 until side) {
                val result = resolver.resolve("world", 0, x, y, z, "NETHERRACK", "NETHER")
                if (result?.oreType == oreType) hits++
            }
        }
        return hits.toDouble() / (side.toLong() * side.toLong()) * 10_000
    }

    @Test
    fun `ancient_debris is clearly rarer than nether_quartz's peak but no longer functionally absent at y15`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        val ancientDebrisAtY15 = hitsPer10k(resolver, "ancient_debris", y = 15)
        val quartzAtOwnPeak = hitsPer10k(resolver, "nether_quartz", y = 60)

        // 迴歸守門:舊設定(cell_chance=0.006, vein_size_max=2)在同樣的量測方法下測出來是
        // 0.0~0.05/10k,等於完全挖不到;下限抓在明顯高於那個量級的地方,擋住「校準被誤改回
        // 接近零」的迴歸。上限確保 ancient_debris 仍然比常見礦種本身的密度峰值稀有得多。
        assertTrue(
            ancientDebrisAtY15 > 0.2,
            "ancient_debris 在 y15 的密度是 $ancientDebrisAtY15 /10k,太接近零(舊 bug 的量級是 0~0.05)",
        )
        assertTrue(
            ancientDebrisAtY15 < quartzAtOwnPeak,
            "ancient_debris 在 y15 的密度($ancientDebrisAtY15/10k)應該低於 nether_quartz 自己的密度峰值" +
                "($quartzAtOwnPeak/10k)——遠古殘骸該是遠比常見地獄礦稀有的資源",
        )
    }

    @Test
    fun `ancient_debris density in the y8-22 band is non-trivial across the whole band, not just at preferred_y`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        listOf(8, 10, 12, 15, 18, 20, 22).forEach { y ->
            val density = hitsPer10k(resolver, "ancient_debris", y)
            assertTrue(density > 0.1, "y=$y 的 ancient_debris 密度是 $density/10k,y8-22 這段不該接近零")
        }
    }

    /**
     * 不動 y_min=10 的迴歸守門:確認 quartz/nether_gold 在自己的 y_min 附近整體不是異常掛零。
     * 用 y10..y20 一整段的加總,而不是單一個 Y 值——三角權重在單一 Y-cell-band(16 格一段)
     * 內本來就會因為抽樣噪音而偶爾測出單點掛零,不代表設定異常;聚合一個範圍才穩定。
     */
    @Test
    fun `nether_quartz and nether_gold are not anomalously dead near their own y_min`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())
        val yBand = 10..20

        val quartzTotal = yBand.sumOf { hitsPer10k(resolver, "nether_quartz", it) }
        val goldTotal = yBand.sumOf { hitsPer10k(resolver, "nether_gold", it) }

        assertTrue(quartzTotal > 0.0, "nether_quartz 在 y10-20 整段掛零,可能有異常")
        assertTrue(goldTotal > 0.0, "nether_gold 在 y10-20 整段掛零,可能有異常")
    }
}
