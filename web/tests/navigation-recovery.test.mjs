import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { ref } from 'vue'

test('懒加载失败仅显示提示，正常导航清理，失败导航保留；卸载移除全部监听', async () => {
  const listeners = new Map(),
    hooks = {},
    released = []
  const context = vm.createContext({
    window: {
      addEventListener: (name, fn) => listeners.set(name, fn),
      removeEventListener: (name) => listeners.delete(name),
      location: {
        reload() {
          throw new Error('Must not automatically reload')
        },
      },
    },
  })
  const source = await readFile(new URL('../src/services/navigation-recovery.ts', import.meta.url), 'utf8')
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.ESNext,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { context },
  )
  await module.link(
    () =>
      new vm.SyntheticModule(
        ['ref'],
        function () {
          this.setExport('ref', ref)
        },
        { context },
      ),
  )
  await module.evaluate()
  const { installNavigationRecovery, navigationLoadFailed } = module.namespace
  const stop = installNavigationRecovery({
    onError(fn) {
      hooks.error = fn
      return () => released.push('error')
    },
    afterEach(fn) {
      hooks.after = fn
      return () => released.push('after')
    },
  })
  listeners.get('vite:preloadError')()
  assert.equal(navigationLoadFailed.value, true)
  hooks.after({}, {}, { type: 'aborted' })
  assert.equal(navigationLoadFailed.value, true)
  hooks.after({}, {}, undefined)
  assert.equal(navigationLoadFailed.value, false)
  hooks.error(new Error('synthetic raw details'))
  assert.equal(navigationLoadFailed.value, true)
  stop()
  assert.equal(listeners.size, 0)
  assert.deepEqual(released, ['error', 'after'])
  assert.equal(navigationLoadFailed.value, false)
})
