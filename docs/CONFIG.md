# Kyokalith configuration reference

[繁體中文](CONFIG.zh-TW.md)

`plugins/Kyokalith/config.yml`. **There is no `/kyo reload`** — config is read once in `onEnable`; restart the server after changes.

Config validation is **fail-fast**: if any single ore definition is invalid (no material, `y_min > y_max`, `cell_chance` outside 0..1, an empty `ores:` block), **the plugin disables itself** rather than running with a broken config. If Kyokalith didn't enable, read the first line of the log.

---

## `locale`

| Key | Type | Default | Description |
|---|---|---|---|
| `locale` | String | `en` | Language of admin-command output. Bundled: `en`, `zh_TW` |

Language files live in `plugins/Kyokalith/lang/<locale>.yml` and override the built-in text key-by-key — keys you delete fall back to the bundled defaults (and any key missing from a locale falls back to English). To add a language, copy `lang/en.yml` to `lang/<name>.yml`, translate the values, and set `locale: <name>`. Color codes use `&`; `{placeholders}` are filled in by the plugin.

---

## `database`

| Key | Type | Default | Description |
|---|---|---|---|
| `database.file` | String | `kyokalith.db` | SQLite file name, relative to `plugins/Kyokalith/` |
| `database.dirty_flush_interval_ticks` | Long | `40` | Interval for writing dirty positions back to the DB, in ticks (40 = 2 s) |

> 🔴 **`dirty_flush_interval_ticks` is a red line in both directions.**
>
> The write-back task runs on the **sync scheduler** (the global region scheduler on Folia); each flush opens a fresh JDBC connection per pending chunk and does `INSERT OR REPLACE` (no connection pool).
>
> - **Too small (e.g. `1`)**: you are now writing SQLite on a ticking thread every tick. Values below `1` are clamped to `1`.
> - **Too large**: more dirty positions are lost on a crash — and losing dirty flags **is a correctness/exploit problem**, not just data loss: blocks a player covered up become "first-exposure resolvable" again, reopening the cover-and-dig exploit.
>
> The default 40 is the balance point. Don't touch it unless you know what you're doing.

---

## `ores`

Data-driven. Adding an ore type, removing one, or changing depth distribution **requires no code changes**.

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

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `true` | `false` = Kyokalith ignores this ore entirely: decoys stay vanilla, mining stays vanilla, no check events |
| `materials.stone` | Material | required (at least one) | Ore block to generate when the base block is `STONE` **or `NETHERRACK`** |
| `materials.deepslate` | Material | – | Ore block when the base is `DEEPSLATE`. **No fallback to `stone`** — unset means this ore never appears in deepslate layers |
| `dimension` | `NORMAL` / `NETHER` / `THE_END` | `NORMAL` | Only resolves in this dimension. **Nether ores must explicitly say `NETHER`** |
| `y_min` / `y_max` | Int | `0` | Hard range; outside it, never resolves |
| `preferred_y` | Int | `0` | Peak of the triangular weight: 1.0 at `preferred_y`, falling linearly toward `y_min`/`y_max` |
| `density` | Double | `1.0` | Multiplier applied to `cell_chance` |
| `vein_size_min` / `vein_size_max` | Int | `1` / `1` | Vein "size". Actual sphere radius = `max(1, size / 2)`, **hard-clamped at 2** |
| `cell_chance` | Double 0.0–1.0 | required | Probability that a 16×16×16 cell spawns a vein origin (before `density` and the Y weight) |

### Effective hit probability

```
activation = clamp(cell_chance × density × yWeight(y), 0, 1)
```

`yWeight` is triangular: 1.0 at `preferred_y`, falling linearly to 0 at `y_min` / `y_max`. So **putting `preferred_y` in the middle of the range vs. at its edge produces completely different distributions** — at the edge, half the height range gets very low weight.

### 🔴 Red lines

**`vein_size_max` beyond ~5 does nothing.** The code has `MAX_VEIN_RADIUS = 2`, hard-clamping the sphere at roughly 33 blocks.

That clamp is the fix for an "one ore type extends forever" bug: with `radius = vein_size_max / 2 = 5` the sphere is ~500 blocks — mining one vein meant mining a whole field. **Do not remove it to "make veins bigger."** Want more ore? Raise `cell_chance` / `density`, not vein size.

**Unset `dimension` = overworld only.** (An older config comment claimed "unset = matches all dimensions"; that was wrong — the code defaults to `NORMAL` and matches exactly.)

**`cell_chance` / `density` are literally your server's ore faucet.** The design target: the ore density a player experiences while tunneling (everything they hit is a decoy) should feel like the density of vanilla ore seen on cave walls. Think about which economy you want before touching these two numbers.

**`salt` is not in the config, and must never be reset.** It lives in the DB `meta` table, generated randomly on first startup. Resetting the salt re-rolls every unexposed vein in the world.

---

## Bundled ores

Overworld: `coal` `iron` `copper` `gold` `redstone` `lapis` `diamond` `emerald`
Nether (`dimension: NETHER`): `nether_quartz` `nether_gold` `ancient_debris`

All `enabled: true`, `density: 1.0`.

## Adding a new ore

```yaml
ores:
  my_custom_ore:
    enabled: true
    materials:
      stone: EMERALD_ORE            # generated when the base is stone/netherrack
      deepslate: DEEPSLATE_EMERALD_ORE
    dimension: NORMAL
    y_min: -16
    y_max: 80
    preferred_y: 32                 # distribution peak
    density: 1.0
    vein_size_min: 1
    vein_size_max: 3                # remember: actual radius caps at 2
    cell_chance: 0.02
```

Save, restart. Check `/kyo stats` for the ore count +1, then stand at the target height and run `/kyo preview 16` to sanity-check hit density.

## Verifying changes

```
/kyo sample volume 24      # stand at the target Y, check hit/scanned ratio
/kyo preview 16            # see actual hit coordinates and ore types
/kyo inspect <x> <y> <z>   # single point: epoch, dirty, vein-function result
```

These three are brute-force scans, radius clamped to 1..24, **admin-only** — don't put them in automation that runs repeatedly.
