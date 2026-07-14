# Kyokalith developer reference

[繁體中文](API.zh-TW.md)

Kyokalith exposes exactly **one** integration point: `OreCheckTriggerEvent`. No `ServicesManager` registration, no `-api` artifact, no interface layer — deliberately. There is only one thing to hook, so it uses Bukkit's native event mechanism.

---

## `OreCheckTriggerEvent`

`com.tinyyana.kyokalith.event.OreCheckTriggerEvent` — a standard Bukkit `Event`, implements `Cancellable`, fired **synchronously**.

### When it fires

A player mines an ore and **all** of the following hold:

- the player is in **survival mode** (creative/spectator/adventure never fire it)
- the player does **not** have `kyokalith.bypass`
- the ore carries a Kyokalith **eligibility token** (explained below)
- it is **not** a Silk Touch break (Silk Touch takes another path: the token moves onto the ItemStack)
- vanilla drops are non-empty

Explosions, pistons, and fire do **not** fire it — `TriggerSource` currently has a single value, `PLAYER_BREAK`. An explosion merely clears the eligible record from the DB.

### Fields

```kotlin
class OreCheckTriggerEvent(
    val player: Player,
    val blockLocation: Location,
    val oreMaterial: Material,
    val oreType: String,                    // ore id from config.yml, e.g. "diamond"
    val tool: ItemStack,                    // a clone; mutating it does nothing
    val fortuneLevel: Int,
    val drops: MutableList<ItemStack>,      // ← the only thing you may mutate
    val triggerSource: TriggerSource,       // PLAYER_BREAK
    val eligibilitySource: EligibilitySource, // NATURAL_BLOCK / PLACED_BLOCK
) : Event(), Cancellable
```

### ⚠ Drop-rewrite semantics (counter-intuitive — read this first)

**Cancelling the event does not mean "no drops" — it means "keep vanilla drops".**

The decision logic:

| What you do | Result |
|---|---|
| `event.isCancelled = true` | **Vanilla drops kept**, Kyokalith stays out of it entirely |
| Leave `drops` untouched | **Vanilla drops kept** (same content = treated as unchanged) |
| Mutate `drops` (add/remove/replace) | Every originally dropped item entity is `remove()`d, then each non-air, `amount > 0` stack in `drops` is re-dropped at the block via `dropItemNaturally` |

So "drop nothing this time" is `event.drops.clear()` — **not** `event.isCancelled = true`.

### Example

```kotlin
class OreCheckListener : Listener {
    @EventHandler
    fun onOreCheck(event: OreCheckTriggerEvent) {
        val total = (1..20).random() + bonusOf(event.player)
        when {
            total >= 20 -> {                       // critical success: double
                val bonus = event.drops.map { it.clone() }
                event.drops.addAll(bonus)
            }
            total >= 15 -> {                       // success: one extra
                event.drops.firstOrNull()?.clone()?.let { event.drops.add(it) }
            }
            else -> return                         // failure: untouched = vanilla drops
        }
    }
}
```

Put Kyokalith in your `plugin.yml` `softdepend` and load the event class via **reflection**, and your plugin runs fine without Kyokalith installed — that's exactly how the original consumer hooks it, with zero compile-time dependency.

---

## Eligibility tokens

This is the rule for "which ore blocks can trigger a check". **Not every ore block can.**

| Where the ore came from | Token? |
|---|---|
| Produced by Kyokalith's first-exposure resolution | ✅ yes (`NATURAL_BLOCK`) |
| Silk Touch'd by a player, then placed back | ✅ yes (`PLACED_BLOCK`) |
| Admin `/give`, WorldEdit paste, shop purchase | ❌ no |
| Exposed on a cave wall at world generation | ❌ no (it's real ore, but Kyokalith didn't produce it) |

**One ore burns exactly one check.** Lifecycle:

```
first-exposure resolution → block carries a token
   ├─ normal break ──────→ fires OreCheckTriggerEvent, token consumed
   └─ Silk Touch break ──→ token moves onto the ItemStack's PDC, no event
         └─ placed back ─→ token moves into the eligible_placed_ores table
               └─ mined again → fires the event (eligibilitySource = PLACED_BLOCK), token consumed
```

So a token-carrying ore can be traded, moved, stockpiled, and re-mined, but **the check happens once**.

**PDC on the ItemStack** (namespace `kyokalith`): `eligible` (BYTE) / `ore_type` (STRING) / `origin_world` (STRING) / `origin_epoch` (INTEGER) / `token_id` (STRING, UUID).

**No free re-rolls**: on `BlockBreakEvent` a `PendingBreak` is stashed and a next-tick reclaimer scheduled. If `BlockDropItemEvent` never arrives (e.g. another plugin suppressed the drops), the token is **consumed anyway**, with a `fine`-level log line.

---

## The deterministic vein function `f`

```
f(salt, world, epoch, oreType, cellX, cellY, cellZ) -> hit / miss
```

- **Pure function**: splitmix64 hashing; reads no world state, no DB.
- **16³ cells**; candidates come from the 3×3×3 neighborhood of cells, and when multiple veins cover a block the smallest `veinId` wins.
- **Idempotent**: same inputs, same output, forever. That's why `/kyo resolve` is safe to re-run — if you suspect a coordinate missed its event, re-run it and the result is guaranteed identical to "what should have happened".
- **`salt` decouples real ore from the world seed**: seed-map sites can compute where vanilla ore generated, but not where Kyokalith's ore is.

**`epoch`**: a per-chunk counter. When a chunk is regenerated (NatureRevive), `epoch += 1` — re-rolling `f` **for that chunk only**, leaving the rest of the world alone.

**Cell cache**: LRU, 200k entries, key includes `epoch`, so entries for regenerated chunks age out naturally — no invalidation sweeps. Cache misses cost nothing correctness-wise (pure function, just recompute).

---

## Tables

`plugins/Kyokalith/kyokalith.db`, SQLite, `journal_mode=WAL`. Every operation opens a fresh connection (no pool).

```sql
meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)
  -- salt (random UUID, never reset), schema_version, created_at

chunk_epoch(world, cx, cz, epoch, PRIMARY KEY(world, cx, cz))

dirty_positions(world, cx, cz, epoch, data BLOB, PRIMARY KEY(world, cx, cz, epoch))
  -- data is not a bitset; it's UTF-8 text "lx,y,lz;lx,y,lz;…"
  -- deliberate: volume never justified a packed encoding, readability wins

eligible_placed_ores(world, x, y, z, epoch, ore_type, ore_material,
                     token_id, placed_by, placed_at, PRIMARY KEY(world, x, y, z))

suspended_chunks(world, cx, cz, reason, created_at, PRIMARY KEY(world, cx, cz))
```

`schema_version` is written but never read — there is no migration code yet.

---

## Performance contract (read before changing anything)

**Per-event cost is bounded by a constant: `removed blocks × 6`.** Six face neighbors only, main thread only, deferred one tick.

**Forbidden:**

- ❌ scanning chunks (in any form: at generation, on `ChunkLoadEvent`, scheduled, shell scripts)
- ❌ DB queries on the event path
- ❌ iterating all players

The pre-1.0 scan-based model (v0.3) force-loaded 121 chunks and dragged TPS to **18.9**; deleting the entire scanning pipeline (including 23 datapack `configured_feature` JSONs) brought it back to **20.1**. This is not a theoretical concern; it was measured.

The only remaining brute-force scans are `/kyo preview` and `/kyo sample` — admin-only, radius clamped to 1..24, skipping unloaded chunks. **A deliberate exception, not a precedent to extend.**

---

## NatureRevive bridge

Loads `engineer.skyouo.plugins.naturerevive.spigot.events.ChunkRegenEvent` via reflection, registered at `MONITOR`. On chunk regeneration:

```
suspend the chunk → epoch += 1 → drop old-epoch dirty positions
                 → drop the chunk's eligible records → lift suspension
```

No re-scan needed: regeneration itself puts vanilla ore back = a fresh layer of decoys, and `epoch + 1` re-rolls `f` for that chunk only.

**If the bridge throws, the chunk is deliberately left suspended** (fail-closed) — better a chunk that never materializes than one operating on inconsistent state.

---

## Known rough edges

Honesty up front, so you don't think you misread:

- `/kyo giveeligible` writes `originEpoch = 0` on the ItemStack. Fine as a QA tool; don't use it as a "official token grant" mechanism.
- `EligibleOrePdc.clear()` is defined but never called.
- Listeners and `KyoCommand` have **no automated tests** (no MockBukkit / server test framework). What is tested: the vein function, the registry, the three stores, first-exposure decision logic, and the message table.
