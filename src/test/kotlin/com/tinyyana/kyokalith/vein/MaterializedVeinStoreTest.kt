package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.db.KyokalithDatabase
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterializedVeinStoreTest {

    @Test
    fun `locked position survives reload and reads back the exact stored decision`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = MaterializedVeinStore(db)
            val chunk = EpochedChunk("world", 0, 0, 0)
            val pos = LocalPos(3, 12, 9)
            val hit = MaterializedVein(oreType = "diamond", veinId = "abc123", material = "DIAMOND_ORE")

            assertNull(store.find(chunk, pos))
            assertTrue(store.upsertAll(chunk, mapOf(pos to hit)))
            assertEquals(hit, store.find(chunk, pos))

            // 新的 store 實例模擬重啟後從資料庫重新載入,結果必須完全一致
            val reloaded = MaterializedVeinStore(db)
            assertEquals(hit, reloaded.find(chunk, pos))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `a whole neighborhood batch is written and read back together`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = MaterializedVeinStore(db)
            val chunk = EpochedChunk("world", 2, -3, 0)
            val trigger = LocalPos(8, 15, 8)
            val entries = mapOf(
                trigger to MaterializedVein("diamond", "vein-trigger", "DIAMOND_ORE"),
                LocalPos(9, 15, 8) to MaterializedVein("diamond", "vein-trigger", "DIAMOND_ORE"),
                LocalPos(7, 15, 8) to MaterializedVein(null, null, "STONE"), // locked miss inside the same ball
                LocalPos(8, 16, 8) to MaterializedVein("diamond", "vein-trigger", "DIAMOND_ORE"),
            )

            assertTrue(store.upsertAll(chunk, entries))

            entries.forEach { (pos, vein) -> assertEquals(vein, store.find(chunk, pos)) }
            assertEquals(entries.size, store.count())
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `locked miss is a real stored row, not treated as absent`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = MaterializedVeinStore(db)
            val chunk = EpochedChunk("world", 0, 0, 0)
            val pos = LocalPos(1, 1, 1)
            val miss = MaterializedVein(oreType = null, veinId = null, material = "NETHERRACK")

            store.upsertAll(chunk, mapOf(pos to miss))

            val found = store.find(chunk, pos)
            assertEquals(miss, found)
            assertNull(found?.oreType)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `clearEpoch removes only the target chunk and epoch`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = MaterializedVeinStore(db)
            val oldEpoch = EpochedChunk("world", 5, 5, 0)
            val newEpoch = EpochedChunk("world", 5, 5, 1)
            val otherChunk = EpochedChunk("world", 6, 5, 0)
            val pos = LocalPos(4, 20, 4)
            val vein = MaterializedVein("iron", "v1", "IRON_ORE")

            store.upsertAll(oldEpoch, mapOf(pos to vein))
            store.upsertAll(newEpoch, mapOf(pos to vein))
            store.upsertAll(otherChunk, mapOf(pos to vein))

            store.clearEpoch(oldEpoch)

            assertNull(store.find(oldEpoch, pos), "舊 epoch 的鎖定應該被清除")
            assertEquals(vein, store.find(newEpoch, pos), "新 epoch 不受影響")
            assertEquals(vein, store.find(otherChunk, pos), "其他 chunk 不受影響")

            // 重新從資料庫載入,確認清除確實落地而不只是記憶體快取
            val reloaded = MaterializedVeinStore(db)
            assertNull(reloaded.find(oldEpoch, pos))
            assertEquals(vein, reloaded.find(newEpoch, pos))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `upsertAll is a no-op success for an empty batch`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = MaterializedVeinStore(db)
            assertTrue(store.upsertAll(EpochedChunk("world", 0, 0, 0), emptyMap()))
            assertEquals(0, store.count())
        } finally {
            file.deleteIfExists()
        }
    }
}
