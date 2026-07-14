package com.tinyyana.kyokalith.pdc

/**
 * ITEM_STACK 狀態的 eligible ore token(token 生命週期見 docs/API.md)。
 * 純資料 + 編碼邏輯,不依賴 Bukkit,方便單元測試;實際讀寫 ItemStack PDC 見 EligibleOrePdc。
 */
data class EligibleOreToken(
    val oreType: String,
    val originWorld: String,
    val originEpoch: Int,
    val tokenId: String,
)
