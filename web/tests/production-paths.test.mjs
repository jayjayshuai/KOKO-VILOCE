import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

async function evaluate(relative, env, imports = {}) {
  const source = await readFile(new URL(relative, import.meta.url), 'utf8')
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const context = vm.createContext({ console })
  const module = new vm.SourceTextModule(output, {
    context,
    initializeImportMeta(meta) {
      meta.env = env
    },
  })
  await module.link((specifier) => {
    const values = imports[specifier]
    if (!values) throw new Error('Unexpected configuration import')
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [name, value] of Object.entries(values)) this.setExport(name, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace
}

test('公开生产构建不依赖私有环境文件，本地API默认与显式覆盖仍兼容', async () => {
  assert.equal((await evaluate('../src/services/http.ts', { PROD: true })).apiBaseUrl, '/koko-api')
  assert.equal((await evaluate('../src/services/http.ts', { PROD: false })).apiBaseUrl, '/api')
  assert.equal(
    (await evaluate('../src/services/http.ts', { PROD: true, VITE_API_BASE: '/custom///' })).apiBaseUrl,
    '/custom',
  )
})

test('真实Vite配置函数按生产/开发选择资源前缀，显式部署配置可覆盖', async () => {
  for (const [mode, env, expected] of [
    ['production', {}, '/koko/'],
    ['development', {}, '/'],
    ['production', { VITE_PUBLIC_BASE: '/custom/' }, '/custom/'],
  ]) {
    const config = await evaluate(
      '../vite.config.ts',
      {},
      {
        vite: { defineConfig: (value) => value, loadEnv: () => env },
        '@vitejs/plugin-vue': { default: () => ({ name: 'synthetic-plugin' }) },
      },
    )
    assert.equal(config.default({ mode }).base, expected)
  }
})
