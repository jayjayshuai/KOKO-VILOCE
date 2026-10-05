import assert from 'node:assert/strict'
import test from 'node:test'
import javaPlugin from 'prettier-plugin-java'
import { javaSyntax } from '../java-format-syntax.mjs'

const parse = async (source) => javaSyntax(await javaPlugin.parsers.java.parse(source))
test('Java 格式比对只允许空白、注释、import 重排及可选数组尾逗号', async () => {
  assert.deepEqual(
    await parse('import java.util.List; import java.util.Set; class A { int[] x={1,2,}; }'),
    await parse('import java.util.Set;\nimport java.util.List; // comment\nclass A { int[] x = { 1, 2 }; }'),
  )
})
for (const [name, source, changed] of [
  ['运算符', 'class A { int f(){return 1+2;} }', 'class A { int f(){return 1-2;} }'],
  ['字符串', 'class A { String x="private"; }', 'class A { String x="public"; }'],
  ['转义字符', "class A { char x='\\n'; }", "class A { char x='n'; }"],
  ['调用顺序', 'class A { void f(){first();second();} }', 'class A { void f(){second();first();} }'],
  ['注解属性', '@Schema(description="one") class A {}', '@Schema(description="two") class A {}'],
  ['括号', 'class A { int x=(1+2)*3; }', 'class A { int x=1+2*3; }'],
]) {
  test(`Java 格式比对拒绝改写${name}`, async () => {
    assert.notDeepEqual(await parse(source), await parse(changed))
  })
}
