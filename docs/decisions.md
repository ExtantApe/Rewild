# Owner decisions

Decisions the owner has made for WildernessRegen, with dates. Later work follows these without asking again.

| Date | Decision |
| --- | --- |
| 2026-11-07 | Default idle times: 30 days without changes and 7 days without visitors, rather than the 60 and 14 first suggested. Both stay configurable. |
| 2026-11-07 | No player marker block in version 1. Admins mark areas with `/wr protect`. |
| 2026-11-07 | Below-ground-only resets are in version 1. |
| 2026-11-07 | CoreProtect is optional throughout and never required. An active Lantern Graves grave keeps its chunk, through a small interface (`KeepProvider`). |
| 2026-11-07 | The Minecraft EULA is accepted for throwaway local test servers on 127.0.0.1. |
| 2026-11-07 | Minecraft 26.1 and up only: `api-version` 26.1, Java 25, Paper only (paper-plugin.yml). No 1.21.x support. Tested on 26.1, 26.2 and 26.3. |
| 2026-11-07 | Kids come first. Nothing exposes where players build or sleep, and kids' worlds must never reset: they go in `never-reset-worlds`, which overrides `enabled`. |
| 2026-11-07 | Release as 0.x versions on GitHub Releases only. Modrinth, Hangar and Spigot wait for the owner's own code review. The repo goes public after the pre-public audit, which it passed. |

Decisions in the design brief that weren't reopened: off by default in every world; reset at restart before worlds load, by removing chunks from the world's files; chunk reset as the only repair besides below-ground; the keep rules and thresholds; unknown chunks never reset; the generator fingerprint; dry run by default; admin approval; the 200-chunk cap; 30-day trash and `/wr undo`; admin-only commands.
- **Name (2026-11-08, owner):** WildernessRegen is renamed Rewild, one word in every place: repo, plugin name, jar, package and prose. Released as 0.2.0. On first start an existing `plugins/WildernessRegen/` folder is moved (not copied) to `plugins/Rewild/`, because it holds undo backups that can be large and an approved run must live in one place; if the move fails, Rewild turns itself off and nothing resets. The old command `/wr` (and `/wildernessregen`) and the permission `wildernessregen.admin` keep working for one release with a log warning. The world store's format marker (`WRS1`) stays.
- **Redesign (owner, 2026-11-10; spec approved 2026-11-12, "Draft OK (Recommended)", relayed by the director):** "everything is rewilded EXCEPT dirt paths to begin with (and protected places like homesteader, of course). So it should have the ability to have the rewild natively blocked by other plugins, and should be able to converse with them via commands. So could be like / items allowed - X - Y - Z / plugins allowed (all blocks and entities created by this plugin, or something like that) - Homesteader - etc". Where this conflicts with the rows above, this wins. Built in 0.3.0:
  - A chunk that rewilds takes fresh terrain from a copy generated with the world's seed, block by block, except what's kept, which stays exactly as it was with its contents: every player-placed block, containers players filled, the types in `keep-blocks` (`DIRT_PATH` by default), areas kept with `/rewild keep` or by a respected plugin, and the blocks holding each of those up (the block under it, the block it hangs from or is fixed to, and the other half of a door or bed).
  - Player builds no longer keep a whole chunk; the intent list and score are retired. Claims, spawn, respawn points, named or tamed animals, a player nearby, strongholds, the End island and `/rewild protect` still keep whole chunks.
  - On first start Rewild lists the installed plugins and resets nothing until an admin has answered for each one with `/rewild plugins respect|ignore <Name>` (or `ignore-rest`). Answers live in `plugins/Rewild/keep-plugins.yml`. A plugin installed later has no answer, so resets stop again until an admin looks at it. A respected plugin that isn't running stops resets.
  - Plugins report through `KeepProvider` (whole chunks, as before, and now block areas) or with `/rewild keep <world> <x y z> [<x y z>] plugin:<Name> [note]` and `/rewild unkeep`. An admin's keeps always count; a plugin's count while it's respected. `--json` on `keep list` and `plugins`.
  - Player-placed blocks are found by Rewild's own tracking, which now records every placed block. CoreProtect stays optional and unused, so Rewild doesn't depend on it while it's off on 26.3. Chunks generated before 0.3.0's tracking began stay unknown, because 0.2.x recorded only some placed blocks.
  - `/wr`, `/wildernessregen` and `wildernessregen.admin` are removed, as announced for the release after 0.2.x.
