package com.tinyyana.kyokalith.db

import java.sql.DriverManager
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class KyokalithDatabaseMigrationTest {
    @Test
    fun `legacy schema initializes without changing salt`() = withDatabase { db, url ->
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                it.executeUpdate("INSERT INTO meta VALUES ('salt', 'legacy-salt')")
                it.executeUpdate("INSERT INTO meta VALUES ('schema_version', '1')")
            }
        }

        db.init()

        assertEquals("legacy-salt", db.getMeta("salt"))
        assertEquals(KyokalithDatabase.VEIN_ALGORITHM_VERSION, db.getMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY))
        assertEquals(0, count(url, "materialized_positions"))
    }

    @Test
    fun `version two algorithm invalidates only materialized positions`() = withDatabase { db, url ->
        db.init()
        db.setMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY, "2")
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate("INSERT INTO chunk_epoch VALUES ('world', 1, 2, 3)")
                it.executeUpdate("INSERT INTO dirty_positions VALUES ('world', 1, 2, 3, X'0102')")
                it.executeUpdate(
                    "INSERT INTO eligible_placed_ores VALUES " +
                        "('world', 1, 2, 3, 3, 'diamond', 'DIAMOND_ORE', 'token', 'player', 1)",
                )
                it.executeUpdate("INSERT INTO suspended_chunks VALUES ('world', 1, 2, 'test', 1)")
                it.executeUpdate(
                    "INSERT INTO materialized_positions VALUES " +
                        "('world', 1, 2, 3, 1, 2, 3, 'diamond', 'legacy-vein', 'DIAMOND_ORE')",
                )
            }
        }
        val salt = db.getMeta("salt")

        db.init()

        assertEquals(salt, db.getMeta("salt"))
        assertEquals("1", db.getMeta("schema_version"))
        assertEquals(1, count(url, "chunk_epoch"))
        assertEquals(1, count(url, "dirty_positions"))
        assertEquals(1, count(url, "eligible_placed_ores"))
        assertEquals(1, count(url, "suspended_chunks"))
        assertEquals(0, count(url, "materialized_positions"))
    }

    @Test
    fun `current algorithm init is idempotent`() = withDatabase { db, url ->
        db.init()
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO materialized_positions VALUES " +
                        "('world', 0, 0, 0, 1, 2, 3, 'iron', 'current-vein', 'IRON_ORE')",
                )
            }
        }

        db.init()

        assertEquals(1, count(url, "materialized_positions"))
    }

    @Test
    fun `failed invalidation rolls back rows and algorithm marker together`() = withDatabase { db, url ->
        db.init()
        db.setMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY, "2")
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO materialized_positions VALUES " +
                        "('world', 0, 0, 0, 1, 2, 3, 'iron', 'legacy-vein', 'IRON_ORE')",
                )
                it.executeUpdate(
                    "CREATE TRIGGER reject_lock_clear BEFORE DELETE ON materialized_positions " +
                        "BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
                )
            }
        }

        assertFails { db.init() }

        assertEquals(1, count(url, "materialized_positions"))
        assertEquals("2", db.getMeta(KyokalithDatabase.VEIN_ALGORITHM_META_KEY))
    }

    private fun count(url: String, table: String): Int =
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun withDatabase(block: (KyokalithDatabase, String) -> Unit) {
        val file = createTempFile(suffix = ".db")
        try {
            block(KyokalithDatabase(file.toFile()), "jdbc:sqlite:${file.toFile().absolutePath}")
        } finally {
            file.deleteIfExists()
        }
    }
}
