# Changelog

All notable changes to Kyokalith are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/). The release CI extracts the matching `## [x.y.z]` section as the GitHub Release notes — a tag without a section here fails the release on purpose.

## [1.2.0] - 2026-07-17

### Added
- **Folia support.** `folia-supported: true` is declared, and every scheduled task now runs on the thread that owns the data it touches: block work on the owning region, `/kyo giveeligible` on the recipient's entity scheduler, and the dirty-position flush (which only writes SQLite) on the global region. Spigot and Paper behaviour is unchanged — the same code path resolves to `Bukkit.getScheduler()` there. The startup line reports which mode is active (`scheduler: Folia regionized` / `Bukkit main thread`).
- Admin commands that read or write blocks (`inspect`, `preview`, `sample`, `markeligible`, `resolve`) now hop to the region owning the target coordinate before touching it. On Folia a console command runs on the global thread, which owns no blocks at all, so these would otherwise have thrown. The visible change on Spigot is that their reply arrives one tick later.

### Changed
- **Folia's scheduler API is compiled in a separate `folia` source set**, not added to `main`. `folia-api` transitively exposes the whole Paper API, so putting it on `main`'s compile classpath would have silently retired the [1.0.0] guarantee that Spigot compatibility is a compile-time fact. `main` still sees only `spigot-api`; the one file that calls Folia (`FoliaSchedulers`) is loaded only when running on Folia, and `SchedulersSpigotSafetyTest` fails if a Folia reference ever reaches the class Spigot does load.
- `database.dirty_flush_interval_ticks` below `1` is now clamped to `1`. Bukkit silently did this already; Folia rejects it outright, so the same config would have failed startup there.

### Fixed
- **Dirty positions could be lost on Folia**, which is an exploit problem rather than mere data loss — a dropped dirty flag makes a block a player covered up "first-exposure resolvable" again, reopening the cover-and-dig hole. Each chunk's position set was a plain `HashSet`, safe on Spigot only because `markDirty` and the flush task shared the main thread. Under regionized scheduling the flush runs on the global thread and encodes the set while a region thread is still adding to it, throwing `ConcurrentModificationException` mid-write. The per-chunk sets are now concurrent; `DirtyPositionConcurrencyTest` reproduces the original failure. Spigot and Paper were never affected.

## [1.1.0] - 2026-07-16

### Changed
- **Eligibility now includes already-exposed vanilla ore.** Previously, only ore Kyokalith itself materialized on first exposure (`NATURAL_BLOCK`) or moved through the placed-block token flow (`PLACED_BLOCK`) could fire `OreCheckTriggerEvent`. Ore that was already exposed at world generation — cave walls, ravine faces, i.e. most of what a player actually mines while exploring — almost never coincidentally matched the deterministic vein function, so it silently never fired the event. That exclusion caught zero cheaters (X-Ray gives no informational edge on ore that's already visible) while starving downstream reward plugins of legitimate check opportunities: a server measured **zero triggers across ~200 ores mined** in ordinary cave exploration. A new `EligibilitySource.WORLDGEN_EXPOSED` now covers this case — any real, currently-standing ore of an enabled type that isn't in a dirty position is eligible. Anti-X-Ray guarantees are unaffected: this only changes reward-check eligibility, not the decoy/materialization logic that decides what's real. See [docs/API.md](docs/API.md#eligibility-tokens).

## [1.0.0] - 2026-07-14

First public release. The decoy-materialization model shipping here has been running in production on a live survival server for the past two weeks without incident.

**What Kyokalith is, in one paragraph:** anti-X-Ray that never touches world generation. Vanilla ores generate exactly as always; any ore fully enclosed by solid blocks is a *decoy* — X-Ray, freecam, and seed-map tools all see it, but whether the block is real is only decided the moment mining first exposes it, by a deterministic function keyed with a private per-server salt. Cheaters tunnel to what they saw through the wall and hit stone; ore an honest player can see is always real. Per-event cost is a constant (removed blocks × 6 neighbor checks) — no packet obfuscation, no chunk scanning. See the [README](README.md) for the full model.

### Added
- **Customizable messages / locales.** All admin-command output moved out of the code into `plugins/Kyokalith/lang/<locale>.yml`. Bundled locales: `en` (default) and `zh_TW`; new config key `locale` selects one. Edit the generated files to customize — deleted keys fall back to the built-in text, and any key missing from a locale falls back to English. Add your own `lang/<name>.yml` and set `locale: <name>` for a new language, no code required.
- **Tab completion for every `/kyo` subcommand**: subcommand names, coordinates (pre-filled from the block you're looking at, or your position), world names, online player names, ore type ids, radius/amount suggestions. Players without `kyokalith.admin` get no completions.
- **LICENSE**: TinyYana Universal Software License (TYUSL) 1.0 — free to use, modify, integrate, and redistribute (commercial servers included); selling the plugin itself or repackaging it as a paid product requires written permission.
- **English documentation** as the primary set: [README.md](README.md), [docs/CONFIG.md](docs/CONFIG.md), [docs/API.md](docs/API.md). 繁體中文版本: [README.zh-TW.md](README.zh-TW.md), [docs/CONFIG.zh-TW.md](docs/CONFIG.zh-TW.md), [docs/API.zh-TW.md](docs/API.zh-TW.md).
- **Automated releases**: pushing a `v*` tag builds, tests, verifies the tag matches `gradle.properties`, and publishes a GitHub Release with the jar and this file's matching section as notes.

### Changed
- **Compiled against the Spigot API** (26.2) instead of the Paper API. The single Paper-only call in the codebase (`pluginMeta`) was replaced with the Bukkit equivalent, making Spigot compatibility a compile-time fact. Production testing still runs on Paper; Folia is not supported.
- Kotlin runtime pinned to stable **2.4.0** (was `2.4.20-Beta1`); the `libraries:` entry in plugin.yml that the Bukkit library loader downloads at startup matches.
- Console/log messages are now English. `/kyo stats` output no longer leaks internal reward-system terminology ("d20").
- Plugin metadata (description, docs) no longer references the private server project this plugin originated from; source comments that pointed at a private design document now explain the concepts inline or link to `docs/` in this repo.

### Fixed
- `processResources` now registers the version as a task input — previously an incremental build after bumping only `gradle.properties` could produce a jar whose embedded `plugin.yml` still carried the old version string.

### Upgrading from a pre-release 0.x build
Drop-in: replace the jar and restart.
- Your existing `config.yml` is kept as-is; the new `locale: en` key is merged in automatically with your values and comments untouched. Set `locale: zh_TW` for Traditional Chinese admin output.
- `plugins/Kyokalith/lang/` is created with editable copies of both bundled locales.
- Database schema, the `salt`, and all tracked state are unchanged — no migration, nothing re-rolls.

## [0.1.0] - 2026-07-04 (never released)

Internal development versions for a private server: the original datapack-strip + chunk-scan anti-X-Ray (v0.3, deleted after it dragged TPS to 18.9), its replacement decoy-materialization model (internally "v0.4"), the eligible-ore token lifecycle, and `OreCheckTriggerEvent`.
