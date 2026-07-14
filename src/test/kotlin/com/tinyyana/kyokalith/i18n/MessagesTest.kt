package com.tinyyana.kyokalith.i18n

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {

    private fun section(vararg pairs: Pair<String, String>) =
        YamlConfiguration().apply { pairs.forEach { (k, v) -> set(k, v) } }

    @Test
    fun `later sections override earlier ones and missing keys fall back`() {
        val en = section("greet" to "hello", "only-en" to "en-only")
        val zh = section("greet" to "你好")
        val messages = Messages.fromSections(en, zh, null)
        assertEquals("你好", messages["greet"])
        assertEquals("en-only", messages["only-en"])
    }

    @Test
    fun `placeholders are substituted and ampersand color codes translated`() {
        val messages = Messages.fromSections(section("hits" to "&a{count} hits at {x} {y}"))
        assertEquals("§a3 hits at 1 -2", messages["hits", "count" to 3, "x" to 1, "y" to -2])
    }

    @Test
    fun `unknown key returns the key itself instead of crashing the command`() {
        val messages = Messages.fromSections(section("a" to "b"))
        assertEquals("nope", messages["nope"])
    }
}
