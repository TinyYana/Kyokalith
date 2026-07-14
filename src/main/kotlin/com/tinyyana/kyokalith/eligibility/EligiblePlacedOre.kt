package com.tinyyana.kyokalith.eligibility

import java.util.UUID

/** PLACED_BLOCK 狀態的 eligible ore token(token 生命週期見 docs/API.md)。 */
data class EligiblePlacedOre(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val epoch: Int,
    val oreType: String,
    val oreMaterial: String,
    val tokenId: String?,
    val placedBy: UUID?,
    val placedAtMillis: Long,
)
