# KOKO Nexus 代码格式

在仓库根目录安装独立开发工具，不需要在每个 Java 服务安装 Node 依赖：

```powershell
npm ci --ignore-scripts
npm run format
npm run format:check
npm run format:debug
npm run test:format
```

固定版本见根目录 `package.json` 和 `package-lock.json`。Java 4 空格，前端/XML/YAML 2 空格，
UTF-8、LF、末尾换行，目标行宽 120；长字面量不强拆，行宽不是每一行的硬性长度保证。
Java 的条件换行、导入排序、record/注解排版由同一 formatter 管理，Lombok 和 Swagger 注解保留。
Vue 内嵌脚本/事件保留分号，因为 Vue 编译器用分号区分多语句事件；其他 TS/MJS 沿用无分号风格。
HTML 使用严格空白策略，不主动改变 inline 节点间距。

范围：后端各模块 main/test Java、POM、资源 XML/YAML，前端 src、测试及配置，仓库 MJS 工具。
排除所有历史 Flyway SQL、部署目录、运行时、依赖、构建产物、凭据、冻结证据及旧验证档案。
历史 Markdown 和 PowerShell/Bash 不使用不兼容的 JS formatter 重写；新文档/脚本按开发规范排版。
不要直接对 `D:/KOKO` 无筛选地执行 `prettier --write .`。

`format:check` 不写文件，不符合格式返回非零，适合 CI。格式化不能代替代码质量扫描、行为测试、
构建和浏览器验收，本项目没有因此宣称已通过 P3C 或完整 ESLint 扫描。

Java 插件的原生 `--debug-check` 会序列化重复 CST 子树，在本项目出现耗时膨胀和
`RangeError: Invalid string length`。Java 改为独立核对 CST 的 children、字面量及二次格式化幂等性；
仅允许导入重排、注释/位置变化和数组可选尾逗号，不豁免运算符、括号、调用顺序、字符串或注解参数。
七项工具测试覆盖这些允许/拒绝边界。Redis Lua 文本块有明确 `prettier-ignore`，保持原始字面量。
其他语言仍运行原生 debug-check；另有真实 Vue 模板编译测试防止其漏掉事件表达式错误。

修改后至少运行：

```powershell
# 根目录
npm run format:check
npm run format:debug
npm run test:format
# 使用 Java 21；不更改已经应用的迁移
mvn -f backend/pom.xml package
# 前端目录
cd web
npm test
npm run build
```

本轮无 Git 仓库，格式化前保存两个源码压缩备份；后续恢复只定向取出需要的文件，
不覆盖用户后续工作或整目录回滚。失败记录与验收边界见
[绑定保护与格式化记录](ASSET_BINDING_FORMATTING_VERIFICATION_20261004.md)。
