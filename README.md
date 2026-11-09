# Rewild

A Paper plugin that quietly resets abandoned, mined-out chunks so survival worlds stay wild. It never removes anything that looks intentional. When in doubt, it keeps the chunk.

**Status:** pre-release. It has passed its tests on throwaway Paper servers with a bot as the player (see [Testing](#testing)). It hasn't run on a real server, and nobody has looked at a reset area in a real game client yet.

- Paper 26.1 and newer, Java 25. Not Spigot, and not 1.21.x.
- Off in every world until you turn it on. Dry run by default. Worlds in `never-reset-worlds`, such as kids' worlds, can never be turned on by mistake.
- Nothing resets until an admin approves a run, and every run can be undone.

## How it works

1. **Track.** From the moment a world is enabled, the plugin records what players change in each chunk: blocks they break and place, paths they make with a shovel, containers they put things in, and explosion damage. Growth, decay and fire don't count.
2. **Scan.** `/rewild scan` scores every chunk the tracker has seen damaged and checks it against the keep rules below. Chunks that pass go into a proposed run.
3. **Approve.** An admin checks the run with `/rewild preview` and `/rewild why`, then approves it with `/rewild approve`.
4. **Reset.** At the next restart, before any world loads, the plugin checks every chunk again with the server stopped. Then it removes the chunks from the world's files, so they generate fresh from the seed. Players in the world get a vague warning when a run is approved, which doesn't say where.

A chunk generated before the world was enabled is **unknown**. The plugin can't tell what a player built there from what was always there, so it never resets an unknown chunk. On a world that's already been played, only land explored after install can ever reset.

### What keeps a chunk

Any one of these keeps a chunk, plus one chunk around it:

- A GriefPrevention claim, or within one chunk of one.
- A player-made dirt path, once about 3 path blocks connect. Paths across a chunk border keep both chunks. A lone accidental path doesn't count, and neither do village paths.
- A player-placed bed, chest, barrel, sign, banner, door, rail, redstone, portal frame and the like. Natural ones, such as village beds and mineshaft rails, don't count.
- Player-placed building blocks adding up to a score of 8. A small cabin passes. A stray torch or crafting table doesn't.
- A container a player put items in, even a natural one.
- Named, tamed or leashed animals, villagers who have traded, allays, armor stands and item frames.
- World spawn, every player's bed or respawn point, and any player online nearby.
- Strongholds, and the End's main island.
- Areas an admin marked with `/rewild protect`, Multiverse portals, and anything another plugin reports (see [For plugin authors](#for-plugin-authors)).
- A neighbour generated before tracking began. A reset chunk only blends with neighbours made by the same generator.

### When a chunk can reset

All of these must hold, and every number is in `config.yml`:

- At least 40 blocks removed by players, or an explosion crater of 12 blocks.
- No player changes for 30 days.
- No player in or near it for 7 days.
- At least 30 days since it last reset.

Admins in spectator or creative mode don't count as visitors, so looking around before approving doesn't hold a run back.

### Below-ground repair

Sometimes the surface is kept, because someone lives there, but the ground under it is mined out and abandoned. Then only the 16-block sections below a cut reset, and the surface stays exactly as it is. The cut sits at least 8 blocks below the lowest point of the surface, rounded down to a section.

When the run is approved, the plugin generates a fresh copy of each such chunk in a temporary world with the same seed and generator, then deletes that world. At the restart it swaps in the sections below the cut. Before it does, it checks the bedrock pattern of the copy against the real chunk, and it refuses if they differ. A repair doesn't happen if anything kept reaches below the cut, if players changed or visited anything down there recently, or if the chunk is in a claim, at spawn or in an admin-protected area. It only runs in overworld-type worlds.

## Safety

- **Dry run by default.** With `dry-run: true`, an approved run is checked at the next restart and reported in the log, and no world file changes.
- **Fail closed.** If the claim plugin isn't running, the tracker can't save, config.yml has an error, or another plugin can't report what it needs kept, nothing resets, and `/rewild scan` says why.
- **A final check at shutdown.** Claims, respawn points and other plugins are read when the server stops, and the startup step uses that reading. If the server didn't shut down cleanly, the run waits for a clean restart.
- **Generator fingerprint.** The plugin saves the game version, seed, generator and a hash of the datapacks. If any of them changes, resets stop and the log names the change with a sample of affected chunks. After checking, `/rewild approve generator <world>` carries on, and chunks generated before the change stay unknown.
- **Capped.** At most 200 chunks a run. Connected groups go first.
- **Trash and undo.** Every region file a run touches is copied to `plugins/Rewild/trash/<date>/<run>/` first, at the same path it has under the server folder. Copies are kept 30 days. `/rewild undo <run>` puts the run's chunks back at the next restart, including their entities and points of interest, and discards anything built there since.
- **Crash-safe.** Each file is rewritten through a temporary file and an atomic rename. A journal records progress, so a crash at any point either finishes the run at the next start or rolls it back from the trash copies.

## Install

1. Put `Rewild-<version>.jar` in `plugins/`, next to GriefPrevention.
2. Start the server once, then enable a world in `plugins/Rewild/config.yml`:

   ```yaml
   worlds:
     example_world:
       enabled: true
   ```

3. Restart. Tracking starts then. Nothing can reset until the tracker has watched a chunk from the moment it was generated, and the idle times have passed.
4. When you're ready, set `dry-run: false`.

If the server has no claim plugin at all, set `claims.plugin: none`.

## Commands

All commands need `rewild.admin`, which ops have. Players get no commands.

| Command | What it does |
| --- | --- |
| `/rewild status` | Shows each world's tracked and unknown chunks, its health, and recent runs. |
| `/rewild why [world] [x z]` | Explains why a chunk is kept, waiting, unknown or queued. It defaults to the chunk you're standing in, and takes chunk coordinates. |
| `/rewild preview [radius]` | Outlines nearby chunks in particles for 30 seconds: red resets, orange resets below ground, green kept, yellow waiting, grey unknown. |
| `/rewild scan [world]` | Scans and proposes a run. |
| `/rewild approve <run>` | Approves a run for the next restart. |
| `/rewild approve generator <world>` | Accepts a changed world generator. |
| `/rewild undo <run>` | Cancels an approved run, or undoes an applied one at the next restart. |
| `/rewild protect [radius] [note]` | Keeps the chunks around you forever. `/rewild protect list` and `/rewild protect remove` manage them. |
| `/rewild reload` | Reloads config.yml. |

With `bluemap.enabled: true`, the last scan also appears as a BlueMap layer. It's off by default, because kept chunks show where people build and sleep. Only turn it on for a map that only admins can open.

## For plugin authors

Another plugin can keep chunks by registering a `KeepProvider` with Bukkit's services manager:

```java
getServer().getServicesManager().register(KeepProvider.class,
        world -> graves.chunksIn(world).stream().map(c -> new KeepProvider.Keep(c.x(), c.z(), "an active grave")).toList(),
        this, ServicePriority.Normal);
```

If a provider throws, nothing in that world resets. If Lantern Graves is running and hasn't registered one, nothing resets either.

## Known limits

- Only chunks explored after install can ever reset. History from CoreProtect can't be imported yet.
- A chunk at the edge of explored land waits until the land around it is explored too.
- A reset chunk doesn't come back block-for-block identical to a fresh world. The shape of the ground is the same (in tests, more than 99.9% of blocks agree on solid or open), and it meets its neighbours at the same height. What differs is the features the game scatters through terrain, because some of them spread in from neighbouring chunks when those generated, and they don't again. In tests, about 4 to 6% of a reset chunk's underground blocks were plain stone or a different ore or stone blob from a fresh world: granite, diorite, andesite, tuff, dirt, gravel or ore. At the surface, a tree from the next chunk can be cut off at the edge. Below-ground repairs don't have this problem, because their terrain comes from a fresh copy generated along with its neighbours.
- A below-ground repair drops mobs and items under the cut, and doesn't bring back mineshaft minecarts.
- GriefPrevention is the only claim plugin supported. Animated Architecture has no hook, but its builds are made of player-placed blocks, which the tracker sees.
- Undo restores a chunk as it was before the run, so anything built in it since is lost.

## Testing

`./gradlew build` runs 52 unit tests on made-up world files: region and NBT round trips, the tracking store's crash log, every keep rule, and the startup step's crash, rollback, undo and dry-run paths.

`test/acceptance.mjs` runs the same plugin on throwaway Paper servers, in a temp folder on 127.0.0.1, with worlds generated fresh from a fixed seed. A mineflayer bot plays: it digs, builds, makes paths with a shovel and claims land. The full suite passed on Paper **26.1.2, 26.2 and 26.3**, with GriefPrevention, Multiverse-Core, Multiverse-Inventories and BlueMap installed: 90 checks on 26.3, and 88 on 26.1.2 and 26.2. Those two ran one build earlier, which listed repeated config errors more than once and checked the dry-run report only in the log.

**Passed on all three:**

- **Keep rules:** a player-placed chest, bed, small build, named pet, respawn point, world spawn, GriefPrevention claim, and a player standing nearby each keep their chunk, and `/rewild why` names the reason. Three connected paths across a chunk border keep both chunks. A lone path, a stray torch, and a village's own paths, beds and villagers don't.
- **Resets:** a mined-out chunk and an explosion crater were removed at the restart, and kept chunks stayed identical, byte for byte. Chunks generated before install stay unknown, and a fresh install on an already-played world proposes nothing.
- **Seams:** reset chunks met their neighbours at exactly the same ground height. Ground shape agreed with a fresh copy of the seed on at least 99.98% of blocks. About 4 to 5% of underground blocks were a different stone or ore (see Known limits).
- **Below ground:** a tunnel 40 blocks down came back as fresh terrain, matching a fresh copy on 99.99% of blocks below the cut, while the chest on the surface stayed.
- **Undo:** restored the reset chunks byte for byte, including the below-ground one.
- **Dry run:** a world-file hash before and after the startup step showed no change.
- **Crashes:** the process was halted dead (like `kill -9`) before the backup, before a region write and before the store update. Each time, the next start finished the run from its journal, and the server loaded the world without chunk errors. A real `kill -9` during startup left the chunk either reset or untouched, never broken.
- **Failing safely:** a read-only world folder failed the run with no file changed. Missing GriefPrevention, a broken config, a tracker that can't save and a changed datapack each stopped resets, and said why.
- **Other:** the cap held, the temporary reference world was deleted, and Multiverse and BlueMap still worked after the runs. A scan of the test world took about 100 ms, with no change in tick time (about 6 ms average before and after).

**Mocked or not tested:**

- **The client side.** The bot speaks 1.21.11 and reaches 26.x through ViaVersion and ViaBackwards, so no real 26.x client was involved. Whether seams look natural in game, whether `/rewild preview` is readable, and whether a reset area feels fresh all need a person in a real client.
- **Scale.** Scan speed and tick time were measured on a small test world with a few thousand tracked chunks, not a long-played one.
- **Disk full** was simulated by making the trash folder unwritable (a unit test), not by filling a disk.
- **LanternGraves** didn't exist when these tests were written. `test/keep.mjs` stands in for it with a tiny plugin of the same name. On 26.3, a chunk it reported was kept, and `/rewild why` named it. Its neighbour was buffered. When it registered nothing, every reset stopped. LanternGraves doesn't register a provider yet, so the real pairing is untested. CoreProtect has no 26.x release to test with, and the plugin doesn't use it yet.
- **Multiverse-Portals** and the BlueMap layer were not exercised. Only BlueMap loading alongside was checked.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. It needs Java 25.

## License

MIT. See [LICENSE](LICENSE).
