import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { isDocumentationPath } from '../document-policy.mjs'

test('开发文档、办公文档及docs目录不发布，源码、迁移和必要清单保留', () => {
  for (const name of [
    'README.md',
    'backend/VOICE_ROOM_PLAN.md',
    'docs/plan.md',
    'DOCS/rules.yml',
    'report.DOCX',
    'sheet.xlsx',
    'notes.txt',
    'plan.pdf',
    'guide.rst',
    'paper.tex',
  ])
    assert.equal(isDocumentationPath(name), true, name)
  for (const name of [
    'web/src/App.vue',
    'backend/demo/src/main/resources/db/migration/V1__init.sql',
    'requirements.txt',
    'requirements-dev.txt',
    'CMakeLists.txt',
    'LICENSE',
    'NOTICE.txt',
    'scripts/check-staged-secrets.mjs',
  ])
    assert.equal(isDocumentationPath(name), false, name)
})

test('实际Git：历史文档不强推重写，当前文档移出保留本地，强制添加再被提交/推送守卫拒绝', () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
  // 全新归档/CI中也能独立运行，不能依赖另一测试碰巧先创建私有运行目录。
  mkdirSync(path.join(root, '.runtime'), { recursive: true })
  const directory = mkdtempSync(path.join(root, '.runtime', 'document-policy-fixture-'))
  const env = {
    ...process.env,
    GIT_AUTHOR_NAME: 'Policy Fixture',
    GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
    GIT_COMMITTER_NAME: 'Policy Fixture',
    GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
  }
  const git = (args) => execFileSync('git', args, { cwd: directory, env, encoding: 'utf8' })
  const checker = path.join(root, 'scripts/check-staged-secrets.mjs')
  const check = (mode, input) =>
    spawnSync(process.execPath, [checker, mode], { cwd: directory, env, encoding: 'utf8', input })
  git(['init', '-q', '-b', 'main'])
  writeFileSync(path.join(directory, 'README.md'), 'Synthetic development notes')
  git(['add', 'README.md'])
  git(['-c', 'core.hooksPath=', 'commit', '-q', '-m', 'historical docs'])
  const old = git(['rev-parse', 'HEAD']).trim()
  git(['rm', '--cached', 'README.md'])
  assert.equal(existsSync(path.join(directory, 'README.md')), true)
  git(['-c', 'core.hooksPath=', 'commit', '-q', '-m', 'remove current docs'])
  assert.equal(check('--history').status, 0)
  git(['add', '-f', 'README.md'])
  const blocked = check('--staged')
  assert.equal(blocked.status, 1)
  assert.match(blocked.stderr, /documentation-not-publishable/)
  git(['-c', 'core.hooksPath=', 'commit', '-q', '-m', 'force docs fixture'])
  const head = git(['rev-parse', 'HEAD']).trim()
  assert.equal(check('--push', `refs/heads/other ${head} refs/heads/other ${old}\n`).status, 1)
})
