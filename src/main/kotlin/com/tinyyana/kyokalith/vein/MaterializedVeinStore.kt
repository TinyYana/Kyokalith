package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * 一個座標已鎖定的礦脈決算結果。`material` 是該座標未來真正曝露時應該 setType 成的材質
 * 名稱(miss 時 = 基底石材質,而不是 null——因為 miss 只有在「屬於某個 hit 候選球的
 * 5x5x5 鄰域成員」這個情境下才會被鎖定,見 MaterializationService.collectNeighborhood)。
 * `oreType`/`veinId` 只在 hit 時非 null,純粹給 `/kyo inspect` 除錯用。
 */
data class MaterializedVein(val oreType: String?, val veinId: String?, val material: String)

/**
 * materialized_positions 的記憶體快取 + 持久化層。讀取路徑比照 [com.tinyyana.kyokalith.chunk.DirtyPositionStore]
 * 的既有慣例:整個 chunk 懶載入進記憶體一次,之後同 chunk 內的查詢全部走記憶體,不逐座標
 * 開新 DB connection——首次曝露決算的熱路徑因此只在「真的命中礦脈」時才有 DB 寫入,單純
 * miss 完全不觸碰這張表(見 MaterializationService 的取捨說明,對應 §15.1「熱路徑無 DB I/O」)。
 */
class MaterializedVeinStore(private val db: KyokalithDatabase) {
    private val loaded = ConcurrentHashMap<EpochedChunk, MutableMap<LocalPos, MaterializedVein>>()

    fun find(chunk: EpochedChunk, pos: LocalPos): MaterializedVein? = loadIfAbsent(chunk)[pos]

    /**
     * 批次 upsert,單一 SQL transaction:全部成功才提交並更新記憶體快取,任何一筆失敗就
     * rollback、回傳 false——呼叫端據此判斷要不要放棄這一批鄰域鎖定(見
     * MaterializationService.resolveAndLock)。`entries` 內所有座標必須屬於同一個 [chunk]
     * (呼叫端負責過濾跨 chunk 的鄰居,見 collectNeighborhood 的 sameChunk 檢查)。
     */
    fun upsertAll(chunk: EpochedChunk, entries: Map<LocalPos, MaterializedVein>): Boolean {
        if (entries.isEmpty()) return true
        val success = runCatching {
            db.connect().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        """
                        INSERT OR REPLACE INTO materialized_positions
                            (world, cx, cz, epoch, lx, y, lz, ore_type, vein_id, material)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { stmt ->
                        entries.forEach { (pos, vein) ->
                            stmt.setString(1, chunk.world)
                            stmt.setInt(2, chunk.cx)
                            stmt.setInt(3, chunk.cz)
                            stmt.setInt(4, chunk.epoch)
                            stmt.setInt(5, pos.lx)
                            stmt.setInt(6, pos.y)
                            stmt.setInt(7, pos.lz)
                            stmt.setString(8, vein.oreType)
                            stmt.setString(9, vein.veinId)
                            stmt.setString(10, vein.material)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
        }.isSuccess
        if (success) loadIfAbsent(chunk).putAll(entries)
        return success
    }

    /** NatureRevive 再生後,舊 epoch 的鎖定結果可清除,否則新一輪誘餌會被舊決定卡住(比照 DirtyPositionStore.clearEpoch)。 */
    fun clearEpoch(chunk: EpochedChunk) {
        loaded.remove(chunk)
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM materialized_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeUpdate()
            }
        }
    }

    private fun loadIfAbsent(chunk: EpochedChunk): MutableMap<LocalPos, MaterializedVein> =
        loaded.getOrPut(chunk) { queryChunk(chunk).toMutableMap() }

    private fun queryChunk(chunk: EpochedChunk): Map<LocalPos, MaterializedVein> =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT lx, y, lz, ore_type, vein_id, material FROM materialized_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeQuery().use { rs ->
                    val map = HashMap<LocalPos, MaterializedVein>()
                    while (rs.next()) {
                        val pos = LocalPos(rs.getInt("lx"), rs.getInt("y"), rs.getInt("lz"))
                        map[pos] = MaterializedVein(rs.getString("ore_type"), rs.getString("vein_id"), rs.getString("material"))
                    }
                    map
                }
            }
        }

    fun count(): Int =
        db.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) AS c FROM materialized_positions").use { rs ->
                    if (rs.next()) rs.getInt("c") else 0
                }
            }
        }
}
