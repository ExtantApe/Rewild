// What a regenerated chunk looks like next to a fresh one: generate, remove one chunk the way a reset does,
// regenerate it, and list every block that differs from a fresh copy of the seed.
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { cpSync, rmSync } from 'node:fs'
import { freshServer, Server, inspect, sleep, REPO, JAVA, HERE } from './harness.mjs'
const VERSION = process.argv[2] || '26.3'
const X = 3000, Z = 3000, CX = X >> 4, CZ = Z >> 4
const load = async s => { for (const dx of [-32, 0, 32]) for (const dz of [-32, 0, 32]) await s.cmd(`forceload add ${X + dx} ${Z + dz}`); await sleep(6000); await s.cmd('save-all flush', /Saved the game/, 120000); await s.cmd('forceload remove all') }
const f = new Server(freshServer(VERSION)); await f.start(); await load(f); await f.stop()
const freshDim = path.join(REPO, 'build/seam-fresh'); rmSync(freshDim, { recursive: true, force: true })
cpSync(path.join(f.dim, 'region'), path.join(freshDim, 'region'), { recursive: true }); f.remove()
const s = new Server(freshServer(VERSION)); await s.start(); await load(s); await s.stop()
console.log(inspect('remove', s.dim, CX, CZ))
await s.start(); await s.cmd(`forceload add ${X} ${Z}`); await sleep(5000); await s.cmd('save-all flush', /Saved the game/, 120000); await s.cmd('forceload remove all'); await s.stop()
for (const [label, dx] of [['regenerated', 0], ['control', 2]]) {
  console.log(label + ': ' + execFileSync(JAVA, ['-Ddiffs=25', '-cp', path.join(REPO, 'build/classes/java/main'), path.join(HERE, 'tools/Inspect.java'), 'compare', s.dim, freshDim, CX + dx, CZ]).toString().trim())
}
s.remove()
