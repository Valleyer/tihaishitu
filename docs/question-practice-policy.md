# 正式训练选题策略（Practice Selection V2）

> 本文件是「正式训练如何选下一道题、答完以后如何推进」的专题长期文档。
>
> 主入口见 [`PROJECT_RULES.md`](./PROJECT_RULES.md) §10；本文件是被它指向的完整策略说明。
> 规则变更后直接改写为当前有效规则，不保留旧说法。

---

## 1. 四套独立选题策略

正式训练固定为四套独立策略，对应四种产品入口：

```text
RANDOM     Learner World / 寒门仕途普通随机正式题
CHAPTER    章节练习（intent chapter_drill）
KNOWLEDGE  知识点专项（intent knowledge_drill）
WRONG      单题错题重做（intent wrong_review）/ 错题快练（intent wrong_drill）
```

四者共享同一套底层能力：

```text
Formal candidate 基础查询
Question Contract V2（答案事实 + attempt 快照）
Attempt Variant（选项排列 + derived answer remap）
Grading
Wrong Book
Mastery V3 / Evidence
```

但选题与推进逻辑必须**分别独立实现**，不允许做成一个巨大 selector 加大量 if/else：

```text
RandomPracticeSelector     RANDOM
ChapterPracticeSelector    CHAPTER
KnowledgePracticeSelector  KNOWLEDGE
WrongPracticeSelector      WRONG
PracticeSelectionStore     四者共用的只读候选事实查询
```

四个 selector 都不写状态：lane 与 cursor 全部从既有事实推导，不新增状态表。

---

## 2. 普通正式训练单层化（无自动 Diagnosis / Remedial）

对 RANDOM / CHAPTER / KNOWLEDGE / WRONG 一律：

```text
一道 Formal Parent Question
→ 一次作答 / 自评
→ 一次 grading
→ 写 Wrong Book / Mastery / Evidence
→ 直接进入下一道普通 Formal Question 或结束当前流程
```

禁止自动：

```text
错题 → Remedial child
错题 → Dependency probe
错题 → Diagnosis session
错题 → retry parent
错题 → training state machine
```

`learner_diagnosis_session`、`learner_diagnosis_dependency`、Remedial 子题、
`DiagnosticLearningService`、`RemedialQuestionStore` 与管理端补救题能力**继续保留**
（诊断以后会重新设计），但普通正式训练不再调用它们。

部署前遗留状态必须可继续、不死锁：

```text
已经发出的 attempt 仍然可以完成
完成后不继续旧 diagnosis / remedial 链
下一题回到该模式的普通 selector
必要时把旧未完成 diagnosis 标记为 abandoned
```

Legacy `/games/**` 不进入本规则，保留既有的兼容推进分支。

一个普通 Formal grading 仍然必须：

```text
更新 study_attempt assessment
写 answer_record
wrong / partial → learner_wrong_question active
应用现有 Mastery V3 / Evidence
保持 Asia/Shanghai 每日首答奖励语义
```

一次 Attempt 仍只给冻结的 `targetKnowledgePointId` 记账，不因为题目多 KP 同时加多个 KP。

“我没思路”是四模式共用的正式 grading action，不是第五种选题模式。它不提交假答案，
直接把当前 Formal Parent Attempt 判为 wrong，正常推进本模式的一个正式 slot，并写入
Wrong Book / Mastery / Evidence；solution 在 active 或 revealed 状态均可使用。它同样不触发
Diagnosis / Remedial。

---

## 3. RANDOM

### 3.1 KP-first

```text
先选 target KnowledgePoint
→ 再在该 KP 内选 Question
```

候选 KP：当前 run 冻结的 selected Book scope 中的 active KnowledgePoint，
且该 KP 至少有一题当下 RANDOM 可选的 Formal Parent Question。

Formal Question 仍必须：

```text
status = published
parent_question_id IS NULL
question_type ∈ single_choice / multiple_choice / true_false / solution
与 target KP 存在 core 或 auxiliary 关系
```

RANDOM 禁止根据以下条件筛题或排序：

```text
Mastery
readiness
Review due
preferred difficulty
dependency gating
历史答对次数
```

`difficulty` 仍是题目 metadata，但本策略不看它。

### 3.2 KP transition

业务日统一 `Asia/Shanghai`。

```text
当天第一次创建 draw_mode=random 的 Attempt
→ 从所有“当天仍有 eligible Question”的 KP 中纯随机选一个
```

纯随机含义：不看 Mastery、不看难度、不做 KP 权重排序。

后续 RANDOM 题查看 Learner **上一个 RANDOM Formal Attempt** 的：

```text
targetKnowledgePointId
assessment
```

```text
上一题 correct
→ 优先从“除上一 target KP 外”的 eligible KP 中纯随机选一个
→ 若当前范围实际上只有一个 eligible KP，允许继续同 KP，不能死锁

上一题 wrong / partial
→ 优先留在上一 Attempt 冻结的 targetKnowledgePointId，换该 KP 内另一道当天未出的题
→ 该 KP 无题可选时，再从其他 eligible KP 中随机切换
→ 全范围都无题时，当日 RANDOM 候选耗尽，明确结束，不得重复当天已出题

上一题 assessment = null（active / revealed 但未 self-assess）
→ 不推断为 wrong，也不推断为 correct
→ 直接在当前 eligible KP 中重新纯随机
```

未作答的上一题仍然占用当天 Question quota，它不是“上一题的 grading 结果”，
因此**不得**用更早一天或更早一题的结果替代它。

一题绑定多个 KP 时，后续依据该 Attempt 已冻结的 `targetKnowledgePointId`，不重新猜。

### 3.3 每日 Question 硬去重

只对 `draw_mode = random` 生效。

```text
同一 Learner × 同一 Asia/Shanghai 业务日
同一个 Question 最多创建一次 RANDOM Attempt
```

题目一经发出就占用当天 quota：

```text
active
revealed
graded
```

都算“今天已经随机出过”。不要等 `answered_at` 才去重。

事实来源是 `study_attempt(draw_mode='random')`。run 内 `seenQuestionIds` 只作为额外保护，
不是唯一事实源。

### 3.4 额度隔离

```text
CHAPTER / KNOWLEDGE / WRONG 做过 Q
→ 不影响今天 RANDOM 是否还能出 Q

RANDOM 今天出过 Q
→ 不阻止 Chapter / KP / Wrong 练到 Q
```

这是硬规则。

### 3.5 oldest / wrong 交替 lane

每个 `Learner × KnowledgePoint` 逻辑上轮换：

```text
oldest
wrong
oldest
wrong
...
```

第一次进入该 KP 为 `oldest`。不新增状态表，用该 Learner 在该 target KP 下
已创建的 RANDOM Attempt 数量决定下一 lane：

```text
偶数 → oldest
奇数 → wrong
```

Attempt 一经创建就消费一个 lane slot（含 active / revealed）。

`draw_reason` 记录实际：

```text
oldest
wrong
wrong_fallback
```

**oldest lane**

候选：该 target KP 下所有当前 eligible Formal Question，排除今天已经 RANDOM 出过的题。

排序事实用 Learner 最近一次 **graded** 该 Question 的 `answered_at`，
而且看所有正式作答模式的历史，不只 RANDOM。

```text
从未 graded 过 = 无限久以前 = 最优先
之后 last graded 最早者优先
同时间 question_id 稳定兜底
```

因此 oldest 是“全局最久没有真正完成过的题”。

**wrong lane**

只看永久错题本当前 active，且把两个事实分开：

```text
“属于当前 target KP”
→ question_resource_knowledge 关系（候选题集合本身已经保证）

“是不是当前 active 错题”
→ learner_wrong_question.status='active' 按 learner + question_id 判断
```

因此 wrong lane **不要求** `learner_wrong_question.target_knowledge_point_id`
等于当前 KP。一道同时关联 K1 / K2 的题，如果是在 K2 context 下做错的，
进入 K1 的 wrong lane 时仍然是候选，只要：

```text
Q 通过 question_resource_knowledge 属于 K1
Q 当前仍是 learner_wrong_question.status='active'
Q 今天没被 RANDOM 出过
```

`learner_wrong_question.target_knowledge_point_id` 继续保留，用于错题本原始归因、
`wrong_review` 与 `wrong_drill` 的 target，不受本规则影响。

手动移出错题本的 Question 在所有 KP 的 wrong lane 中都立即不再是 candidate。

候选还必须满足：

```text
当前 Formal 可用
今天未 RANDOM 出过
```

有多道时优先 `last graded` 最早的一道。没有 active wrong candidate 时 fallback oldest，
记录 `draw_reason = wrong_fallback`，而且这个 wrong slot **仍然算消费**，下一次回到 oldest。

### 3.6 World 进度

现代 Learner World / 寒门仕途普通正式题：

```text
每完成一道 Formal Parent 的 grading
= 本轮正式题进度 +1
```

无论 `correct` / `wrong` / `partial` 都推进。禁止 wrong 后停在原 slot、插 Remedial、retry parent。

进度必须实现为 `completedFormalCount / plannedRounds`，不硬编码百分比。

开始 run 时：

```text
remainingToday = 当前 Book scope 中今天未 RANDOM 出过的 Formal Questions
plannedRounds  = min(activity rounds, remainingToday)
```

`plannedRounds = 0` 时开始阶段直接返回清晰业务提示：

```text
今天学习范围内的随机题已经全部出过了，明天再来吧。
当前学习范围内没有可用的正式题。      （范围里根本没有正式题时）
```

不得先启动 run 再 500。

---

## 4. CHAPTER

### 4.1 固定确定性题序

章节练习不再是“KP 顺序 + KP 内随机”，整个 Chapter 有一条确定的 Question sequence。

顺序规则：

```text
1. Chapter 内 KnowledgePoint 的 question_bank_knowledge.sort_order
2. 同 KP 内按稳定 Source identity 分组
3. exam_year
4. question_number 自然排序
5. question_id 稳定兜底
```

Source 排序**不得**使用 `display_name`，因为 displayName 是可修改的展示事实。
优先使用 `source_id`；legacy 无 `source_id` 时才 fallback `source_type + source_name`。

题号必须自然排序：

```text
1 < 2 < 7 < 10 < 22
```

不能字符串排序出 `1,10,2`。MySQL 5.7 不引入 Window Function；稳定自然排序在 Java 内完成。

同一道 Question 若关联 Chapter 内多个 KP，只出现一次，归属到按 KP 顺序第一次遇到它的位置；
本次 Attempt 冻结的 `targetKnowledgePointId` 就是第一次出现的那个 KP。

### 4.2 跨 Session 持久 cursor + wrap

cursor 粒度：

```text
Learner × Book × Chapter
```

不新增 cursor 表，从历史推导：最新 graded 的 chapter Attempt，
JOIN `learner_practice_session`，限定 learner + target_book_id + target_chapter_id，
然后在当前确定性 sequence 中找 successor。

```text
只创建 / reveal 但未 graded → cursor 不推进
graded                      → cursor 推进
最后一题 graded             → 下一题 wrap 到第一题
新开 Chapter Session        → 从上次 graded 的下一题继续
```

如果历史 cursor Question 已 archive / unbind / 删除 / 不再属于该 Chapter，
当前 sequence 找不到时安全从第一题重新开始，不能 500。

Chapter 是连续练习模式，到序列末尾 wrap，不因为“到末尾”永久 complete；
用户可手动结束 Session。

---

## 5. KNOWLEDGE

知识点专项限定目标 KP。

```text
候选：该 KP 全部 published Formal Parent Question（core + auxiliary 都算）
选择：Session 内随机且不重复
```

等价于每个 Session 一轮随机 permutation，不要求预先把队列落库；可以从
“本 Session 尚未 seen”的候选里随机一题。

```text
全部耗尽 → 本轮完成
新开 Session → 重新对完整池随机
```

禁止 Mastery / readiness / Review / preferred difficulty 影响候选资格或排序。

---

## 6. WRONG

### 6.1 wrong_drill（错题快练）

候选：

```text
learner_wrong_question.status = 'active'
当前 selected Books 范围可用
published Formal Parent
```

```text
Session 内随机且不重复
全部耗尽 → 本轮完成
新开 Session → 重新随机
```

用户手动移出错题本后，future wrong candidate 立即不再包含该题。

### 6.2 wrong_review（单题错题重做）

从错题列表点指定题时直接发该题；graded 后流程完成，返回错题列表，
不插诊断或补救题。

### 6.3 永久错题语义

```text
答对         ≠ 自动移出错题本
用户手动移出 → removed
以后再次 wrong / partial → 重新 active
```

---

## 7. 不再受 Adaptive selection 干预

以下能力可以暂时保留，但普通四模式选题不得再依赖它们：

```text
AdaptiveStudyPlanner
preferredDifficulty
DiagnosticLearningService
Mode.TRAINING
```

`difficulty` 可以继续作为题目 metadata / 未来策略输入，但本策略不按 difficulty 筛普通题。

---

## 8. Attempt 记录 draw mode

`study_attempt` 增加：

```text
draw_mode   VARCHAR(24) NULL
draw_reason VARCHAR(24) NULL
```

正式取值：

```text
RANDOM    → random
CHAPTER   → chapter
KNOWLEDGE → knowledge
WRONG     → wrong
Legacy /games/** → legacy
```

`draw_reason` 目前只由 RANDOM 写 `oldest` / `wrong` / `wrong_fallback`，其他模式为 NULL。

约束：

```text
旧 Attempt 保持 draw_mode = NULL，不做高风险历史回填
“最近一次 graded”仍然读取 Learner 全部历史正式 Attempt
draw_mode / draw_reason 由服务端 strategy 决定，前端不得提交
旧 diagnosis / remedial 兼容路径可以继续写 NULL
```

Attempt 创建仍必须复用现有：

```text
QuestionAnswerDeriver
QuestionAttemptVariantService
QuestionExamMetadataBuilder
QuestionAttemptStore
```

不复制第二套发题快照逻辑。

---

## 9. Session 冻结 scope

每一个 active Practice Session 在创建时冻结自己的 `learner_practice_scope`。
已经开始的 Session 只认这份 frozen scope：

```text
CHAPTER   确定性题序只按 Session 冻结的 KP scope 计算
WRONG     wrong_drill 候选与 canRepeat 只按 Session 冻结的 KP scope 计算
KNOWLEDGE 只限定建立 Session 时冻结的 target KP
```

因此 Learner 在别的页面修改 selected Books，**不会**让正在进行的 Session
换题池，也不会错误显示 `canRepeat`。

“文集 / 章节是否在当前学习范围内”只在**新开 Session 的入口**校验：不满足时返回明确的
400 业务提示，而不是让已经开始的 Session 中途失效。

---

## 10. 本专题边界

```text
题目报错 / report
新的 Diagnosis 设计
删除 diagnosis / remedial 数据库
奖励档位、奖励卡与奖励数值改造
寒门仕途视觉与题面布局调整
```

---

## 11. 与全平台题库浏览的边界

Learning Hub 的**全平台题库**（`/questions`，见
[`PROJECT_RULES.md`](./PROJECT_RULES.md) §9.1）是全局只读浏览，与学习范围解耦：

```text
可以浏览所有 published Formal Parent Question（含未 selected Book 的题）
但浏览不创建 study_attempt，不计 Exposure
不影响 Mastery / Wrong Book
不消耗 RANDOM 每日额度
```

上表四套策略的候选范围一律**不因新增全平台题库而放宽**：

```text
RANDOM     仍限定当前 run 冻结的 selected Book scope
CHAPTER    仍限定 Session 冻结的 Book + Chapter + KP scope
KNOWLEDGE  仍限定建立 Session 时冻结的 target KP
WRONG      仍限定当前 selected Books 覆盖范围内的 active 错题
```

“浏览到了某道题”不等于“该题进入了训练范围”。`/questions/{id}` 同样只是只读详情，
不提供开始练习入口。
