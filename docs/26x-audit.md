# 26.x audit

WildernessRegen targets Paper 26.1 and up, built and run on Java 25. It has no 1.21.x code paths.

## What uses 26.x features

- **paper-plugin.yml** replaces plugin.yml. Dependencies are declared with load order, none required. Only BlueMap joins the classpath, because the map layer calls its API directly.
- **Paper's command API.** `/wr` is a `BasicCommand` registered in the `COMMANDS` lifecycle event, with its permission set on the command. There's no plugin.yml command entry.
- **Adventure components** for every message. There are no legacy colour codes. The player warning in config is MiniMessage.
- **The 26.x world layout.** Every world is a dimension under `<level>/dimensions/minecraft/<name>/`, and the plugin takes that folder from `World.getWorldFolder()`. It never builds the path from a world's name. The old `DIM-1` and `DIM1` fallback was removed.
- **26.x chunk data.** Block palettes are now stored as bare strings, `{"": name}` or `{id, properties}`. The reader handles all of these. It also reads the older `{Name, Properties}` form, because a world upgraded from 1.21 can hold chunks nobody has loaded since. That's reading old data, not support for running on 1.21.
- **Java 25:** records, pattern matching in `instanceof` and `switch`, and switch expressions.

## What stayed, and why

- **`JavaPlugin.onLoad` for the startup step.** It runs before any world loads, which is when chunk files can be changed safely. This was confirmed on 26.1.2, 26.2 and 26.3. A Paper bootstrapper would run even earlier, but nothing here needs that, and `onLoad` keeps the plugin's logger and config available.
- **Bukkit events for tracking.** Paper has no newer API for block place and break, explosions or containers.
- **Region files, read and written directly.** No API removes a chunk from disk, and `World.regenerateChunk` is deprecated and unreliable. The Anvil format is unchanged in 26.x apart from the palette encoding above.
- **Reflection for GriefPrevention.** GriefPrevention publishes no Paper API artifact. The plugin reads two methods by reflection and fails closed if they're missing.
- **No display entities or dialogs.** `/wr preview` uses particles shown only to the admin who asked. Display entities would be real entities saved into the world, which this plugin should never leave behind. A dialog adds nothing to a text explanation from `/wr why`.
- **No data component or registry APIs.** The plugin doesn't touch items beyond counting container contents, and it matches blocks by `Material` name from config.

## Versions tested

See the README's Testing section. Bots speak 1.21.11 and reach 26.x through ViaVersion and ViaBackwards, so the client side of every bot test was a translated 1.21.11 client, not a real 26.x client.
