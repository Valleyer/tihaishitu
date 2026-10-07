# 万境书院项目长期规则

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
万境书院
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
确定后共享正式 Question Contract、Attempt Variant、grading、Wrong Book、
Evidence 与 Mastery。

普通正式训练的选题由四套独立策略负责：

```text
RANDOM / CHAPTER / KNOWLEDGE / WRONG
```

它们**不自动进入** Diagnosis / Training / Remedial，见
[`question-practice-policy.md`](./question-practice-policy.md)。
诊断与补救相关表、服务、管理端能力与历史数据只作为 Legacy / 未来重新设计能力保留
（见 §21.3），不再由普通正式训练触发。

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

普通正式训练（RANDOM / CHAPTER / KNOWLEDGE / WRONG）**不再自动进入**补救教学，
子题能力保留但不再由普通训练触发，见 §21.3。

### 6.1 正式题答案契约（Question Contract V2）

正式父题（`parent_question_id IS NULL`）**不再保存独立的“标准答案配置”**。
`question_resource.standard_answer_json` 已退场，若仍留库只作为 Legacy Remedial /
旧格式兼容列存在，Formal Parent 一律为 `NULL`。

客观题：

```text
single_choice
multiple_choice
true_false
```

唯一答案事实：

```text
question_resource_option.correct_option
```

综合题：

```text
solution
```

唯一内容事实：

```text
analysis_markdown
```

综合题不再拥有独立 reference / standard answer，只保存一份
`analysis_markdown`，其中包含答案、过程与解析。历史迁移后 `analysis_markdown`
内部可以带 `## 参考答案` / `## 解析` 小标题，但它仍然是同一个字段。

必须区分两件事：

```text
question_resource.standard_answer_json   正式 Question 配置层：退场
study_attempt.standard_answer_json       Attempt 判题快照：必须保留
```

正式客观题运行流程：

```text
correct_option
→ 后端运行时派生 answer（QuestionAnswerDeriver）
→ Attempt 动态排列选项
→ 同步 remap derived answer
→ 冻结 question_snapshot_json
→ 冻结 study_attempt.standard_answer_json
→ 判题
```

约束：

```text
QuestionDto.answer 若保留，只是 runtime-derived grading value，
                 不是 Question 持久化的配置答案
study_attempt.standard_answer_json 是创建 Attempt 时冻结的不可变判题事实，
                 后续修改题库不得回写历史 Attempt
solution Attempt 的 study_attempt.standard_answer_json 保存为 JSON null
作答前 DTO 不得暴露 correct_option，也不得暴露 derived standard
作答后客观题 result 可以返回 Attempt-specific standard 用于批卷
solution reveal / self-assessment 不返回 separate standard
true_false 的 correct option key 必须显式是 true 或 false；
                 非法 key 必须显式失败，禁止宽松解析成 false
```

正式题型的答案字段事实只有这一套；`Question Management`、Formal Question Pool、
Learning Browse、Catalog Formal projection、Learner Practice / World Formal path 与
`global-question-batch/v4` 都不得再读写 Formal Parent 的 `standard_answer_json`。

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

## 9. 知识、题库与 Study 职责

Hub 里“知识”和“题库”是两个不同的产品概念，不要混在一起：

```text
知识（技术 route 仍是 /books）
= Book → Chapter → KnowledgePoint 目录
= 单知识点详情、知识讲解、知识点专项

题库（/questions）
= 全平台所有 published Formal Parent Question 的只读浏览
= 独立筛选 / 搜索 / 分页 + inline preview + 完整题目详情 + 答案与解析
```

`/books` 只是技术 route 保持兼容，不是“题库”；不要为了展示名做 API、URL、
内部标识或测试的大规模重命名。

Study：

```text
攻克 Book
主要训练：Chapter Practice（intent chapter_drill）
```

Question Bank / 知识页：

```text
攻克单个 KnowledgePoint
主要训练：Knowledge Drill（intent knowledge_drill）
```

Chapter Practice 使用**固定确定性题序**：先按 Chapter 内 KnowledgePoint 的
`question_bank_knowledge.sort_order`，再按稳定 Source identity、`exam_year`、
`question_number` 自然排序与 `question_id` 兜底；跨 Session 持久 cursor，末尾 wrap，
不因为“到末尾”永久 complete。详见
[`question-practice-policy.md`](./question-practice-policy.md) §4。

二者共享：

```text
Question
KnowledgePoint
Mastery
Wrong Book
Evidence
```

不能做两套独立学习状态。Study 只展示已加入学习范围的 Book；知识目录也以当前
selected books 为准。

Chapter 入口的“可练知识点数”等于该 Chapter 内**存在至少一道正式父题**的知识点数
（core + auxiliary 都算，见 §5）。它**不再**随以下因素变化：

```text
依赖 readiness
今日是否已经答对
Review 是否到期
Mastery 高低
```

### 9.1 全平台题库浏览与学习范围解耦

全平台题库（`/questions`）是**全局只读浏览**，与 Learner 当前 selected Books 解耦：

```text
没有选择某本 Book 也可以在“题库”里浏览平台已发布 Formal Parent Question
题目详情同样不再要求该题属于当前 selected Books
```

但“浏览 ≠ 训练”：

```text
浏览题目不创建 Attempt
不影响 Mastery
不写 Wrong Book
不消耗 RANDOM 每日额度
不进入 Question Exposure
```

训练入口仍然受 Study Profile / Practice scope 约束：RANDOM / CHAPTER / KNOWLEDGE /
WRONG 的候选范围一律不放宽（见 §10 与
[`question-practice-policy.md`](./question-practice-policy.md) §9）。

全平台题库只展示：

```text
status = published
parent_question_id IS NULL
question_type ∈ single_choice / multiple_choice / true_false / solution
```

draft / rejected / archived / Remedial 子题一律不可浏览。

全平台题库的知识点标签**不做强跳转**：题目可能关联 Learner 尚未选择的 Book 下的
KnowledgePoint，跳 `/knowledge/{id}` 会 404。因此全局题库列表与题目详情里的知识点
标签默认只做展示；`KnowledgePage` / Practice 等已经确定学习范围的上下文继续使用
可点击标签。不要为此放宽 `KnowledgePage` 的 selected Books 访问边界。

### 9.2 Playability 与 Mastery Reward 分离

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

### 10.1 四套独立选题策略

正式训练固定为四套独立策略，各自独立实现，不做成巨型 if/else selector：

```text
RANDOM     Learner World / 寒门仕途普通随机正式题（KP-first + 每日硬去重 + oldest/wrong lane）
CHAPTER    章节练习（固定确定性题序 + 跨 Session 持久 cursor + 末尾 wrap）
KNOWLEDGE  知识点专项（Session 内随机不重复）
WRONG      单题错题重做 / 错题快练（Session 内随机不重复）
```

完整规则见 [`question-practice-policy.md`](./question-practice-policy.md)。要点：

```text
RANDOM 先选 target KnowledgePoint 再在该 KP 内选题，不再对整书题池直接 uniform random
RANDOM 同一 Learner × 同一 Asia/Shanghai 业务日同一 Question 最多出一次（发出即占额度）
CHAPTER / KNOWLEDGE / WRONG 做过的题不消耗 RANDOM quota，RANDOM 也不阻止它们练到同一题
四模式都不再根据 Mastery / readiness / Review due / preferred difficulty / dependency 筛题
```

`rounds` 表示“本轮最多完成多少道正式题”。现代 Learner World 取
`plannedRounds = min(activity rounds, 当天剩余可出的随机题数)`；
`plannedRounds = 0` 时开始阶段直接给清晰业务提示，不做“先启动 run 再报错”。
本轮每完成一道 Formal Parent 的 grading（correct / wrong / partial 都算）就推进一个 slot，
进度是 `completedFormalCount / plannedRounds`，不硬编码百分比，也不在答错后停在原地。

### 10.2 target KnowledgePoint 的稳定归属

从任何按 ID 发题的路径（仍然保留的 Book-level 题池 API、wrong_review 等）取题后，
仍只归属一个 target KnowledgePoint：

```text
该题关联 KnowledgePoint 中，先限定在当前 selected Book scope 内
→ 优先 relation_role = 'core'，按 sort_order、knowledge_point_id 取第一个
→ 没有 core 时取 auxiliary 中 sort_order 最小的一个
```

解析结果必须稳定，不随机，否则同一道题会在不同时间强化不同知识点。
RANDOM 策略则由 KP-first 直接冻结它选中的那个 KP，不重新解析。
两种路径都保证：一次 attempt 仍然只有一个 `targetKnowledgePointId`，**不会**因为
一题绑定多个 KP 就一次作答同时给全部 KP 加分（Mastery coverage 是 core + auxiliary，
但单次 Evidence 只有唯一目标）。

### 10.3 KnowledgePoint 专项、Chapter Practice 与 Wrong Drill

```text
KnowledgePoint 专项：当前 KnowledgePoint → 该 KP 关联的全部 Formal Question（core + auxiliary）→ Session 内未见 → 随机
Chapter Practice：限定当前 Book + Chapter，走固定确定性题序 + 持久 cursor + 末尾 wrap
Wrong Drill：active Wrong Book → 当前 selected Book scope → Session 内未见 → 随机
```

KNOWLEDGE 与 WRONG 都是 Session 内随机且不重复，候选耗尽即本轮完成，
新开 Session 重新洗牌。CHAPTER 的题序、跨 KP 去重、target KP 归属与 cursor 规则见
[`question-practice-policy.md`](./question-practice-policy.md) §4。

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
四个正式题型
当前训练上下文所属 Book / Chapter / KnowledgePoint 范围
本 Session / run 未见（RANDOM 另加“同一业务日同一题最多出一次”的硬去重）
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
但只作为保留的 Remedial / training 能力的软提示，普通四模式的选题一律不看它。
掌握上限按有效掌握度分档（未开始或低于 40 为 2、低于 70 为 3、低于 100 为 4、100 为 5），
`standard` 取目标难度与上限的较小值，`gentle` 再下调一级但不低于 1。

普通正式题答错后**不再**补救训练、不再 retry 同一道题：一次 grading 即完成一个正式 slot
（correct / wrong / partial 都推进），下一题由该模式自己的 selector 决定。
Remedial 子题能力继续保留，但普通正式训练不再自动进入。

`study_attempt` 仍是 Question Exposure 的事实来源，Learning Hub 的只读题目浏览
不创建 attempt，不计入 Exposure。全部候选都见过时不报错阻断，由调用方结束本轮。

---

### 10.5 真题来源与展示标签

Question Source 是全局独立资源。正式题优先通过：

```text
question_resource.source_id → question_source
```

绑定来源时，`canonical_name` 是不会随展示文案调整而改变的来源事实名称，
`display_name` 是当前用户界面使用的展示名称。实时 Question 页面统一按：

```text
question_source.display_name
→ question_resource.source_name（Legacy compatibility fallback）
→ 全服题库
```

读取来源；不得由 Hub、World 或前端各自拼接。Chapter 题序的 Source 分组只使用
`question_resource.source_id`（legacy 无 source_id 时回退 `source_type + source_name`），
**不得**使用可修改的 `display_name` 作为排序事实。
`question_resource.source_type / source_name`
暂时保留，绑定来源时同步写入来源类型与 `canonical_name` 兼容快照。`disabled` 来源不能用于
新的题目绑定，但既有绑定仍然可读。来源展示名变更只影响后续实时读取和新 attempt；
已经写入 `study_attempt.question_snapshot_json` 的历史来源 metadata 永不回写。
浏览器题目创建与编辑必须提交已存在的 `source_id`，不得通过兼容的类型与名称隐式创建来源；
只有 ADMIN 来源管理与 ADMIN 批量导入兼容路径可以创建来源。已有题目绑定的来源不得修改
`source_type`，但仍可修改 `canonical_name`、`display_name` 与 `status`；尚未绑定题目的来源可以改类型。

真题年份、科目和题号继续来自结构化字段：

```text
question_resource.exam_year
question_resource.subject_name
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

### 10.6 Question 搜索与默认排序

Question 的两个浏览入口（Management 题目管理 / 审核中心，以及全平台题库
`/questions`）使用**同一套**搜索解析与默认排序规则，避免同一份数据出现两种顺序。

#### 10.6.1 “年份-题号”结构化搜索
用户在关键词里输入的结构化题号必须能命中标准写法。正式规则：

```text
^\s*(\d{4})\s*[-—–]\s*(.+?)\s*$
```

即：

```text
2020-7 / 2020 - 7 / 2020—7   → year = 2020, displayNumber = 7
```

至少匹配：

```text
q.exam_year = 2020
AND (q.question_number = '7' OR q.question_number = '2020-7')
```

这是对普通 broad search 的**并列**命中方式（OR），不能与题干 / 来源 / 题号的
`LIKE` 做 AND，否则“`exam_year` + 纯题号”的标准写法反而被排除。
普通关键词（`极限`、`数学一`、`7`）继续走 broad search。

解析规则由共享的 `QuestionSearchQuery` 唯一实现；Management 与 Learning Browse
只共用解析结果，SQL WHERE 片段各自维护。

#### 10.6.2 默认排序

```text
1. 来源（用户可见的解析后名称）
2. exam_year
3. question_number 自然排序
4. question_id 稳定兜底
```

题号必须自然排序：

```text
1 < 2 < 7 < 10 < 22
```

历史写法 `question_number = "2020-7"`（`exam_year = 2020`）与 `"7"` 取同一个数字事实
7，因此它们落在同一个自然位置（两者之间由 `question_id` 稳定兜底）。剥离年份前缀
的条件必须与 `QuestionNumberFormatter` 一致：

```text
2020 + "7"       → 7
2020 + "2020-7"  → 7          只有题号确实带“相同 exam_year 前缀”时才剥离
2021 + "2020-7"  → 非数字      年份前缀不一致，不能剥掉 2020
2020 + "A-3"     → 非数字      绝不实现成“有连接符就取后半段”，否则 A-3 会变成 3
2020 + "21A" / "3(1)" → 非数字
```

非纯数字题号统一归入最后一档，只按 `question_id` 兜底，不得插进 1–22 的自然序列。
Unicode 破折号（`‐ ‑ ‒ – — ―`）在判断前缀之前先归一为 ASCII `-`。

禁止字符串排序出 `1, 10, 2`。排序键必须在 DB 级形成（分页排序不能只在取出一页之后
用 Java 排序），并且只用 MySQL 5.7 安全的能力：

```text
允许：CASE / SUBSTRING / LEFT / LOCATE / CHAR_LENGTH / TRIM / REPLACE / CONCAT / COALESCE / LPAD
禁止：Window Function、REGEXP / REGEXP_LIKE、依赖隐式类型转换的“字符串与字母比较”
```

不使用 `SUBSTRING_INDEX` 与 `CAST(... AS UNSIGNED)`：它们在生产 MySQL 5.7 可用，
但本仓库后端集成测试使用的 H2 MySQL 兼容模式不支持，会让 DB 级排序无法被测试覆盖。
不使用 `REGEXP`：MySQL 5.7 没有 `REGEXP_LIKE` 函数，两种引擎的正则方言也不一致。
排序规则由共享的 `QuestionNumberSort` 唯一实现，Management 与全平台题库必须复用；
它同时接收题号与 `exam_year` 表达式，否则无法判断“前缀是否同年份”。

全平台题库的分页 ID 查询只做 `question_resource LEFT JOIN question_source`（一对一），
Book / Chapter / Knowledge 都通过 EXISTS 子查询参与，不会复制 Question 行，因此使用
`SELECT q.id ... ORDER BY ... LIMIT/OFFSET`，**不用 `DISTINCT`**：`DISTINCT` 与
“ORDER BY 引用未出现在 SELECT list 的表达式”组合在真实 MySQL 5.7 严格 sql_mode 下有
报错风险。`totalElements` 继续使用 `COUNT(DISTINCT q.id)`。

审核中心也使用同一默认排序，不再以“更新时间优先”。

Management 题目列表与审核列表展示 `displayQuestionNumber`（由
`QuestionNumberFormatter.display(questionNumber, examYear)` 生成），不要在前端拼
`examYear + "-" + questionNumber`（会把历史写法显示成 `2020-2020-7`）。编辑器输入框
继续使用并保存原始 `questionNumber`，display 值不回写数据库。

#### 10.6.3 Management 题目列表筛选

普通题目管理页（`reviewOnly=false`）按**题型**筛选，请求参数是 `questionType`，
不再发送 `status`。审核中心（`reviewOnly=true`）继续固定：

```text
status = pending_review
```

因此后端 `status` filter 必须保留，不能因为普通页取消状态筛选而删除。

---

## 11. 选项随机

正式客观题（Single Choice / Multiple Choice / True False）：

```text
每次创建新的 study_attempt 时生成一次稳定 permutation
```

同时必须：

```text
重映射 derived standard answer
并冻结进 question_snapshot_json / study_attempt.standard_answer_json
```

```text
刷新同一个 attempt：顺序不能变化
再次做同一道 Question：可以重新随机，并尽量避免与上次完全相同
```

判断题同样参与排列：`true` / `false` 的选项文字交换时，boolean standard 必须同步 remap。

Hub Practice 与 Learner World / 副本共用同一套 variant / remap 规则
（`QuestionAttemptVariantService`），不得各自实现一套：

```text
correct_option
→ runtime derived answer
→ variant（排列 + remap）
→ study_attempt 冻结 standard
→ 判题
```

Legacy `/games/**` 没有 Learner，保持既有兼容行为，不在本规则范围内。

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

正式题型与答案内容事实（见 §6.1）：

```text
single_choice / multiple_choice / true_false → 唯一答案事实 option.correct_option
solution                                     → 唯一内容事实 analysis_markdown
```

形态约束：

```text
single_choice   : 2–6 个选项，恰好 1 个正确
multiple_choice : 2–6 个选项，至少 2 个正确
true_false      : 选项键固定为 true / false，恰好 1 个正确
solution        : 不提供客观题选项，analysis_markdown 非空
```

`QuestionContractValidator.validateFormal` 是运行期契约的唯一实现；迁移与导入
必须与它保持同一套合法性标准，不允许出现“迁移放行、应用启动后判为非法”。

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
global-question-batch/v4    题目批次 canonical
global-question-batch/v3    仅 compatibility
global-question-batch/v2    仅 compatibility
remedial-question-generation/v1
remedial-question-batch/v1
knowledge-guide-generation/v1
knowledge-guide-batch/v1
```

题目批次（`global-question-batch/v4`）规则：

```text
客观题：options[].correct 是唯一答案，禁止 standardAnswer
综合题：analysis 是唯一内容，禁止 standardAnswer
```

v3 / v2 compatibility：

```text
客观题：standardAnswer 只做一致性校验，最终不持久化
综合题：standardAnswer + analysis → SolutionAnalysisComposer → analysis_markdown
最终 Formal Parent 的 standard_answer_json 一律为 NULL
```

管理后台题目导入 UI 允许 `v4` 与 `v3`，并把 `v4` 作为推荐格式；
v3 必须真的可以提交，不能只在文案里写“兼容”。详细生成规范见
[`docs/题库生成提示词.md`](./题库生成提示词.md)。

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
V1–V22 已冻结
```

以后：

```text
禁止修改历史 migration
新 schema 变更只能 V23+
或新的 Java Flyway Migration
```

`V13__flatten_book_chapters.sql` 属于既有发布历史，保持原样。

`V20__formal_question_answer_contract.java` 是 Question Contract V2 的结构收口，
只做确定性操作，不猜内容：

```text
校验 published Formal 题目仍满足正式契约（与 validateFormal 对齐）
把旧综合题 standard answer 并进 analysis_markdown
再次校验 published Formal 综合题 analysis 非空，否则 fail 并指出 Question ID
standard_answer_json 改为可空
Formal Parent 的 standard_answer_json 清为 NULL
Remedial 子题的 legacy standard_answer_json 保持原样
```

迁移失败时必须报告具体 Question ID，不允许绕过迁移或直接删数据。

`V21__practice_selection_draw_mode.sql` 是 Practice Selection V2 的结构补充，只做确定性
加法，不猜历史来源：

```text
study_attempt.draw_mode   VARCHAR(24) NULL
study_attempt.draw_reason VARCHAR(24) NULL
索引 (learner_id, draw_mode, created_at, question_id)
旧 Attempt 的 draw_mode / draw_reason 保持 NULL，不做回填
```

它必须兼容 MySQL 5.7；`draw_mode` 由服务端 selection strategy 写入，前端不得提交。
完整取值与语义见 [`question-practice-policy.md`](./question-practice-policy.md) §8。

`V22__question_report.sql` 新增 `question_report`。Learner 只提交 Attempt ID、问题类型和可选备注；
服务端必须从属于当前 Learner 的 Formal Parent Attempt 派生 learner / question。每个
`Learner × Attempt` 最多一条反馈。REVIEWER / ADMIN 可分页查看并标记 `resolved` 或
`dismissed`；反馈不改变 Attempt、Mastery、Wrong Book 或选题状态。Learner / Attempt
删除时反馈级联清理，Question 仍按全局资产规则禁止被反馈记录级联删除。

正式训练支持独立服务端动作“我没思路”：objective 与 solution 都直接 graded wrong，
submitted answer 保存 JSON `null`，正常写 Wrong Book、Question Mastery 与 Evidence，并推进
一个正式题 slot；不得伪造选项，也不得进入 Diagnosis / Remedial。

repeatable Activity 的 canonical runtime 只有 0 分档与一个 pass reward 档，100 分不再叠加
奖励。task 继续使用 `completionReward` 保留关键 item / flag / title。World 题面把知识点、
真题标签、题号与来源放在同一 metadata ribbon，冻结来源只显示一次。

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

### 21.3 保留但已停用的自动诊断 / 补救链路

Practice Selection V2 之后，普通正式训练（RANDOM / CHAPTER / KNOWLEDGE / WRONG）
不再自动调用：

```text
DiagnosticLearningService.handleGradedAttempt
learner_diagnosis_session / learner_diagnosis_dependency
Remedial 子题与 retry parent
```

下列内容**全部保留**，因为诊断以后会重新设计，不要在无关 PR 中删除或顺手清理：

```text
learner_diagnosis_session / learner_diagnosis_dependency 表与历史数据
DiagnosticLearningService / DiagnosticLearningStore
RemedialQuestionStore 与管理端补救题导入导出
AdaptiveStudyPlanner / AdaptiveSchedulingPolicy / Mode.TRAINING
KnowledgeQuestionPoolStore.candidatesForBooks 等保留的题池查询 API
```

历史 diagnosis / remedial 数据仍然必须可读；旧未完成 diagnosis 在现代流程中被标记为
`abandoned`，不阻塞下一题。

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
