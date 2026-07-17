# Kyokalith

[繁體中文](README.zh-TW.md)

**Anti-X-Ray that never touches world generation.** Vanilla ores generate exactly as they always have — no packet obfuscation, no wiping-and-regenerating ores, no chunk scanning. Any ore fully enclosed by solid blocks is treated as a **decoy**: X-Ray, freecam, and seed-map tools all see it, but whether that block is *real* is only decided **the moment it is first exposed by mining**.

A cheater tunnels straight toward an ore they can see through the walls — and hits its base block; an honest player looking at an ore in a cave wall is always looking at the real thing.

> Built for (and battle-tested on) the Lycohinya survival server. The anti-X-Ray core is fully generic — any Spigot/Paper 26.2 server can run Kyokalith standalone. The second feature (eligible-ore check tokens via `OreCheckTriggerEvent`) is an optional integration point for other plugins; with no listener installed it simply stays silent.

## How it actually works

Traditional anti-X-Ray takes one of two roads, each with a cost: packet obfuscation (defeated by freecam, burns CPU) or wiping ores and regenerating them yourself (requires chunk scanning, kills TPS). Kyokalith takes a third road:

1. **World generation is untouched.** Vanilla ores stay where they generated. Ores that were already exposed at generation time (cave walls, ravine faces) are **real** and will never be altered.
2. **Fully buried ores are decoys.** They exist in the world file and X-Ray sees them, but they say nothing about where ore actually is.
3. **The only trigger is "a block disappeared".** Player mining, explosions, fire, pistons — one tick after the event, Kyokalith looks at the **six face neighbors** of each removed block.
4. **Only neighbors exposed for the first time are processed.** A block that already had another open face was already visible — it is never touched. This one rule prevents both failure modes: ore never pops into existence on a wall a player is staring at, and real ore a player has already seen is never wiped.
5. **Reality is decided.** A deterministic function `f(salt, world, epoch, x, y, z, base block, dimension)` decides: hit → the decoy becomes real ore (or a plain base block **becomes** ore); miss → the decoy reverts to its base block (stone / deepslate / netherrack). The block hasn't been sent to any client yet, so honest players see nothing happen at all.

Per-event cost is bounded by a constant: `removed blocks × 6`. **No scanning, no scheduled scan tasks, no `ChunkLoadEvent` work.**

> The current decoy model replaced an earlier scan-based approach (pre-v1.0 "v0.3"), which wiped ores via datapack and regenerated them by scanning chunks — 121 force-loaded chunks dragged TPS down to 18.9. Deleting the entire scanning pipeline brought it back to 20.1. **Any "let's just scan a few chunks while we're at it" idea is a hard red line in this plugin.**

### Covered-up ore is never re-resolved

Blocks placed by players (plus snow/ice formation, entity placement, and piston destinations) are marked **dirty**. Dirty blocks are never materialized, and **removing a dirty block does not resolve its neighbors**.

Rationale: if something is hiding under a player-placed block, it must have been exposed before it was covered. This rule simultaneously blocks the "cover a decoy, dig it back up to skip resolution" exploit and guarantees "cover real ore, dig it up later, it's still there."  A piston pulling away the last covering block counts as removal too — you can't peek at unresolved decoys with pistons.

## Requirements

| | |
|---|---|
| Server | **Spigot or Paper 26.2, or Folia 26.1.2** (newest Folia — no 26.2 Folia exists, hence `api-version: 26.1`; compiled against the Spigot API; runs in production on Paper) |
| Java | **25** |
| Hard dependencies | None |
| Soft dependencies | NatureRevive (chunk-regeneration bridge, loaded via reflection only if present) |

The Kotlin stdlib and SQLite driver are downloaded at startup by the Bukkit library loader — **not shaded into the jar**.

## Installation

1. Drop `Kyokalith-<version>.jar` into `plugins/` and start the server.
2. A default `config.yml` is generated with all 11 ore types (including nether ores) enabled — it works out of the box.
3. First startup creates `plugins/Kyokalith/kyokalith.db` containing a random `salt`.

> ⚠ **Never delete the DB or reset the `salt` once generated.** The salt is what decouples real ore positions from the world seed; resetting it re-rolls every unexposed vein in the entire world.

## Upgrading

Config upgrades are automatic: new keys are merged into your existing `config.yml` on startup with their default values; your existing values are never overwritten. Notably, `locale` defaults to `en` — set `locale: zh_TW` (or your own lang file) if you want something else.

## Commands

`/kyokalith` (alias `/kyo`). **Every subcommand requires `kyokalith.admin`.**

| Subcommand | Args | What it does | Who |
|---|---|---|---|
| `stats` | – | Ore type count, eligible block count, suspended chunks, NatureRevive bridge state | Console OK |
| `inspect` | `<x> <y> <z> [world]` | Dump epoch, block, dirty/suspended flags, and vein-function result for a coordinate | Console OK |
| `preview` | `[radius]` or `<radius> <x> <y> <z> [world]` | Brute-force scan a cube, report hits and up to 12 example coordinates | Short form player-only |
| `sample` | `volume [radius]` | Same scan, reports `hits / scanned` only | Player-only |
| `resolve` | `<x> <y> <z> [world]` | Re-run first-exposure resolution for a coordinate (`f` is deterministic, safe to re-run) | Console OK |
| `suspend` | `<cx> <cz> <reason...>` | Suspend materialization in a chunk | Player-only |
| `resume` | `<cx> <cz>` | Lift a suspension | Player-only |
| `markeligible` | `[x y z]` | QA tool: mark a block dirty and write an eligible token for it | Player-only |
| `giveeligible` | `<player> <oreType> <1-64>` | QA tool: give a stack of ore blocks carrying PDC tokens | Console OK |

The `preview` / `sample` radius is clamped to `1..24` — these two are **deliberate brute-force exceptions**, admin-only and never on a hot path.

## Permissions

| Node | Default | Effect |
|---|---|---|
| `kyokalith.admin` | `op` | All `/kyo` subcommands |
| `kyokalith.bypass` | `false` | Holder's mining consumes no check token and fires no `OreCheckTriggerEvent`. **Note: decoy resolution still runs** — this only skips the check path |

Non-survival modes (creative/spectator/adventure) never consume tokens either.

## Configuration

`config.yml` has three blocks: `locale`, `database` (file name, dirty write-back interval), and `ores` (data-driven ore definitions — adding an ore type requires no code).

The knobs you'll touch most are each ore's `cell_chance` / `density` / `preferred_y` — **these three are literally your server economy's faucet**. Full field reference, the hit-probability formula, and the red lines live in **[docs/CONFIG.md](docs/CONFIG.md)**.

There is no `/kyo reload`; config is read once in `onEnable`.

### Messages / languages

Admin-command output is fully customizable. Bundled locales: `en`, `zh_TW`. Set `locale` in `config.yml`, then edit the files under `plugins/Kyokalith/lang/` — keys you delete fall back to the built-in text. To add your own language, copy `lang/en.yml` to `lang/<name>.yml`, translate, and set `locale: <name>`.

## For developers

Kyokalith exposes exactly **one** integration point: `OreCheckTriggerEvent` — fired synchronously when a survival-mode player mines an ore that Kyokalith itself produced or tracked. It is cancellable and its `drops` list is rewritable.

Admin-given ores, WorldEdit-pasted ores, and shop-bought ores carry **no token** and never fire the event. Silk Touch moves the token onto the ItemStack (PDC); placing the block moves it into the DB — so one ore can be traded, moved, and re-mined, but **burns exactly one check**.

```kotlin
@EventHandler
fun onOreCheck(event: OreCheckTriggerEvent) {
    if ((1..20).random() < 15) return          // failed check: leave drops alone = vanilla drops
    val bonus = event.drops.firstOrNull()?.clone() ?: return
    event.drops.add(bonus)                     // passed check: one extra drop
}
```

The event contract, fields, exact drop-rewrite semantics, and the counter-intuitive "cancel ≠ no drops" rule are in **[docs/API.md](docs/API.md)**.

## Building

```bash
./gradlew build      # compile + unit tests + jar
./gradlew test       # unit tests only (vein function, registry, stores, messages)
./gradlew runServer  # local Paper 26.2 test server
```

The Kotlin version in `plugin.yml`'s `libraries:` **must match `gradle/libs.versions.toml`**, otherwise the stdlib you compile against and the one loaded at runtime are different builds.

## Data

A single SQLite file, `plugins/Kyokalith/kyokalith.db` (WAL). Stores the `salt`, per-chunk `epoch`s, dirty positions, placed eligible ores, and suspended chunks. Schema details in [docs/API.md](docs/API.md#tables).

## License

[TinyYana Universal Software License (TYUSL) 1.0](LICENSE) — free to use, modify, integrate, and redistribute, including on commercial servers; you may **not** sell the plugin itself or repackage it as a paid product/service without written permission.

TinyYana · [tinyyana.com](https://tinyyana.com)
