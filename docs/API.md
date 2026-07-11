# Kyokalith 開發者參考

Kyokalith 對外只有**一個**整合點:`OreCheckTriggerEvent`。沒有 `ServicesManager` 註冊、沒有 `-api` artifact、沒有介面層——刻意的。要接的東西只有一件,就用 Bukkit 原生的事件機制。

---

## `OreCheckTriggerEvent`

`com.tinyyana.kyokalith.event.OreCheckTriggerEvent` — 標準 Bukkit `Event`,實作 `Cancellable`,**同步觸發**。

### 什麼時候會發

玩家挖掉一顆礦,而且**全部條件成立**時:

- 玩家在**生存模式**(創造/旁觀/冒險不發)
- 玩家**沒有** `kyokalith.bypass`
- 這顆礦帶有 Kyokalith 的**資格令牌**(下面說明)
- **不是**絲綢之觸(絲綢之觸走另一條路:令牌搬到 ItemStack 上)
- 原版掉落物非空

爆炸、活塞、燒毀**不會**觸發——`TriggerSource` 目前只有 `PLAYER_BREAK` 一個值。爆炸只會把 DB 裡的 eligible 紀錄清掉。

### 欄位

```kotlin
class OreCheckTriggerEvent(
    val player: Player,
    val blockLocation: Location,
    val oreMaterial: Material,
    val oreType: String,                    // config.yml 裡的礦種 id,例如 "diamond"
    val tool: ItemStack,                    // clone,改它沒用
    val fortuneLevel: Int,
    val drops: MutableList<ItemStack>,      // ← 唯一可以改的東西
    val triggerSource: TriggerSource,       // PLAYER_BREAK
    val eligibilitySource: EligibilitySource, // NATURAL_BLOCK / PLACED_BLOCK
) : Event(), Cancellable
```

### ⚠ 掉落改寫的語意(反直覺,先讀這段)

**「取消事件」不等於「不掉東西」,而是「保留原版掉落」。**

判定邏輯是這樣:

| 你做的事 | 結果 |
|---|---|
| `event.isCancelled = true` | **保留原版掉落**,Kyokalith 完全不插手 |
| 完全不動 `drops` | **保留原版掉落**(內容跟原本一樣 = 視為沒改) |
| 修改 `drops`(加/刪/換) | 原本掉出來的所有 item entity 被 `remove()`,然後把 `drops` 裡每個非空氣、`amount > 0` 的 stack 用 `dropItemNaturally` 重新丟在方塊位置 |

所以要「這次挖礦什麼都不掉」,是 `event.drops.clear()`,**不是** `event.isCancelled = true`。

### 範例

```kotlin
class OreCheckListener : Listener {
    @EventHandler
    fun onOreCheck(event: OreCheckTriggerEvent) {
        val total = (1..20).random() + bonusOf(event.player)
        when {
            total >= 20 -> {                       // 大成功:雙倍
                val bonus = event.drops.map { it.clone() }
                event.drops.addAll(bonus)
            }
            total >= 15 -> {                       // 成功:多一顆
                event.drops.firstOrNull()?.clone()?.let { event.drops.add(it) }
            }
            else -> return                         // 失敗:不動 = 原版掉落
        }
    }
}
```

`plugin.yml` 裡把 Kyokalith 放 `softdepend`,程式碼用**反射**取事件類別,就能做到「沒裝 Kyokalith 也能跑」——LycohinyaCore 就是這樣接的,沒有編譯期相依。

---

## 資格令牌(Eligibility Token)

這是「哪顆礦能觸發檢定」的規則。**不是所有礦方塊都能。**

| 這顆礦怎麼來的 | 有令牌? |
|---|---|
| Kyokalith 首次曝光解析時產生的 | ✅ 有(`NATURAL_BLOCK`) |
| 玩家用絲綢之觸挖走、再放回去的 | ✅ 有(`PLACED_BLOCK`) |
| 管理員 `/give`、WorldEdit 貼的、商店買的 | ❌ 沒有 |
| 原版生成時就露在洞穴壁上的 | ❌ 沒有(它是真礦,但不是 Kyokalith 產的) |

**一顆礦只會燒掉一次檢定。** 生命週期:

```
首次曝光解析 → 方塊帶令牌
   ├─ 一般挖掘 ────→ 觸發 OreCheckTriggerEvent,令牌消耗
   └─ 絲綢之觸挖掘 → 令牌搬到 ItemStack 的 PDC 上,不觸發
         └─ 放回世界 → 令牌搬進 eligible_placed_ores 表
               └─ 再挖 → 觸發事件(eligibilitySource = PLACED_BLOCK),令牌消耗
```

所以帶令牌的礦可以交易、搬運、囤起來、再挖,但**檢定只會發生一次**。

**ItemStack 上的 PDC**(namespace `kyokalith`):`eligible` (BYTE) / `ore_type` (STRING) / `origin_world` (STRING) / `origin_epoch` (INTEGER) / `token_id` (STRING, UUID)。

**沒有免費重骰**:`BlockBreakEvent` 時會先暫存一筆 `PendingBreak` 並排一個下一 tick 的回收器。如果 `BlockDropItemEvent` 從來沒到(例如被別的插件把掉落擋掉了),令牌**照樣消耗**,並寫一行 `fine` 級的 log。

---

## 確定性礦脈函數 `f`

```
f(salt, world, epoch, oreType, cellX, cellY, cellZ) -> 命中/不命中
```

- **純函數**:splitmix64 雜湊,不讀世界狀態、不讀 DB。
- **16³ 的 cell**,候選來自 3×3×3 的鄰居 cell,同一格被多條脈命中時取 `veinId` 最小的。
- **冪等**:同樣的輸入永遠同樣的輸出。這就是 `/kyo resolve` 可以安全重跑的原因——你懷疑某個座標漏掉了事件,直接對它重跑一次,結果一定跟「當初應該發生的」一致。
- **`salt` 讓真礦位置與世界種子脫鉤**:種子地圖網站算得出原版礦在哪,但算不出 Kyokalith 的礦在哪。

**`epoch`**:每個區塊一個計數器。區塊被重生(NatureRevive)時 `epoch += 1`,等於**只對那個區塊**重骰 `f`,不影響世界其他地方。

**Cell 快取**:LRU 20 萬筆,key 含 `epoch`,所以區塊重生後舊項目自然老化掉,不需要失效掃描。快取掉了對正確性沒有影響(純函數重算就好)。

---

## 資料表

`plugins/Kyokalith/kyokalith.db`,SQLite,`journal_mode=WAL`。每次操作開一條新連線(沒有連線池)。

```sql
meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)
  -- salt(隨機 UUID,不能重置)、schema_version、created_at

chunk_epoch(world, cx, cz, epoch, PRIMARY KEY(world, cx, cz))

dirty_positions(world, cx, cz, epoch, data BLOB, PRIMARY KEY(world, cx, cz, epoch))
  -- data 不是 bitset,是 UTF-8 文字 "lx,y,lz;lx,y,lz;…"
  -- 刻意的:量沒大到需要壓縮編碼,可讀性優先

eligible_placed_ores(world, x, y, z, epoch, ore_type, ore_material,
                     token_id, placed_by, placed_at, PRIMARY KEY(world, x, y, z))

suspended_chunks(world, cx, cz, reason, created_at, PRIMARY KEY(world, cx, cz))
```

`schema_version` 有寫但沒讀——目前沒有 migration 程式碼。

---

## 效能契約(改動前先讀)

**每個事件的成本上限是常數:`被移除的方塊數 × 6`。** 只看六個面鄰居,只在主執行緒,延後一 tick。

**不准做的事:**

- ❌ 掃描區塊(任何形式:生成時掃、`ChunkLoadEvent` 掃、排程掃、shell 掃)
- ❌ 在事件路徑上查 DB
- ❌ 遍歷全體玩家

v0.3 用掃描模型,121 個 forceload 區塊把 TPS 壓到 **18.9**;v0.4 刪掉整套掃描(含 23 個資料包 `configured_feature` JSON)之後是 **20.1**。這不是理論上的顧慮,是實測過的。

唯一保留的暴力掃描是 `/kyo preview` 和 `/kyo sample`——管理員專用、半徑夾在 1..24、跳過未載入的區塊。**這是刻意的例外,不是可以擴大的先例。**

---

## NatureRevive 橋接

反射載入 `engineer.skyouo.plugins.naturerevive.spigot.events.ChunkRegenEvent`,註冊在 `MONITOR`。區塊重生時:

```
暫停該區塊 → epoch += 1 → 丟掉舊 epoch 的 dirty 位置
           → 丟掉該區塊的 eligible 紀錄 → 解除暫停
```

不需要重新掃描:重生本身就把原版礦放回去了 = 一層全新的誘餌,`epoch + 1` 只對那個區塊重骰 `f`。

**橋接拋例外的話,區塊會被刻意留在暫停狀態**(fail-closed)——寧可那個區塊完全不材質化,也不要在不一致的狀態下運作。

---

## 已知的粗糙處

老實說在前面,免得你以為是自己看錯:

- `gradle.properties` 的版本是 `0.1.0`,但設計文件、commit、這份 README 講的模型都叫 **v0.4**。版本號還沒跟上。
- `/kyo giveeligible` 產生的 ItemStack `originEpoch` 寫死 `0`。QA 工具夠用,但不要拿它當「正式發放令牌」的手段。
- `EligibleOrePdc.clear()` 有定義但沒人呼叫。
- 監聽器與 `KyoCommand` **沒有測試**(沒有 MockBukkit / 伺服器測試框架)。有測試的是礦脈函數、註冊表、三個 store,以及首次曝光的判定邏輯。
