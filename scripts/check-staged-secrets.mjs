import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 仅检查Git将发布的内容，不扫描/输出本机.env、备份或凭据；拒绝时只给路径与规则名。
const DEPLOY_TEMPLATES = new Set([
  'deploy/.dockerignore',
  'deploy/Dockerfile.java',
  'deploy/Dockerfile.web',
  'deploy/compose.production.yml',
  'deploy/edge-locations.conf',
  'deploy/livekit.yaml',
  'deploy/minio-koko-policy.json',
  'deploy/nginx-web.conf',
])

/** 配置支持未加引号、JSON键和短密码；只输出规则，不输出值。 */
function configurationCredentials(filename, source, fixture, currentConfiguration) {
  const config = /(?:^|\/)\.env(?:\.|$)/.test(filename) || /\.(?:ya?ml|json|properties|toml|ini|cnf)$/i.test(filename)
  if (!config) return [] // 代码中的中文契约说明交给原有代码字面量规则，不视为配置。
  const sensitive = (key) =>
    /(?:password|secret|token|api[_-]?key|access[_-]?key|secret[_-]?key|private[_-]?key|internal[_-]?key)$/i.test(key)
  const safe = (value) =>
    !value ||
    /^(?:replace[-_ ]|<[^>]+>$)/i.test(value) ||
    (fixture && /^(?:isolated[-_]|synthetic[-_]|unit[-_]|test[-_]|fake[-_]|dummy[-_])/.test(value))
  const allowedReference = (value) => {
    const expression = /^\$\{[A-Za-z_][A-Za-z0-9_.-]*(?::([^{}]*))?\}$/.exec(value)
    if (!expression) return false
    // 旧提交的公开开发默认值已核对不等于现有凭据；只对当前树禁止再次发布这种默认值。
    // 历史的直接字面量、令牌、私钥和已知部署信息仍检查，不对整个历史跳过扫描。
    if (!currentConfiguration) return true
    const compose = /(?:^|\/)(?:docker-)?compose(?:\.[a-z0-9_-]+)*\.ya?ml$/i.test(filename)
    return expression[1] === undefined || safe(expression[1]) || (compose && expression[1].startsWith('?'))
  }
  if (/\.json$/i.test(filename)) {
    const unsafe = (item) => {
      if (Array.isArray(item)) return item.some(unsafe)
      if (!item || typeof item !== 'object') return false
      return Object.entries(item).some(([key, value]) =>
        sensitive(key) && typeof value === 'string' ? !safe(value) && !allowedReference(value) : unsafe(value),
      )
    }
    try {
      if (unsafe(JSON.parse(source))) return ['configuration-credential-literal']
    } catch {
      // 非标准JSON仍检查逐行字面量；本工具不充当配置语法校验器。
    }
  }
  for (const line of source.split(/\r?\n/)) {
    const assignment = /^\s*(?:export\s+)?(?:["']([A-Za-z_][\w.-]*)["']|([A-Za-z_][\w.-]*))\s*[:=]\s*(.*?)\s*$/.exec(
      line,
    )
    if (!assignment || !sensitive(assignment[1] ?? assignment[2])) continue
    let raw = assignment[3].replace(/,\s*$/, '').trim()
    const quoted = /^(["'])(.*?)\1(?:\s+#.*)?$/.exec(raw)
    raw = quoted ? quoted[2] : raw.replace(/\s+#.*$/, '').trim()
    if (safe(raw) || allowedReference(raw) || (config && /^(?:null|~)$/i.test(raw))) continue
    return ['configuration-credential-literal']
  }
  return []
}

export function inspectFile(filename, source, privateHosts = [], currentConfiguration = true) {
  const issues = []
  const normalized = filename.replaceAll('\\', '/')
  const basename = path.posix.basename(normalized)
  if (
    /[\p{Cc}\p{Cf}]/u.test(normalized) ||
    /(^|\/)(?:\.runtime|\.ssh|\.codex|\.idea|\.vscode|node_modules|target|dist|__pycache__)(\/|$)/.test(normalized) ||
    /^(?:acl-recovery|cn)\//.test(normalized) ||
    /^docs\/[^/]+\//.test(normalized) ||
    (normalized.startsWith('deploy/') && !DEPLOY_TEMPLATES.has(normalized)) ||
    /\.(?:tgz|tar\.gz|zip|jar|class|dump|pem|key|p12|pfx|pyc|tsbuildinfo|log)$/i.test(normalized) ||
    (/^\.env(?:\.|$)/.test(basename) && !/^\.env(?:\.[a-z0-9_-]+)?\.example$/i.test(basename)) ||
    /^(?:runtime(?:[._-].*)?\.json|credentials?\.json|secrets?\.(?:json|ya?ml|toml|properties))$/i.test(basename) ||
    (/\.sql$/i.test(normalized) &&
      !/^backend\/[^/]+\/src\/(?:main\/resources\/db\/migration|test\/resources)\//.test(normalized))
  )
    issues.push('private-path-or-artifact')

  if (/-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----/.test(source)) issues.push('private-key')
  if (
    /\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{30,}|sk-(?:proj-)?[A-Za-z0-9_-]{24,})\b/.test(
      source,
    )
  )
    issues.push('provider-token')
  if (/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/.test(source)) issues.push('literal-jwt')
  if (/\bBearer\s+[A-Za-z0-9._-]{24,}\b/.test(source)) issues.push('literal-bearer')
  if (/(?:\/home\/ubuntu\/|[A-Za-z]:\\Users\\|\/Users\/)/.test(source)) issues.push('personal-deployment-path')
  if (privateHosts.some((host) => host && source.includes(host))) issues.push('private-deployment-host')

  const fixture = /^(?:web\/tests\/|scripts\/tests\/|backend\/[^/]+\/src\/test\/)/.test(normalized)
  issues.push(...configurationCredentials(normalized, source, fixture, currentConfiguration))
  for (const match of source.matchAll(
    /\b(?:https?|wss?|mysql|postgresql|rediss?|mongodb(?:\+srv)?|amqps?):\/\/([^\s/"'@]+):([^\s/"'@]+)@/g,
  )) {
    // 仅允许明确的拒绝URL测试canary，不允许对测试目录整体跳过密钥扫描。
    if (!(fixture && match[1] === 'user' && match[2] === 'secret')) issues.push('url-credential')
  }
  for (const match of source.matchAll(
    /\b(?:password|secret|token|api[_-]?key)\w*\s*[:=]\s*["']([^"'\r\n]{8,})["']/gi,
  )) {
    const value = match[1]
    if (
      value.includes('$') ||
      value.includes('%s') ||
      /^(?:replace[-_ ]|<|\$\{|process\.env|environment)/i.test(value) ||
      (value.match(/[\u3400-\u9fff]/g)?.length ?? 0) >= 4 ||
      (fixture && /^(?:isolated[-_]|synthetic[-_]|unit[-_]|test[-_]|fake[-_]|dummy[-_])/.test(value))
    )
      continue
    issues.push('credential-like-literal')
  }
  return [...new Set(issues)]
}

function git(args, options = {}) {
  return execFileSync('git', args, { maxBuffer: 64 * 1024 * 1024, ...options })
}

export function pushObjectIds(input) {
  return [
    ...new Set(
      input
        .split('\n')
        .filter((line) => line.trim())
        .map((line) => {
          const fields = line.trim().split(/\s+/)
          if (fields.length !== 4 || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(fields[1]))
            throw new Error('invalid-push-ref')
          return fields[1]
        })
        .filter((hash) => !/^0+$/.test(hash)),
    ),
  ]
}

function entriesFor(mode, refs) {
  if (mode === '--staged') {
    return git(['ls-files', '--stage', '-z'])
      .toString('utf8')
      .split('\0')
      .filter(Boolean)
      .map((line) => {
        const separator = line.indexOf('\t')
        if (separator < 0) throw new Error('invalid-index-entry')
        const header = line.slice(0, separator)
        const filename = line.slice(separator + 1)
        const [, hash, stage] = header.split(' ')
        if (stage !== '0') throw new Error('unmerged-index')
        return { hash, filename, currentConfiguration: true }
      })
  }
  // 只检查实际待推送对象的可达历史；不读取不会推送的reflog旧孤立提交。
  if (!refs.length) return []
  const tips = new Set(
    refs.map((ref) =>
      git(['rev-parse', '--verify', ref + '^{commit}'])
        .toString('utf8')
        .trim(),
    ),
  )
  const commits = git(['rev-list', ...refs])
    .toString('utf8')
    .trim()
    .split('\n')
  return commits.flatMap((commit) =>
    git(['ls-tree', '-r', '-z', commit])
      .toString('utf8')
      .split('\0')
      .filter(Boolean)
      .map((line) => {
        const separator = line.indexOf('\t')
        if (separator < 0) throw new Error('invalid-tree-entry')
        const header = line.slice(0, separator)
        const filename = line.slice(separator + 1)
        const [, type, hash] = header.split(' ')
        if (type !== 'blob') throw new Error('unsupported-tree-entry')
        return { hash, filename, currentConfiguration: tips.has(commit) }
      }),
  )
}

function blobContents(entries) {
  const hashes = [...new Set(entries.map((entry) => entry.hash))]
  const contents = new Map()
  if (!hashes.length) return contents
  const output = git(['cat-file', '--batch'], { input: hashes.join('\n') + '\n' })
  let offset = 0
  for (const hash of hashes) {
    const end = output.indexOf(10, offset)
    const [observed, type, length] = output.subarray(offset, end).toString('ascii').split(' ')
    const size = Number(length)
    if (observed !== hash || type !== 'blob' || !Number.isSafeInteger(size) || size > 5 * 1024 * 1024)
      throw new Error('invalid-or-oversized-blob')
    const data = output.subarray(end + 1, end + 1 + size)
    if (data.length !== size || data.includes(0)) throw new Error('opaque-binary-content')
    contents.set(hash, data.toString('utf8'))
    offset = end + 1 + size + 1
  }
  return contents
}

export function run(mode = '--staged', pushInput) {
  if (!['--staged', '--history', '--push'].includes(mode)) throw new Error('invalid-mode')
  let privateHosts = []
  try {
    privateHosts = git(['config', '--get-all', 'koko.privateHost'], { stdio: ['pipe', 'pipe', 'ignore'] })
      .toString('utf8')
      .trim()
      .split('\n')
  } catch (error) {
    if (error.status !== 1) throw error
  }
  const refs = mode === '--push' ? pushObjectIds(pushInput ?? readFileSync(0, 'utf8')) : ['HEAD']
  const entries = entriesFor(mode, refs)
  const contents = blobContents(entries)
  const failures = new Set()
  for (const entry of entries) {
    for (const issue of inspectFile(entry.filename, contents.get(entry.hash), privateHosts, entry.currentConfiguration))
      failures.add(`${JSON.stringify(entry.filename)}: ${issue}`)
  }
  if (failures.size) {
    for (const failure of failures) process.stderr.write(`BLOCK ${failure}\n`)
    return 1
  }
  process.stdout.write(
    `PASS secret guard ${mode}: ${entries.length} file entries; heuristic scan, not a zero-secret guarantee\n`,
  )
  return 0
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = run(process.argv[2])
  } catch {
    process.stderr.write('BLOCK secret guard could not complete; raw error/content withheld\n')
    process.exitCode = 1
  }
}
