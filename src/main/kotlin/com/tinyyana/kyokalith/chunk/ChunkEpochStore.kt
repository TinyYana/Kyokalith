package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.concurrent.ConcurrentHashMap

data class ChunkCoord(val world: String, val cx: Int, val cz: Int)

/**
 * chunk epoch:每次 NatureRevive 再生一個 chunk,epoch +1,舊 epoch 的紀錄自然作廢。
 * 決定性礦脈函數與 dirty positions 都以 (world, cx, cz, epoch) 為範圍鍵。
 */
class ChunkEpochStore(private val db: KyokalithDatabase) {
    private val cache = ConcurrentHashMap<ChunkCoord, Int>()

    fun get(coord: ChunkCoord): Int = cache.getOrPut(coord) { queryEpoch(coord) ?: 0 }

    fun increment(coord: ChunkCoord): Int {
        val next = get(coord) + 1
        persist(coord, next)
        cache[coord] = next
        return next
    }

    private fun queryEpoch(coord: ChunkCoord): Int? =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT epoch FROM chunk_epoch WHERE world = ? AND cx = ? AND cz = ?",
            ).use { stmt ->
                stmt.setString(1, coord.world)
                stmt.setInt(2, coord.cx)
                stmt.setInt(3, coord.cz)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt("epoch") else null }
            }
        }

    private fun persist(coord: ChunkCoord, epoch: Int) {
        db.connect().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO chunk_epoch(world, cx, cz, epoch) VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, coord.world)
                stmt.setInt(2, coord.cx)
                stmt.setInt(3, coord.cz)
                stmt.setInt(4, epoch)
                stmt.executeUpdate()
            }
        }
    }
}
