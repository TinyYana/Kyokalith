package com.tinyyana.kyokalith.ore

import org.bukkit.configuration.ConfigurationSection

/** 從 config.yml 的 `ores:` 節點載入礦種表(data-driven,新增礦種不需要改程式碼)。 */
class OreRegistry private constructor(
    private val ores: Map<String, OreDefinition>,
    private val oreMaterials: Set<String>,
) {

    fun all(): Collection<OreDefinition> = ores.values

    fun enabled(): Collection<OreDefinition> = ores.values.filter { it.enabled }

    operator fun get(oreType: String): OreDefinition? = ores[oreType]

    fun isOreMaterial(material: String): Boolean = material in oreMaterials

    companion object {
        fun load(section: ConfigurationSection?): Result<OreRegistry> = runCatching {
            requireNotNull(section) { "config.yml 缺少 ores 節點" }
            val defs = section.getKeys(false).associateWith { oreType ->
                val ore = section.getConfigurationSection(oreType)
                    ?: error("ores.$oreType 不是合法的設定節點")
                val materials = ore.getConfigurationSection("materials")
                OreDefinition(
                    oreType = oreType,
                    enabled = ore.getBoolean("enabled", true),
                    stoneMaterial = materials?.getString("stone"),
                    deepslateMaterial = materials?.getString("deepslate"),
                    yMin = ore.getInt("y_min"),
                    yMax = ore.getInt("y_max"),
                    preferredY = ore.getInt("preferred_y"),
                    density = ore.getDouble("density", 1.0),
                    exposedDensityMultiplier = ore.getDouble("exposed_density_multiplier", 1.0),
                    veinSizeMin = ore.getInt("vein_size_min", 1),
                    veinSizeMax = ore.getInt("vein_size_max", 1),
                    cellChance = ore.getDouble("cell_chance"),
                )
            }
            require(defs.isNotEmpty()) { "config.yml 的 ores 節點不能是空的" }
            val oreMaterials = defs.values
                .flatMap { listOfNotNull(it.stoneMaterial, it.deepslateMaterial) }
                .toSet()
            OreRegistry(defs, oreMaterials)
        }
    }
}
