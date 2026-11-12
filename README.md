# Rewild

A Paper plugin that rewilds abandoned, mined-out land back to fresh terrain from the world's seed, and keeps every block players placed exactly where it is. Paths, builds, chests and protected places stay; the holes, quarries and craters around them fill back in.

**Status:** pre-release. 0.3.0 is a redesign and has passed its tests on throwaway Paper 26.3 servers with a bot as the player (see [Testing](#testing)). It hasn't run on a real server, and nobody has looked at a rewilded area in a real game client yet.

- Paper 26.1 and newer, Java 25. Not Spigot, and not 1.21.x.
- Off in every world until you turn it on. Dry run by default. Worlds in `never-reset-worlds`, such as kids' worlds, can never be turned on by mistake.
- Nothing resets until an admin has said which plugins to respect, an admin approves a run, and every run can be undone.

## How it works

1. **Track.** From the moment a world is enabled, the plugin records what players change in each chunk: every block they place, blocks they break, paths they make with a shovel, containers they put things in, and explosion damage. Growth, decay and fire don't count.
2. **Scan.** `/rewild scan` checks every chunk the tracker has seen damaged. Chunks that are mined out and abandoned go into a proposed run.
3. **Approve.** An admin checks the run with `/rewild preview` and `/rewild why`, then approves it with `/rewild approve`. Rewild then generates a fresh copy of each chunk in a temporary world with the same seed and generator, saves it with the run, and deletes that world.
4. **Rewild.** At the next restart, before any world loads, the plugin checks every chunk again with the server stopped. Then it rebuilds each one from the fresh copy, block by block, except what's kept, which it copies over exactly as it was, contents and all. Players in the world get a vague warning when a run is approved, which doesn't say where.

A chunk generated before the world was enabled is **unknown**. The plugin can't tell what a player built there from what was always there, so it never rewilds an unknown chunk. On a world that's already been played, only land explored after install can ever rewild.

### What stays in place

When a chunk rewilds, these stay exactly as they are, and the rest of the chunk becomes fresh terrain:

- Every block a player placed: builds, chests, beds, signs, torches, farms, a bridge.
- Containers a player put items in, even natural ones.
- Every block of a type in `keep-blocks`. That's dirt paths by default, so paths stay wherever they are. Add your own, such as `OAK_FENCE` or `*LANTERN`.
- Block areas an admin keeps with `/rewild keep`, and areas a respected plugin reports.
- What holds each of those up: the block under it, the block a lantern hangs from or a torch is fixed to, and the other half of a door or bed. Only one block deep, because the fresh terrain usually brings the rest back.

### What keeps a whole chunk

These leave the whole chunk alone, plus one chunk around it:

- A GriefPrevention claim, or within one chunk of one.
- Named, tamed or leashed animals, villagers who have traded, allays, armor stands and item frames.
- World spawn, every player's bed or respawn point, and any player online nearby.
- Strongholds, and the End's main island.
- Areas an admin marked with `/rewild protect`, Multiverse portals, and whole chunks a respected plugin reports.
- A neighbour generated before tracking began. A rewilded chunk only blends with neighbours made by the same generator.

### Which plugins are respected

On first start Rewild lists every installed plugin and resets nothing until an admin has answered for each one: `/rewild plugins` shows them with buttons. Respect a plugin and everything it reports stays. Ignore it and its reports don't count. `/rewild plugins ignore-rest` answers the rest at once. Answers are kept in `plugins/Rewild/keep-plugins.yml`.

A plugin installed later has no answer, so resets stop again until an admin looks at it. A respected plugin that isn't running stops resets too, because Rewild can't ask it what to keep.

### When a chunk can rewild

All of these must hold, and every number is in `config.yml`:

- At least 40 blocks removed by players, or an explosion crater of 12 blocks. Removing your own placed blocks doesn't count.
- No player changes for 30 days.
- No player in or near it for 7 days.
- At least 30 days since it last rewilded.

Admins in spectator or creative mode don't count as visitors, so looking around before approving doesn't hold a run back.

### Below-ground repair

Sometimes the chunk is kept whole, by a pet or a respawn point, or someone still uses the surface, but the ground under it is mined out and abandoned. Then only the ground below a cut rewilds, and everything above it stays exactly as it is. The cut sits at least 8 blocks below the lowest point of the surface, rounded down to a 16-block section. Player-placed blocks below the cut stay in place too.

Before either kind of change, the plugin checks the bedrock pattern of the fresh copy against the real chunk, and refuses if they differ. A repair doesn't happen if anything that keeps the chunk whole reaches below the cut, if players changed or visited anything down there recently, or if the chunk is in a claim, at spawn or in an admin-protected area. It only runs in overworld-type worlds.

## Safety

- **Dry run by default.** With `dry-run: true`, an approved run is checked at the next restart and reported in the log, and no world file changes.
- **Fail closed.** If the claim plugin isn't running, the tracker can't save, config.yml has an error, a plugin has no answer, or a respected plugin can't report what it keeps, nothing resets, and `/rewild scan` says why.
- **A final check at shutdown.** Claims, respawn points, kept areas and other plugins are read when the server stops, and the startup step uses that reading. If the server didn't shut down cleanly, the run waits for a clean restart.
- **Generator fingerprint.** The plugin saves the game version, seed, generator and a hash of the datapacks. If any of them changes, resets stop and the log names the change with a sample of affected chunks. After checking, `/rewild approve generator <world>` carries on, and chunks generated before the change stay unknown.
- **Capped.** At most 200 chunks a run. Connected groups go first.
- **Trash and undo.** Every region file a run touches is copied to `plugins/Rewild/trash/<date>/<run>/` first, at the same path it has under the server folder. Copies are kept 30 days. `/rewild undo <run>` puts the run's chunks back at the next restart, including their entities and points of interest, and discards anything built there since.
- **Crash-safe.** Each file is rewritten through a temporary file and an atomic rename. A journal records progress, so a crash at any point either finishes the run at the next start or rolls it back from the trash copies. What's kept is worked out again from the trash copy, so a resumed run keeps exactly the same blocks.

## Install

1. Put `Rewild-<version>.jar` in `plugins/`, next to GriefPrevention.
2. Start the server once. Answer for each plugin with `/rewild plugins`.
3. Enable a world in `plugins/Rewild/config.yml`:

   ```yaml
   worlds:
     example_world:
       enabled: true
   ```

4. Restart. Tracking starts then. Nothing can rewild until the tracker has watched a chunk from the moment it was generated, and the idle times have passed.
5. When you're ready, set `dry-run: false`.

If the server has no claim plugin at all, set `claims.plugin: none`.

**Upgrading from 0.2.x:** 0.2.x only recorded some placed blocks, so chunks it tracked stay unknown and never rewild. Only land generated after the upgrade can. The `intent` settings in config.yml do nothing now and can be deleted. `/wr`, `/wildernessregen` and the `wildernessregen.admin` permission are gone; use `/rewild` and `rewild.admin`.

## Commands

All commands need `rewild.admin`, which ops have. Players get no commands.

| Command | What it does |
| --- | --- |
| `/rewild status` | Shows each world's tracked and unknown chunks, its health, recent runs, and whether plugins still need an answer. |
| `/rewild why [world] [x z]` | Explains why a chunk is kept, waiting, unknown or queued, and what stays in place if it rewilds. It defaults to the chunk you're standing in, and takes chunk coordinates. |
| `/rewild preview [radius]` | Outlines nearby chunks in particles for 30 seconds: red rewilds, orange rewilds below ground, green kept, yellow waiting, grey unknown. |
| `/rewild scan [world]` | Scans and proposes a run. |
| `/rewild approve <run>` | Makes fresh terrain for the run and approves it for the next restart. |
| `/rewild approve generator <world>` | Accepts a changed world generator. |
| `/rewild undo <run>` | Cancels an approved run, or undoes an applied one at the next restart. |
| `/rewild protect [radius] [note]` | Keeps the chunks around you whole, forever. `/rewild protect list` and `/rewild protect remove` manage them. |
| `/rewild keep <world> <x y z> [<x y z>] [plugin:<Name>] [note]` | Keeps a block, or a box of blocks, exactly as it is when the land around it rewilds. |
| `/rewild keep list [world] [--json]` | Lists kept areas, with who kept them and whether they count. |
| `/rewild unkeep <id>` | Removes a kept area. `/rewild unkeep <world> <x y z> [<x y z>] [plugin:<Name>]` removes one by its exact corners. |
| `/rewild plugins [--json]` | Lists installed plugins and which Rewild respects. `respect <Name>...`, `ignore <Name>...` and `ignore-rest` answer. |
| `/rewild reload` | Reloads config.yml, keep-plugins.yml and the kept areas. |

With `bluemap.enabled: true`, the last scan also appears as a BlueMap layer. It's off by default, because kept chunks show where people build and sleep. Only turn it on for a map that only admins can open.

## For plugin authors

Another plugin can keep what it owns in two ways, and Rewild listens once an admin respects it.

**A service.** Register a `KeepProvider` with Bukkit's services manager. `keeps` reports whole chunks to leave alone; `areas` reports block boxes to keep in place while the rest of the chunk rewilds:

```java
getServer().getServicesManager().register(KeepProvider.class, new KeepProvider() {
    public Collection<Keep> keeps(World world) {
        return graves.chunksIn(world).stream().map(c -> new Keep(c.x(), c.z(), "an active grave")).toList();
    }
    public Collection<Area> areas(World world) {
        return homes.in(world).stream().map(h -> new Area(h.minX(), h.minY(), h.minZ(), h.maxX(), h.maxY(), h.maxZ(), "a homestead")).toList();
    }
}, this, ServicePriority.Normal);
```

**A command**, with no compile-time dependency: `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rewild keep world 10 64 10 20 80 20 plugin:MyPlugin a homestead")`, and `rewild unkeep` with the same corners to undo it.

If a respected provider throws, nothing in that world resets. If LanternGraves is respected and running but hasn't registered a provider, nothing resets either.

## Known limits

- Only chunks explored after install (and, for 0.2.x worlds, after the upgrade) can ever rewild. History from CoreProtect can't be imported, and Rewild doesn't use CoreProtect.
- A chunk at the edge of explored land waits until the land around it is explored too.
- A rewilded chunk's terrain comes from a copy generated in a temporary world, so it isn't block-for-block identical to a fresh world. In tests the shape of the ground matched exactly and it met its neighbours at the same height; a few leaves and a stone blob differed, from features neighbouring chunks spread into it.
- A player-placed block whose ground was dug out from under it gets back only its one supporting block. If the fresh terrain there is air (a ravine, say), the support floats.
- Trees grown from a player's saplings weren't placed, so they rewild. Water a player poured stays as a source but doesn't flow again until something nearby updates.
- A below-ground repair drops mobs and items under the cut, and doesn't bring back mineshaft minecarts.
- GriefPrevention is the only claim plugin supported. Animated Architecture has no hook, but its builds are made of player-placed blocks, which the tracker sees.
- Undo restores a chunk as it was before the run, so anything built in it since is lost.

## Testing

`./gradlew build` runs 82 unit tests on made-up world files: region and NBT round trips, the block-by-block merge (in both the old save format and 26.x's), what holds a kept block up, the tracking store's crash log, the respected-plugin answers and kept areas, every keep rule, and the startup step's crash, rollback, undo and dry-run paths.

`test/acceptance.mjs` runs the plugin on a throwaway Paper server, in a temp folder on 127.0.0.1, with a world generated fresh from a fixed seed. A mineflayer bot plays: it digs, builds, places chests, torches and a bed, makes paths with a shovel and claims land. `test/keep.mjs` adds a stand-in plugin named LanternGraves that reports chunks and block areas.

0.3.0 passed on **Paper 26.3 build 249** and **26.2**, with GriefPrevention, Multiverse-Core, Multiverse-Inventories and BlueMap installed: all 113 acceptance checks on each, and the 9 provider checks on 26.3. (On 26.3, one check, that a kept hole stays empty, was loosened after the run to accept water flowing into it from fresh terrain next door; that run's readings pass the loosened check, and 26.2 ran with it from the start.)

**Passed on 26.3 and 26.2:**

- **Plugins first:** before any plugin had an answer, a scan refused to propose and said why, and the log asked for answers once every plugin had started. `/rewild plugins ignore-rest` and `--json` worked. A respected plugin's chunk was kept and `/rewild why` named it; ignored, the same report didn't count. A respected LanternGraves that registered nothing stopped every reset.
- **Kept in place:** in chunks that rewilded, the player's chest (and the block under it), a stray torch, every cabin wall block, a lone dirt path, three paths across a chunk border, a bed and a surface chest over a mined tunnel all stayed. A dug-out area kept with `/rewild keep`, and one reported by the stand-in plugin, stayed dug out, while the dug ground next to them came back as fresh grass.
- **Kept whole:** a named pet, a respawn point, world spawn, a GriefPrevention claim and a player standing nearby each kept their chunk byte for byte.
- **Terrain:** a mined-out chunk and an explosion crater matched a fresh copy of the seed on 100% of ground shape and at least 99.99% of blocks, and met their neighbours at the same height. The differences were a few leaves and a stone blob. A tunnel 40 blocks down came back as fresh terrain.
- **Undo, dry run, crashes:** undo restored the chunks byte for byte. A dry run changed no world file. A halt like `kill -9` before the backup, a region write and the store update each finished from the journal at the next start, with no chunk errors, and a real `kill -9` during startup left the run applied.
- **Failing safely:** a read-only world folder failed the run with no file changed. Missing GriefPrevention, a broken config, a tracker that can't save and a changed datapack each stopped resets and said why. The cap held. Multiverse and BlueMap still worked after the runs. A scan of the test world took about 130 ms, with tick time unchanged.

**Found by these tests and fixed before release:** 26.3 saves block states in a newer shape. The first build read every plain block in a merged section as the same block, and could write a section mixing two list shapes that nothing could read back. Both are fixed, covered by unit tests, and the NBT writer now refuses a mixed list, so a mistake like that fails the run and rolls it back instead of saving.

**Mocked or not tested:**

- **The client side.** The bot speaks 1.21.11 and reaches 26.x through ViaVersion and ViaBackwards, so no real 26.x client was involved. How a rewilded area looks around kept builds, whether seams look natural, and whether `/rewild preview` is readable need a person in a real client.
- **26.1.** 0.3.0 hasn't been run on 26.1.2.
- **Scale.** Measured on a small test world, not a long-played one. Approval generates fresh terrain for every chunk in the run, up to 200, in a temporary world; how long that takes on a busy server isn't measured.
- **Real plugins as providers.** LanternGraves 0.1.2 registers whole-chunk keeps but hasn't been run against 0.3.0. No other plugin reports block areas yet. Multiverse-Portals and the BlueMap layer weren't exercised.
- **Disk full** was simulated by making the trash folder unwritable (a unit test), not by filling a disk.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. It needs Java 25.

## License

MIT. See [LICENSE](LICENSE).
