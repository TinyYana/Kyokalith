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
    /**
     * 跨礦種重疊時的優先序:同一座標若被多顆不同礦種的候選球同時命中,priority 數字較大的
     * 那個贏(取代舊行為的 veinId 字典序雜湊排序,那組排序沒有維運可解釋性)。同礦種、不同
     * cell 的候選球互相重疊時不受此影響(仍是同一種礦物,材質相同,無關緊要)。
     */
    val priority: Int,
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
