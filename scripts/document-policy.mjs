import path from 'node:path'

/** 文档只留本地。必要构建/依赖清单和许可证不当作开发说明移除，内容仍须查秘密。 */
export function isDocumentationPath(filename) {
  const name = filename.replaceAll('\\', '/').toLowerCase()
  const base = path.posix.basename(name)
  if (/^(?:license|copying|notice)(?:\.|$)/.test(base)) return false
  if (/^(?:requirements(?:-(?:dev|prod|test|build))?\.txt|cmakelists\.txt)$/.test(base)) return false
  return (
    /(?:^|\/)(?:docs|documentation)\//.test(name) ||
    /\.(?:md|markdown|rst|adoc|txt|pdf|docx?|docm|pptx?|xlsx?|odt|ods|odp|rtf|tex)$/.test(name)
  )
}
