// 仅规范源码排版；不格式化已应用迁移、部署凭据、冻结证据或构建产物。
export default {
  plugins: ['prettier-plugin-java', '@prettier/plugin-xml'],
  tabWidth: 2,
  printWidth: 120,
  useTabs: false,
  endOfLine: 'lf',
  singleQuote: true,
  semi: false,
  htmlWhitespaceSensitivity: 'strict',
  xmlWhitespaceSensitivity: 'strict',
  overrides: [
    { files: '**/*.java', options: { tabWidth: 4 } },
    // Vue 编译器用分号识别多语句事件处理器；不能由格式化去掉语句边界。
    { files: '**/*.vue', options: { semi: true } },
  ],
}
