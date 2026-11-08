import { readFileSync } from 'node:fs'
import path from 'node:path'
import { REPO } from './harness.mjs'

/** The shipped config.yml with test overrides, so the intent lists stay the real defaults. */
export function testConfig({ dryRun = false, claims = 'GriefPrevention', worlds = ['world'], maxChunks = 200, extraDefaults = '', minGroup = 1 } = {}) {
  let c = readFileSync(path.join(REPO, 'src/main/resources/config.yml'), 'utf8')
  c = c.replace(/^dry-run: true$/m, `dry-run: ${dryRun}`)
  c = c.replace(/^worlds:\n(?:(?: {2}.*|\s*#.*)?\n)*/m, 'worlds:\n' + worlds.map(w => `  ${w}:\n    enabled: true\n`).join('') + '\n')
  c = c.replace(/^  plugin: GriefPrevention$/m, `  plugin: ${claims}`)
  c = c.replace(/days-without-changes: 30/, 'days-without-changes: 0')
  c = c.replace(/days-without-visitors: 7/, 'days-without-visitors: 0')
  c = c.replace(/days-since-last-reset: 30/, 'days-since-last-reset: 0')
  c = c.replace(/spawn-radius-chunks: 8/, 'spawn-radius-chunks: 2')
  c = c.replace(/max-chunks: 200/, `max-chunks: ${maxChunks}`)
  c = c.replace(/min-group-size: 1/, `min-group-size: ${minGroup}`)
  return c + extraDefaults
}
