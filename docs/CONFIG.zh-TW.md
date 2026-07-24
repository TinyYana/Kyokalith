# Kyokalith 設定參考

[English](CONFIG.md)

`plugins/Kyokalith/config.yml`。**沒有 `/kyo reload`**——設定只在 `onEnable` 讀一次,改完要重開伺服器。

設定驗證是 **fail-fast**:任何一個礦定義不合法(沒有材質、`y_min > y_max`、`cell_chance` 超出 0..1、`ores:` 整段空的),**插件會直接自我停用**,而不是帶著壞設定跑。看到 `Kyokalith` 沒 enable,先看 log 第一行。

---

## `locale`

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `locale` | String | `en` | 管理指令輸出語系。內建:`en`、`zh_TW` |

語系檔在 `plugins/Kyokalith/lang/<locale>.yml`,逐 key 覆蓋內建文字——刪掉的 key 回退內建預設(語系缺的 key 回退英文)。要加語言:把 `lang/en.yml` 複製成 `lang/<名稱>.yml` 翻譯後,設 `locale: <名稱>`。色碼用 `&`,`{佔位符}` 由插件代入。

---

## `database`

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `database.file` | String | `kyokalith.db` | SQLite 檔名,相對於 `plugins/Kyokalith/` |
| `database.dirty_flush_interval_ticks` | Long | `40` | dirty 位置寫回 DB 的間隔,單位 tick(40 = 2 秒) |

> 🔴 **`dirty_flush_interval_ticks` 是紅線,兩邊都不能亂調。**
>
> 寫回任務跑在**同步排程**上(Folia 上是 global region 排程),每次 flush 對每個待寫區塊開一條新的 JDBC 連線做 `INSERT OR REPLACE`(沒有連線池)。
>
> - **調太小(例如 `1`)**:等於每 tick 在一條 ticking 執行緒上寫 SQLite。小於 `1` 會被夾成 `1`。
> - **調太大**:當機時遺失的 dirty 位置變多——而遺失 dirty 旗標**是正確性/漏洞問題**,不只是資料掉了:被玩家蓋住的方塊會重新變成「可首次曝光解析」,蓋起來再挖開的漏洞就回來了。
>
> 預設 40 是兩邊之間的平衡點,除非你知道自己在做什麼,不要動。

---

## `ores`

資料驅動。加一種礦、拿掉一種礦、改深度分布,**都不用改程式碼**。

```yaml
ores:
  diamond:
    enabled: true
    materials:
      stone: DIAMOND_ORE
      deepslate: DEEPSLATE_DIAMOND_ORE
    dimension: NORMAL
    y_min: -63
    y_max: 16
    preferred_y: -59
    density: 1.0
    vein_size_min: 1
    vein_size_max: 4
    cell_chance: 0.06
```

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `enabled` | Boolean | `true` | `false` = Kyokalith 完全無視這種礦:誘餌保持原版、挖掘保持原版、不發檢定事件 |
| `materials.stone` | Material | 必填(至少一個) | 基礎方塊是 `STONE` **或 `NETHERRACK`** 時要生成的礦方塊 |
| `materials.deepslate` | Material | – | 基礎方塊是 `DEEPSLATE` 時的礦方塊。**不會 fallback 到 `stone`**——沒設就是深板岩層不出這種礦 |
| `dimension` | `NORMAL` / `NETHER` / `THE_END` | `NORMAL` | 只在這個維度解析。**地獄礦一定要明寫 `NETHER`** |
| `y_min` / `y_max` | Int | `0` | 硬性範圍,超出永遠不解析 |
| `preferred_y` | Int | `0` | 三角形權重的峰值:在 `preferred_y` 是 1.0,線性遞減到 `y_min`/`y_max` 較遠的那一端變成 0 |
| `density` | Double | `1.0` | 乘在 `cell_chance` 上的倍率 |
| `vein_size_min` / `vein_size_max` | Int | `1` / `1` | 礦脈「大小」。實際球半徑 = `max(1, size / 2)`,**上限硬夾在 4** |
| `cell_chance` | Double 0.0–1.0 | 必填 | 一個 16×16×16 的 cell 生出礦脈原點的機率(還沒乘 `density` 與 Y 權重) |
| `priority` | Int | `0` | 另一種礦種的候選球也命中同一座標時的仲裁——**數字較大的贏**。內建預設依稀有度排:常見礦低、稀有礦高,重疊時永遠是較稀有的礦贏。同礦種互相重疊不受影響(材質反正一樣)。`/kyo inspect` 會顯示 |

### 實際命中機率

```
啟用機率 = clamp(cell_chance × density × yWeight(y), 0, 1)
```

`yWeight` 是三角形:`preferred_y` 處為 1.0,往 `y_min` / `y_max` 兩端線性掉到 0。所以**把 `preferred_y` 設在範圍正中間跟設在邊緣,分布形狀完全不同**——邊緣的話,一半的高度區間權重會很低。

### 🔴 紅線

**`vein_size_max` 超過 ~9 會收斂成同一個半徑。** 程式裡 `MAX_VEIN_RADIUS = 4`,把球體硬夾在約 257 個方塊(整數除法 `size / 2` 讓 8、9、10 全部落在半徑 4)。

這個上限的存在是因為歷史上修過「同一種礦無限延伸」的 bug:半徑 `= vein_size_max / 2 = 5` 時球體約 515 個方塊,玩家挖到一條就等於挖到一整片。把上限從舊值 2(`vein_size_max` 4~10 全部收斂成同一顆約 33 格的球,不管設 4 還是 10 感受不出差異——這正是「挖到一顆礦後續突然變石頭」的直接成因)提高到 4(約 257 格),讓 `vein_size` 在 1~10 的範圍內有實際區隔,同時沒有重新打開舊 bug。**不要為了「讓礦脈大一點」把上限整個拿掉。** 想要整體礦更多,調的是 `cell_chance` / `density`,不是脈大小。

**`dimension` 沒設 = 只在主世界。** (舊版 config 的註解說「不設 = 所有維度都會命中」,那是錯的——程式預設 `NORMAL` 並做精確比對。)

**`cell_chance` / `density` 直接等於伺服器的礦產水龍頭。** 設計目標是:玩家挖隧道(碰到的都是誘餌)感受到的礦密度,要跟原版在洞穴壁上看到的密度差不多。改這兩個數字之前先想清楚你要的是哪一種經濟。

**`salt` 不在 config 裡,而且不能重置。** 它在 DB 的 `meta` 表,第一次啟動時隨機生成。重置 salt = 全世界所有還沒挖開的礦脈重骰。

---

## 內建的礦

主世界:`coal` `iron` `copper` `gold` `redstone` `lapis` `diamond` `emerald`
地獄(`dimension: NETHER`):`nether_quartz` `nether_gold` `ancient_debris`

全部 `enabled: true`、`density: 1.0`。

## 加一種新礦

```yaml
ores:
  my_custom_ore:
    enabled: true
    materials:
      stone: EMERALD_ORE            # 基礎方塊是石頭/地獄岩時出這個
      deepslate: DEEPSLATE_EMERALD_ORE
    dimension: NORMAL
    y_min: -16
    y_max: 80
    preferred_y: 32                 # 分布峰值
    density: 1.0
    vein_size_min: 1
    vein_size_max: 3                # 記得:實際半徑上限是 4
    cell_chance: 0.02
```

存檔、重開伺服器。`/kyo stats` 看礦種數有沒有 +1,`/kyo preview 16` 站在目標高度看命中密度合不合預期。

## 驗證改動

```
/kyo sample volume 24      # 站在目標 Y,看 命中/掃描 比例
/kyo preview 16            # 看實際命中的座標與礦種
/kyo inspect <x> <y> <z>   # 單點:epoch、dirty、礦脈函數結果
```

這三個是暴力掃描,半徑夾在 1..24,**只有管理員能用**,不要寫進自動化腳本裡反覆跑。
