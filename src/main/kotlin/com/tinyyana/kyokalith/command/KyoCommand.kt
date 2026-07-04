package com.tinyyana.kyokalith.command

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOre
import com.tinyyana.kyokalith.materialization.MaterializationService
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.util.UUID

/** 管理用最小指令:stats + Phase 2/3 的預覽、抽樣與 QA token 操作。 */
class KyoCommand(private val plugin: KyokalithPlugin) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("kyokalith.admin")) {
            sender.sendMessage("§c沒有權限")
            return true
        }
        return when (args.firstOrNull()?.lowercase()) {
            "stats" -> stats(sender)
            "inspect" -> inspect(sender, args.drop(1))
            "preview" -> preview(sender, args.drop(1))
            "sample" -> sample(sender, args.drop(1))
            "markeligible" -> markEligible(sender, args.drop(1))
            "giveeligible" -> giveEligible(sender, args.drop(1))
            "suspend" -> suspend(sender, args.drop(1))
            "resume" -> resume(sender, args.drop(1))
            "resolve" -> resolve(sender, args.drop(1))
            else -> {
                sender.sendMessage("§e用法:/kyo stats | inspect <x> <y> <z> [world] | preview [radius] [x y z [world]] | sample volume [radius] | markeligible [x y z] | giveeligible <player> <oreType> <amount> | suspend <cx> <cz> <reason> | resume <cx> <cz> | resolve <x> <y> <z> [world]")
                true
            }
        }
    }

    private fun stats(sender: CommandSender): Boolean {
        val oreCount = plugin.oreRegistry.enabled().size
        val totalOreCount = plugin.oreRegistry.all().size
        val placedEligibleCount = plugin.eligiblePlacedOreStore.count()
        val suspendedCount = plugin.suspendedChunkStore.count()

        sender.sendMessage(
            listOf(
                "§b[Kyokalith] 安全基底狀態",
                "§7礦種:§f$oreCount 已啟用 / $totalOreCount 總計",
                "§7placed eligible ores:§f$placedEligibleCount",
                "§7suspended chunks:§f$suspendedCount",
                "§7礦脈函數/preview/sample:§a可用",
                "§7事件驅動曝露決算(誘餌模型,無 chunk 掃描):§a可用",
                "§7eligible token/d20 drops:§a可用",
                "§7誘餌層:§f原版世界生成礦(不抹礦、無 datapack)",
                "§7NatureRevive epoch 整合:§f${if (plugin.natureReviveBridgeActive) "§aactive" else "§cinactive"}",
            ).joinToString("\n"),
        )
        return true
    }

    private fun inspect(sender: CommandSender, args: List<String>): Boolean {
        if (args.size !in 3..4) {
            sender.sendMessage("§e用法:/kyo inspect <x> <y> <z> [world]")
            return true
        }
        val x = args[0].toIntOrNull()
        val y = args[1].toIntOrNull()
        val z = args[2].toIntOrNull()
        if (x == null || y == null || z == null) {
            sender.sendMessage("§c座標必須是整數")
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(3)) ?: return true
        val block = world.getBlockAt(x, y, z)
        val coord = chunkCoord(world, x, z)
        val epoch = plugin.chunkEpochStore.get(coord)
        val epoched = EpochedChunk(world.name, coord.cx, coord.cz, epoch)
        val local = LocalPos(Math.floorMod(x, 16), y, Math.floorMod(z, 16))
        val placed = plugin.eligiblePlacedOreStore.find(world.name, x, y, z)
        // 已是礦物的座標要用基底材質問 f,否則永遠 none,無法確認天然 eligible 的 f-match
        val baseName = MaterializationService.nativeOreBase(block.type)?.name ?: block.type.name
        val result = plugin.oreVeinResolver.resolve(world.name, epoch, x, y, z, baseName, world.environment.name)

        sender.sendMessage(
            listOf(
                "§b[Kyokalith] inspect $x $y $z",
                "§7world/epoch:§f${world.name}/$epoch",
                "§7block:§f${block.type.name}",
                "§7dirty:§f${plugin.dirtyPositionStore.isDirty(epoched, local)}",
                "§7suspended:§f${plugin.suspendedChunkStore.isSuspended(coord)}",
                "§7placed eligible:§f${placed?.oreType ?: "none"}",
                "§7vein result:§f${result?.let { "${it.oreType} -> ${it.material} (${it.veinId})" } ?: "none"}",
            ).joinToString("\n"),
        )
        return true
    }

    /** `/kyo preview [radius]`(玩家,以自身為中心)或 `/kyo preview <radius> <x> <y> <z> [world]`(主控台可用)。 */
    private fun preview(sender: CommandSender, args: List<String>): Boolean {
        val radius = parseRadius(sender, args.firstOrNull(), 8) ?: return true
        val center = if (args.size >= 4) {
            val x = args[1].toIntOrNull()
            val y = args[2].toIntOrNull()
            val z = args[3].toIntOrNull()
            if (x == null || y == null || z == null) {
                sender.sendMessage("§c座標必須是整數")
                return true
            }
            val world = resolveWorld(sender, args.getOrNull(4)) ?: return true
            world.getBlockAt(x, y, z)
        } else {
            val player = sender as? Player ?: return playerOnly(sender)
            player.location.block
        }
        val hits = collectHits(center, radius, limit = 12)
        sender.sendMessage("§b[Kyokalith] preview 半徑 $radius 命中 ${hits.total} 格")
        hits.examples.forEach { sender.sendMessage("§7- §f${it.x} ${it.y} ${it.z} §7${it.oreType}->${it.material}") }
        if (hits.total > hits.examples.size) sender.sendMessage("§7...其餘 ${hits.total - hits.examples.size} 格省略")
        return true
    }

    private fun sample(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (args.firstOrNull()?.lowercase() != "volume") {
            sender.sendMessage("§e用法:/kyo sample volume [radius]")
            return true
        }
        val radius = parseRadius(sender, args.getOrNull(1), 8) ?: return true
        val hits = collectHits(player.location.block, radius, limit = 0)
        sender.sendMessage("§b[Kyokalith] sample volume 半徑 $radius:§f ${hits.total} / ${hits.scanned} 格命中")
        return true
    }

    private fun markEligible(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        val block = when (args.size) {
            0 -> player.getTargetBlockExact(8) ?: run {
                sender.sendMessage("§c請看著 8 格內的一個礦物方塊,或使用 /kyo markeligible <x> <y> <z>")
                return true
            }
            3 -> {
                val x = args[0].toIntOrNull()
                val y = args[1].toIntOrNull()
                val z = args[2].toIntOrNull()
                if (x == null || y == null || z == null) {
                    sender.sendMessage("§c座標必須是整數")
                    return true
                }
                player.world.getBlockAt(x, y, z)
            }
            else -> {
                sender.sendMessage("§e用法:/kyo markeligible [x y z]")
                return true
            }
        }
        val ore = oreForMaterial(block.type) ?: run {
            sender.sendMessage("§c${block.type.name} 不是 Kyokalith 已啟用礦種")
            return true
        }
        val epoch = epochAt(block.world, block.x, block.z)
        val coord = chunkCoord(block.world, block.x, block.z)
        val epoched = EpochedChunk(block.world.name, coord.cx, coord.cz, epoch)
        val local = LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))
        plugin.dirtyPositionStore.markDirty(epoched, local)
        plugin.dirtyPositionStore.flush(epoched)
        plugin.eligiblePlacedOreStore.insert(
            EligiblePlacedOre(
                world = block.world.name,
                x = block.x,
                y = block.y,
                z = block.z,
                epoch = epoch,
                oreType = ore.oreType,
                oreMaterial = block.type.name,
                tokenId = UUID.randomUUID().toString(),
                placedBy = player.uniqueId,
                placedAtMillis = Instant.now().toEpochMilli(),
            ),
        )
        sender.sendMessage("§a已標記 eligible: ${ore.oreType} @ ${block.x} ${block.y} ${block.z}")
        return true
    }

    private fun giveEligible(sender: CommandSender, args: List<String>): Boolean {
        if (args.size != 3) {
            sender.sendMessage("§e用法:/kyo giveeligible <player> <oreType> <amount>")
            return true
        }
        val target = plugin.server.getPlayerExact(args[0]) ?: run {
            sender.sendMessage("§c玩家不在線:${args[0]}")
            return true
        }
        val ore = plugin.oreRegistry[args[1]]?.takeIf { it.enabled } ?: run {
            sender.sendMessage("§c未知或停用的礦種:${args[1]}")
            return true
        }
        val amount = args[2].toIntOrNull()
        if (amount == null || amount !in 1..64) {
            sender.sendMessage("§c數量必須在 1..64")
            return true
        }
        val materialName = ore.stoneMaterial ?: ore.deepslateMaterial ?: run {
            sender.sendMessage("§c礦種 ${ore.oreType} 沒有可給予的材質")
            return true
        }
        val material = Material.matchMaterial(materialName) ?: run {
            sender.sendMessage("§c未知 Bukkit 材質:$materialName")
            return true
        }
        val item = plugin.eligibleOrePdc.tag(ItemStack(material, amount), ore.oreType, target.world.name, 0)
        val leftovers = target.inventory.addItem(item)
        leftovers.values.forEach { target.world.dropItemNaturally(target.location, it) }
        sender.sendMessage("§a已給予 ${target.name} ${amount}x eligible ${ore.oreType}")
        return true
    }

    private fun suspend(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (args.size < 3) {
            sender.sendMessage("§e用法:/kyo suspend <cx> <cz> <reason>")
            return true
        }
        val cx = args[0].toIntOrNull()
        val cz = args[1].toIntOrNull()
        if (cx == null || cz == null) {
            sender.sendMessage("§cchunk 座標必須是整數")
            return true
        }
        val coord = ChunkCoord(player.world.name, cx, cz)
        plugin.suspendedChunkStore.suspend(coord, args.drop(2).joinToString(" "))
        sender.sendMessage("§a已暫停實體化:${coord.world} $cx $cz")
        return true
    }

    private fun resume(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (args.size != 2) {
            sender.sendMessage("§e用法:/kyo resume <cx> <cz>")
            return true
        }
        val cx = args[0].toIntOrNull()
        val cz = args[1].toIntOrNull()
        if (cx == null || cz == null) {
            sender.sendMessage("§cchunk 座標必須是整數")
            return true
        }
        val coord = ChunkCoord(player.world.name, cx, cz)
        plugin.suspendedChunkStore.resume(coord)
        sender.sendMessage("§a已恢復實體化:${coord.world} $cx $cz")
        return true
    }

    /**
     * 手動把某座標當成「剛剛消失的方塊」重跑一次首次曝露決算,走與事件監聽完全相同的
     * `MaterializationService.resolveRemoved` 路徑。用途:維運排錯(懷疑漏了曝露事件時,
     * 對已挖開的座標補決算;決定性 f 保證冪等),以及無真人玩家環境下的行為驗證。
     */
    private fun resolve(sender: CommandSender, args: List<String>): Boolean {
        if (args.size !in 3..4) {
            sender.sendMessage("§e用法:/kyo resolve <x> <y> <z> [world](座標應是已挖開的空氣/流體格)")
            return true
        }
        val x = args[0].toIntOrNull()
        val y = args[1].toIntOrNull()
        val z = args[2].toIntOrNull()
        if (x == null || y == null || z == null) {
            sender.sendMessage("§c座標必須是整數")
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(3)) ?: return true
        plugin.materializationService.resolveRemoved(listOf(world.getBlockAt(x, y, z)))
        sender.sendMessage("§a已把 $x $y $z 視為消失方塊,對其鄰居重跑首次曝露決算")
        return true
    }

    /** 主控台/RCON 沒有自身世界:優先用明確參數,其次玩家所在世界,最後伺服器第一個世界。 */
    private fun resolveWorld(sender: CommandSender, name: String?): World? {
        if (name != null) {
            val world = plugin.server.getWorld(name)
            if (world == null) sender.sendMessage("§c未知世界:$name")
            return world
        }
        val world = (sender as? Player)?.world ?: plugin.server.worlds.firstOrNull()
        if (world == null) sender.sendMessage("§c找不到任何世界")
        return world
    }

    private fun collectHits(center: org.bukkit.block.Block, radius: Int, limit: Int): HitSummary {
        val world = center.world
        var scanned = 0
        var total = 0
        val examples = mutableListOf<HitExample>()
        val minY = maxOf(world.minHeight, center.y - radius)
        val maxY = minOf(world.maxHeight - 1, center.y + radius)
        for (x in center.x - radius..center.x + radius) {
            for (y in minY..maxY) {
                for (z in center.z - radius..center.z + radius) {
                    val coord = chunkCoord(world, x, z)
                    if (!world.isChunkLoaded(coord.cx, coord.cz)) continue
                    val block = world.getBlockAt(x, y, z)
                    scanned++
                    val epoch = plugin.chunkEpochStore.get(coord)
                    val result = plugin.oreVeinResolver.resolve(world.name, epoch, x, y, z, block.type.name, world.environment.name) ?: continue
                    total++
                    if (examples.size < limit) {
                        examples += HitExample(x, y, z, result.oreType, result.material)
                    }
                }
            }
        }
        return HitSummary(scanned, total, examples)
    }

    private fun parseRadius(sender: CommandSender, raw: String?, default: Int): Int? {
        val radius = raw?.toIntOrNull() ?: default
        if (radius !in 1..24) {
            sender.sendMessage("§c半徑必須在 1..24")
            return null
        }
        return radius
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        sender.sendMessage("§c這個指令只能由玩家使用")
        return true
    }

    private fun chunkCoord(world: World, x: Int, z: Int): ChunkCoord =
        ChunkCoord(world.name, Math.floorDiv(x, 16), Math.floorDiv(z, 16))

    private fun epochAt(world: World, x: Int, z: Int): Int = plugin.chunkEpochStore.get(chunkCoord(world, x, z))

    private fun oreForMaterial(material: Material) =
        plugin.oreRegistry.enabled().firstOrNull { it.stoneMaterial == material.name || it.deepslateMaterial == material.name }

    private data class HitExample(val x: Int, val y: Int, val z: Int, val oreType: String, val material: String)

    private data class HitSummary(val scanned: Int, val total: Int, val examples: List<HitExample>)
}
