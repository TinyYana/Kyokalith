package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirtyPositionStoreTest {

    @Test
    fun `codec round-trips positions`() {
        val positions = setOf(LocalPos(0, 64, 15), LocalPos(15, -60, 0), LocalPos(7, 0, 7))
        val decoded = DirtyPositionStore.decode(DirtyPositionStore.encode(positions))
        assertTrue(decoded == positions)
    }

    @Test
    fun `codec handles empty set`() {
        assertTrue(DirtyPositionStore.decode(DirtyPositionStore.encode(emptySet())).isEmpty())
        assertTrue(DirtyPositionStore.decode(null).isEmpty())
    }

    @Test
    fun `marked position is dirty and survives flush`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = DirtyPositionStore(db)
            val chunk = EpochedChunk("world", 1, 2, 0)
            val pos = LocalPos(3, 12, 9)

            assertFalse(store.isDirty(chunk, pos))
            store.markDirty(chunk, pos)
            assertTrue(store.isDirty(chunk, pos))

            store.flush(chunk)

            // 新的 store 實例模擬重啟後從資料庫重新載入
            val reloaded = DirtyPositionStore(db)
            assertTrue(reloaded.isDirty(chunk, pos))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `clearEpoch removes persisted dirty positions`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = DirtyPositionStore(db)
            val chunk = EpochedChunk("world", 0, 0, 0)
            store.markDirty(chunk, LocalPos(1, 1, 1))
            store.flush(chunk)

            store.clearEpoch(chunk)

            val reloaded = DirtyPositionStore(db)
            assertFalse(reloaded.isDirty(chunk, LocalPos(1, 1, 1)))
        } finally {
            file.deleteIfExists()
        }
    }
}
