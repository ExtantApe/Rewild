# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

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
| Upgrade from WildernessRegen: folder moved, fails closed | unit `MigrationTest`; acceptance "Upgrade from WildernessRegen" |

**Gaps added this audit:** the never-reset test in bold.

**Found and fixed:** 0.2.0 still told admins to use `/wr` in several messages and log lines (a miss in the rename). 0.2.1 names `/rewild` everywhere; the deprecation notice still names `/wr` on purpose.

**Skipped, with reasons:**
- How a reset area looks and blends in a real client: real client only, in the README's untested list.
- LanternGraves as a real keep provider: it doesn't register one yet; `keep.mjs` uses a stand-in with the same name.
