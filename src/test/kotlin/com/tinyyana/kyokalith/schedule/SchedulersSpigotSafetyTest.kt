package com.tinyyana.kyokalith.schedule

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulersSpigotSafetyTest {

    private fun classFile(name: String): File {
        val f = File("build/classes/kotlin/main/com/tinyyana/kyokalith/schedule/$name.class")
        assertTrue(f.isFile, "找不到編譯產物 ${f.path} —— 這個測試依賴 :compileKotlin 的輸出")
        return f
    }

    @Test
    fun `Schedulers carries no reference to Folia classes`() {
        val bytes = classFile("Schedulers").readBytes()
        val refs = String(bytes, Charsets.ISO_8859_1)
        assertFalse(
            refs.contains("io/papermc/paper/threadedregions/scheduler"),
            "Schedulers 直接引用了 Folia 的 scheduler 類別:Spigot 上會 NoClassDefFoundError。" +
                "Folia API 的呼叫必須留在 src/folia 的 FoliaSchedulers,由 isFolia 分支延後載入。",
        )
    }

    @Test
    fun `Folia detection probes a class that folia-api does not provide`() {
        val bytes = classFile("Schedulers").readBytes()
        val refs = String(bytes, Charsets.ISO_8859_1)
        assertTrue(
            refs.contains("io.papermc.paper.threadedregions.RegionizedServer"),
            "偵測用的類別名不見了:isFolia 只要抓錯名字就會永遠 false,Folia 上會靜默退回 " +
                "Bukkit.getScheduler() 然後拋 UnsupportedOperationException。",
        )
        assertFalse(
            Schedulers.isFolia,
            "測試環境不是 Folia,isFolia 卻是 true —— 偵測邏輯反了",
        )
    }
}
