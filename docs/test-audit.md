# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-22: 0.4.0 gradual healing and the Magic hook (K675)

Offline, unit tests only: no test servers tonight (owner). 103 unit tests pass.

| Promise | Covered by |
| --- | --- |
| A typical crater heals in about a week, bottom up, one band of layers a step | `HealPlanTest.aTypicalCraterTakesAboutAWeekAndFillsBottomUp`, `aDeepHoleTakesMoreLayersPerStepNotMoreDays` |
| Grass comes back as dirt first, and the grass step may replace only that dirt | `grassComesBackAsDirtFirstThenGrassReplacingIt` |
| Felled trees: sapling, then young tree replacing only its sapling, then whole | `aFelledTreeComesBackAsASaplingThenAYoungTreeThenWhole`, `touchingLogsAreOneTreeAndSeparateOnesAreTwo`, `saplingsMatchTheWoodAndStrippedOrUnknownLogsGetNone` |
| Small plants last; air never placed; each block once | `smallPlantsComeBackLast`, `airIsNeverPlacedAndEveryRecordedBlockComesBackOnce`, `nothingToHealMeansNoSteps` |
| **Only natural, known land heals without approval** (fails closed) | `HealCheckAndJobTest.unknownLandNeverHealsOnItsOwn`, `aBlastThroughPlayerBlocksWaitsForAnAdmin`, `aBuildNearbyStopsIt_evenInTheNextChunk`, `aFilledContainerNearbyStopsIt`, `aHoleInKnownNaturalLandHeals` |
| Healing survives a restart; a damaged file is refused, not guessed | `aJobSurvivesARestartExactly`, `aDamagedJobFileIsRefusedNotGuessed` |
| **A Magic world without Magic running never resets or heals**; Magic that can't answer stops resets | `MagicKeepsTest` (5 tests) |
| Settings default to a week and bad values stop resets | `RegenConfigTest.healingIsOnWithAWeekAndMagicWorldsAreRead` |

Bot run, same night after the owner's go: `test/heal.mjs` on Paper 26.3, 19 of 19. It found one bug, fixed before release: a step skipped any block an entity was in, so the blast's own dropped items kept 13 of 114 blocks from coming back. Only mobs and pets count now, and the rerun brought all 111 back.

Still open:
- Covered by `heal.mjs` now: pausing near a player, not overwriting a block placed since, dry run placing nothing, a nearby build and a blast through a chest refusing a hole, restart. Not yet: waiting for unloaded chunks, skipping a block a mob is in, claims and kept areas (the test runs with `claims: none`).
- The Magic hook against a running Magic 11.3: it was written against the jar's interfaces only.

Skipped: none of the pure rules; the reflection calls themselves are left to the bot run, since a mock would only test the mock.

## 2026-11-12: the 0.3.0 redesign

The redesign changes what a reset does, so every keep and safety promise was mapped again. "Acceptance" is `test/acceptance.mjs` and `test/keep.mjs` on a throwaway Paper 26.3 (build 249) server; "unit" is `src/test`.

| Promise | Covered by |
| --- | --- |
| **Every player-placed block stays in place when its chunk rewilds**, with the block holding it up | unit `keptBlocksAndTheirSupportsComeThroughEverythingElseIsFresh`, `KeepMaskTest` (below, above, behind, both halves of doors and beds); acceptance chest, torch, cabin walls, bed, surface chest over a tunnel |
| Dirt paths stay (`keep-blocks`) | unit merge tests; acceptance lone path and paths across a border |
| Areas kept with `/rewild keep` and by respected plugins stay exactly as they were, and the rest of the chunk rewilds | unit `anAreaKeepsEveryBlockInsideItEvenAir`; acceptance kept area; `keep.mjs` reported area |
| **Nothing resets until every installed plugin has an answer**, a later plugin stops resets again, and a respected plugin that isn't running stops them | unit `nothingResetsUntilEveryInstalledPluginHasAnAnswer`, `aRespectedPluginThatIsntRunningStopsResets`, `aBrokenOrContradictoryFileStopsResets`; acceptance before answering |
| An ignored plugin's reports don't count; a respected one's do | `keep.mjs` (both) |
| A provider written before 0.3.0 still works: LanternGraves 0.1.2 answers through a proxy that refuses `areas()` | `test/lanterngraves.mjs` with the real 0.1.2 jar on 26.3 (found while writing K346: without the fix, Rewild reset nothing while LanternGraves ran) |
| A plugin's `/rewild keep` doesn't count until it's respected; unkeep by exact corners | acceptance keep and unkeep |
| Kept areas carry through the shutdown check, so the restart keeps the same blocks | unit `areasSurviveTheShutdownSnapshot` |
| Chunks tracked by 0.2.x (partial placed-block records) stay unknown | unit `chunkBornBeforeEveryBlockWasRecordedIsUnknown` |
| **26.x save formats are read and written correctly** | unit `the26xPaletteShapesKeepEveryStateApart`, `aSectionMixingBareNamesAndPropertiesSavesAndReadsBack`, `theWriterRefusesAMixedList`, `encodeRoundTripsEveryPaletteSize` |
| Fresh terrain from a different world is refused | unit `differentTerrainIsRefused`, `referenceWithDifferentBedrockIsRefused` |
| A rewilded chunk stays known and its damage is cleared | unit `appliesThenUndoRestoresExactly` |
| Undo, dry run, crash recovery, read-only folder, cap, kids' worlds, fingerprint, claims | unchanged tests, rerun: all pass |

**Gaps added in this pass:** all of the above are new. Two were found by the first 26.3 run: plain block states read as one state (the merge would have changed blocks around kept ones), and a merged section that couldn't be read back. Both have unit tests now.

**Skipped, with reasons:**
- `/rewild keep` argument edge cases (a world name that looks like a number, notes containing `plugin:`): low risk, an admin sees the reply at once.
- How a rewilded area looks around kept builds in a real client: real client only, on the README's untested list.
- 26.1.2 run of 0.3.0: the server moves to 26.3; 0.2.x passed on 26.1.2 and the code paths are the same.

## 2026-11-09: first full audit (at 0.2.0, fixed in 0.2.1)

Rewild changes world files, so its safety promises come first. "Acceptance" is `test/acceptance.mjs` (with `keep.mjs`, `configs.mjs` and `seam.mjs`) on throwaway Paper 26.1.2, 26.2 and 26.3; "unit" is `src/test`.

| Promise | Covered by |
| --- | --- |
| **Kids' worlds in `never-reset-worlds` can never be turned on** | unit `neverResetWorldsWinOverEnabled` (config); **new** unit `aWorldAddedToNeverResetAfterApprovalIsNeverReset` (an approved run is refused at startup and no world file changes) |
| Off until enabled; dry run by default changes no world file | unit `dryRunChangesNoWorldFile`; acceptance dry run |
| Nothing resets without an approved run | acceptance scan → approve → restart |
| **Fail closed:** no claim plugin, tracker can't save, broken config, a plugin that can't report | acceptance "Claims, config and tracker health" (tracker can't save, claims), `keep.mjs` (a provider that registered nothing stops every reset); unit `brokenConfigBlocksTheRun`, `claimsUnreadableAtShutdownBlocksTheRun` |
| Final check at shutdown; unclean shutdown waits | unit `uncleanShutdownBlocksTheRun`, `finalCheckDropsChunksThatChangedOrGainedAClaimAfterApproval` |
| Generator fingerprint stops resets on a change | unit `gameVersionChangeBlocksTheRun`; acceptance generator section |
| Unknown chunks never reset | acceptance "fresh install on a played world: nothing is proposed", "the mined chunk is unknown" |
| Keep rules (claims, paths, built blocks, containers, pets, spawn, beds, portals, other plugins, protect) | `IntentRules` and keep unit tests; acceptance and `keep.mjs` |
| Capped at 200 chunks a run | unit and acceptance (max-chunks) |
| Trash and undo restore exactly | unit `appliesThenUndoRestoresExactly`; acceptance undo |
| Crash-safe at every step, including undo | unit `crashAtEveryStepResumesToTheSameResult`, `crashDuringUndoResumes`; acceptance kill -9 |
| Read-only world or unwritable trash: no change at all | unit `readOnlyWorldFolderFailsWithoutChangingAnything`, `trashThatCantBeWrittenStopsBeforeAnyChange` |
| Below-ground repair only under the cut; refused if bedrock differs | unit `belowGroundRepairReplacesOnlySectionsUnderTheCut`, `referenceWithDifferentBedrockIsRefused` |

**Gaps added this audit:** the never-reset test in bold.


**Skipped, with reasons:**
- How a reset area looks and blends in a real client: real client only, in the README's untested list.
- LanternGraves as a real keep provider: it doesn't register one yet; `keep.mjs` uses a stand-in with the same name.

## 2026-11-16: cleanup (D226)

- **Covered:** the harness records each server folder it creates and each Java process it starts. When the run ends (pass, fail, an uncaught error, Ctrl-C, SIGTERM or SIGHUP) it kills those processes and deletes those folders, and nothing else. A folder from an earlier run that's in the way stops the run; it isn't deleted. `KEEP_TEST_SERVERS=1` keeps the folders for debugging.
- **Checked by hand:** on MobShowdown, a full run (42/42) and a run interrupted after 45 seconds both left the work folder empty, with no Java left and the test port closed. The other harnesses use the same code.
- **Gap skipped:** the suite can't check its own leftovers, because cleanup runs as the process exits. A wrapper that checks after the suite exits would add more machinery than the check is worth here.
