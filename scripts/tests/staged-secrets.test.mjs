import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync, unlinkSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { inspectFile, pushObjectIds } from '../check-staged-secrets.mjs'

test('private files stay blocked even with Git force-add', () => {
  for (const file of [
    '.env',
    'web/.env.production',
    'deploy/tests/check.java',
    'docs/evidence/dump.sql',
    '.ssh/id_rsa',
    'backend/demo/target/application.jar',
    'runtime.json',
  ]) {
    assert.ok(inspectFile(file, 'redacted').includes('private-path-or-artifact'))
  }
  assert.deepEqual(inspectFile('.env.example', 'PASSWORD=replace-with-a-random-password'), [])
  assert.deepEqual(
    inspectFile(
      'backend/voice-service/src/main/resources/db/migration/V1__voice.sql',
      'CREATE TABLE voice_room(id BIGINT);',
    ),
    [],
  )
})

test('private keys and provider tokens are rejected without echoing their value', () => {
  const key = ['-----BEGIN ', 'PRIVATE KEY-----'].join('')
  const token = ['gh', 'p_', 'A'.repeat(40)].join('')
  assert.deepEqual(inspectFile('source.txt', key), ['private-key'])
  assert.deepEqual(inspectFile('source.txt', token), ['provider-token'])
})

test('hardcoded credentials are rejected but environment references and placeholders work', () => {
  const unsafe = ['password=', "'", 'hardcoded-credential-canary', "'"].join('')
  assert.deepEqual(inspectFile('config.ts', unsafe), ['credential-like-literal'])
  assert.deepEqual(inspectFile('application.yml', 'password: "${DATABASE_PASSWORD}"'), [])
  assert.deepEqual(inspectFile('config.ts', "password='replace-with-your-own-secret'"), [])
})

test('test location never bypasses provider token scanning', () => {
  const token = ['gh', 'p_', 'B'.repeat(40)].join('')
  assert.ok(inspectFile('web/tests/security.test.mjs', token).includes('provider-token'))
  assert.deepEqual(inspectFile('web/tests/auth.test.mjs', "password='isolated-test-password'"), [])
})

test('URL credentials only have an exact synthetic rejection fixture exception', () => {
  const unsafe = ['https://', 'alice:', 'real-looking-password', '@example.invalid'].join('')
  assert.deepEqual(inspectFile('config.ts', unsafe), ['url-credential'])
  assert.deepEqual(inspectFile('web/tests/url.test.mjs', 'ws://user:secret@198.51.100.10'), [])
  assert.ok(inspectFile('config.ts', 'ws://user:secret@198.51.100.10').includes('url-credential'))
})

test('locally configured private hosts and personal deployment paths are blocked', () => {
  assert.deepEqual(inspectFile('README.md', 'https://private.example.invalid', ['private.example.invalid']), [
    'private-deployment-host',
  ])
  assert.ok(inspectFile('README.md', ['/', 'home/', 'ubuntu/', 'app'].join('')).includes('personal-deployment-path'))
  assert.deepEqual(inspectFile('README.md', 'https://deployment.example.invalid /srv/deployment-home/app'), [])
})

test('push inputs cover other branches, deduplicate objects and ignore deletions', () => {
  const hash = 'a'.repeat(40)
  assert.deepEqual(
    pushObjectIds(
      `refs/heads/other ${hash} refs/heads/other ${'0'.repeat(40)}\nrefs/tags/test ${hash} refs/tags/test ${'0'.repeat(40)}\n`,
    ),
    [hash],
  )
  assert.deepEqual(pushObjectIds(`(delete) ${'0'.repeat(40)} refs/heads/old ${hash}\n`), [])
  assert.throws(() => pushObjectIds('refs/heads/other HEAD refs/heads/other zero'), /invalid-push-ref/)
})

// 实际Git层检查：仅在D盘工作区的独立临时库操作合成canary，无网络/真实密码。
const workspace = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const runtime = path.join(workspace, '.runtime')
const checker = path.join(workspace, 'scripts/check-staged-secrets.mjs')
function repository() {
  mkdirSync(runtime, { recursive: true })
  const directory = mkdtempSync(path.join(runtime, 'secret-guard-fixture-'))
  execFileSync('git', ['init', '-q', '-b', 'main'], { cwd: directory })
  return directory
}
function git(directory, args) {
  return execFileSync('git', args, {
    cwd: directory,
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 'Security Fixture',
      GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
      GIT_COMMITTER_NAME: 'Security Fixture',
      GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
    },
  }).trim()
}
function scan(directory, mode, input) {
  return spawnSync(process.execPath, [checker, mode], { cwd: directory, input, encoding: 'utf8' })
}

test('actual staged blob is checked even if working file was replaced with clean content', () => {
  const directory = repository()
  const token = ['gh', 'p_', 'C'.repeat(40)].join('')
  writeFileSync(path.join(directory, 'config.ts'), token)
  git(directory, ['add', 'config.ts'])
  writeFileSync(path.join(directory, 'config.ts'), 'clean-working-tree')
  const result = scan(directory, '--staged')
  assert.equal(result.status, 1)
  assert.ok(result.stderr.includes('provider-token'))
  assert.ok(!result.stderr.includes(token))
})

test('actual forced private file and local private host are rejected; safe example passes', () => {
  const directory = repository()
  writeFileSync(path.join(directory, '.env'), 'NO_REAL_PASSWORD=canary')
  git(directory, ['add', '-f', '.env'])
  assert.equal(scan(directory, '--staged').status, 1)
  git(directory, ['rm', '--cached', '.env'])
  writeFileSync(path.join(directory, '.env.example'), 'PASSWORD=replace-with-a-random-password')
  git(directory, ['add', '.env.example'])
  assert.equal(scan(directory, '--staged').status, 0)
  git(directory, ['config', '--add', 'koko.privateHost', 'private.example.invalid'])
  writeFileSync(path.join(directory, 'README.md'), 'https://private.example.invalid')
  git(directory, ['add', 'README.md'])
  assert.equal(scan(directory, '--staged').status, 1)
})

test('actual deleted historical secret stays blocked and non-HEAD push cannot bypass', () => {
  const directory = repository()
  const token = ['gh', 'p_', 'D'.repeat(40)].join('')
  writeFileSync(path.join(directory, 'config.ts'), token)
  git(directory, ['add', 'config.ts'])
  git(directory, ['commit', '-q', '-m', 'synthetic fixture only'])
  const secretCommit = git(directory, ['rev-parse', 'HEAD'])
  unlinkSync(path.join(directory, 'config.ts'))
  git(directory, ['add', '-u'])
  git(directory, ['commit', '-q', '-m', 'remove fixture'])
  assert.equal(scan(directory, '--staged').status, 0)
  const historical = scan(directory, '--history')
  assert.equal(historical.status, 1)
  assert.ok(!historical.stderr.includes(token))
  const cleanTree = git(directory, ['write-tree'])
  const cleanRoot = git(directory, ['commit-tree', cleanTree, '-m', 'independent clean fixture'])
  git(directory, ['update-ref', 'refs/heads/main', cleanRoot])
  assert.equal(scan(directory, '--history').status, 0)
  const outgoing = scan(directory, '--push', `refs/heads/other ${secretCommit} refs/heads/other ${'0'.repeat(40)}\n`)
  assert.equal(outgoing.status, 1)
  assert.ok(outgoing.stderr.includes('provider-token'))
})
