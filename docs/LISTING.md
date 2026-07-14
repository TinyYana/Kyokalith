# Platform listing copy

Paste-ready copy for each distribution platform. Keep claims in sync with README.md — if a number or behavior changes, change it here too.

---

## SpigotMC

**Resource name:** `Kyokalith — Anti-X-Ray that never touches world generation`

**Tag line (100 chars max):** `Buried ores are decoys until first exposed. Beats X-Ray, freecam & seed maps. No packets, no scans.`

**Category:** Anti-Griefing Tools
**Tested versions:** 26.2
**Source code link:** https://github.com/TinyYana/Kyokalith

**Description (BBCode):**

```bbcode
[B]Every anti-X-Ray plugin picks a poison.[/B] Packet obfuscation burns CPU and loses to freecam. Wiping ores and regenerating them means chunk scanning, and chunk scanning eats your TPS. Kyokalith picks neither:

[B]Fully buried ores are decoys.[/B] They generate exactly where vanilla puts them — X-Ray, freecam, and seed-map sites all see them — but whether that block is [I]real[/I] is only decided the moment mining first exposes it. A deterministic function keyed by a private per-server salt makes the call: hit, and the decoy becomes real ore (plain stone can become ore too); miss, and it reverts to its base block. The block hasn't been sent to any client yet, so honest players never see anything change.

A cheater tunnels straight to an ore they can see through the wall — and mines stone. An honest player looking at ore on a cave wall is always looking at the real thing, because ore exposed at generation time is never touched.

[B]What this gets you[/B]
[LIST]
[*]Defeats X-Ray texture packs, X-Ray mods, freecam, and seed-map tools for every fully buried ore
[*]Zero packet interception — no per-player obfuscation cost, no ProtocolLib
[*]Zero chunk scanning — per-event cost is a constant: removed blocks × 6 neighbor checks, one tick later
[*]World generation untouched; the only block writes happen at the moment of first exposure
[*]Cover-up exploits handled: player-placed blocks are tracked as dirty and never re-rolled, and pistons can't peek
[*]Data-driven config: 11 vanilla ore types bundled (overworld + nether), add your own without code
[*]Customizable messages with bundled English and Traditional Chinese locales
[*]Integration API: [ICODE]OreCheckTriggerEvent[/ICODE] fires when a tracked ore is mined — build custom mining rewards on top (silently inactive if nothing listens)
[/LIST]

[B]Performance, measured[/B]
An earlier prototype used the classic wipe-and-regenerate approach: 121 force-loaded chunks dragged TPS to 18.9. The current model deleted the entire scanning pipeline and runs at a constant cost per block-change event. It has been running in production on a survival server for weeks.

[B]Requirements[/B]
Spigot or Paper 26.2 · Java 25 · no dependencies (Kotlin stdlib and SQLite driver are fetched by the Bukkit library loader at startup). Compiled against the Spigot API; production-tested on Paper. Not Folia-compatible.

[B]Commands & permissions[/B]
Everything lives under [ICODE]/kyokalith[/ICODE] (alias [ICODE]/kyo[/ICODE]), gated by [ICODE]kyokalith.admin[/ICODE] (default: op): stats, inspect, preview, sample, resolve, suspend/resume, plus QA token tools. [ICODE]kyokalith.bypass[/ICODE] skips the reward-check path only — decoy resolution always runs. Full reference in the GitHub README.

[B]⚠ One warning[/B]
First startup generates a random [ICODE]salt[/ICODE] in [ICODE]plugins/Kyokalith/kyokalith.db[/ICODE]. Never delete or reset it — the salt is what decouples real ore positions from your world seed, and resetting it re-rolls every unexposed vein in the world.

[B]Docs & source[/B]
GitHub (source, English/繁體中文 docs, issues): https://github.com/TinyYana/Kyokalith
Config reference: https://github.com/TinyYana/Kyokalith/blob/main/docs/CONFIG.md
Developer API: https://github.com/TinyYana/Kyokalith/blob/main/docs/API.md

License: TinyYana Universal Software License 1.0 — free to use, modify, and redistribute; you may not sell the plugin itself.
```

**FAQ (post as first discussion reply):**

```bbcode
[B]Q: Does it edit my world files ahead of time?[/B]
No. World generation is untouched and nothing is scanned. The only block writes happen at the moment a buried position is first exposed, before the block is sent to any client.

[B]Q: What happens if I uninstall it?[/B]
Your world is a normal vanilla world. Unresolved decoys are just the vanilla ores that always generated there; already-resolved blocks simply stay as they are. Nothing breaks.

[B]Q: Can I combine it with Paper's built-in anti-xray (engine-mode)?[/B]
Yes. Kyokalith never touches packets, so they operate at different layers. Paper's obfuscation hides what the client is told; Kyokalith makes what the client is told unreliable.

[B]Q: Why do honest players never notice?[/B]
A position is only resolved on its *first* exposure. Anything a player could already see (another open face, generation-time exposure) is never modified. The swap happens before the block reaches the client.

[B]Q: Doesn't reading the source let cheaters beat it?[/B]
No. Real-vs-decoy is decided by a hash keyed with a random per-server salt stored only in your server's database. Knowing the algorithm without the salt tells you nothing.

[B]Q: TPS impact?[/B]
Per block-change event: at most (removed blocks × 6) neighbor checks plus an in-memory hash — constant, main-thread, deferred one tick. No scheduled tasks, no chunk scans, no DB on the event path.

[B]Q: Folia?[/B]
Not supported (Bukkit scheduler is used). PRs welcome.
```

---

## Modrinth

**Slug:** `kyokalith` · **Loaders:** Paper, Spigot · **Game version:** 26.2 · **License:** Custom (link to LICENSE on GitHub) · **Categories:** utility, game-mechanics
**Summary (one-liner):** Anti-X-Ray without touching world gen: buried ores stay decoys until first exposed — beats X-Ray, freecam, and seed maps with no packet tricks and no chunk scans.

**Body:** use README.md verbatim (it is already Markdown), minus the Building section.

---

## Hangar

**Platform:** Paper 26.2 · **Category:** Protection
**Short description:** same as Modrinth summary.
**Body:** same as Modrinth.

---

## Release-notes template (GitHub / platform updates)

```markdown
## Kyokalith X.Y.Z

### Changes
- …

### Upgrading
Drop-in. New config keys are merged into your existing config.yml on startup with defaults; your values are never overwritten.
```
