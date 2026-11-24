// The Magic hook (0.4.0, D224) against the real Magic plugin, on a throwaway server: in a world listed in
// magic.worlds, a TNT hole heals while Magic is running and answers; a spell's blast is left to Magic's own undo; and
// with Magic gone, nothing in that world heals (fail closed). Needs the Magic jar: MAGIC_JAR=/path/to/Magic-11.3.jar
//   MAGIC_JAR=... node magic.mjs 26.3
import path from 'node:path'
import { readdirSync, existsSync, rmSync } from 'node:fs'
import { freshServer, Server, bot, waitTop, Vec3, CACHE, REPO, sleep, check, note, report } from './harness.mjs'
import { testConfig } from './configs.mjs'

const VERSION = process.argv[2] || '26.3'
const MAGIC = process.env.MAGIC_JAR
if (!MAGIC || !existsSync(MAGIC)) { console.log('Set MAGIC_JAR to the Magic plugin jar.'); process.exit(2) }
const P = n => path.join(CACHE, 'p26', n)
const JAR = path.join(REPO, 'build/libs', readdirSync(path.join(REPO, 'build/libs')).find(f => /^Rewild-0\.4\.[\d.]+\.jar$/.test(f)))
const config = testConfig({ claims: 'none', dryRun: false }).replace(/^magic:\n  worlds: \[\]$/m, 'magic:\n  worlds: [world]')
if (!/^  worlds: \[world\]$/m.test(config)) { console.log('could not set magic.worlds in the test config'); process.exit(2) }
const dir = freshServer(VERSION, { plugins: [JAR, MAGIC, P('ViaVersion-5.12.2.jar'), P('ViaBackwards-5.12.2.jar')], config })
const s = new Server(dir)
console.log(`# Rewild's Magic hook with the real Magic plugin on Paper ${VERSION}`)
await s.start()
check('Magic is running', /Enabling Magic v/.test(s.log))
await s.cmd('rewild plugins ignore-rest', /Ignored:|Usage|nothing/i)
let b = await bot('tester')
await s.cmd('gamemode creative tester')

const out = await s.cmd('execute positioned 3000 0 3000 run locate biome minecraft:plains', /nearest/, 120_000)
const m = out.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
const X = +m[1], Z = +m[2]
const away = async (x, z) => { await s.cmd(`tp tester ${x + 60} 200 ${z}`); await sleep(1500) }
const blockAt = (x, y, z) => { const bl = b.blockAt(new Vec3(x, y, z)); return bl ? bl.name : '?' }
const airCount = (x, y, z) => { let n = 0; for (let dx = -3; dx <= 3; dx++) for (let dz = -3; dz <= 3; dz++) for (let dy = -3; dy <= 1; dy++) if (blockAt(x + dx, y + dy, z + dz) === 'air') n++; return n }
async function ground(x, z) { await s.cmd(`tp tester ${x} 200 ${z}`); await sleep(3000); const y = (await waitTop(b, x, z)).position.y; await away(x, z); return y }
async function jobs() {
  const o = await s.cmd('rewild healing', /hole\(s\) healing|No explosion holes/)
  return [...new Set(o.split(/\s+/).map(w => w.replace(/\.$/, '')).filter(w => w.startsWith('h2') && w.split('-').length === 3))]
}
async function tnt(x, y, z) { await s.cmd(`summon tnt ${x} ${y + 1} ${z} {fuse:1}`); await sleep(2500) }

// 1. Magic running and answering: a plain TNT hole in a Magic world heals as usual.
const Y = await ground(X, Z)
const n0 = (await jobs()).length
await tnt(X, Y, Z)
check('with Magic running, a TNT hole in a Magic world is set to heal', (await jobs()).length === n0 + 1)

// 2. A spell's blast: Magic will put it back, so Rewild leaves it alone. The spell is cast by console at a spot.
const X2 = X + 40
const Y2 = await ground(X2, Z)
await s.cmd(`tp tester ${X2} ${Y2 + 1} ${Z - 12}`); await sleep(1500)
await b.lookAt(new Vec3(X2 + 0.5, Y2, Z + 0.5)); await sleep(500)
const before2 = (await jobs()).length
const airBefore = airCount(X2, Y2, Z)
let cast = ''
for (const spell of ['fireball', 'boom', 'grenade']) {
  cast = await s.cmd(`castp tester ${spell}`, /cast|Cast|Unknown|not|No spell|Usage/i, 8000).catch(() => '')
  await sleep(3000)
  if (airCount(X2, Y2, Z) > airBefore + 4) break
}
const blasted = airCount(X2, Y2, Z) > airBefore + 4
if (!blasted) note('a spell made no hole where the test looked', (cast || '').split('\n').slice(-2).join(' | '))
else check('a spell\'s blast is left to Magic (no healing recorded)', (await jobs()).length === before2)

// 3. Magic gone: the world is listed in magic.worlds, so nothing there heals.
await b.quit(); await sleep(500)
await s.stop()
rmSync(path.join(dir, 'plugins', path.basename(MAGIC)), { force: true })
await s.start()
check('Magic is not running now', !/Enabling Magic v/.test(s.log))
b = await bot('tester')
await s.cmd('gamemode creative tester')
const X3 = X - 40
const Y3 = await ground(X3, Z)
const before3 = (await jobs()).length
await tnt(X3, Y3, Z)
check('without Magic, a TNT hole in a Magic world doesn\'t heal (fail closed)', (await jobs()).length === before3)
const scan = await s.cmd('rewild scan world', /Magic|scan|chunk|nothing|waiting/i, 30_000).catch(() => '')
note('rewild scan without Magic', scan.split('\n').filter(l => /Magic/.test(l)).slice(0, 2).join(' | ') || '(no Magic line)')

await b.quit(); await sleep(500)
await s.stop()
report('magic')
