# Kyokalith

[English](README.md)

**不動世界生成的反 X-Ray 礦層。** 原版怎麼生就怎麼生,不混淆封包、不清礦重生、不掃區塊——被實心方塊完全包住的礦,一律當成**誘餌**:X-Ray / freecam / 種子地圖看得到,但那顆方塊的真假**要等它第一次被挖開露出來的瞬間才決定**。

作弊者對著透視裡直接看到的礦一路挖過去,挖到的是它的基底方塊;老實玩家在洞穴壁上看到的礦,永遠是真的。

> 這是為 Lycohinya 伺服器打造、並已在其上長期實戰運行的插件,但反 X-Ray 核心是通用的——任何 Spigot/Paper 26.2 伺服器單獨裝 Kyokalith 都能用。第二個功能(挖礦檢定令牌 `OreCheckTriggerEvent`)是給其他插件接的整合點,沒人接就只是靜靜地不發事件。

## 它到底怎麼運作

傳統反 X-Ray 走兩條路,兩條都有代價:封包混淆(騙不過 freecam、吃 CPU)、或是清空礦再自己重生(要掃區塊,TPS 會死)。Kyokalith 走第三條:

1. **世界生成完全不動。** 原版的礦還在原地。生成時就已經露在外面的礦(洞穴壁、峽谷面)是**真的**,永遠不會被改。
2. **完全被包住的礦是誘餌。** 它存在於世界檔裡,X-Ray 看得到,但跟「這裡有沒有礦」無關。
3. **唯一的觸發點是「方塊消失」。** 玩家挖掉、爆炸、燒掉、活塞推走——事件發生後**延後一 tick**,只看被移除方塊的**六個面鄰居**。
4. **只處理「這次才第一次露出來」的鄰居。** 一個方塊如果本來就有另一個面通風,代表玩家早就看得到它了,碰都不碰。這條規則同時擋掉兩件事:礦不會在玩家正盯著的牆上憑空長出來,已經看到的真礦也不會被抹掉。
5. **決定真假。** 用確定性函數 `f(salt, world, epoch, x, y, z, 基底方塊, 維度)`:命中 → 誘餌變成真礦(或普通基底方塊**變成**礦);沒命中 → 誘餌變回基底方塊(石頭/深板岩/地獄岩)。因為方塊還沒送到客戶端,老實玩家眼中什麼都沒發生。

每次事件的成本上限是常數:`被移除的方塊數 × 6`。**沒有掃描、沒有排程掃描任務、沒有 `ChunkLoadEvent` 工作。**

> 目前的誘餌模型取代了 1.0 之前的掃描式做法(v0.3):當時用「資料包清礦 + 掃描區塊重生」,121 個 forceload 區塊就把 TPS 壓到 18.9;把掃描整套刪掉之後回到 20.1。**任何「順便掃一下區塊」的想法在這個插件裡都是紅線。**

### 蓋起來的礦不會被重新決算

玩家放下的方塊(以及雪/冰生成、實體放置、活塞推到的目的地)會被標記為 **dirty**。dirty 的方塊永遠不會被材質化,而且**挖掉 dirty 方塊也不會去解析它的鄰居**。

理由:一個玩家放的方塊底下如果藏著東西,那東西在被蓋住之前一定已經露出來過了。這條規則同時擋掉「把誘餌蓋起來、再挖開來跳過解析」的漏洞,也保證「把真礦蓋起來、晚點再挖開,它還在」。活塞把最後一層遮蔽拉走也算「移除」,不能拿活塞偷看未決定的誘餌。

## 需求

| | |
|---|---|
| 伺服器 | **Spigot、Paper 或 Folia 26.2**(以 Spigot API 編譯;正式環境跑 Paper) |
| Java | **25** |
| 硬相依 | 無 |
| 軟相依 | NatureRevive(有裝才會接區塊重生事件,反射載入) |

Kotlin stdlib 與 SQLite 驅動由 Bukkit library loader 在啟動時下載,**不 shade 進 jar**。

## 安裝

1. 把 `Kyokalith-<版本>.jar` 丟進 `plugins/`,啟動。
2. 預設 `config.yml` 會生成,11 種礦(含地獄礦)全部啟用,直接可用。
3. 第一次啟動會在 `plugins/Kyokalith/kyokalith.db` 產生一組隨機 `salt`。

> ⚠ **`salt` 產生後絕對不要刪 DB 或重置它。** salt 是「真礦位置與世界種子無關」的來源;重置等於把全世界所有還沒挖開的礦脈重骰一次。

## 升級

設定升級是自動的:啟動時新 key 會以預設值併進你既有的 `config.yml`,已有的值不會被覆蓋。注意 `locale` 預設是 `en`——要中文輸出請設 `locale: zh_TW`。

## 指令

`/kyokalith`(別名 `/kyo`)。**所有子指令都需要 `kyokalith.admin`。**

| 子指令 | 參數 | 做什麼 | 誰能用 |
|---|---|---|---|
| `stats` | – | 礦種數、eligible 方塊數、暫停區塊數、NatureRevive 橋接狀態 | 主控台可 |
| `inspect` | `<x> <y> <z> [world]` | 傾印該座標的 epoch、方塊、dirty/暫停旗標、礦脈函數結果 | 主控台可 |
| `preview` | `[半徑]` 或 `<半徑> <x> <y> <z> [world]` | 暴力掃一個立方體,回報命中數與最多 12 個範例座標 | 短式限玩家 |
| `sample` | `volume [半徑]` | 同上,只回報 `命中 / 掃描` | 限玩家 |
| `resolve` | `<x> <y> <z> [world]` | 對該座標重跑一次首次曝光解析(`f` 是確定性的,重跑安全) | 主控台可 |
| `suspend` | `<cx> <cz> <理由...>` | 暫停該區塊的材質化 | 限玩家 |
| `resume` | `<cx> <cz>` | 解除暫停 | 限玩家 |
| `markeligible` | `[x y z]` | QA 工具:把方塊標 dirty 並寫入一筆 eligible 令牌 | 限玩家 |
| `giveeligible` | `<玩家> <礦種> <1-64>` | QA 工具:發一疊帶 PDC 令牌的礦方塊 | 主控台可 |

`preview` / `sample` 的半徑夾在 `1..24`——這兩個是**刻意保留的暴力掃描例外**,管理員專用,不在熱路徑上。

## 權限

| 節點 | 預設 | 效果 |
|---|---|---|
| `kyokalith.admin` | `op` | 所有 `/kyo` 子指令 |
| `kyokalith.bypass` | `false` | 持有者挖礦不消耗檢定令牌、不觸發 `OreCheckTriggerEvent`。**注意:誘餌解析照常跑**,這個權限只跳過檢定路徑 |

非生存模式(創造/旁觀/冒險)也不會消耗令牌。

## 設定

`config.yml` 有三塊:`locale`、`database`(檔名、dirty 寫回間隔)與 `ores`(資料驅動的礦種定義,加一種礦不用改程式)。

最常被動到的是礦的 `cell_chance` / `density` / `preferred_y`——**這三個直接等於伺服器經濟的水龍頭**。完整欄位說明、計算公式與紅線在 **[docs/CONFIG.zh-TW.md](docs/CONFIG.zh-TW.md)**。

沒有 `/kyo reload`,設定只在 `onEnable` 讀一次。

### 訊息 / 語系

管理指令輸出可完全自訂。內建語系:`en`、`zh_TW`。在 `config.yml` 設 `locale`,然後改 `plugins/Kyokalith/lang/` 底下的檔案——刪掉的 key 會回退內建文字。要加自己的語言,把 `lang/en.yml` 複製成 `lang/<名稱>.yml` 翻譯後,設 `locale: <名稱>`。

## 給開發者

Kyokalith 對外只有**一個整合點**:`OreCheckTriggerEvent`——玩家在生存模式挖掉一顆「Kyokalith 自己產生/追蹤過」的礦時同步觸發,可取消,`drops` 可改寫。

管理員給的礦、WorldEdit 貼的礦、商店買的礦**沒有令牌**,不會觸發。絲綢之觸會把令牌搬到 ItemStack 上(PDC),重新放下會搬進 DB,所以一顆礦可以交易、搬運、再挖,但**只會燒掉一次檢定**。

```kotlin
@EventHandler
fun onOreCheck(event: OreCheckTriggerEvent) {
    if ((1..20).random() < 15) return          // 檢定失敗:不動 drops = 保留原版掉落
    val bonus = event.drops.firstOrNull()?.clone() ?: return
    event.drops.add(bonus)                     // 檢定成功:多掉一份
}
```

介面契約、欄位、掉落改寫的精確語意、以及「取消 ≠ 不掉東西」這個反直覺點,見 **[docs/API.zh-TW.md](docs/API.zh-TW.md)**。

## 建置

```bash
./gradlew build      # 編譯 + 單元測試 + 出 jar
./gradlew test       # 只跑單元測試(礦脈函數、註冊表、各 store、訊息表)
./gradlew runServer  # 本機 Paper 26.2 測試伺服器
```

`plugin.yml` 的 `libraries:` 裡的 Kotlin 版本**必須跟 `gradle/libs.versions.toml` 一致**,不然編譯用的 stdlib 跟執行期載入的不是同一份。

## 資料

單一 SQLite 檔 `plugins/Kyokalith/kyokalith.db`(WAL)。存 `salt`、每區塊的 `epoch`、dirty 位置、已放置的 eligible 礦、暫停中的區塊。詳細 schema 見 [docs/API.zh-TW.md](docs/API.zh-TW.md#資料表)。

## 授權

[TinyYana Universal Software License (TYUSL) 1.0](LICENSE)——可自由使用、修改、整合、散布(含商業伺服器);但未經書面同意,**不得**把插件本身拿去販售或重新包裝成付費產品/服務。

TinyYana · [tinyyana.com](https://tinyyana.com)
