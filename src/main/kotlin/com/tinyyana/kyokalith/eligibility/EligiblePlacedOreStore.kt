package com.tinyyana.kyokalith.eligibility

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * eligible_placed_ores:玩家放置的 qualified 礦物座標(token 生命週期見 docs/API.md)。
 * 座標唯一(world, x, y, z)——同座標重複放置會覆蓋前一筆紀錄。
 */
class EligiblePlacedOreStore(private val db: KyokalithDatabase) {

    fun insert(ore: EligiblePlacedOre) {
        db.connect().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO eligible_placed_ores
                    (world, x, y, z, epoch, ore_type, ore_material, token_id, placed_by, placed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, ore.world)
                stmt.setInt(2, ore.x)
                stmt.setInt(3, ore.y)
                stmt.setInt(4, ore.z)
                stmt.setInt(5, ore.epoch)
                stmt.setString(6, ore.oreType)
                stmt.setString(7, ore.oreMaterial)
                stmt.setString(8, ore.tokenId)
                stmt.setString(9, ore.placedBy?.toString())
                stmt.setLong(10, ore.placedAtMillis)
                stmt.executeUpdate()
            }
        }
    }

    fun find(world: String, x: Int, y: Int, z: Int): EligiblePlacedOre? =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT * FROM eligible_placed_ores WHERE world = ? AND x = ? AND y = ? AND z = ?",
            ).use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, x)
                stmt.setInt(3, y)
                stmt.setInt(4, z)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toEligiblePlacedOre() else null }
            }
        }

    fun remove(world: String, x: Int, y: Int, z: Int): EligiblePlacedOre? {
        val existing = find(world, x, y, z) ?: return null
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM eligible_placed_ores WHERE world = ? AND x = ? AND y = ? AND z = ?",
            ).use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, x)
                stmt.setInt(3, y)
                stmt.setInt(4, z)
                stmt.executeUpdate()
            }
        }
        return existing
    }

    /** NatureRevive 再生 chunk 後,刪除該 chunk 內的 placed eligible ores(§13.2)。 */
    fun removeInChunk(world: String, cx: Int, cz: Int) {
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM eligible_placed_ores WHERE world = ? AND (x >> 4) = ? AND (z >> 4) = ?",
            ).use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, cx)
                stmt.setInt(3, cz)
                stmt.executeUpdate()
            }
        }
    }

    fun count(): Int =
        db.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) AS c FROM eligible_placed_ores").use { rs ->
                    if (rs.next()) rs.getInt("c") else 0
                }
            }
        }

    private fun ResultSet.toEligiblePlacedOre() = EligiblePlacedOre(
        world = getString("world"),
        x = getInt("x"),
        y = getInt("y"),
        z = getInt("z"),
        epoch = getInt("epoch"),
        oreType = getString("ore_type"),
        oreMaterial = getString("ore_material"),
        tokenId = getString("token_id"),
        placedBy = getString("placed_by")?.let { UUID.fromString(it) },
        placedAtMillis = getLong("placed_at"),
    )
}
