# 万境求知项目长期规则

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
万境求知
```

主学习中枢：

```text
万境中枢
```

品牌名与技术身份分离：改名只调整展示名，不重命名下列稳定技术标识。

历史技术名称继续保留，不为了品牌统一做大规模重命名：

```text
仓库名        tihaishitu
本地目录      E:\题海仕途
Spring 技术名 tihaishitu-backend
数据库 schema tihaishitu
Java package  cn.tihaishitu
World ID      ancient-official（寒门仕途）
API 路由      不变
数据库表名     不变
Flyway migration 文件名  不变
UUID          不变
兼容 localStorage key  不变
```

旧展示名“知境 / 知境中枢”已废弃；`docs/archive/**` 与明确的
历史记录保留旧品牌作为历史事实，不做机械替换。

产品结构：

```text
Learner
├─ 万境中枢 Learning Hub
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

一个 KnowledgePoint 的**正式题集合**定义为：

```text
与该 KnowledgePoint 存在 question_resource_knowledge 关系的
所有 published Formal Parent Question
= core + auxiliary（按题目 ID 去重）
```

`relation_role` 只用于知识标签主次显示与诊断 / 内容解释，**不决定**：

```text
题目能不能做
是否进入该 KnowledgePoint 的 Mastery 分母
```

UI 题数、Mastery 分母与专项候选必须共用同一个覆盖口径，统一由
`KnowledgeQuestionCoveragePolicy` 提供 SQL 片段。禁止再出现“页面显示 3 道、
Mastery 分母只算 core 的 2 道”这类多套口径。

若一题

```text
Q → K1 core
Q → K2 core
```

则对 Learner 而言存在两个独立槽位：

```text
Learner + K1 + Q
Learner + K2 + Q
```

一次 attempt 仍然只有一个 `targetKnowledgePointId`：一题绑定多个 KnowledgePoint
**不会**在一次作答中同时给所有 KnowledgePoint 加分。

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
Knowledge Mastery = 当前所有正式父题槽位分数之和 / 当前正式父题总数
正式父题总数 = 与该 KnowledgePoint 有关系的 published Formal Parent Question
             = core + auxiliary（按题目 ID 去重，见 §5）
```

口径必须唯一：知识点页面显示的“相关正式真题”数量、Mastery 分母与专项候选
必须来自同一份覆盖口径（`KnowledgeQuestionCoveragePolicy`）。

例如某 KnowledgePoint 关联 3 道正式父题：

```text
Q1 core
Q2 core
Q3 auxiliary
```

只做了 1 道且第一次正确（该题 slot = 30）：

```text
Knowledge Mastery = 30 / 3 = 10.0%
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

### 8.5 错题练习的两个 intent

```text
wrong_review  用户从错题本点某一题，只重做这一题（sourceQuestionId 必填）
wrong_drill   快速练习错题：随机连续刷 active 错题
```

`wrong_drill` 行为：

```text
只读取 learner_wrong_question.status = 'active'
排除下架 / 非 Formal 题
限制在当前 selected Books 覆盖范围
Session 内不重复
全部做完后本轮结束
0 道 active 错题时入口禁用并给出友好提示，不返回 500
```

永久错题规则在快练中不变：

```text
快速练习中答对 ≠ 自动移出错题本
```

只有用户手动移出才 `removed`。`wrong_review` 与 `wrong_drill` 都计入
Statistics 的错题练习作答统计（`wrongReviewAttempts`）。

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
selected books 为准。

Chapter 入口的“可练知识点数”等于该 Chapter 内**存在至少一道正式父题**的知识点数
（core + auxiliary 都算，见 §5）。它**不再**随以下因素变化：

```text
依赖 readiness
今日是否已经答对
Review 是否到期
Mastery 高低
```

### 9.1 Playability 与 Mastery Reward 分离

必须区分：

```text
能不能练
```

和：

```text
这次作答能不能增加 Mastery
```

今天已经答对过一道题：

```text
仍然可以再次练
但同一业务日后续正确不再获得 Mastery 奖励
```

禁止再用：

```text
今天答对过，所以不可练
等 Review 到期才能重新开放
已掌握题禁止练
```

只要当前训练上下文里存在正式题，用户就能练。Knowledge Drill 不得因为今日做过或
Review 未到期返回“当前没有待练题”。

---

## 10. 正式题抽取

### 10.1 Book-level：World / 副本 / 自由训练的题池

World / 副本 / Book-level 自由训练统一使用 **Book Question Pool**：

```text
Selected Book(s)
→ 这些 Book 下全部 active KnowledgePoint
→ 所有与这些 KnowledgePoint 有关系的 published Formal Parent Question
→ core + auxiliary 都算覆盖
→ 按 question_id DISTINCT 去重
→ 排除当前 run 的 seenQuestionIds
→ 在剩余 Question 中直接等概率随机
```

关键约束：

```text
不先选 KnowledgePoint 再抽题
同一道题关联多个 KP、或同时属于多本 selected Book，都只出现一次
不会因为“知识点数量少于 rounds”阻止副本开始
```

`rounds` 表示“本轮最多完成多少道正式题”，不表示“必须预先准备多少个不同
KnowledgePoint”。Formal Question 总量少于 `rounds` 时，本轮做完全部题目即自然完成，
不为了凑满轮数而立刻重复出题。

### 10.2 target KnowledgePoint 的稳定归属

Book-level 抽到题后仍只归属一个 target KnowledgePoint：

```text
该题关联 KnowledgePoint 中，先限定在当前 selected Book scope 内
→ 优先 relation_role = 'core'，按 sort_order、knowledge_point_id 取第一个
→ 没有 core 时取 auxiliary 中 sort_order 最小的一个
```

解析结果必须稳定，不随机，否则同一道题会在不同时间强化不同知识点。
一次 attempt 仍然只有一个 `targetKnowledgePointId`，**不会**因为一题绑定多个 KP
就一次作答同时给全部 KP 加分（Mastery coverage 是 core + auxiliary，但单次 Evidence
只有唯一目标）。

### 10.3 KnowledgePoint 专项与 Chapter Practice

```text
KnowledgePoint 专项：当前 KnowledgePoint → 该 KP 关联的全部 Formal Question（core + auxiliary）→ Session 内未见 → 随机
Chapter Practice：继续限定当前 Book + Chapter，保持自己的轮次推进实现
Wrong Drill：active Wrong Book → 当前 selected Book scope → Session 内未见 → 随机
```

三者都直接随机 Question，不先随机 KnowledgePoint。Chapter Practice 不在
Book-level 改造范围内，不要顺手重写。

### 10.3.1 Legacy `/games/**` 兼容路径

Legacy `/games/**` 没有 Learner，**不参与** Book-level 题池，继续按启动时冻结的
KnowledgePoint 顺序出题：

```text
planKnowledgePoints(legacyBookScope, rounds)
→ knowledgePointIds
→ 按 knowledgePointIds[knowledgePointIndex] 取当前 KP
→ 只在当前 KP 的题里抽题
```

因此 Legacy 的 `plannedRounds` 只能由它自己计划出的知识点数量决定
（`legacyPlan.knowledgePointIds().size()`），**绝不能**引用 Book-level 题池的题数：

```text
Legacy 文集可能只通过 legacy_knowledge_map 归属知识点
Book 题池主要走 question_bank_knowledge
两者不一致时（Legacy plan 有 N 个知识点、Book 题池为 0）
把 plannedRounds 压成 1 会让旧游戏第 1 题答完就提前结算
```

Learner World / 副本与 Legacy 的轮数计算必须在各自的 if 分支内完成，不要共用同一个
`plannedRounds` 表达式。

### 10.4 正式题发题条件

正式题发题条件只剩：

```text
status = published
parent_question_id IS NULL
正式题型
当前训练上下文所属 Book / Chapter / KnowledgePoint 范围
本 Session / run 未见
```

禁止再用以下因素决定一题“能不能被抽到”：

```text
dependency readiness
Mastery
Review due
preferred difficulty
exposure soft ordering
不同 KnowledgePoint 数量
```

`difficulty` 只是题目元数据；`AdaptiveSchedulingPolicy.preferredDifficulty` 仍然存在，
但只作为 Remedial / training 出题的软提示，不阻止正式题被抽中。掌握上限按有效掌握度
分档（未开始或低于 40 为 2、低于 70 为 3、低于 100 为 4、100 为 5），
`standard` 取目标难度与上限的较小值，`gentle` 再下调一级但不低于 1。

Remedial 子题内部若仍需要低难度策略可以保留，因为它不是普通 Formal Question 抽取。
正式题答错后的补救训练继续练同一道题，不换题、不换知识点。

`study_attempt` 仍是 Question Exposure 的事实来源，Learning Hub 的只读题目浏览
不创建 attempt，不计入 Exposure。全部候选都见过时不报错阻断，由调用方结束本轮。

---

### 10.5 真题来源与展示标签

真题的事实来源是结构化字段：

```text
question_resource.exam_year
question_resource.subject_name
question_resource.source_name
question_resource.question_number
```

展示标签由 API 动态生成，**不为显示文字新增 question_tag 表**：

```text
数学一 + 2021 → 2021年考研数学一真题
408 + 2024    → 2024年408考研真题
```

所有正式做题页（KnowledgePoint 专项、Chapter Practice、单题错题重做、错题快速练习、
World / 副本与其他共享 Question Engine 的页面）都要显示：

```text
sourceName
examYear
questionNumber
examLabel
knowledgePoints[{id, name, role}]（core / auxiliary 都显示）
```

这些元数据在发题时冻结进 `question_snapshot_json`，同一 attempt 刷新后保持稳定。
前端不得通过字符串猜来源。

**年份不是 KnowledgePoint。** 不要创建 `2021年` / `2022年` 这类 KnowledgePoint，
否则会污染 Mastery。年份事实源只有 `question_resource.exam_year`。
未来若做“历年真题 / 年份分组”，优先设计 Question Collection / Exam Paper / Year grouping；
如果未来要做“基础书达标才能进入真题书”，也应实现为 **Book → Book prerequisite**，
不得恢复 Question 级 KnowledgePoint prerequisite gating。

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

真题 metadata 整理（`exam_year` 回填、408 `source_name` 补年份前缀）属于数据维护，
不是 schema 变更，使用幂等脚本 `scripts/normalize-exam-metadata.sql` 执行，不新增 migration。
`exam_year` 只做确定性回填：已有值不覆盖，只对空值按 `question_number` 的
`YYYY-` 前缀或 `source_name` 开头的四位年份推断；无法确定的一律不猜。

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

Agent 默认执行**最小必要测试**（完整规则见 [`/AGENTS.md`](../AGENTS.md) 的
“Agent 测试边界”），验证范围与改动范围匹配，不要求每轮都跑全量：

- 纯文档 / 文案改动：不跑测试；
- 前端单个组件 / 工具函数：跑直接相关 targeted test；
- TypeScript 类型或构建配置变化：运行必要的 `npm run build`（含内容校验与 `tsc -b`）；
- 后端单个 Store / Service / Controller：运行直接相关 Maven test class；
- SQL / migration：运行直接相关的数据库验证或 targeted integration test
  （本仓库后端测试使用 H2 的 MySQL 兼容模式，不会改动本机 MySQL）；
- 只有跨多个核心领域的高风险算法修改，才扩大测试范围。

完整回归主要由用户本地人工验收、用户决定执行的完整测试，以及用户 push 后的
GitHub Actions 承担；除非用户明确要求 full test，否则默认采用最小验证。
Agent 最终报告必须写明实际跑了哪些最小测试、哪些 full tests 未运行，以及
为什么本次最小验证足以覆盖改动范围。

生产部署、数据库迁移与发布 / 回滚流程见 [`docs/deployment.md`](./deployment.md)。
