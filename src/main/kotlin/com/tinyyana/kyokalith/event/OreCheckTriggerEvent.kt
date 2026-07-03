package com.tinyyana.kyokalith.event

import com.tinyyana.kyokalith.mining.EligibilitySource
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack

class OreCheckTriggerEvent(
    val player: Player,
    val blockLocation: Location,
    val oreMaterial: Material,
    val oreType: String,
    val tool: ItemStack,
    val fortuneLevel: Int,
    val drops: MutableList<ItemStack>,
    val triggerSource: TriggerSource,
    val eligibilitySource: EligibilitySource,
) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
