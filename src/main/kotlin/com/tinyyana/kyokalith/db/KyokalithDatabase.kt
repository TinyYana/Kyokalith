package com.tinyyana.kyokalith.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * Schema per docs/KYOKALITH_SPEC.md §14. WAL 由 §10.4 要求(dirty positions 是安全關鍵資料)。
 */
class KyokalithDatabase(private val file: File) {

    fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    fun init() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("PRAGMA journal_mode=WAL")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chunk_epoch (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        PRIMARY KEY(world, cx, cz)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS dirty_positions (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        data BLOB NOT NULL,
                        PRIMARY KEY(world, cx, cz, epoch)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS eligible_placed_ores (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        ore_type TEXT NOT NULL,
                        ore_material TEXT NOT NULL,
                        token_id TEXT,
                        placed_by TEXT,
                        placed_at INTEGER NOT NULL,
                        PRIMARY KEY(world, x, y, z)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS suspended_chunks (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(world, cx, cz)
                    )
                    """.trimIndent(),
                )
            }
        }
        ensureSalt()
    }

    fun getMeta(key: String): String? =
        connect().use { conn ->
            conn.prepareStatement("SELECT value FROM meta WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
            }
        }

    fun setMeta(key: String, value: String) {
        connect().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
        }
    }

    /** salt 首次啟動時生成;正式服不允許熱重置(§14.1),此類危險指令留給後續階段。 */
    private fun ensureSalt() {
        if (getMeta("salt") != null) return
        setMeta("salt", UUID.randomUUID().toString())
        setMeta("schema_version", "1")
        setMeta("created_at", Instant.now().toString())
    }
}
