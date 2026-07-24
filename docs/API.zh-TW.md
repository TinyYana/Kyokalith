# Kyokalith 開發者參考

[English](API.md)

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
    val eligibilitySource: EligibilitySource, // NATURAL_BLOCK / PLACED_BLOCK / WORLDGEN_EXPOSED
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

`plugin.yml` 裡把 Kyokalith 放 `softdepend`,程式碼用**反射**取事件類別,就能做到「沒裝 Kyokalith 也能跑」——最初的使用端就是這樣接的,沒有編譯期相依。

---

## 資格令牌(Eligibility Token)

這是「哪顆礦能觸發檢定」的規則。**不是所有礦方塊都能。**

| 這顆礦怎麼來的 | 有令牌? |
|---|---|
| Kyokalith 首次曝光解析時產生的 | ✅ 有(`NATURAL_BLOCK`) |
| 玩家用絲綢之觸挖走、再放回去的 | ✅ 有(`PLACED_BLOCK`) |
| 其他任何目前站著的真礦、屬於已啟用礦種(例如世界生成時就露在洞穴壁上的) | ✅ 有(`WORLDGEN_EXPOSED`) |
| 管理員 `/give`、WorldEdit 貼的、商店買的,或任何玩家放置的方塊 | ❌ 沒有(放置任何方塊都會把該座標標成 dirty) |

`WORLDGEN_EXPOSED` 存在的理由:已經曝露的礦對 xray 本來就沒有情報價值,排除它只是讓獎勵類插件少了合法的檢定機會,擋不到任何作弊者。

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
- **16³ 的 cell**,候選來自 3×3×3 的鄰居 cell。當**不同礦種**的候選球同時命中同一格時,`priority`(config 欄位,見 CONFIG.md)較大的贏——取代舊版用 `veinId` 雜湊排序的做法(那組排序沒有任何維運意義)。**同一種礦種**的候選球互相重疊時,才退回 `veinId` 當 tie-break(材質反正相同,誰贏都無所謂)。
- **冪等**:同樣的輸入永遠同樣的輸出。這就是 `/kyo resolve` 可以安全重跑的原因——你懷疑某個座標漏掉了事件,直接對它重跑一次,結果一定跟「當初應該發生的」一致。
- **`salt` 讓真礦位置與世界種子脫鉤**:種子地圖網站算得出原版礦在哪,但算不出 Kyokalith 的礦在哪。

**`epoch`**:每個區塊一個計數器。區塊被重生(NatureRevive)時 `epoch += 1`,等於**只對那個區塊**重骰 `f`,不影響世界其他地方。

**Cell 快取**:LRU 20 萬筆,key 含 `epoch`,所以區塊重生後舊項目自然老化掉,不需要失效掃描。快取掉了對正確性沒有影響(純函數重算就好)。

### 首次曝露時鎖定礦脈形狀(`materialized_positions`)

固定 salt/epoch/config 下 `f` 本身就已經是決定性的——但如果之後 `cell_chance`、`vein_size`、`priority`,或幾何演算法本身被調整(這次改動剛好就對 `ancient_debris` 做了這件事),一顆玩家**正在挖到一半**的礦脈,如果調整剛好卡在玩家兩次挖掘之間,還埋著的另一半有可能算出跟已經挖出來的那一半不一致的結果。為了堵住這個縫:任何座標第一次被決算時,Kyokalith 也會(但**不會放置**)決算 5×5×5 局部鄰域內、幾何上屬於同一顆贏家候選球的其他所有座標,並把這些決定一次性批次鎖進 `materialized_positions`。之後這些座標中任何一個真正首次曝露時(可能是版本更新之後),直接讀鎖定的決定,不再呼叫 `f`。

這個機制刻意維持有界、刻意不做任何事:

- **每個事件常數工作量**:最多 124 個額外座標(5×5×5 扣掉觸發座標本身),絕不掃整條礦脈或整個 chunk。比這個視窗還大的礦脈,就讓玩家自然挖過去時一次鎖一小片(每次一個 5×5×5 視窗),分批鎖完。
- **絕不對未曝露的座標呼叫 setBlock。** 鎖定只是 SQLite(或記憶體快取)裡的一筆紀錄,寫的是「這個座標未來真正首次曝露時該套用什麼材質」——不是 `Block.setType`。一個「已鎖定但還沒曝露」的座標,不管是對客戶端還是對 xray 使用者,跟任何其他還埋著的誘餌沒有任何差別:世界資料裡的材質相同,傳給客戶端的封包位元組相同。只有觸發座標本身——這一個 tick 真正正在首次曝露決算的那一個——才會被呼叫 `setType`。
- **觸發座標本身的 miss 不會被記錄。** 如果 `f` 完全沒有命中任何礦種,`materialized_positions` 不會寫入任何東西——世界方塊狀態本身已經是永久記錄(`isNewlyExposed` 保證這個座標不會被決算第二次),記錄每一次 miss 只會讓幾乎每次挖石頭都變成一次同步 SQLite 寫入,違反下面「熱路徑不查 DB」的效能契約。但作為某次 hit 的 5×5×5 視窗**內**被順帶判定成 miss 的鄰居,則會照樣記錄(反正那筆寫入本來就要發生)。

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

materialized_positions(world, cx, cz, epoch, lx, y, lz, ore_type, vein_id, material,
                       PRIMARY KEY(world, cx, cz, epoch, lx, y, lz))
  -- 見上面「首次曝露時鎖定礦脈形狀」。鎖定的是 miss 時 ore_type/vein_id 是 null;
  -- material 永遠有值(這個座標未來曝露時該套用的方塊)。NatureRevive 重生時
  -- 逐 chunk 清除,做法與 dirty_positions 相同。
```

`schema_version` 有寫但沒讀——目前沒有 migration 程式碼。

---

## 效能契約(改動前先讀)

**每個事件的成本上限是常數:`被移除的方塊數 × 6`。** 只看六個面鄰居,延後一 tick,而且一定在「擁有該方塊的執行緒」上跑——Spigot/Paper 是主執行緒,Folia 是擁有它的 region。永遠不要改成 async:各個 store 之所以安全,前提就是同一個 chunk 的寫入永遠來自同一條執行緒。

**不准做的事:**

- ❌ 掃描區塊(任何形式:生成時掃、`ChunkLoadEvent` 掃、排程掃、shell 掃)
- ❌ 在事件路徑上查 DB
- ❌ 遍歷全體玩家

1.0 之前的掃描式模型(v0.3)forceload 了 121 個區塊,把 TPS 壓到 **18.9**;刪掉整套掃描(含 23 個資料包 `configured_feature` JSON)之後是 **20.1**。這不是理論上的顧慮,是實測過的。

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

- `/kyo giveeligible` 產生的 ItemStack `originEpoch` 寫死 `0`。QA 工具夠用,但不要拿它當「正式發放令牌」的手段。
- `EligibleOrePdc.clear()` 有定義但沒人呼叫。
- 監聽器與 `KyoCommand` **沒有自動化測試**(沒有 MockBukkit / 伺服器測試框架)。有測試的是礦脈函數、註冊表、三個 store、首次曝光的判定邏輯,以及訊息表。
