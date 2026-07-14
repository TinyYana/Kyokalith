package com.tinyyana.kyokalith.i18n

import org.bukkit.ChatColor
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader

/**
 * 管理指令輸出文字表。key 一律 kebab-case 且不含 '.'(Bukkit YAML 會把 '.' 當路徑分隔字元)。
 *
 * 覆蓋順序(後者蓋前者,缺 key 自動回退):
 * 1. jar 內建 en(完整 fallback,保證任何 key 都有值)
 * 2. jar 內建指定語系(如 zh_TW)
 * 3. 伺服器 `plugins/Kyokalith/lang/<locale>.yml`(伺服主自訂;只寫想改的 key 也行)
 *
 * 色碼用 &,佔位符用 {name}。config.yml 的 `locale` 指定語系;
 * 填內建沒有的語系名並自備 lang 檔即可加新語言,不用改程式。
 */
class Messages private constructor(private val table: Map<String, String>) {

    /** 取文字並代入佔位符;未知 key 直接回傳 key 本身(可見即可察覺,不炸指令)。 */
    operator fun get(key: String, vararg args: Pair<String, Any?>): String {
        var text = table[key] ?: return key
        for ((name, value) in args) text = text.replace("{$name}", value.toString())
        return text
    }

    companion object {
        const val DEFAULT_LOCALE = "en"
        val BUNDLED_LOCALES = listOf("en", "zh_TW")

        fun load(plugin: JavaPlugin, locale: String): Messages {
            val custom = File(File(plugin.dataFolder, "lang"), "$locale.yml")
                .takeIf { it.isFile }
                ?.let { YamlConfiguration.loadConfiguration(it) }
            return fromSections(bundled(plugin, DEFAULT_LOCALE), bundled(plugin, locale), custom)
        }

        /** 把內建語系檔複製到資料夾當自訂範本;已存在就不動(伺服主的編輯永遠優先)。 */
        fun saveBundledTemplates(plugin: JavaPlugin) {
            for (locale in BUNDLED_LOCALES) {
                if (!File(File(plugin.dataFolder, "lang"), "$locale.yml").isFile) {
                    plugin.saveResource("lang/$locale.yml", false)
                }
            }
        }

        /** 合併邏輯與 [load] 分離,讓單元測試不需要 plugin 實例。 */
        fun fromSections(vararg sections: ConfigurationSection?): Messages {
            val merged = mutableMapOf<String, String>()
            for (section in sections) {
                section?.getKeys(false)?.forEach { key ->
                    section.getString(key)?.let { merged[key] = ChatColor.translateAlternateColorCodes('&', it) }
                }
            }
            return Messages(merged)
        }

        private fun bundled(plugin: JavaPlugin, locale: String): ConfigurationSection? =
            plugin.getResource("lang/$locale.yml")?.let {
                YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
            }
    }
}
