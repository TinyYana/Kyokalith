package com.tinyyana.kyokalith.schedule

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

object Schedulers {

    val isFolia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    fun atRegion(plugin: Plugin, location: Location, task: Runnable) {
        if (isFolia) FoliaSchedulers.atRegion(plugin, location, task) else Bukkit.getScheduler().runTask(plugin, task)
    }

    fun atRegion(plugin: Plugin, world: World, cx: Int, cz: Int, task: Runnable) {
        if (isFolia) FoliaSchedulers.atRegion(plugin, world, cx, cz, task) else Bukkit.getScheduler().runTask(plugin, task)
    }

    fun atEntity(plugin: Plugin, entity: Entity, task: Runnable) {
        if (isFolia) FoliaSchedulers.atEntity(plugin, entity, task) else Bukkit.getScheduler().runTask(plugin, task)
    }

    fun globalTimer(plugin: Plugin, delay: Long, period: Long, task: Runnable): () -> Unit {
        val d = delay.coerceAtLeast(1L)
        val p = period.coerceAtLeast(1L)
        if (isFolia) return FoliaSchedulers.globalTimer(plugin, d, p, task)
        val id = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, task, d, p)
        return { Bukkit.getScheduler().cancelTask(id) }
    }
}
