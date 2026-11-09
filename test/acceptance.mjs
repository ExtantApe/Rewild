// Acceptance tests on a throwaway Paper server, with a mineflayer bot as the player.
//   node acceptance.mjs 26.3      (also 26.2, 26.1.2)
// Every world is generated fresh from a fixed seed. Nothing here touches a real server.
import path from 'node:path'
import { existsSync, readFileSync, readdirSync, writeFileSync, mkdirSync, chmodSync } from 'node:fs'
import {
  freshServer, Server, bot, tp, digBox, placeOn, hold, waitTop, Vec3, CACHE, REPO, sleep, check, note, report,
  inspect, hashTree, diffTrees, setConfig, dimension,
} from './harness.mjs'
import { testConfig } from './configs.mjs'

const VERSION = process.argv[2] || '26.3'
const FULL = !process.argv.includes('--quick')
const JAR = path.join(REPO, 'build/libs', readdirSync(path.join(REPO, 'build/libs')).find(f => /^Rewild-[\d.]+\.jar$/.test(f)))
const P = n => path.join(CACHE, 'p26', n)
const VIA = [P('ViaVersion-5.12.2.jar'), P('ViaBackwards-5.12.2.jar')]
// GriefPrevention 16.18.8 is tagged for 26.3 only; 16.18.7 covers 26.1 and 26.2.
const GP = VERSION === '26.3' ? P('GriefPrevention.jar') : path.join(CACHE, 'p26old', 'GriefPrevention.jar')
const OTHERS = [GP, P('multiverse-core-5.8.2.jar'), P('multiverse-inventories-5.3.6.jar'), P('bluemap-5.29-paper.jar')]
const SEED = 424242
console.log(`# Rewild acceptance on Paper ${VERSION}`)

const dir = freshServer(VERSION, { seed: SEED, plugins: [JAR, ...VIA, ...OTHERS], config: testConfig() })
console.log('server folder: ' + dir)
const s = new Server(dir)
const chunk = (x, z) => [x >> 4, z >> 4]
const why = async (cx, cz) => {
  const out = await s.cmd(`rewild why world ${cx} ${cz}`, new RegExp(`Chunk ${cx}, ${cz}: .*`), 60_000)
  await sleep(500)
  const lines = s.log.slice(s.log.lastIndexOf(`Chunk ${cx}, ${cz}:`)).split('\n').slice(0, 8)
  return lines.filter(l => l.includes(`Chunk ${cx}`) || l.includes(' - ')).join(' | ')
}
const regionHas = (cx, cz, kind = 'region') => inspect('has', s.dim, kind, cx, cz)

await s.start()
check('plugin enabled with no errors', /Enabling Rewild/.test(s.log) && !/Error occurred while enabling Rewild|Could not load.*Rewild/.test(s.log))
check('onLoad ran before any world loaded', s.log.indexOf('Loading server plugin Rewild') < s.log.search(/Preparing level|Preparing start region/))

const b = await bot('tester')
await s.cmd('gamemode creative tester')

// ---- find land: one plains spot per site, far from spawn and from each other ----
async function plainsNear(x, z) {
  const out = await s.cmd(`execute positioned ${x} 0 ${z} run locate biome minecraft:plains`, /nearest|Could not find/, 120_000)
  const m = out.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
  if (!m) throw new Error('no plains near ' + x + ', ' + z)
  // Centre of that chunk.
  return [(+m[1] >> 4) * 16 + 8, (+m[2] >> 4) * 16 + 8]
}

const sites = {}
let siteNo = 0
async function site(name) {
  const [x, z] = await plainsNear(3000 + siteNo * 700, 3000 + (siteNo % 3) * 700)
  siteNo++
  await s.cmd(`tp tester ${x} 220 ${z}`)
  await sleep(3000)
  const t = await waitTop(b, x, z)
  await tp(s, b, x, t.position.y + 1, z)
  b.creative.startFlying()
  const [cx, cz] = chunk(x, z)
  sites[name] = { x, z, y: t.position.y, cx, cz }
  return sites[name]
}

async function mine(st, want = 50) {
  const n = await digBox(b, new Vec3(st.x, st.y + 1, st.z), { want })
  st.dug = n
  return n
}

// ---- build the scenarios ----
console.log('\n## Building scenarios')
const s0 = await site('mined'); check('bot dug a mined-out chunk', await mine(s0) >= 45, `dug ${s0.dug}`)
const s1 = await site('chest'); await mine(s1); check('placed a chest', await placeOn(b, new Vec3(s1.x + 3, null, s1.z + 3), 'chest'))
const s2 = await site('torch'); await mine(s2); check('placed a stray torch', await placeOn(b, new Vec3(s2.x + 3, null, s2.z + 3), 'torch'))
const s3 = await site('cabin'); await mine(s3)
let planks = 0
const ring = [[3, -2], [3, -1], [3, 0], [3, 1], [3, 2], [-3, -2], [-3, -1], [-3, 0], [-3, 1], [-3, 2], [-2, 3], [-1, 3], [0, 3], [1, 3], [2, 3]]
// Two passes: the second stacks a wall course on whatever the first laid, until there are enough.
for (const [dx, dz] of [...ring, ...ring]) {
  if (planks >= 10) break
  const at = new Vec3(s3.x + dx, null, s3.z + dz)
  if (await placeOn(b, at, 'bricks') || await placeOn(b, at, 'glass')) planks++
}
check('built the start of a small cabin (bricks or glass, score 1 each, 8 needed)', planks >= 8, `${planks} blocks`)
const s4 = await site('lonepath'); await mine(s4)
async function path1(x, z) {
  await hold(b, 'iron_shovel')
  const g = await waitTop(b, x, z)
  if (g.name !== 'grass_block' && g.name !== 'dirt') return false
  // A shovel only makes a path with air above; clear any grass or flower first.
  const above = b.blockAt(g.position.offset(0, 1, 0))
  if (above && above.name !== 'air') { await b.dig(above, true).catch(() => {}); await sleep(300) }
  await hold(b, 'iron_shovel')
  await b.activateBlock(g).catch(() => {})
  await sleep(600)
  return b.blockAt(g.position)?.name === 'dirt_path'
}
let lone = false
for (const [dx, dz] of [[3, 3], [-3, 3], [3, -3], [-3, -3], [3, 0], [0, 3]]) if (!lone) lone = await path1(s4.x + dx, s4.z + dz)
check('made one lone dirt path', lone)

// Three connected paths across a chunk border: the border is at the next multiple of 16.
const s5 = await site('borderpaths')
const border = (s5.cx + 1) * 16
await mine(s5)
await tp(s, b, s5.x + 16, s5.y + 2, s5.z); b.creative.startFlying()
const s5b = { x: s5.x + 16, z: s5.z, y: (await waitTop(b, s5.x + 16, s5.z)).position.y, cx: s5.cx + 1, cz: s5.cz }
await mine(s5b)
sites.borderpaths2 = s5b
await tp(s, b, border, s5.y + 2, s5.z + 5); b.creative.startFlying()
let paths = 0
for (const x of [border - 2, border - 1, border]) if (await path1(x, s5.z + 5)) paths++
check('made three connected paths across a chunk border', paths === 3, `${paths} paths`)

const s6 = await site('pet'); await mine(s6)
{
  // On the ground, with no AI, so it can't wander into the next chunk or into a wall.
  const g = await waitTop(b, s6.x + 4, s6.z + 4)
  await s.cmd(`summon minecraft:wolf ${s6.x + 4} ${g.position.y + 1} ${s6.z + 4} {CustomName:"Rex",PersistenceRequired:1b,NoAI:1b}`)
}
const s7 = await site('bed'); await mine(s7); check('placed a bed', await placeOn(b, new Vec3(s7.x - 3, null, s7.z - 3), 'red_bed'))
const s8 = await site('respawn'); await mine(s8)
await s.cmd(`spawnpoint tester ${s8.x} ${s8.y + 1} ${s8.z}`)
const s9 = await site('spawn'); await mine(s9)
await s.cmd(`setworldspawn ${s9.x} ${s9.y + 1} ${s9.z}`)

// A GriefPrevention claim, made by the player in survival.
const s10 = await site('claim'); await mine(s10)
// GriefPrevention's /claim makes a square claim centred on the player, who must hold a golden shovel. The shovel is
// given in creative, then the bot switches to survival like a normal player.
await hold(b, 'golden_shovel')
await s.cmd('gamemode survival tester')
await s.cmd('adjustbonusclaimblocks tester 1000')
await sleep(500)
b.chat('/claim 5')
await sleep(2500)
const claimOut = await s.cmd('claimslist tester')
note('GriefPrevention claim attempt', claimOut.split('\n').filter(l => /claim|blocks/i.test(l)).slice(0, 3).join(' / ').slice(0, 200))
await s.cmd('gamemode creative tester')

// Below ground: a mine well under a kept surface. The chest keeps the surface; the tunnel is far below the cut.
const s12 = await site('underground')
check('placed a surface chest over the mine', await placeOn(b, new Vec3(s12.x + 3, null, s12.z + 3), 'chest'))
const deep = s12.y - 40
await tp(s, b, s12.x, deep, s12.z); b.creative.startFlying()
const dugDeep = await digBox(b, new Vec3(s12.x, deep, s12.z), { ys: [1, 0, -1, -2], dx: 3, dz: 3, want: 90 })
s12.deep = deep
check('bot dug a tunnel far below the surface', dugDeep >= 45, `dug ${dugDeep} around y=${deep}`)

// An explosion crater, with no player mining at all.
const s13 = await site('crater')
await s.cmd(`summon minecraft:tnt ${s13.x} ${s13.y} ${s13.z} {fuse:0}`)
await s.cmd(`summon minecraft:tnt ${s13.x + 2} ${s13.y} ${s13.z} {fuse:0}`)
await sleep(2000)

// A village, mined into, whose generated paths and beds must not count as a player's.
let village = null
const vOut = await s.cmd('execute positioned 6000 0 6000 run locate structure minecraft:village_plains', /nearest|Could not find/, 180_000)
const vm = vOut.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
if (vm) {
  const vx = (+vm[1] >> 4) * 16 + 8, vz = (+vm[2] >> 4) * 16 + 8
  await s.cmd(`tp tester ${vx} 220 ${vz}`); await sleep(4000)
  const t = await waitTop(b, vx, vz)
  await tp(s, b, vx, t.position.y + 1, vz); b.creative.startFlying()
  village = { x: vx, z: vz, y: t.position.y, cx: vx >> 4, cz: vz >> 4 }
  sites.village = village
  await mine(village)
  note('village site', `chunk ${village.cx}, ${village.cz}, dug ${village.dug}, top block ${t.name}`)
}

// The "player nearby" site is last: the bot stays there through the scan.
const s11 = await site('nearby'); await mine(s11)

await s.cmd('save-all flush', /Saved the game|saved/i, 120_000)
await sleep(3000)

// ---- scan ----
console.log('\n## Scan and why')
const MSPT = /Server tick times[^\n]*\n[^\n]*\d/
const mspt0 = await s.cmd('mspt', MSPT)
const scanOut = await s.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved|Scan failed/, 120_000)
const mspt1 = await s.cmd('mspt', MSPT)
note('server tick time before and after the scan', 'avg/min/max over 5s ' + (mspt0.match(/[\d.]+\/[\d.]+\/[\d.]+/) || [''])[0] + ' -> ' + (mspt1.match(/[\d.]+\/[\d.]+\/[\d.]+/) || [''])[0])
const scanLine = (scanOut.match(/Scanned world in (\d+) ms[^\n]*/) || [''])[0]
note('scan', scanLine)
const runId = (scanOut.match(/Proposed run (\S+?)\./) || [])[1]
check('a run was proposed', !!runId, scanOut.split('\n').slice(-4).join(' | '))

const expect = async (name, kind, re) => {
  const st = sites[name]
  if (!st) return note(name, 'site missing')
  const w = await why(st.cx, st.cz)
  check(`${name}: ${kind}${re ? ' because ' + re : ''}`, w.includes(`: ${kind}`) && (!re || new RegExp(re).test(w)), w.slice(0, 260))
  return w
}
await expect('mined', 'would reset')
await expect('chest', 'kept', 'chest')
await expect('torch', 'would reset')
await expect('cabin', 'kept', 'score')
await expect('lonepath', 'would reset')
await expect('borderpaths', 'kept', 'path')
await expect('borderpaths2', 'kept', 'path')
await expect('pet', 'kept', 'named wolf')
await expect('bed', 'kept', 'bed')
await expect('respawn', 'kept', 'bed or respawn')
await expect('spawn', 'kept', 'spawn')
await expect('claim', 'kept', 'claim')
await expect('underground', 'would reset below y=')
await expect('crater', 'would reset')
await expect('nearby', 'kept', 'player is nearby')
if (village) {
  const w = await why(village.cx, village.cz)
  check('village: its generated paths, beds and villagers don\'t count as a player\'s', !/path|player-placed|villager that/.test(w), w.slice(0, 260))
}
const spawnArea = await why(0, 0)
check('chunks generated before the plugin ran are unknown', /unknown/.test(spawnArea), spawnArea.slice(0, 160))

// ---- approve, then a clean restart applies it ----
console.log('\n## Approve and apply')
const approveOut = await s.cmd(`rewild approve ${runId}`, /approved|Not approved/, 300_000)
check('run approved (with fresh terrain for the below-ground repair)', /Run \S+ approved/.test(approveOut), approveOut.split('\n').filter(l => /approved|Not approved/.test(l)).join(' ').slice(0, 200))
check('the temporary reference world was cleaned up', !readdirSync(path.join(dir, 'world/dimensions/minecraft')).some(f => f.startsWith('wr_reference_')))
b.quit()
await sleep(2000)
await s.stop()
check('the shutdown check was written', existsSync(path.join(s.dataDir, 'worlds')) &&
  readdirSync(path.join(s.dataDir, 'worlds')).some(u => existsSync(path.join(s.dataDir, 'worlds', u, 'final-check.txt'))))

const kept = ['chest', 'cabin', 'borderpaths', 'pet', 'bed', 'claim', 'nearby']
const before = {}
for (const k of [...kept, 'mined', 'underground', 'torch']) before[k] = regionHas(sites[k].cx, sites[k].cz)
const chestY = sites.underground.y + 1

// The fresh seed copy, for comparing terrain after the reset.
console.log('\n## Fresh copy of the seed')
const fresh = freshServer(VERSION, { seed: SEED, plugins: [] })
const fs2 = new Server(fresh)
await fs2.start()
for (const k of ['mined', 'underground', 'torch', 'crater']) {
  const st = sites[k]
  await fs2.cmd(`forceload add ${st.x} ${st.z}`)
  // A control: an untouched chunk three chunks over, never reset, generated in both worlds.
  await fs2.cmd(`forceload add ${st.x + 48} ${st.z}`)
}
await sleep(5000)
await fs2.cmd('save-all flush', /Saved the game|saved/i, 120_000)
await fs2.stop()
// Keep only the region files the comparison needs, then delete the fresh server: one server copy at a time.
const freshDim = path.join(REPO, 'build', `fresh-seed-${VERSION}`)
;(await import('node:fs')).rmSync(freshDim, { recursive: true, force: true })
;(await import('node:fs')).cpSync(path.join(dimension(fresh, 'overworld'), 'region'), path.join(freshDim, 'region'), { recursive: true })
fs2.remove()

console.log('\n## Restart')
await s.start()
const appliedLine = (s.log.match(/Run \S+ \(world\) applied:[^\n]*/) || [''])[0]
check('startup applied the run', !!appliedLine, appliedLine)
check('mined chunk is gone from the region file', regionHas(sites.mined.cx, sites.mined.cz) === 'absent')
check('crater chunk is gone from the region file', regionHas(sites.crater.cx, sites.crater.cz) === 'absent')
for (const k of kept) check(`${k} chunk is untouched, byte for byte`, regionHas(sites[k].cx, sites[k].cz) === before[k])
// Let the reset chunks generate again, then compare with the fresh copy.
for (const k of ['mined', 'underground', 'torch', 'crater']) await s.cmd(`forceload add ${sites[k].x} ${sites[k].z}`)
await sleep(6000)
await s.cmd('save-all flush', /Saved the game|saved/i, 120_000)
// Forced chunks stay loaded across restarts and get saved again, which would change their bytes; release them.
await s.cmd('forceload remove all')
await s.stop()
for (const k of ['mined', 'torch', 'crater']) {
  // Two generations of the same chunk never match exactly: features from neighbours (ore veins, clay, trees) overlap
  // differently. So the reset chunk is judged against an untouched control chunk generated in both worlds too.
  const control = inspect('compare', s.dim, freshDim, sites[k].cx + 3, sites[k].cz)
  const c = inspect('compare', s.dim, freshDim, sites[k].cx, sites[k].cz)
  const surf = +c.match(/surfaceEdge ([\d.]+)/)[1], ctlSurf = +control.match(/surfaceEdge ([\d.]+)/)[1]
  const ter = +c.match(/terrain ([\d.]+)/)[1], ctlTer = +control.match(/terrain ([\d.]+)/)[1]
  check(`${k}: the reset chunk's ground meets its neighbours at the same height, as an untouched chunk's does`,
    surf >= Math.min(0.95, ctlSurf - 0.02), `reset: ${c}; control: ${control}`)
  // A regenerated chunk misses the ore veins and stone blobs its neighbours spread into it when they generated, so
  // the expected differences are plain stone or deepslate where a fresh world has those. Anything else is a bug.
  const shape = +c.match(/shape ([\d.]+)/)[1]
  check(`${k}: the reset chunk's ground has the same shape as a fresh copy's; only which ore or stone fills it differs`,
    shape >= 0.999, `shape ${shape}; same block ${ter} (control ${ctlTer}); differences: ${(c.match(/diffs \[(.*)\]/) || [])[1]}`)
}
{
  const st = sites.underground
  const c = inspect('compare', s.dim, freshDim, st.cx, st.cz, st.deep + 20)
  const below = +c.match(/below ([\d.]+)/)[1]
  check('underground: the mined tunnel came back as fresh terrain below the cut', below > 0.97, c)
  const chest = inspect('block', s.dim, st.x + 3, chestY, st.z + 3)
  check('underground: the surface chest is still there', chest === 'minecraft:chest', chest)
}

// ---- undo ----
console.log('\n## Undo')
await s.start()
const undoOut = await s.cmd(`rewild undo ${runId}`, /undone at the next restart|nothing to undo|can't/)
check('/rewild undo accepted', /undone at the next restart/.test(undoOut))
await s.stop()
const undoneHashBefore = before.mined
await s.start()
check('startup undid the run', /Run \S+ undone/.test(s.log))
check('mined chunk is back exactly as it was', regionHas(sites.mined.cx, sites.mined.cz) === undoneHashBefore)
check('underground chunk is back exactly as it was', regionHas(sites.underground.cx, sites.underground.cz) === before.underground)
await s.stop()

if (FULL) {
  // ---- dry run ----
  console.log('\n## Dry run')
  setConfig(dir, testConfig({ dryRun: true }))
  await s.start()
  const scan2 = await s.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved/, 120_000)
  const run2 = (scan2.match(/Proposed run (\S+?)\./) || [])[1]
  check('a second run was proposed after the undo', !!run2, scan2.slice(-200))
  await s.cmd(`rewild approve ${run2}`, /approved|Not approved/, 300_000)
  await s.stop()
  const h0 = hashTree(path.join(dir, 'world'))
  await s.start({ jvm: ['-Drewild.test.haltAfterStartup=true'], wait: false })
  await s.ended()
  const h1 = hashTree(path.join(dir, 'world'))
  check('dry run: the startup step changed no world file', diffTrees(h0, h1).length === 0, diffTrees(h0, h1).slice(0, 3).join(', '))
  // The halt above can beat the logger to the console, so the saved result is checked, then the log on a normal start.
  const saved = readFileSync(path.join(s.dataDir, 'runs', run2, 'run.properties'), 'utf8').match(/dryRunResult=([^\n]*)/)
  check('dry run: the run records what it would do', !!saved && /would do \d+ full resets/.test(saved[1]), saved ? saved[1] : 'nothing saved')
  const h2 = hashTree(path.join(dir, 'world'))
  await s.start()
  check('dry run: a normal start logs what it would do, with where the details are', /Dry run for \S+ \(world\): would do \d+ full resets[^\n]*check\.txt/.test(s.log), (s.log.match(/Dry run for[^\n]*/) || [''])[0].slice(0, 220))
  check('dry run: the reset chunk is still in the world', regionHas(sites.mined.cx, sites.mined.cz) === undoneHashBefore)
  await s.stop()

  // ---- kill -9 at several points ----
  console.log('\n## Crash recovery')
  setConfig(dir, testConfig({ dryRun: false }))
  const markers = ['backup', 'write world/dimensions/minecraft/overworld/region/' + `r.${sites.mined.cx >> 5}.${sites.mined.cz >> 5}.mca`, 'store']
  let runN = run2
  for (const m of markers) {
    await s.start({ jvm: [`-Drewild.test.crashAt=${m}`], wait: false })
    const code = await s.ended()
    check(`halted like kill -9 before "${m}"`, code === 137, `exit ${code}`)
    await s.start()
    check(`after the crash before "${m}", the run finished from its journal`, /was interrupted while being applied/.test(s.log) && /finished after the interruption/.test(s.log))
    check(`after the crash before "${m}", the mined chunk is reset`, regionHas(sites.mined.cx, sites.mined.cz) === 'absent')
    check(`after the crash before "${m}", the server runs without chunk errors`, !/(Exception|corrupt|Failed to read chunk)/i.test(s.log.replace(/TEST: halting[^\n]*/g, '')))
    // Undo, then a fresh run for the next crash point.
    await s.cmd(`rewild undo ${runN}`, /undone at the next restart|nothing to undo/)
    await s.stop()
    await s.start()
    check(`undo after the crash before "${m}" restored the chunk`, regionHas(sites.mined.cx, sites.mined.cz) === undoneHashBefore)
    const sc = await s.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved/, 120_000)
    runN = (sc.match(/Proposed run (\S+?)\./) || [])[1]
    await s.cmd(`rewild approve ${runN}`, /approved|Not approved/, 300_000)
    await s.stop()
  }

  // A kill -9 from outside, in the middle of the startup step. Where it lands isn't controlled, so this only proves
  // that whatever state it leaves, the next start finishes or rolls back cleanly.
  await s.start({ wait: false })
  await s.waitFor(/Loading server plugin Rewild/, 120_000)
  await sleep(150)
  await s.kill9()
  await s.start()
  const ok = regionHas(sites.mined.cx, sites.mined.cz) === 'absent' || regionHas(sites.mined.cx, sites.mined.cz) === undoneHashBefore
  check('kill -9 during startup: the chunk is either reset or untouched, never broken', ok)
  const state = readFileSync(path.join(s.dataDir, 'runs', runN, 'run.properties'), 'utf8').match(/state=(\w+)/)[1]
  check('kill -9 during startup: the run ended applied', state === 'APPLIED', state)
  await s.stop()

  // ---- read-only world folder and a trash that can't be written ----
  console.log('\n## Failing safely')
  await s.start()
  await s.cmd(`rewild undo ${runN}`, /undone at the next restart|nothing to undo/)
  await s.stop()
  await s.start()
  const sc = await s.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved/, 120_000)
  const runRO = (sc.match(/Proposed run (\S+?)\./) || [])[1]
  await s.cmd(`rewild approve ${runRO}`, /approved|Not approved/, 300_000)
  await s.stop()
  const regionDir = path.join(s.dim, 'region')
  const hRO = hashTree(regionDir)
  chmodSync(regionDir, 0o555)
  await s.start({ jvm: ['-Drewild.test.haltAfterStartup=true'], wait: false })
  await s.ended()
  chmodSync(regionDir, 0o755)
  check('read-only world folder: the run failed and no region file changed', diffTrees(hRO, hashTree(regionDir)).length === 0 &&
    /state=FAILED/.test(readFileSync(path.join(s.dataDir, 'runs', runRO, 'run.properties'), 'utf8')))

  // GriefPrevention missing: no resets, and it says why.
  console.log('\n## Claims, config and tracker health')
  const gp = path.join(dir, 'plugins/GriefPrevention.jar')
  const gpAway = path.join(dir, 'GriefPrevention.jar.away')
  ;(await import('node:fs')).renameSync(gp, gpAway)
  await s.start()
  const noGp = await s.cmd('rewild scan world', /Nothing can be approved|Proposed run|Nothing to reset/, 120_000)
  check('claim plugin missing: scan refuses to propose and says why', /Nothing can be approved[^\n]*GriefPrevention isn't running/.test(noGp))
  await s.stop()
  ;(await import('node:fs')).renameSync(gpAway, gp)

  setConfig(dir, testConfig().replace('max-chunks: 200', 'max-chunks: lots'))
  await s.start()
  check('broken config: logged at startup', /config.yml: run.max-chunks must be a whole number/.test(s.log))
  const badCfg = await s.cmd('rewild scan world', /Nothing can be approved|Proposed run|Nothing to reset/, 120_000)
  check('broken config: scan refuses to propose', /Nothing can be approved[^\n]*config.yml/.test(badCfg))
  await s.stop()
  setConfig(dir, testConfig({ maxChunks: 2 }))

  // Cap
  await s.start()
  const capped = await s.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved/, 120_000)
  const capId = (capped.match(/Proposed run (\S+?)\./) || [])[1]
  const capChunks = capId ? readFileSync(path.join(s.dataDir, 'runs', capId, 'chunks.txt'), 'utf8').trim().split('\n').length : -1
  check('the cap holds: max-chunks 2 proposes 2', capChunks === 2, `${capChunks} chunks`)

  // Tracker unhealthy: its folder can't be written.
  const worldsDir = path.join(s.dataDir, 'worlds')
  const uid = readdirSync(worldsDir)[0]
  chmodSync(path.join(worldsDir, uid), 0o555)
  const b2 = await bot('tester')
  await s.cmd('gamemode creative tester')
  await tp(s, b2, sites.torch.x, sites.torch.y + 2, sites.torch.z)
  await digBox(b2, new Vec3(sites.torch.x, sites.torch.y + 1, sites.torch.z), { want: 3 })
  await sleep(4000)
  b2.quit()
  const sick = await s.cmd('rewild scan world', /Nothing can be approved|Proposed run|Nothing to reset/, 120_000)
  check('tracker can\'t save: scan refuses to propose and says why', /Nothing can be approved[^\n]*tracking data couldn't be saved/.test(sick), sick.slice(-200))
  chmodSync(path.join(worldsDir, uid), 0o755)
  await s.stop()

  // Generator fingerprint: add a datapack, and resets stop with a warning.
  mkdirSync(path.join(dir, 'world/datapacks/test-pack/data'), { recursive: true })
  writeFileSync(path.join(dir, 'world/datapacks/test-pack/pack.mcmeta'), JSON.stringify({ pack: { description: 'test', pack_format: 100, supported_formats: [1, 999] } }))
  await s.start()
  check('generator changed: a warning names the change and a sample of chunks', /the world generator changed \([^)]*datapack[^)]*\)\. Resets there are stopped.*Sample of affected chunks: -?\d+, -?\d+/.test(s.log),
    (s.log.match(/the world generator changed[^\n]*/) || [''])[0].slice(0, 220))
  const gen = await s.cmd('rewild scan world', /Nothing can be approved|Proposed run|Nothing to reset/, 120_000)
  check('generator changed: nothing can be approved', /Nothing can be approved[^\n]*generator changed/.test(gen))
  const app = await s.cmd(`rewild approve ${capId}`, /approved|Not approved/, 60_000)
  check('generator changed: an older proposed run can\'t be approved', /Not approved/.test(app))

  // Other plugins still work after runs.
  check('Multiverse-Core, Multiverse-Inventories and BlueMap enabled after the runs, without errors',
    /Enabling Multiverse-Core/.test(s.log) && /Enabling Multiverse-Inventories/.test(s.log) && /Enabling BlueMap/.test(s.log) &&
    !/Error occurred while enabling (Multiverse|BlueMap)/.test(s.log))
  const mv = await s.cmd('mv list', /world/)
  check('Multiverse lists the world after the runs', /world/.test(mv))
  const mvi = await s.cmd('mvinv list', /group|default|Groups/i)
  check('Multiverse-Inventories answers after the runs', /group|default/i.test(mvi), mvi.split('\n').slice(-3).join(' ').slice(0, 160))
  await s.stop()
}

// Done with the main server: delete it before making the next one.
s.remove()

// ---- fresh install on a played world ----
console.log('\n## Fresh install on a played world')
const played = freshServer(VERSION, { seed: SEED + 1, plugins: [...VIA] })
const ps = new Server(played)
await ps.start()
const b3 = await bot('tester')
await ps.cmd('gamemode creative tester')
const [px, pz] = await (async () => {
  const out = await ps.cmd('execute positioned 3000 0 3000 run locate biome minecraft:plains', /nearest|Could not find/, 120_000)
  const m = out.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
  return [(+m[1] >> 4) * 16 + 8, (+m[2] >> 4) * 16 + 8]
})()
await ps.cmd(`tp tester ${px} 220 ${pz}`); await sleep(3000)
const pt = await waitTop(b3, px, pz)
await tp(ps, b3, px, pt.position.y + 1, pz); b3.creative.startFlying()
const playedDug = await digBox(b3, new Vec3(px, pt.position.y + 1, pz), { want: 50 })
b3.quit()
await ps.stop()
const { copyFileSync } = await import('node:fs')
copyFileSync(JAR, path.join(played, 'plugins', path.basename(JAR)))
mkdirSync(path.join(played, 'plugins/Rewild'), { recursive: true })
writeFileSync(path.join(played, 'plugins/Rewild/config.yml'), testConfig({ claims: 'none' }))
await ps.start()
const pScan = await ps.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved/, 120_000)
check('fresh install on a played world: nothing is proposed', /Nothing to reset/.test(pScan), `dug ${playedDug} before install; ` + pScan.split('\n').filter(l => /Scanned|Nothing|Proposed/.test(l)).join(' ').slice(0, 200))
const pWhy = await ps.cmd(`rewild why world ${px >> 4} ${pz >> 4}`, /Chunk -?\d+, -?\d+: .*/)
check('fresh install on a played world: the mined chunk is unknown', /unknown/.test(pWhy))
await ps.stop()

console.log('\n## Upgrade from WildernessRegen')
{
  const { renameSync, readdirSync } = await import('node:fs')
  const now = path.join(played, 'plugins/Rewild'), old = path.join(played, 'plugins/WildernessRegen')
  const before = readdirSync(now).sort().join(',')
  renameSync(now, old)
  await ps.start()
  check('plugins/WildernessRegen is moved to plugins/Rewild on first start', existsSync(path.join(now, 'config.yml')) && !existsSync(old) && readdirSync(now).sort().join(',') === before, readdirSync(now).sort().join(','))
  check('the log says what was moved', /Moved plugins\/WildernessRegen to plugins\/Rewild/.test(ps.log))
  const oldWhy = await ps.cmd(`wr why world ${px >> 4} ${pz >> 4}`, /Chunk -?\d+, -?\d+: .*/)
  check('the old command /wr still works for one release', /unknown/.test(oldWhy))
  check('and /wr logs that it\'s the old name', /old names for \/rewild/.test(ps.log))
  await ps.stop()
}
ps.remove()

process.exitCode = report(path.join(REPO, `build/acceptance-${VERSION}.json`)) ? 1 : 0
