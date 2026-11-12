// Rewild with the real LanternGraves release on a live server: its graves keep their chunks, and its older provider
// (which refuses the block-areas call added in 0.3.0) doesn't stop resets.
//   node lanterngraves.mjs 26.3 /path/to/LanternGraves-0.1.2.jar
import path from 'node:path'
import { readdirSync } from 'node:fs'
import { freshServer, Server, bot, tp, digBox, waitTop, Vec3, CACHE, REPO, sleep, check, report } from './harness.mjs'
import { testConfig } from './configs.mjs'

const VERSION = process.argv[2] || '26.3'
const LG = process.argv[3]
const P = n => path.join(CACHE, 'p26', n)
const JAR = path.join(REPO, 'build/libs', readdirSync(path.join(REPO, 'build/libs')).find(f => /^Rewild-[\d.]+\.jar$/.test(f)))
const dir = freshServer(VERSION, { plugins: [JAR, LG, P('ViaVersion-5.12.2.jar'), P('ViaBackwards-5.12.2.jar')], config: testConfig({ claims: 'none' }) })
const s = new Server(dir)
console.log(`# Rewild with ${path.basename(LG)} on Paper ${VERSION}`)
await s.start()
check('LanternGraves told Rewild about its graves', /Telling Rewild which chunks hold graves/.test(s.log))
await s.cmd('rewild plugins respect LanternGraves', /Respected:/)
await s.cmd('rewild plugins ignore-rest', /Ignored:|Usage/)
const b = await bot('tester')
await s.cmd('gamemode creative tester')

async function minedSite(x0, z0) {
  const out = await s.cmd(`execute positioned ${x0} 0 ${z0} run locate biome minecraft:plains`, /nearest/, 120_000)
  const m = out.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
  const X = (+m[1] >> 4) * 16 + 8, Z = (+m[2] >> 4) * 16 + 8
  await s.cmd(`tp tester ${X} 220 ${Z}`); await sleep(3000)
  const t = await waitTop(b, X, Z)
  await tp(s, b, X, t.position.y + 1, Z); b.creative.startFlying()
  await digBox(b, new Vec3(X, t.position.y + 1, Z), { want: 50 })
  return { X, Z, Y: t.position.y, CX: X >> 4, CZ: Z >> 4 }
}
// A mined site with no grave, then a grave four chunks away. A grave keeps its chunk whether or not it's mined.
const plain = await minedSite(3000, 3000)
await s.cmd(`tp tester ${plain.X + 64} 220 ${plain.Z}`); await sleep(3000)
const gt = await waitTop(b, plain.X + 64, plain.Z)
await tp(s, b, plain.X + 64, gt.position.y + 1, plain.Z)
const grave = { X: plain.X + 64, Z: plain.Z, CX: (plain.X + 64) >> 4, CZ: plain.Z >> 4 }
// Die there with something in the inventory, so LanternGraves makes a grave.
b.creative.stopFlying()
await s.cmd('gamemode survival tester')
await s.cmd('give tester minecraft:oak_log 5')
await sleep(500)
await s.cmd('kill tester')
await sleep(3000)
const list = await s.cmd('lanterngraves list tester', /grave|Grave|No /i).catch(() => '')
b.respawn?.()
await sleep(2000)
b.quit()
await s.cmd('save-all flush', /Saved the game/, 120_000)

const w1 = await s.cmd(`rewild why world ${grave.CX} ${grave.CZ}`, /Chunk -?\d+, -?\d+: .*/)
await sleep(500)
const tail = s.log.slice(s.log.lastIndexOf(`Chunk ${grave.CX}, ${grave.CZ}:`)).split('\n').slice(0, 4).join(' | ')
check('the chunk with a grave is kept, and /rewild why names LanternGraves', /: kept/.test(w1) && /a grave \(LanternGraves\)/.test(tail), tail.slice(0, 240) + ' / ' + list.split('\n').slice(-2).join(' ').slice(0, 120))
const w2 = await s.cmd(`rewild why world ${plain.CX} ${plain.CZ}`, /Chunk -?\d+, -?\d+: .*/)
check('a mined chunk with no grave would rewild', /would rewild/.test(w2), w2.split('\n').find(l => /Chunk/.test(l)))
const scan = await s.cmd('rewild scan world', /Proposed run|Nothing to reset|Nothing can be approved/, 120_000)
check('a scan proposes a run: the older provider\'s refusal of the areas call doesn\'t stop resets', /Proposed run/.test(scan) && !/couldn't say what to keep/.test(scan),
  scan.split('\n').filter(l => /Proposed|Nothing/.test(l)).join(' ').slice(0, 220))
await s.stop()
s.remove()
process.exitCode = report(path.join(REPO, `build/lanterngraves-${VERSION}.json`)) ? 1 : 0
