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
