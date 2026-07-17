package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** chunk 內局部座標,x/z 為 0..15。 */
data class LocalPos(val lx: Int, val y: Int, val lz: Int)

data class EpochedChunk(val world: String, val cx: Int, val cz: Int, val epoch: Int)

/**
 * dirty positions:被玩家或機制重新填入的天然座標,永不實體化礦物。
 *
 * ponytail: 編碼用分號分隔字串,不用 BitSet/Roaring bitmap;
 * 若日後 dirty 量體證明是效能瓶頸,再換更精簡的編碼。
 */
class DirtyPositionStore(
    private val db: KyokalithDatabase,
    private val logger: Logger = Logger.getLogger(DirtyPositionStore::class.java.name),
) {
    private val loaded = ConcurrentHashMap<EpochedChunk, MutableSet<LocalPos>>()
    private val pendingFlush = ConcurrentHashMap.newKeySet<EpochedChunk>()

    fun markDirty(chunk: EpochedChunk, pos: LocalPos) {
        loadIfAbsent(chunk).add(pos)
        pendingFlush.add(chunk)
    }

    fun isDirty(chunk: EpochedChunk, pos: LocalPos): Boolean = loadIfAbsent(chunk).contains(pos)

    /** chunk unload / plugin disable 時強制呼叫(§10.4)。 */
    fun flush(chunk: EpochedChunk) {
        if (!pendingFlush.remove(chunk)) return
        val positions = loaded[chunk] ?: return
        try {
            persist(chunk, positions)
        } catch (e: Exception) {
            // 失敗必須放回佇列:dirty flag 沒落地會讓蓋挖漏洞重開(CONFIG.md 紅線),下輪重試
            pendingFlush.add(chunk)
            throw e
        }
    }

    fun flushAll() {
        // 逐 chunk 隔離失敗:Folia 上多執行緒寫 SQLite 可能撞 SQLITE_BUSY,
        // 一個 chunk 失敗不能讓其餘 chunk 這輪全部跳過
        pendingFlush.toList().forEach { chunk ->
            try {
                flush(chunk)
            } catch (e: Exception) {
                logger.warning("dirty positions flush failed for $chunk, requeued for next cycle: $e")
            }
        }
    }

    /** NatureRevive 再生後,舊 epoch 的 dirty positions 可刪除(§10.3)。 */
    fun clearEpoch(chunk: EpochedChunk) {
        loaded.remove(chunk)
        pendingFlush.remove(chunk)
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM dirty_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeUpdate()
            }
        }
    }

    private fun loadIfAbsent(chunk: EpochedChunk): MutableSet<LocalPos> =
        loaded.getOrPut(chunk) { ConcurrentHashMap.newKeySet<LocalPos>().apply { addAll(queryPositions(chunk)) } }

    private fun queryPositions(chunk: EpochedChunk): Set<LocalPos> =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT data FROM dirty_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) emptySet() else decode(rs.getBytes("data"))
                }
            }
        }

    private fun persist(chunk: EpochedChunk, positions: Set<LocalPos>) {
        db.connect().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO dirty_positions(world, cx, cz, epoch, data) VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.setBytes(5, encode(positions))
                stmt.executeUpdate()
            }
        }
    }

    companion object {
        fun encode(positions: Set<LocalPos>): ByteArray =
            positions.joinToString(";") { "${it.lx},${it.y},${it.lz}" }.toByteArray(Charsets.UTF_8)

        fun decode(raw: ByteArray?): Set<LocalPos> {
            if (raw == null || raw.isEmpty()) return emptySet()
            return String(raw, Charsets.UTF_8).split(';').mapNotNull { entry ->
                val parts = entry.split(',')
                if (parts.size != 3) return@mapNotNull null
                runCatching { LocalPos(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
            }.toSet()
        }
    }
}
