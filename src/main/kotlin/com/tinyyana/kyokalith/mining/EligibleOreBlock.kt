package com.tinyyana.kyokalith.mining

enum class EligibilitySource {
    NATURAL_BLOCK,
    PLACED_BLOCK,
}

data class EligibleOreBlock(
    val source: EligibilitySource,
    val oreType: String,
    val oreMaterial: String,
    val epoch: Int,
)
