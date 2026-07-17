package com.tinyyana.kyokalith.mining

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOre
import com.tinyyana.kyokalith.event.OreCheckTriggerEvent
import com.tinyyana.kyokalith.event.TriggerSource
import com.tinyyana.kyokalith.schedule.Schedulers
import org.bukkit.GameMode
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class OreLifecycleListener(
    private val plugin: KyokalithPlugin,
    private val eligibility: OreEligibilityService,
) : Listener {
    private val pendingBreaks = ConcurrentHashMap<BlockKey, PendingBreak>()

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val token = plugin.eligibleOrePdc.read(event.itemInHand) ?: return
        val block = event.blockPlaced
        if (!plugin.oreRegistry.isOreMaterial(block.type.name)) return
        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        val epoch = plugin.chunkEpochStore.get(coord)
        plugin.eligiblePlacedOreStore.insert(
            EligiblePlacedOre(
                world = block.world.name,
                x = block.x,
                y = block.y,
                z = block.z,
                epoch = epoch,
                oreType = token.oreType,
                oreMaterial = block.type.name,
                tokenId = token.tokenId,
                placedBy = event.player.uniqueId,
                placedAtMillis = Instant.now().toEpochMilli(),
            ),
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL) return
        if (player.hasPermission("kyokalith.bypass")) return

        val eligible = eligibility.find(event.block) ?: return
        val key = BlockKey.of(event.block)
        val pending = PendingBreak(eligible, hasSilkTouch(event))
        pendingBreaks[key] = pending
        Schedulers.atRegion(plugin, event.block.location) {
            pendingBreaks.remove(key)?.let {
                if (!it.silkTouch) {
                    plugin.logger.fine("eligible ore break at $key had no BlockDropItemEvent; token consumed without OreCheckTriggerEvent")
                }
                consumePlacedIfNeeded(key, it)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockDropItem(event: BlockDropItemEvent) {
        val key = BlockKey.of(event.block)
        val pending = pendingBreaks.remove(key) ?: return
        if (pending.silkTouch) {
            event.items.forEach { item ->
                if (item.itemStack.type.name == pending.eligible.oreMaterial) {
                    item.itemStack = plugin.eligibleOrePdc.tag(
                        item.itemStack,
                        pending.eligible.oreType,
                        key.world,
                        pending.eligible.epoch,
                    )
                }
            }
        }
        if (!pending.silkTouch) {
            fireOreCheck(event, key, pending)
        }
        consumePlacedIfNeeded(key, pending)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().forEach { removePlacedEligible(it.world.name, it.x, it.y, it.z) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().forEach { removePlacedEligible(it.world.name, it.x, it.y, it.z) }
    }

    private fun hasSilkTouch(event: BlockBreakEvent): Boolean =
        event.player.inventory.itemInMainHand.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0

    private fun fireOreCheck(event: BlockDropItemEvent, key: BlockKey, pending: PendingBreak) {
        val originalDrops = event.items.map { it.itemStack.clone() }
        if (originalDrops.isEmpty()) return
        val checkEvent = OreCheckTriggerEvent(
            player = event.player,
            blockLocation = event.block.location,
            oreMaterial = event.blockState.type,
            oreType = pending.eligible.oreType,
            tool = event.player.inventory.itemInMainHand.clone(),
            fortuneLevel = event.player.inventory.itemInMainHand.getEnchantmentLevel(Enchantment.FORTUNE),
            drops = originalDrops.map { it.clone() }.toMutableList(),
            triggerSource = TriggerSource.PLAYER_BREAK,
            eligibilitySource = pending.eligible.source,
        )
        plugin.server.pluginManager.callEvent(checkEvent)
        if (checkEvent.isCancelled || checkEvent.drops == originalDrops) return

        event.items.forEach { it.remove() }
        checkEvent.drops.forEach { drop ->
            if (!drop.type.isAir && drop.amount > 0) {
                event.block.world.dropItemNaturally(event.block.location, drop)
            }
        }
    }

    private fun consumePlacedIfNeeded(key: BlockKey, pending: PendingBreak) {
        if (pending.eligible.source == EligibilitySource.PLACED_BLOCK) {
            removePlacedEligible(key.world, key.x, key.y, key.z)
        }
    }

    private fun removePlacedEligible(world: String, x: Int, y: Int, z: Int) {
        plugin.eligiblePlacedOreStore.remove(world, x, y, z)
    }

    private data class PendingBreak(val eligible: EligibleOreBlock, val silkTouch: Boolean)

    private data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {
        companion object {
            fun of(block: org.bukkit.block.Block) = BlockKey(block.world.name, block.x, block.y, block.z)
        }
    }
}
