package com.tinyyana.kyokalith.schedule

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

object FoliaSchedulers {

    fun atRegion(plugin: Plugin, location: Location, task: Runnable) {
        Bukkit.getRegionScheduler().run(plugin, location) { task.run() }
    }

    fun atRegion(plugin: Plugin, world: World, cx: Int, cz: Int, task: Runnable) {
        Bukkit.getRegionScheduler().run(plugin, world, cx, cz) { task.run() }
    }

    fun atEntity(plugin: Plugin, entity: Entity, task: Runnable) {
        entity.scheduler.run(plugin, { task.run() }, null)
    }

    fun globalTimer(plugin: Plugin, delay: Long, period: Long, task: Runnable): () -> Unit {
        val handle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task.run() }, delay, period)
        return { handle.cancel() }
    }
}
