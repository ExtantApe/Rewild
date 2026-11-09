// Test harness: throwaway Paper servers in a temp folder on 127.0.0.1:25616, driven through the console, with
// mineflayer bots as players. It only ever creates its own fresh folders, and refuses any other path.
import { spawn, execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, existsSync, readFileSync, readdirSync, cpSync, statSync, rmSync } from 'node:fs'
import { createHash } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import mineflayer from 'mineflayer'
import { Vec3 } from 'vec3'

export const HERE = path.dirname(fileURLToPath(import.meta.url))
export const REPO = path.resolve(HERE, '..')
export const CACHE = path.join(os.homedir(), '.cache/rewild-test')
export const PORT = 25616
export const JAVA = process.env.WR_JAVA || '/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home/bin/java'
export const sleep = ms => new Promise(r => setTimeout(r, ms))

const results = []
export function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
  return ok
}
export function note(name, detail) {
  results.push({ name, ok: null, detail })
  console.log(`NOTE  ${name}  (${detail})`)
}
export function report(file) {
  writeFileSync(file, JSON.stringify(results, null, 2))
  const failed = results.filter(r => r.ok === false)
  console.log(`\n${results.filter(r => r.ok).length} passed, ${failed.length} failed`)
  for (const f of failed) console.log('  FAILED: ' + f.name + (f.detail ? ' (' + f.detail + ')' : ''))
  return failed.length
}

/** A fresh server folder. Paper's downloaded libraries are cloned from a primed folder to save time. */
export function freshServer(version, { seed = 424242, plugins = [], config = null, props = {} } = {}) {
  const dir = mkdtempSync(path.join(os.tmpdir(), `rewild-test-${version}-`))
  copyFileSync(path.join(CACHE, `paper-${version}.jar`), path.join(dir, 'paper.jar'))
  const prime = path.join(CACHE, `prime-${version}`)
  for (const f of ['libraries', 'versions', 'cache']) {
    if (existsSync(path.join(prime, f))) execFileSync('cp', ['-cR', path.join(prime, f), path.join(dir, f)])
  }
  // The owner accepted the Minecraft EULA for these throwaway test servers on 2026-11-07.
  writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n')
  const p = {
    'server-ip': '127.0.0.1', 'server-port': PORT, 'level-seed': seed, 'online-mode': false, 'spawn-protection': 0,
    'view-distance': 6, 'simulation-distance': 4, 'max-players': 10, 'enable-command-block': false,
    'pause-when-empty-seconds': -1, 'white-list': false, 'enforce-whitelist': false, 'motd': 'Rewild test', ...props,
  }
  writeFileSync(path.join(dir, 'server.properties'), Object.entries(p).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
  mkdirSync(path.join(dir, 'plugins/Rewild'), { recursive: true })
  for (const j of plugins) copyFileSync(j, path.join(dir, 'plugins', path.basename(j)))
  if (config) writeFileSync(path.join(dir, 'plugins/Rewild/config.yml'), config)
  return dir
}

export function setConfig(dir, config) {
  writeFileSync(path.join(dir, 'plugins/Rewild/config.yml'), config)
}

export class Server {
  constructor(dir) {
    if (!dir.startsWith(os.tmpdir()) && !dir.startsWith('/private/tmp') && !dir.startsWith('/tmp')) throw new Error('refusing non-temp folder ' + dir)
    this.dir = dir
    this.log = ''
    this.proc = null
  }

  /** Starts the server and waits for it to finish loading. Extra JVM properties go in jvm. */
  async start({ jvm = [], wait = true, timeout = 300_000 } = {}) {
    this.log = ''
    this.exit = new Promise(resolve => {
      this.proc = spawn(JAVA, ['-Xms1G', '-Xmx3G', '-Dterminal.jline=false', '-Dterminal.ansi=false', ...jvm, '-jar', 'paper.jar', 'nogui'],
        { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
      const add = d => { this.log += d.toString() }
      this.proc.stdout.on('data', add)
      this.proc.stderr.on('data', add)
      this.proc.on('exit', code => { this.code = code; resolve(code) })
    })
    // Never leave a test server running if the test script dies.
    const proc = this.proc
    process.once('exit', () => { try { proc.kill('SIGKILL') } catch {} })
    if (wait) await this.waitFor(/Done \(/, timeout)
  }

  async waitFor(re, timeout = 60_000, from = 0) {
    const t0 = Date.now()
    while (Date.now() - t0 < timeout) {
      const m = this.log.slice(from).match(re)
      if (m) return m
      if (this.code !== undefined && this.proc.exitCode !== null) throw new Error(`server exited (${this.code}) waiting for ${re}`)
      await sleep(100)
    }
    throw new Error(`timed out waiting for ${re}\n--- last log ---\n${this.log.slice(-3000)}`)
  }

  /** Sends a console command and returns the log written in the next moment. */
  async cmd(line, waitRe = null, timeout = 60_000) {
    const from = this.log.length
    this.proc.stdin.write(line + '\n')
    if (waitRe) {
      await this.waitFor(waitRe, timeout, from)
      await sleep(200)
    } else {
      await sleep(1500)
    }
    return this.log.slice(from)
  }

  async stop() {
    if (!this.proc || this.proc.exitCode !== null) return this.code
    this.proc.stdin.write('stop\n')
    const t = setTimeout(() => this.proc.kill('SIGKILL'), 180_000)
    const code = await this.exit
    clearTimeout(t)
    return code
  }

  async kill9() {
    this.proc.kill('SIGKILL')
    return this.exit
  }

  /** Waits for the process to end on its own (for the halt hooks). */
  async ended(timeout = 300_000) {
    const t = setTimeout(() => this.proc.kill('SIGKILL'), timeout)
    const code = await this.exit
    clearTimeout(t)
    return code
  }

  /** Deletes this server's folder. Only ever a folder this harness made. */
  remove() {
    if (this.proc && this.proc.exitCode === null) throw new Error('stop the server before removing it')
    if (!path.basename(this.dir).startsWith('rewild-test-')) throw new Error('refusing to delete ' + this.dir)
    rmSync(this.dir, { recursive: true, force: true })
  }

  get dim() { return dimension(this.dir, 'overworld') }
  get dataDir() { return path.join(this.dir, 'plugins/Rewild') }
}

/** Where a vanilla dimension's region files live, whichever layout the version uses. */
export function dimension(dir, which) {
  return path.join(dir, 'world/dimensions/minecraft', which)
}

export function inspect(...args) {
  return execFileSync(JAVA, ['-cp', path.join(REPO, 'build/classes/java/main'), path.join(HERE, 'tools/Inspect.java'), ...args.map(String)]).toString().trim()
}

/** Hashes every file under a folder, for "nothing changed" checks. */
export function hashTree(root) {
  const out = {}
  const walk = d => {
    for (const f of readdirSync(d)) {
      const p = path.join(d, f)
      const s = statSync(p)
      if (s.isDirectory()) walk(p)
      else out[path.relative(root, p)] = createHash('sha256').update(readFileSync(p)).digest('hex')
    }
  }
  if (existsSync(root)) walk(root)
  return out
}

export function diffTrees(a, b) {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  return [...keys].filter(k => a[k] !== b[k])
}

// ---- bots ----

export async function bot(username = 'tester') {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username, version: '1.21.11', auth: 'offline', viewDistance: 'normal' })
  // Bots speak 1.21.11 and reach 26.x through ViaVersion and ViaBackwards. Translated, mineflayer's plain "position"
  // packet gets the bot kicked for an invalid move, so it's dropped. Tests move the bot by console teleport, and the
  // position_look answer to each teleport still goes through.
  const write = b._client.write.bind(b._client)
  const recent = []
  b._client.write = (name, params) => {
    if (name === 'position') return undefined
    // position_look is only needed to answer a server teleport; at other times, translated, it's rejected too.
    if (name === 'position_look' && !(recent.length && recent[recent.length - 1].startsWith('teleport_confirm'))) return undefined
    recent.push(name + ' ' + JSON.stringify(params, (k, v) => typeof v === 'bigint' ? v.toString() : v).slice(0, 160))
    if (recent.length > 8) recent.shift()
    return write(name, params)
  }
  b.on('kicked', r => console.log('BOT KICKED: ' + JSON.stringify(r).slice(0, 120) + '\n  last packets:\n    ' + recent.join('\n    ')))
  await new Promise((resolve, reject) => {
    b.once('spawn', () => { b.physicsEnabled = false; resolve() })  // physics off: moves happen by teleport
    b.once('error', reject)
    b.once('kicked', r => reject(new Error('kicked: ' + JSON.stringify(r))))
  })
  b.on('error', () => {})
  return b
}

/** Teleports through the console and waits until the bot is there and the ground under it has loaded. */
export async function tp(server, b, x, y, z) {
  await server.cmd(`tp ${b.username} ${x} ${y} ${z}`)
  const t0 = Date.now()
  while (Date.now() - t0 < 30_000) {
    if (b.entity.position.distanceTo(new Vec3(x, y, z)) < 2 && b.blockAt(new Vec3(x, y - 1, z))) break
    await sleep(200)
  }
  await sleep(2500)
}

/** The y of the highest solid block in a column, from the bot's loaded chunks. */
export function surface(b, x, z) {
  for (let y = 200; y > -64; y--) {
    const bl = b.blockAt(new Vec3(x, y, z))
    if (bl && bl.boundingBox === 'block' && !bl.name.includes('leaves') && !bl.name.includes('log')) return y
  }
  return null
}

const SOFT = /air|water|lava|leaves|grass$|fern|flower|snow|vine|bush|log|sapling|tulip|poppy|dandelion|seagrass|kelp/

/** Digs solid blocks in a box around the bot. Returns how many it broke. */
export async function digBox(b, center, { dx = 2, dz = 2, ys = [-1, -2, -3], want = 50 } = {}) {
  let n = 0
  for (const dy of ys) {
    for (let x = -dx; x <= dx; x++) {
      for (let z = -dz; z <= dz; z++) {
        if (x === 0 && z === 0) continue
        if (n >= want) return n
        const bl = b.blockAt(center.offset(x, dy, z))
        if (!bl || SOFT.test(bl.name) || bl.boundingBox !== 'block' || bl.name === 'bedrock') continue
        try {
          await b.dig(bl, true)
          n++
        } catch (e) { /* out of reach or changed: skip */ }
      }
    }
  }
  return n
}

/** Gives the bot an item in its main hand, in creative mode. */
export async function hold(b, name, count = 1) {
  const item = b.registry.itemsByName[name]
  if (!item) throw new Error('no item ' + name)
  const Item = (await import('prismarine-item')).default(b.registry)
  await b.creative.setInventorySlot(36, new Item(item.id, count))
  await sleep(300)
  b.setQuickBarSlot(0)
  await sleep(300)
}

/** The highest non-air block in a column, as the bot sees it now. */
export function top(b, x, z) {
  for (let y = 250; y > -64; y--) {
    const bl = b.blockAt(new Vec3(x, y, z))
    // The ground: solid, not a plant, tree or fluid.
    if (bl && bl.boundingBox === 'block' && !/air|water|lava|leaves|log/.test(bl.name)) return bl
  }
  return null
}

/** Like top(), waiting up to 15 s for the column to load in the bot. */
export async function waitTop(b, x, z) {
  for (let i = 0; i < 75; i++) {
    const t = top(b, x, z)
    if (t) return t
    await sleep(200)
  }
  throw new Error(`column ${x}, ${z} never loaded in the bot`)
}

/** Places a held block on top of a column (x, z), or on the block at pos if given a Vec3 with a y. */
export async function placeOn(b, pos, name) {
  await hold(b, name)
  const ref = pos.y === undefined || pos.y === null ? await waitTop(b, pos.x, pos.z) : b.blockAt(pos)
  pos = ref.position
  await b.placeBlock(ref, new Vec3(0, 1, 0)).catch(async e => {
    // mineflayer sometimes misses the block update; the result is checked below instead.
    if (process.env.WR_DEBUG) console.log('  place ' + name + ' at ' + ref.position + ' on ' + ref.name + ': ' + e.message)
    await sleep(500)
  })
  await sleep(300)
  const placed = b.blockAt(pos.offset(0, 1, 0))
  return placed && placed.name === name
}

export { Vec3 }
