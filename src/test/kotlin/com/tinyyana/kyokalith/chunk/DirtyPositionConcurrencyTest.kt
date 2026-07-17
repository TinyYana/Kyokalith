package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirtyPositionConcurrencyTest {

    private fun store(): Pair<DirtyPositionStore, File> {
        val dir = Files.createTempDirectory("kyokalith-dirty").toFile()
        val db = KyokalithDatabase(File(dir, "test.db")).apply { init() }
        return DirtyPositionStore(db) to dir
    }

    private fun pos(i: Int) = LocalPos(i % 16, i, (i / 16) % 16)

    @Test
    fun `concurrent markDirty and flushAll neither throw nor lose positions`() {
        val (store, dir) = store()
        try {
            val chunk = EpochedChunk("world", 0, 0, 0)
            val preloaded = 40_000
            repeat(preloaded) { store.markDirty(chunk, pos(it)) }

            val added = 40_000
            val failure = AtomicReference<Throwable?>()
            val start = CountDownLatch(1)

            val writer = Thread {
                start.await()
                runCatching { repeat(added) { store.markDirty(chunk, pos(preloaded + it)) } }
                    .onFailure { failure.compareAndSet(null, it) }
            }
            val flusher = Thread {
                start.await()
                runCatching { while (writer.isAlive) store.flushAll() }
                    .onFailure { failure.compareAndSet(null, it) }
            }

            writer.start(); flusher.start(); start.countDown()
            writer.join(60_000); flusher.join(60_000)
            assertNull(failure.get(), "併發 markDirty/flushAll 爆了:${failure.get()}")

            store.flushAll()
            val reloaded = DirtyPositionStore(KyokalithDatabase(File(dir, "test.db")))
            val missing = (0 until preloaded + added).filterNot { reloaded.isDirty(chunk, pos(it)) }
            assertTrue(missing.isEmpty(), "flush 後有 ${missing.size} 個 dirty 位置沒落地(前幾個:${missing.take(5)})")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `encode survives a concurrent set`() {
        val set = java.util.concurrent.ConcurrentHashMap.newKeySet<LocalPos>()
        set.addAll(listOf(LocalPos(0, -64, 15), LocalPos(3, 320, 7)))
        assertEquals(set, DirtyPositionStore.decode(DirtyPositionStore.encode(set)))
    }
}
