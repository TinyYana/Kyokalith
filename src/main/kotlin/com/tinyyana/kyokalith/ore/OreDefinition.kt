package com.tinyyana.kyokalith.ore

/** 單一礦種設定,對應 config.yml 的 ores 區塊;欄位語意與紅線見 docs/CONFIG.md。 */
data class YWeightPoint(val y: Int, val weight: Double)

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
    /** 可選的分段線性 Y 權重;空白時維持 preferred_y 單峰三角分布。 */
    val yWeightPoints: List<YWeightPoint>,
    val density: Double,
    val veinSizeMin: Int,
    val veinSizeMax: Int,
    val cellChance: Double,
    /**
     * 跨礦種候選形狀重疊時的優先序:priority 較大的保留完整形狀，輸家整顆淘汰，
     * 避免低優先礦只剩 1–2 格殘片。同礦種相鄰 cell 的形狀若六面相接，
     * 則較大的 veinId 也整顆淘汰，避免多顆礦脈接成一條長礦帶。
     */
    val priority: Int,
) {
    init {
        require(stoneMaterial != null || deepslateMaterial != null) {
            "ore '$oreType' 至少需要一個 materials.stone 或 materials.deepslate"
        }
        require(yMin <= yMax) { "ore '$oreType' y_min ($yMin) 不可大於 y_max ($yMax)" }
        if (yWeightPoints.isNotEmpty()) {
            require(yWeightPoints.first().y == yMin && yWeightPoints.last().y == yMax) {
                "ore '$oreType' y_weight_points 必須從 y_min 到 y_max"
            }
            require(yWeightPoints.zipWithNext().all { (left, right) -> left.y < right.y }) {
                "ore '$oreType' y_weight_points 的 y 必須嚴格遞增"
            }
            require(yWeightPoints.all { it.weight in 0.0..1.0 }) {
                "ore '$oreType' y_weight_points.weight 必須在 0..1"
            }
        }
        require(veinSizeMin in 1..veinSizeMax && veinSizeMax <= MAX_VEIN_BLOCKS) {
            "ore '$oreType' vein_size_min/max 必須在 1..$MAX_VEIN_BLOCKS"
        }
        require(cellChance in 0.0..1.0) { "ore '$oreType' cell_chance 必須在 0..1" }
    }

    companion object {
        /** vein_size 現在直接代表單一 veinId 的最大實際方塊數。 */
        const val MAX_VEIN_BLOCKS = 32
    }
}
