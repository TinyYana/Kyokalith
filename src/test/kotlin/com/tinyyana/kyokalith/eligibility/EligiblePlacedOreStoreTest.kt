package com.tinyyana.kyokalith.eligibility

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.UUID
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EligiblePlacedOreStoreTest {

    private fun sample(x: Int = 10, y: Int = 20, z: Int = 30) = EligiblePlacedOre(
        world = "world",
        x = x,
        y = y,
        z = z,
        epoch = 0,
        oreType = "diamond",
        oreMaterial = "DIAMOND_ORE",
        tokenId = UUID.randomUUID().toString(),
        placedBy = UUID.randomUUID(),
        placedAtMillis = 1_000L,
    )

    @Test
    fun `insert then find round-trips`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val ore = sample()

            store.insert(ore)
            val found = store.find(ore.world, ore.x, ore.y, ore.z)

            assertEquals(ore, found)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `remove deletes and returns the removed row`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val ore = sample()
            store.insert(ore)

            val removed = store.remove(ore.world, ore.x, ore.y, ore.z)

            assertEquals(ore, removed)
            assertNull(store.find(ore.world, ore.x, ore.y, ore.z))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `removeInChunk only clears the matching chunk`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val inChunk = sample(x = 16, y = 20, z = 16) // chunk (1,1)
            val outsideChunk = sample(x = 100, y = 20, z = 100) // chunk (6,6)
            store.insert(inChunk)
            store.insert(outsideChunk)

            store.removeInChunk("world", 1, 1)

            assertNull(store.find(inChunk.world, inChunk.x, inChunk.y, inChunk.z))
            assertEquals(outsideChunk, store.find(outsideChunk.world, outsideChunk.x, outsideChunk.y, outsideChunk.z))
        } finally {
            file.deleteIfExists()
        }
    }
}
