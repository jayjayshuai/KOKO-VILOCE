import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import ts from 'typescript'

/** 运行生产TS，依赖必须显式提供；不自动吞未知网络/设备导入。 */
export async function executeTs(relative, imports = {}, globals = {}) {
  const source = await readFile(new URL('../../src/' + relative, import.meta.url), 'utf8')
  const context = vm.createContext({ AbortController, Error, URL, ...globals })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText,
    { context },
  )
  await module.link((name) => {
    const values = imports[name]
    if (!values) throw new Error('Unconfigured test dependency ' + name)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace
}
