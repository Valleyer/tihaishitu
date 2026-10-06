# 知境项目长期规则

> 本文件是整个项目**跨 PR 长期规则的唯一主入口**。
>
> 它不是开发日志、PR 总结、历史时间线或 Agent 交接流水账。
> 它只维护**当前仍然有效的长期规则**。
>
> 规则变更后直接改写为当前有效规则，不要把旧规则留在本文件里制造冲突。
> 历史过程交给 Git history、PR 与 `docs/后端开发记录.md`。
>
> 开发流程与文档纪律见根目录 [`/AGENTS.md`](../AGENTS.md)。

维护约定：

- 任何预计跨 PR 继续有效的结论，必须写进本文件或本文件指向的专题文档；
- 只存在于聊天、PR 评论或某次 Agent 回复里的结论不算项目规则；
- 本文件与代码冲突时，以代码为当前事实，但必须立即修正本文件，不能放任两套说法并存。

---

## 1. 项目定位

项目中文名：

```text
知境
```

历史技术名称继续保留，不为了品牌统一做大规模重命名：

```text
仓库名        tihaishitu
Java package  cn.tihaishitu
World ID      ancient-official（寒门仕途）
```

产品结构：

```text
Learner
├─ 知境 Learning Hub
│  ├─ Study
│  ├─ Progress
│  ├─ Statistics
│  ├─ Question Bank
│  └─ Wrong Book
└─ Worlds
   └─ 寒门仕途
```

Hub 与 World：

```text
共享学习资源与 Mastery
但不是同一个产品层
```

具体边界：

```text
World 不拥有 Question
World 不拥有 KnowledgePoint
World 不复制题库
Hub 不属于 World
```

World 负责学习体验与游戏体验，Hub 负责学习进度与内容组织；两者在 target
确定后调用同一套 Question Pool、rotation、grading、Evidence、Diagnosis、
Training 与 Mastery 更新逻辑。

---

## 2. 正式学习目录

固定结构，只有这一套正式目录：

```text
Book
→ Chapter
→ KnowledgePoint
→ Question
```

正式产品层级中**没有**：

```text
Subject
Section
Category
```

`global_knowledge_point.subject_name / section_name / chapter_name` 属于 legacy
兼容字段，可以继续留在数据库兼容旧数据，但**不得**作为新的用户目录、路径、
进度或最近学习展示依据。正式用户路径优先来自：

```text
question_bank
question_bank_chapter
question_bank_knowledge
```

---

## 3. Chapter 只有一级

正确：

```text
数学一
→ 多元函数微分学
→ KnowledgePoint
```

错误（重新引入 Section）：

```text
数学一
→ 高等数学
→ 多元函数微分学
→ KnowledgePoint
```

也错误（缺少 Chapter）：

```text
数学一
→ KnowledgePoint
```

“高等数学 / 线性代数 / 概率论与数理统计”如果以后需要作为独立学习范围，
应创建独立 `Book`，而不是重新增加一层目录。

数学一当前为 22 个正式 Chapter（高数 8、线代 6、概率统计 8），例如
`M1-H05 多元函数微分学` 必须真实存在，不能被合并或删除。

---

## 4. Question 与 KnowledgePoint 是全局资源

禁止：

```text
Book owns Question
World owns Question
World owns KnowledgePoint
```

Question 与 KnowledgePoint 都是全局资源，通过关系表建立联系，同一道 Question
可被多个 Book / KnowledgePoint 复用。**不要复制 Question 内容来实现共享。**

Book：

```text
负责组织 KnowledgePoint
```

World：

```text
负责学习体验 / 游戏体验
```

---

## 5. Question 与 KnowledgePoint 多对多

一题可以绑定多个知识点。关系角色：

```text
core
auxiliary
```

Mastery 只按 `core` 计入对应知识点的正式熟练度；`auxiliary` 不进入该知识点的
熟练度分母。若一题

```text
Q → K1 core
Q → K2 core
```

则对 Learner 而言存在两个独立槽位：

```text
Learner + K1 + Q
Learner + K2 + Q
```

---

## 6. Formal Question 与 Remedial SubQuestion

正式父题：

```text
parent_question_id IS NULL
```

子题：

```text
parent_question_id IS NOT NULL
derivation_type = remedial_step
```

子题只用于：

```text
拆解父题
辅助理解
错误后的补救教学
```

子题**不进入**：

```text
Mastery
Wrong Book
Review Queue
正式题数量
正式题统计
```

即：子题不产生正式学习证据、不作为普通随机题、不计入分母、不进入错题本。

---

## 7. Mastery V3

模型版本：

```text
v3-question-reinforcement
```

业务粒度：

```text
Learner
× KnowledgePoint
× Formal Parent Question
```

每道正式父题拥有独立槽位 `questionScore`：

```text
0 ～ 100
```

### 7.1 每日首答

业务日统一 `Asia/Shanghai`（见 §14）。每道正式父题、每个业务日：

```text
只有当天第一次 graded 正式作答有资格决定当天奖励
```

当天第一次是 `correct`：

```text
历史第一次有效正确        → questionScore = 30
以后其他业务日第一次正确  → questionScore += 7
上限                      → 100
```

当天第一次是 `wrong` / `partial`：

```text
当天 +0
当天后续即使重新答对仍 +0（子题、重做父题都不再奖励）
```

第二天重新判断。

### 7.2 错误不直接扣 Mastery

禁止：

```text
wrong   → -X
partial → -X
```

错误只代表“当天没有获得熟练度奖励”。Mastery 下降的唯一来源是自然衰减。
若某次错误恰好触发 lazy decay，那是自然衰减，不是错误扣分，代码与文案必须区分。

### 7.3 自然衰减

每个题目槽位独立衰减：

```text
每完整 3 天无有效强化 → -1
下限                  → 0
```

采用 `lazy settlement`：在读取 / 作答 / 统计时结算，无需每日定时任务。
不得改成整个 KnowledgePoint 统一衰减（否则只刷一道简单题即可保护整个知识点）。

### 7.4 KnowledgePoint 熟练度

```text
Knowledge Mastery = 当前所有正式 core 父题槽位分数的平均值
```

新增正式题：

```text
新 slot = 0
分母扩大
Knowledge Mastery 自动下降
```

### 7.5 100% 彻底掌握

当 KnowledgePoint 当前所有正式题槽位均为 `100`：

```text
100%
彻底掌握
UI 使用金色奖励状态
100% 时停止自然衰减
```

新增正式题后自动退出 100% 并重新参与衰减。被冻结期间不追溯补扣历史衰减。

### 7.6 用户熟练度等级

统一四档：

```text
0.0–29.9   尚未稳固
30.0–69.9  基本掌握
70.0–99.9  熟练掌握
100        彻底掌握
```

不再使用“未掌握 / 学习中 / ready”等旧文案作为用户可见档位。

---

## 8. 永久错题本

错题本不是临时待重做队列，而是**长期学习资产**。

Formal Parent Question 出现 `wrong` / `partial` 即写入：

```text
learner_wrong_question
```

### 8.1 答对不能自动移除

禁止：

```text
wrong → later correct → 自动消失
```

正确行为：

```text
wrong / partial → active
后来答对        → 仍 active
用户手动移出    → removed
以后再次 wrong / partial → 重新 active
```

### 8.2 Wrong Book 与 Mastery 独立

允许同时存在：

```text
KnowledgePoint = 100%
Wrong Book 中仍有该题的历史错题
```

只有用户自己确认“已经掌握”才手动移除。不得实现“Mastery 达标自动清错题”。

### 8.3 题目下架

Question 被 archive / unavailable 时：

```text
错题历史不能自动删除
只需标记“当前不可练”
```

错题卡返回 `available` 与 `unavailableReason`（`out_of_scope` /
`question_unavailable` / `knowledge_unavailable`），离开学习范围时提前禁用入口，
但记录永久保留，用户仍可手动移出。

### 8.4 后台删除题目

已存在于 `learner_wrong_question` 的题目（`active` 或 `removed` 都算）**禁止物理删除**，
批量删除返回 409 并引导改用下架 / 归档。用户学习历史优先于后台清理便利，
外键不得改成 `ON DELETE CASCADE`。

---

## 9. Study 与 Question Bank 职责

Study：

```text
攻克 Book
主要训练：Chapter Practice（intent chapter_drill）
```

Question Bank：

```text
攻克单个 KnowledgePoint
主要训练：Knowledge Drill（intent knowledge_drill）
```

Chapter 内按 KnowledgePoint 顺序轮次推进：第一轮每章知识点各一题，还有待练内容时
再从第一个知识点开启下一轮，而不是在同一知识点一次刷十道。

二者共享：

```text
Question
KnowledgePoint
Mastery
Wrong Book
Evidence
```

不能做两套独立学习状态。Study 只展示已加入学习范围的 Book；题库也以当前
selected books 为准。章节入口的“当前可练知识点数”必须按 Learner 实时状态计算
（scope + core 正式题 + 依赖满足 + 未见 / 上一业务日答对 / 复习到期），不得用目录静态
计数冒充，避免“按钮可点、点击才报错”。

---

## 10. Question Rotation

当前 run：

```text
seenQuestionIds 是硬排除
```

跨 run 的排序原则：

```text
曝光更少
→ 与 preferred difficulty 更接近
→ 更久未见
→ 完全并列时随机
```

难度只是 **soft preference**：

```text
difficulty 只参与排序
不形成 hard difficulty bucket
```

禁止恢复“只在同难度题中轮换”“nearestDifficultyBucket 硬过滤”这类旧逻辑，
否则其他难度的题可能永远抽不到。Exposure 是软排序，不设置固定 cooldown 或
永久 blacklist；全部题都见过或题库只有一题时仍允许旧题再次出现。
Learning Hub 的只读题目浏览不创建 attempt，不计入正式 Exposure。

---

## 11. 选项随机

Single Choice / Multiple Choice：

```text
每次创建新的 study_attempt 时生成一次稳定 permutation
```

同时必须：

```text
重映射 standard answer
并冻结进 question_snapshot_json / standard_answer_json
```

```text
刷新同一个 attempt：顺序不能变化
再次做同一道 Question：可以重新随机
```

禁止 React 前端运行时临时 `Math.random()` shuffle，导致答案映射不稳定。

---

## 12. 正式题型

正式题型仅：

```text
single_choice
multiple_choice
true_false
solution
```

正式题**不支持** `blank`。原填空题必须先改造成可判定的正式题型，才能进入正式题库。
判断题、单选、多选继续支持，选项每次发卷打乱。

---

## 13. Knowledge Guide

知识点讲解的正式数据源：

```text
knowledge_point_guide
```

不要重新依赖 `global_knowledge_point.explanation` 作为权威讲解字段。

支持并必须通过前端统一 `RichText` 渲染：

```text
Markdown
LaTeX
列表
表格
代码块
引用
```

题干、解析、错题内容、Knowledge Guide 等一律走 `RichText`，不得直接
`<p>{rawMarkdown}</p>`，否则 `$f(x)$`、`\int`、`\frac` 会作为乱码源码显示。
管理后台支持知识点导出给 AI、AI 生成 Markdown + LaTeX、批量导入 Guide。

---

## 14. 业务时区

所有“每日”产品语义统一：

```text
Asia/Shanghai
```

包括：

```text
Mastery
每日首答
Progress
Statistics
Review 业务日
```

不要重新使用 `UTC natural day` 作为业务规则。

---

## 15. 分页规则

正式分页列表统一：

```text
20 条 / 页
```

不要提供 `20 / 50 / 100` 这类用户自选 page-size 控件，也不要出现每页 30、
每页 50、每页 5 的新正式列表。内部 autocomplete / lookup 的小查询不受此限制。

---

## 16. 登录与身份

统一账号：

```text
learner_account
```

管理角色只是附加权限，不是第二套账号系统：

```text
CONTRIBUTOR
REVIEWER
ADMIN
```

Learner Session：

```text
HttpOnly
SameSite Cookie
```

本地前端必须通过同源 `/api/v1` 代理后端；不要把 `VITE_API_BASE_URL` 改成跨 Origin
的绝对地址，否则 Origin 与 Session Cookie 站点分离，登录态会失效。

---

## 17. Import / Export 格式

已发布格式：

```text
global-knowledge-batch/v2
global-question-batch/v3
remedial-question-generation/v1
remedial-question-batch/v1
knowledge-guide-generation/v1
knowledge-guide-batch/v1
```

新 schema 不要再把 `subject` 当正式业务边界。legacy 的 `subject_name` /
`section_name` / `chapter_name` 可以继续留库兼容旧数据，但新逻辑不要依赖。

---

## 18. 数据库兼容

生产数据库目标：

```text
MySQL 5.7 compatible
```

禁止直接依赖：

```text
WITH RECURSIVE
ROW_NUMBER()
Window Function
JSON_TABLE
其他 MySQL 8 only 特性
```

测试不能只看 H2。复杂迁移若必须遍历树，优先使用 Java Flyway Migration。
任何新增 SQL 都要在 MySQL 5.7 上可执行。

---

## 19. Migration 规则

已发布：

```text
V1–V18 已冻结
```

以后：

```text
禁止修改历史 migration
新 schema 变更只能 V19+
或新的 Java Flyway Migration
```

`V13__flatten_book_chapters.sql` 属于既有发布历史，保持原样。

---

## 20. 长期知识维护规则

### 20.1 一个 PR 一个 Agent 对话

```text
一个 PR = 一个独立 Agent 对话
```

PR 合并后下一 PR 新开对话。新 Agent 从：

```text
最新 main
+ 仓库正式文档
```

重新获取上下文，而不是依赖上一轮聊天。

### 20.2 跨 PR 规则必须进仓库

只要一个结论预计跨 PR 继续有效，就不能只存在于聊天、PR 评论或 Agent 最终回答，
必须同步到正式仓库文档；长期规则统一收敛到本文件。

### 20.3 临时过程留当前对话

以下不要求进入长期文档：

```text
临时排查过程
失败尝试
猜测
临时 SQL
一次性日志
某轮测试次数
环境问题
当前 PR 的实现过程
```

### 20.4 PR 完成前文档检查

Agent 最后必须判断“本 PR 是否改变了长期规则”。如果是，必须先同步长期文档，
再标记 complete。

---

## 21. 尚未清理的遗留债（长期 TODO）

以下为已知、明确保留、**尚未处理**的债务。不要在无关 PR 中顺手扩大范围：

### 21.1 Legacy Subject / Section Cleanup

部分 API、DTO 与管理后台 UI 仍存在：

```text
subject
section
subject_name
section_name
```

这是历史兼容债，用户端正式路径已改用 `Book → Chapter`；剩余清理单独开 PR。

### 21.2 World Legacy Library Cleanup

```text
frontend/src/components/Library.tsx
```

是寒门仕途“藏书阁”的真实入口（非死代码），仍使用旧 World Bank 模型：
按“科目 / 分类”筛选、显示 `subject · category`、分页每页 5 条。与现行正式原则冲突，
但涉及 World legacy 模型，需单独任务重构，不在稳定化 PR 中处理。

---

## 22. 验证要求

改动的验证范围与改动范围匹配，不要求每轮都跑全量：

- 后端行为改动：至少跑受影响测试，合并前跑 `.\mvnw.cmd test` 全量；
- 前端行为改动：`npm run test` 与 `npm run build`（含内容校验与 `tsc -b`）；
- 纯文档改动：不要求重跑测试，但最终报告必须注明“本轮仅文档治理，无业务代码变化”。
