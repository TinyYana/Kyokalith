package com.tinyyana.kyokalith.notify

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.event.OreCheckTriggerEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * 選用的管理員通知:玩家挖到真礦(非誘餌)時廣播給有 `kyokalith.admin` 權限的在線玩家。
 * 預設關閉(`config.yml` 的 `notify_admins_on_ore_find`),`/kyo notify <on|off>` 可執行期切換
 * (2026-08-06 Yana 需求)。
 *
 * MONITOR 優先度、`ignoreCancelled`:純觀察,不影響誘餌決算本身的判定/取消
 * (docs/API.zh-TW.md 的單一整合點慣例——這裡只是插件自己也訂閱一次自己的事件,
 * 不影響「第三方插件應該監聽 OreCheckTriggerEvent」這個既有契約)。
 */
class OreFindNotifyListener(private val plugin: KyokalithPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOreCheck(event: OreCheckTriggerEvent) {
        if (!plugin.notifyOnOreFind) return
        val loc = event.blockLocation
        val message = plugin.messages.get(
            "ore-found-broadcast",
            "player" to event.player.name,
            "ore" to event.oreType,
            "x" to loc.blockX,
            "y" to loc.blockY,
            "z" to loc.blockZ,
        )
        for (viewer in plugin.server.onlinePlayers) {
            if (viewer.hasPermission("kyokalith.admin")) viewer.sendMessage(message)
        }
    }
}
