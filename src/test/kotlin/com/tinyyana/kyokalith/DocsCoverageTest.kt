package com.tinyyana.kyokalith

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 文件同步檢查:plugin.yml 宣告的每個指令與權限,都必須在 README.md 或 docs 底下的 .md 裡出現。
 *
 * 為什麼做成測試而不是另一套 CI 腳本:build 本來就會跑測試,所以本機 ./gradlew build 跟 CI
 * 檢查的是同一件事,不會有「本機過、CI 紅」的落差,也不用多裝任何工具。
 *
 * 加了指令或權限卻沒寫進文件 -> 這裡會紅。這是刻意的:文件過期是最容易發生的腐爛。
 *
 * build.gradle.kts 的 tasks.test 有宣告 README/docs/plugin.yml 是輸入 —— 少了那段,
 * 「只改文件」不會讓 :test 失效,這個檢查會被 UP-TO-DATE 靜默跳過。
 */
class DocsCoverageTest {

    private val docs: String by lazy {
        val files = buildList {
            add(File("README.md"))
            File("docs").listFiles()?.filter { it.extension == "md" }?.let { addAll(it) }
        }
        val readable = files.filter { it.isFile }
        assertTrue(readable.isNotEmpty(), "找不到 README.md 或 docs 底下的 .md —— 這個插件還沒有文件")
        readable.joinToString("\n") { it.readText() }
    }

    private val pluginYml: YamlConfiguration by lazy {
        val f = File("src/main/resources/plugin.yml")
        assertTrue(f.isFile, "找不到 src/main/resources/plugin.yml")
        YamlConfiguration().apply {
            // 權限節點本身就含 '.'(例如 kyokalith.admin)。Bukkit 預設把 '.' 當成路徑分隔字元,
            // 那樣 getKeys(false) 只會回傳 "kyokalith",檢查就變成永遠通過的空殼(實際踩過)。
            options().pathSeparator('/')
            load(f)
        }
    }

    @Test
    fun `every declared command appears in the docs`() {
        val section = pluginYml.getConfigurationSection("commands")
        val commands = section?.getKeys(false).orEmpty()
        assertTrue(commands.isNotEmpty(), "plugin.yml 沒有 commands 區塊")

        // 用別名寫文件是可以的(例如文件寫 /lyco,而 plugin.yml 宣告的是 lycohinya)。
        val missing = commands.filterNot { command ->
            val names = listOf(command) + section!!.getStringList("$command/aliases")
            names.any { docs.contains("/$it") }
        }
        assertTrue(
            missing.isEmpty(),
            "這些指令沒有寫進文件(用別名寫也算):$missing",
        )
    }

    @Test
    fun `every declared permission appears in the docs`() {
        val permissions = pluginYml.getConfigurationSection("permissions")?.getKeys(false).orEmpty()
        assertTrue(permissions.isNotEmpty(), "plugin.yml 沒有 permissions 區塊")

        val missing = permissions.filterNot { docs.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "這些權限節點沒有寫進文件:$missing",
        )
    }
}
