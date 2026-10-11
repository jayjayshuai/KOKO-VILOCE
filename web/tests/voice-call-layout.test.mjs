import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

test('常驻通话条按实际高度留白，换页/移除/卸载释放观察器及全局样式', async () => {
  const source = await readFile(new URL('../src/components/PersistentVoiceCallBar.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'voice-bar-layout-test' }).content
  const hooks = [],
    observers = [],
    styles = new Map()
  const auth = vue.reactive({ user: { id: '1' }, sessionRevision: 1 })
  const callStore = vue.reactive({
    call: { registration: 1, userId: '1', roomId: '9', sessionRevision: 1, status: { phase: 'connected' } },
    clear() {},
  })
  const route = vue.reactive({ name: 'messages', params: {} })
  const context = vm.createContext({
    document: {
      documentElement: {
        style: {
          setProperty: (name, value) => styles.set(name, value),
          removeProperty: (name) => styles.delete(name),
        },
      },
    },
    ResizeObserver: class {
      constructor(callback) {
        this.callback = callback
        this.disconnected = false
        observers.push(this)
      }
      observe() {}
      disconnect() {
        this.disconnected = true
      }
    },
  })
  const imports = {
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    'vue-router': { RouterLink: {}, useRoute: () => route },
    '../stores/auth': { useAuthStore: () => auth },
    '../stores/voice-call': { useVoiceCallStore: () => callStore },
  }
  const module = new vm.SourceTextModule(
    ts.transpileModule(script, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText,
    { context },
  )
  await module.link((name) => {
    const values = imports[name]
    assert.ok(values)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  const state = module.namespace.default.setup({}, { expose() {} })
  let height = 132
  state.bar.value = { getBoundingClientRect: () => ({ height }) }
  await vue.nextTick()
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(styles.get('--koko-voice-call-space'), '164px')
  height = 180
  observers[0].callback()
  assert.equal(styles.get('--koko-voice-call-space'), '212px')
  route.name = 'voice-room'
  route.params = { roomId: '9' }
  await vue.nextTick()
  assert.equal(styles.has('--koko-voice-call-space'), false)
  assert.equal(observers[0].disconnected, true)
  route.name = 'messages'
  await vue.nextTick()
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(styles.get('--koko-voice-call-space'), '212px')
  hooks.forEach((callback) => callback())
  observers.at(-1).callback()
  assert.equal(styles.has('--koko-voice-call-space'), false)
  assert.ok(observers.every((observer) => observer.disconnected))
})
