// The KeepProvider interface on a live server, with a stand-in plugin named LanternGraves (test/fixtures/keepstub).
//   node keep.mjs 26.3
import path from 'node:path'
import { freshServer, Server, bot, tp, digBox, waitTop, Vec3, CACHE, REPO, sleep, check, report } from './harness.mjs'
import { testConfig } from './configs.mjs'

const VERSION = process.argv[2] || '26.3'
const P = n => path.join(CACHE, 'p26', n)
const dir = freshServer(VERSION, {
  plugins: [path.join(REPO, 'build/libs/Rewild-0.2.0.jar'), path.join(REPO, 'build/keepstub.jar'), P('ViaVersion-5.12.2.jar'), P('ViaBackwards-5.12.2.jar')],
  config: testConfig({ claims: 'none' }),
})
const s = new Server(dir)
console.log(`# KeepProvider on Paper ${VERSION}`)
await s.start({ jvm: ['-Dkeepstub.chunk=99999,99999'] })
const b = await bot('tester')
await s.cmd('gamemode creative tester')
const out = await s.cmd('execute positioned 3000 0 3000 run locate biome minecraft:plains', /nearest/, 120_000)
const m = out.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/)
const X = (+m[1] >> 4) * 16 + 8, Z = (+m[2] >> 4) * 16 + 8, CX = X >> 4, CZ = Z >> 4
await s.cmd(`tp tester ${X} 220 ${Z}`); await sleep(3000)
const t = await waitTop(b, X, Z)
await tp(s, b, X, t.position.y + 1, Z); b.creative.startFlying()
await digBox(b, new Vec3(X, t.position.y + 1, Z), { want: 50 })
await s.cmd(`tp tester ${X + 2000} 220 ${Z}`); await sleep(2000)
b.quit()
await s.cmd('save-all flush', /Saved the game/, 120_000)
const why0 = await s.cmd(`rewild why world ${CX} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
check('control: with the grave elsewhere, the mined chunk would reset', /would reset/.test(why0))
await s.stop()

await s.start({ jvm: [`-Dkeepstub.chunk=${CX},${CZ}`] })
await sleep(1000)
const why1 = await s.cmd(`rewild why world ${CX} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
await sleep(500)
const tail1 = s.log.slice(s.log.lastIndexOf(`Chunk ${CX}, ${CZ}:`)).split('\n').slice(0, 3).join(' | ')
check('a chunk another plugin reports is kept, and /wr why says which plugin', /kept/.test(why1) && /an active grave \(LanternGraves\)/.test(tail1), tail1)
const why2 = await s.cmd(`rewild why world ${CX + 1} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
check('its neighbour is kept by the buffer', /kept/.test(why2))
await s.stop()

await s.start({ jvm: ['-Dkeepstub.silent=true'] })
const silent = await s.cmd('rewild scan world', /Nothing can be approved|Proposed run|Nothing to reset/, 120_000)
check('LanternGraves running without reporting stops every reset, and says why', /Nothing can be approved[^\n]*LanternGraves is running but doesn't report/.test(silent), silent.split('\n').filter(l => /Nothing|Proposed/.test(l)).join(' ').slice(0, 200))
await s.stop()
s.remove()
process.exitCode = report(path.join(REPO, `build/keep-${VERSION}.json`)) ? 1 : 0
