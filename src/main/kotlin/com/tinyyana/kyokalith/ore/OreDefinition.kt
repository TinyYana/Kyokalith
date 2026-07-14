package com.tinyyana.kyokalith.ore

/** 單一礦種設定,對應 config.yml 的 ores 區塊;欄位語意與紅線見 docs/CONFIG.md。 */
data class OreDefinition(
    val oreType: String,
    val enabled: Boolean,
    val stoneMaterial: String?,
    val deepslateMaterial: String?,
    /** Bukkit `World.Environment` name (NORMAL/NETHER/THE_END) this ore is allowed to resolve in. */
    val dimension: String,
    val yMin: Int,
    val yMax: Int,
    val preferredY: Int,
    val density: Double,
    val veinSizeMin: Int,
    val veinSizeMax: Int,
    val cellChance: Double,
) {
    init {
        require(stoneMaterial != null || deepslateMaterial != null) {
            "ore '$oreType' 至少需要一個 materials.stone 或 materials.deepslate"
        }
        require(yMin <= yMax) { "ore '$oreType' y_min ($yMin) 不可大於 y_max ($yMax)" }
        require(veinSizeMin in 1..veinSizeMax) { "ore '$oreType' vein_size_min/max 範圍不合法" }
        require(cellChance in 0.0..1.0) { "ore '$oreType' cell_chance 必須在 0..1" }
    }
}
