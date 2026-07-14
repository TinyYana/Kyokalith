package com.tinyyana.kyokalith.pdc

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Silk Touch 挖出的 eligible 礦物 ItemStack 標記(token 生命週期見 docs/API.md)。
 * Phase 1 只提供標記/讀取/清除工具;實際掛在 BlockDropItemEvent 上屬於後續挖掘生命週期階段。
 */
class EligibleOrePdc(plugin: JavaPlugin) {
    private val eligibleKey = NamespacedKey(plugin, "eligible")
    private val oreTypeKey = NamespacedKey(plugin, "ore_type")
    private val originWorldKey = NamespacedKey(plugin, "origin_world")
    private val originEpochKey = NamespacedKey(plugin, "origin_epoch")
    private val tokenIdKey = NamespacedKey(plugin, "token_id")

    fun tag(item: ItemStack, oreType: String, originWorld: String, originEpoch: Int): ItemStack {
        val meta = item.itemMeta ?: return item
        val container = meta.persistentDataContainer
        container.set(eligibleKey, PersistentDataType.BYTE, 1)
        container.set(oreTypeKey, PersistentDataType.STRING, oreType)
        container.set(originWorldKey, PersistentDataType.STRING, originWorld)
        container.set(originEpochKey, PersistentDataType.INTEGER, originEpoch)
        container.set(tokenIdKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        item.itemMeta = meta
        return item
    }

    fun isEligible(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.get(eligibleKey, PersistentDataType.BYTE) == 1.toByte()
    }

    fun read(item: ItemStack): EligibleOreToken? {
        val meta = item.itemMeta ?: return null
        val container = meta.persistentDataContainer
        if (container.get(eligibleKey, PersistentDataType.BYTE) != 1.toByte()) return null
        val oreType = container.get(oreTypeKey, PersistentDataType.STRING) ?: return null
        val originWorld = container.get(originWorldKey, PersistentDataType.STRING) ?: return null
        val originEpoch = container.get(originEpochKey, PersistentDataType.INTEGER) ?: return null
        val tokenId = container.get(tokenIdKey, PersistentDataType.STRING) ?: return null
        return EligibleOreToken(oreType, originWorld, originEpoch, tokenId)
    }

    /** 放置 qualified 礦物後,資格轉移到 eligible_placed_ores,ItemStack 上的標記要清除(§11.4)。 */
    fun clear(item: ItemStack): ItemStack {
        val meta = item.itemMeta ?: return item
        val container = meta.persistentDataContainer
        container.remove(eligibleKey)
        container.remove(oreTypeKey)
        container.remove(originWorldKey)
        container.remove(originEpochKey)
        container.remove(tokenIdKey)
        item.itemMeta = meta
        return item
    }
}
