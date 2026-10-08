# 万境书院项目 Agent 开发规则

本仓库采用：

```text
一个 PR = 一个独立 Agent 对话
```

PR 合并后即结束该对话；下一个 PR 新开一个独立对话。Agent 不应依赖以前聊天、
PR 评论或某个已结束对话里的上下文作为长期事实来源。

## Language

- 与用户交流时始终使用简体中文。
- 最终总结、阶段性进度、错误说明、测试结果均使用简体中文。
- 工具调用前后的自然语言说明使用简体中文。
- Git commit message、代码标识符、命令、日志原文可以保留英文。
- 不要为了翻译而改写命令、文件路径、类名、方法名或错误日志。

## 项目位置

```text
产品名称：万境书院
本地项目目录：E:\题海仕途
远程仓库：https://github.com/Valleyer/tihaishitu
GitHub Repository：Valleyer/tihaishitu
```

上面的“万境书院 / 题海仕途 / tihaishitu”指向同一个项目，不是三个项目：
万境书院是产品名，题海仕途是本地目录名，`tihaishitu` 是 GitHub 仓库名。

品牌名与技术身份分离：产品改名不重命名仓库、目录、Java package、
数据库 schema、World ID、API 路由、数据库表名、Flyway migration 文件名、
UUID 或兼容 localStorage key。旧的展示名“知境 / 知境中枢”已统一改为
“万境书院 / 万境中枢”；`docs/archive/**` 与历史记录保留旧品牌作为历史事实。

具备本地文件系统访问能力的 Agent，默认以：

```text
E:\题海仕途
```

为项目根目录。**不要在陈旧副本（例如其他盘的旧克隆、临时解包目录）上继续开发。**

每次开始新 PR 前先确认：

```text
本地仓库已同步最新 main
当前分支正确
当前 remote 是 Valleyer/tihaishitu
```

项目事实来源是：

```text
代码
+
仓库长期文档
```

而不是：

```text
代码
+
某个 Agent 记得上一轮聊天
```

## Git 远程写入规则

由于用户当前网络连接不稳定，所有编码 Agent 默认只负责**本地** Git 操作。
远程写入一律由用户亲自执行。

Agent **可以**执行：

- 从最新 `main` 创建本地开发分支；
- 在本地修改代码；
- 在本地运行测试；
- `git add`；
- `git commit`；
- 整理 commit message；
- 输出本地 HEAD SHA；
- 整理建议的 PR 标题与 PR 描述；
- 检查本地分支与 `main` 的差异。

Agent **不得执行**：

```text
git push
git push --force
git push --force-with-lease
创建远程分支
gh pr create
创建 Pull Request
修改远程 Pull Request
merge / squash / rebase 远程 PR
任何会写入 GitHub 远程仓库的操作
```

标准交付边界：

```text
本地创建分支
→ 修改代码
→ 本地测试
→ git add
→ git commit
→ 报告本地 HEAD SHA
→ 停止
```

之后由用户亲自执行：

```text
git push
创建 / 更新 PR
最终 merge
```

即使 Agent 已经拥有 GitHub 凭据、GitHub Connector、`gh` CLI 或其他远程写入能力，
也不能因为“可以操作”就自动 push / 建 PR。

除非用户在当前对话中**明确临时授权本次远程写入**，否则默认一律禁止。
如果任务说明与本规则冲突：

```text
以“不自动 push / 不自动创建 PR”为准
```

## Agent 测试边界

Agent 默认执行“最小必要测试”，不要在每个任务末尾机械运行整套前后端全量测试。
本规则与上面的“Git 远程写入规则”并列，都是 Agent 的默认交付边界。

- 纯文档 / 文案：不跑测试；
- 单个前端组件 / 工具函数：跑直接相关 targeted test；
- TypeScript 类型或构建配置变化：运行必要的 `npm run build`；
- 单个后端 Store / Service / Controller：运行直接相关 Maven test class；
- SQL / migration：运行直接相关的数据库验证或 targeted integration test；
- 只有跨多个核心领域的高风险算法修改，才扩大测试范围。

默认不要机械执行：

```text
全量 backend test
全量 frontend test
多轮重复 full test
长时间随机压力测试
```

完整回归主要由：

```text
用户本地人工验收
+
用户决定执行的完整测试
+
用户 push 后 GitHub Actions
```

承担。

Agent 最终报告必须写清：

1. 实际运行哪些最小测试；
2. 哪些 full tests 没运行；
3. 为什么本次最小验证足以覆盖修改范围。

除非用户明确要求 full test，否则默认采用最小验证。

## 开始任何 PR 前必须阅读

按顺序：

1. `/AGENTS.md`（本文件）
2. `/docs/PROJECT_RULES.md`（跨 PR 长期规则的唯一主入口）
3. `/README.md`（项目现状与上手方式）
4. `/docs/api.md`（接口契约）
5. 与当前任务直接相关的专题文档（如 `docs/development.md`、`docs/configuration-guide.md`、`docs/adventure-guide.md`、`docs/deployment.md`；**已有生产站点上线/迁移/回滚时必须优先读 `docs/production-release-runbook.md` 与 `docs/automated-release.md` 及 `scripts/deploy/` 中的正式脚本**）
6. 当前代码实现

`/docs/HANDOFF.md` 已不再是规则来源，只是指向归档材料的短说明。

如果文档、代码与用户当前明确需求冲突：

- 不要自行猜测；
- 以用户最新明确需求为最高优先级；
- 修正代码时同步修正文档；
- 不允许只改代码、不更新已经失效的长期规则。

## 长期知识与临时过程

### 必须进入仓库的长期知识

包括但不限于：

- 架构边界
- 领域模型
- 业务规则
- 数据模型原则
- Mastery / Wrong Book / Practice 等算法
- API 长期契约
- 分页规则
- 时区规则
- 数据库兼容要求
- 命名规范
- Agent 开发约定
- 跨 PR 仍然有效的限制

### 留在当前 PR / 当前对话的内容

包括：

- 排查过程
- 临时猜测
- 中间失败方案
- 临时 SQL
- 一次性日志
- 某次测试记录
- 环境问题
- 不会跨 PR 持续有效的实现过程

不要把所有聊天过程原样写入仓库。长期规则的固定内容写入
`docs/PROJECT_RULES.md`；按主题成篇的决策可以另建专题文档，
但必须在 `PROJECT_RULES.md` 中留下入口。

## PR 完成前必须检查

如果本 PR：

- 新增长期规则；
- 修改长期规则；
- 废弃旧规则；
- 改变 API 长期行为；
- 改变领域模型或架构边界；

则必须同步更新：

```text
docs/PROJECT_RULES.md
```

必要时再更新：

```text
README.md
docs/api.md
相关专题文档
```

规则变更时直接改写为当前有效规则，不要把旧规则留在同一份权威文档里
制造两套冲突说法；历史过程交给 Git history、PR 与 `docs/后端开发记录.md`。

## 最终交付报告

Agent 最终报告必须明确说明：

1. 本 PR 是否新增或修改长期规则；
2. 修改了哪些仓库长期文档；
3. 哪些内容仅属于本 PR 临时过程，没有写入长期文档；
4. 是否存在代码与文档仍不一致的地方。

## 硬性技术约束（摘要）

完整规则见 `/docs/PROJECT_RULES.md`，以下为最容易被违反的几条：

- 正式学习目录只有 `Book → Chapter → KnowledgePoint → Question`，Chapter 只有一级；
- `Subject / Section / Category` 不是正式目录层级，legacy 字段仅保留数据库兼容；
- Question 与 KnowledgePoint 是全局资源，World 不拥有、不复制题库；
- 子题（remedial subquestion）不进入 Mastery / 错题本 / Review Queue / 正式题数量；
- 错题是永久资产，答对不自动移除，只有用户手动移出；
- 业务日统一 `Asia/Shanghai`，不使用 UTC 自然日；
- 生产数据库必须兼容 MySQL 5.7，不使用 MySQL 8 专属语法；
- `V1–V22` migration 已冻结，新 schema 变更只能 `V23+` 或新的 Java Flyway migration；
- 正式分页列表统一每页 20 条，不提供 20/50/100 自选控件；
- 选项随机在 attempt 级完成并冻结答案映射，禁止前端运行时 `Math.random()` shuffle。