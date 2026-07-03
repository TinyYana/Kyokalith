package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkEpochStoreTest {

    @Test
    fun `defaults to zero and increments persist across instances`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = ChunkEpochStore(db)
            val coord = ChunkCoord("world", 4, -2)

            assertEquals(0, store.get(coord))
            assertEquals(1, store.increment(coord))
            assertEquals(2, store.increment(coord))

            val reloaded = ChunkEpochStore(db)
            assertEquals(2, reloaded.get(coord))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `epoch increments only affect the target chunk`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = ChunkEpochStore(db)
            val target = ChunkCoord("world", 0, 0)
            val neighbor = ChunkCoord("world", 0, 1)

            store.increment(target)

            assertEquals(1, store.get(target))
            assertEquals(0, store.get(neighbor))
        } finally {
            file.deleteIfExists()
        }
    }
}
