package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendedChunkStoreTest {

    @Test
    fun `suspend and resume update cached status`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = SuspendedChunkStore(db)
            val coord = ChunkCoord("world", 3, -4)

            assertFalse(store.isSuspended(coord))

            store.suspend(coord, "test")
            assertTrue(store.isSuspended(coord))

            store.resume(coord)
            assertFalse(store.isSuspended(coord))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `suspended status persists across store instances`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val coord = ChunkCoord("world", 0, 0)

            SuspendedChunkStore(db).suspend(coord, "test")

            val reloaded = SuspendedChunkStore(db)
            assertTrue(reloaded.isSuspended(coord))
        } finally {
            file.deleteIfExists()
        }
    }
}
