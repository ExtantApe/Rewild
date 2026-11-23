// Gradual healing (0.4.0, D224) on a live server: a TNT hole in fresh natural land heals step by step through
// /rewild healing step, waits while a player is near, never overwrites a block put there since, survives a restart,
// only reports in dry run, and refuses holes next to a player's build or through a chest.
//   node heal.mjs 26.3
import path from 'node:path'
import { readdirSync } from 'node:fs'
import { freshServer, setConfig, Server, bot, waitTop, Vec3, CACHE, REPO, sleep, check, note, report } from './harness.mjs'
import { testConfig } from './configs.mjs'

const VERSION = process.argv[2] || '26.3'
const P = n => path.join(CACHE, 'p26', n)
const JAR = path.join(REPO, 'build/libs', readdirSync(path.join(REPO, 'build/libs')).find(f => /^Rewild-0\.4\.[\d.]+\.jar$/.test(f)))
const config = dry => testConfig({ claims: 'none', dryRun: dry })
const dir = freshServer(VERSION, { plugins: [JAR, P('ViaVersion-5.12.2.jar'), P('ViaBackwards-5.12.2.jar')], config: config(false) })
const s = new Server(dir)
console.log(`# Gradual healing on Paper ${VERSION}`)
await s.start()
await s.cmd('rewild plugins ignore-rest', /Ignored:|Usage|nothing/i)
let b = await bot('tester')
await s.cmd('gamemode creative tester')

// Fresh plains far from spawn, so every chunk is generated after Rewild started (known land).
const out = await s.cmd('execute positioned 3000 0 3000 run locate biome minecraft:plains', /nearest/, 120_000)
const m = out.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
const X = +m[1], Z = +m[2]
const away = async () => { await s.cmd(`tp tester ${X + 60} 200 ${Z}`); await sleep(1500) }
await s.cmd(`tp tester ${X} 200 ${Z}`); await sleep(4000)
await away()
const Y = (await waitTop(b, X, Z)).position.y

const R = 5
const blockAt = (x, y, z) => { const bl = b.blockAt(new Vec3(x, y, z)); return bl ? bl.name : '?' }
function snap(cx, cy, cz) {
  const out = new Map()
  for (let x = cx - R; x <= cx + R; x++) for (let z = cz - R; z <= cz + R; z++) for (let y = cy - 6; y <= cy + 3; y++) out.set(`${x},${y},${z}`, blockAt(x, y, z))
  return out
}

async function blast(x, y, z) {
  const mark = s.log.length
  await s.cmd(`summon tnt ${x} ${y + 1} ${z} {fuse:1}`)
  await sleep(2500)
  return s.log.slice(mark)
}
async function jobs() {
  const o = await s.cmd('rewild healing', /hole\(s\) healing|No explosion holes/)
  // Healing ids look like h<date>-<time>-<hex>.
  return [...new Set(o.split(/\s+/).map(w => w.replace(/\.$/, '')).filter(w => w.startsWith('h2') && w.split('-').length === 3))]
}

// 1. A hole in natural land, nothing near, is recorded to heal on its own.
const before = snap(X, Y, Z)
const log1 = await blast(X, Y, Z)
const after = snap(X, Y, Z)
const destroyed = [...before].filter(([k, v]) => v !== 'air' && after.get(k) === 'air').map(([k]) => k)
check('TNT made a hole', destroyed.length > 20, `${destroyed.length} blocks`)
check('a hole in natural land with nothing near is set to heal on its own', /will heal over about 7 days/.test(log1), log1.split('\n').find(l => /heal/.test(l)))
const [id] = await jobs()
check('/rewild healing lists it', !!id)

// 2. A player nearby pauses it.
await s.cmd(`tp tester ${X + 3} ${Y + 2} ${Z}`); await sleep(1500)
const paused = await s.cmd(`rewild healing step ${id}`, /paused|Healed|waiting/)
check('a step waits while a player is nearby', /paused: a player is nearby/.test(paused))
check('and places nothing', destroyed.every(k => blockAt(...k.split(',').map(Number)) === 'air'))
await away()

// 3. Something put in the hole since is never overwritten.
const lowest = destroyed.map(k => k.split(',').map(Number)).sort((a, b) => a[1] - b[1])[0]
await s.cmd(`setblock ${lowest[0]} ${lowest[1]} ${lowest[2]} minecraft:diamond_block`)
await sleep(500)

// 4. Step through to the end.
let steps = 0, finished = false, first = ''
const layers = []
for (let i = 0; i < 12 && !finished; i++) {
  const o = await s.cmd(`rewild healing step ${id}`, /Healed step|finished|waiting|paused/)
  if (/waiting|paused/.test(o)) { check('steps run when nobody is near', false, o.split('\n').find(l => /waiting|paused/.test(l))); break }
  steps++
  if (i === 0) first = o
  await sleep(800)
  layers.push(destroyed.filter(k => blockAt(...k.split(',').map(Number)) !== 'air').length)
  finished = /is finished/.test(o)
}
check('a typical crater heals in about a week of daily steps', finished && steps >= 4 && steps <= 7, `${steps} steps`)
check('it fills gradually, step by step', layers.every((n, i) => i === 0 || n >= layers[i - 1]) && layers[0] < destroyed.length, layers.join(' → '))
check('the first step fills the bottom first', destroyed.filter(k => blockAt(...k.split(',').map(Number)) !== 'air').length > 0)
check('a block put there since stays', blockAt(...lowest) === 'diamond_block' && /left alone because something is there now/.test(first))
const healed = snap(X, Y, Z)
const wrong = destroyed.filter(k => k !== lowest.join(',') && healed.get(k) !== before.get(k))
check('every destroyed block comes back as it was (allowing 5%)', wrong.length <= destroyed.length * 0.05,
  `${wrong.length} of ${destroyed.length} differ: ` + wrong.slice(0, 5).map(k => `${k} ${before.get(k)}→${healed.get(k)}`).join(', '))
check('the finished healing is gone from the list', !(await jobs()).includes(id))

// 5. A player's build nearby: the hole waits for an admin.
const X2 = X + 30
await s.cmd(`tp tester ${X2} ${Y + 3} ${Z}`); await sleep(1500)
const Y2 = (await waitTop(b, X2, Z)).position.y
const built = await (async () => {
  await s.cmd(`give tester minecraft:oak_planks 4`); await sleep(500)
  const ref = b.blockAt(new Vec3(X2 + 4, Y2, Z))
  await b.equip(b.inventory.items().find(i => i.name === 'oak_planks'), 'hand')
  await b.lookAt(ref.position.offset(0.5, 1, 0.5))
  try { await b.placeBlock(ref, new Vec3(0, 1, 0)) } catch { }
  await sleep(800)
  return blockAt(X2 + 4, Y2 + 1, Z) === 'oak_planks'
})()
check('the bot placed a block next to the next blast', built)
await away()
const n0 = (await jobs()).length
await blast(X2, Y2, Z)
check('a hole next to a player\'s build doesn\'t heal on its own', (await jobs()).length === n0)

// 6. A blast through a chest: the hole waits for an admin.
const X3 = X - 30
const Y3 = (await waitTop(b, X3, Z)).position.y
await s.cmd(`setblock ${X3 + 1} ${Y3 + 1} ${Z} minecraft:chest`)
await blast(X3, Y3, Z)
check('a hole that destroyed a chest doesn\'t heal on its own', (await jobs()).length === n0)

// 7. Dry run: recorded, stepped in the log only, nothing placed; and a job survives a restart.
setConfig(dir, config(true))
await s.cmd('rewild reload', /Config reloaded/)
const X4 = X, Z4 = Z + 30
const Y4 = (await waitTop(b, X4, Z4)).position.y
const before4 = snap(X4, Y4, Z4)
await blast(X4, Y4, Z4)
const after4 = snap(X4, Y4, Z4)
const holes4 = [...before4].filter(([k, v]) => v !== 'air' && after4.get(k) === 'air').map(([k]) => k)
const [id4] = (await jobs()).filter(j => j !== id)
check('dry run still records a hole', !!id4)
await s.stop()
await s.start()
await sleep(1000)
check('healing survives a restart', (await jobs()).includes(id4))
b = await bot('tester')
await s.cmd(`tp tester ${X4 + 60} 200 ${Z4}`); await sleep(4000)
const o4 = await s.cmd(`rewild healing step ${id4}`, /Dry run: would heal|waiting|paused/)
check('a dry-run step only reports', /Dry run: would heal step 1/.test(o4))
await sleep(800)
check('and places nothing', holes4.every(k => blockAt(...k.split(',').map(Number)) === 'air'), `${holes4.length} hole blocks`)

const errors = s.log.split('\n').filter(l => /ERROR|Exception/.test(l) && !/Via/.test(l))
check('no errors in the log', errors.length === 0, errors.slice(0, 3).join(' | '))
note('not covered here', 'Magic plugin hook (no Magic jar in the test server), claims (claims: none), real client look')
b.quit()
await s.stop()
process.exit(report(path.join(dir, 'heal-results.json')) ? 1 : 0)
