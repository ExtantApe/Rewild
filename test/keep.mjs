// The KeepProvider interface on a live server, with a stand-in plugin named LanternGraves (test/fixtures/keepstub).
//   test/fixtures/keepstub/build.sh && node keep.mjs 26.3
import path from 'node:path'
import { readdirSync } from 'node:fs'
import { freshServer, Server, bot, tp, digBox, waitTop, Vec3, CACHE, REPO, sleep, check, report, inspect } from './harness.mjs'
import { testConfig } from './configs.mjs'

const VERSION = process.argv[2] || '26.3'
const P = n => path.join(CACHE, 'p26', n)
const dir = freshServer(VERSION, {
  plugins: [path.join(REPO, 'build/libs', readdirSync(path.join(REPO, 'build/libs')).find(f => /^Rewild-[\d.]+\.jar$/.test(f))),
    path.join(REPO, 'build/keepstub.jar'), P('ViaVersion-5.12.2.jar'), P('ViaBackwards-5.12.2.jar')],
  config: testConfig({ claims: 'none' }),
})
const s = new Server(dir)
console.log(`# KeepProvider on Paper ${VERSION}`)
await s.start({ jvm: ['-Dkeepstub.chunk=99999,99999'] })
// Respect the stand-in graves plugin; ignore the rest.
await s.cmd('rewild plugins respect LanternGraves', /Respected:/)
await s.cmd('rewild plugins ignore-rest', /Ignored:|Usage/)
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
check('control: with the grave elsewhere, the mined chunk would rewild', /would rewild/.test(why0))
await s.stop()

await s.start({ jvm: [`-Dkeepstub.chunk=${CX},${CZ}`] })
await sleep(1000)
const why1 = await s.cmd(`rewild why world ${CX} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
await sleep(500)
const tail1 = s.log.slice(s.log.lastIndexOf(`Chunk ${CX}, ${CZ}:`)).split('\n').slice(0, 3).join(' | ')
check('a chunk a respected plugin reports is kept whole, and /rewild why says which plugin', /kept/.test(why1) && /an active grave \(LanternGraves\)/.test(tail1), tail1)
const why2 = await s.cmd(`rewild why world ${CX + 1} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
check('its neighbour is kept by the buffer', /kept/.test(why2))
await s.stop()

// The same plugin, ignored: what it reports no longer counts.
const { writeFileSync } = await import('node:fs')
const answers = path.join(dir, 'plugins/Rewild/keep-plugins.yml')
const respectedFile = (await import('node:fs')).readFileSync(answers, 'utf8')
writeFileSync(answers, respectedFile.replace(/keep-plugins:\n- LanternGraves\n/, 'keep-plugins: []\n').replace(/ignore-plugins:\n/, 'ignore-plugins:\n- LanternGraves\n'))
await s.start({ jvm: [`-Dkeepstub.chunk=${CX},${CZ}`] })
const whyIgnored = await s.cmd(`rewild why world ${CX} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
check('an ignored plugin\'s keeps don\'t count', /would rewild/.test(whyIgnored), whyIgnored.split('\n').find(l => /Chunk/.test(l)))
await s.stop()
writeFileSync(answers, respectedFile)

// A block area from the respected plugin: those blocks stay as they are, and the rest of the chunk rewilds.
// The bot dug the ground layer around the middle column; the box holds 8 dug blocks and the undug middle.
const AREA = [X - 1, t.position.y, Z - 1, X + 1, t.position.y, Z + 1]
await s.start({ jvm: [`-Dkeepstub.area=${AREA.join(',')}`] })
const whyArea = await s.cmd(`rewild why world ${CX} ${CZ}`, /Chunk -?\d+, -?\d+: .*/)
await sleep(500)
const tailA = s.log.slice(s.log.lastIndexOf(`Chunk ${CX}, ${CZ}:`)).split('\n').slice(0, 4).join(' | ')
check('a reported block area doesn\'t hold the chunk back; /rewild why counts it', /would rewild/.test(whyArea) && /1 kept area/.test(tailA), tailA.slice(0, 240))
const scan = await s.cmd('rewild scan world', /Proposed run (\S+?)\.|Nothing/, 120_000)
const run = (scan.match(/Proposed run (\S+?)\./) || [])[1]
await s.cmd(`rewild approve ${run}`, /approved|Not approved/, 300_000)
await s.stop()
await s.start({ jvm: [`-Dkeepstub.area=${AREA.join(',')}`] })
check('the run applied', /Run \S+ \(world\) applied/.test(s.log), (s.log.match(/Run \S+ \(world\)[^\n]*/) || [''])[0].slice(0, 200))
await s.stop()
const ring = [], outside = []
for (let x = AREA[0]; x <= AREA[3]; x++) for (let z = AREA[2]; z <= AREA[5]; z++) if (x !== X || z !== Z) ring.push(inspect('block', s.dim, x, AREA[1], z))
for (const [dx, dz] of [[2, 0], [0, 2], [-2, 0], [0, -2]]) outside.push(inspect('block', s.dim, X + dx, AREA[1], Z + dz))
check('the plugin\'s block area is still dug out: no fresh ground filled it', ring.every(n => n === 'minecraft:air' || n === 'minecraft:water'), ring.join(','))
check('the dug ground just outside it came back as fresh terrain', outside.every(n => n !== 'minecraft:air'), outside.join(','))

await s.start({ jvm: ['-Dkeepstub.silent=true'] })
const silent = await s.cmd('rewild scan world', /Nothing can be approved|Proposed run|Nothing to reset/, 120_000)
check('LanternGraves running without reporting stops every reset, and says why', /Nothing can be approved[^\n]*LanternGraves is running but doesn't report/.test(silent), silent.split('\n').filter(l => /Nothing|Proposed/.test(l)).join(' ').slice(0, 200))
await s.stop()
s.remove()
process.exitCode = report(path.join(REPO, `build/keep-${VERSION}.json`)) ? 1 : 0
