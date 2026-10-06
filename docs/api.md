# Java API 契约

Java 17 + Spring Boot 后端已在 `backend` 目录开始实现，服务端口为 `12345`。统一接口在 `frontend/src/domain/types.ts` 的 `GameApi`，HTTP 路由映射在 `frontend/src/api/http.ts`。

正式产品已经切换为联机 Learner + Learning Hub + WorldState。`/games/**` 和 `game_save` 只保留旧数据兼容；新前端通过学习者 Cookie、Study Profile 和 `/worlds/ancient-official/**` 工作。后端启动与数据库配置见 `backend/README.md`。

## 切换方式

frontend/.env.local：

~~~dotenv
VITE_API_MODE=http
VITE_API_BASE_URL=/api/v1
API_PROXY_TARGET=http://localhost:12345
~~~

重启开发服务器。Vite 开发代理把 /api 请求交给 Java；部署时自行配置同源代理或 CORS。
默认使用 HTTP 联机模式；如需运行旧本地兼容实现，可显式设置 `VITE_API_MODE=local`。

Learner Session 使用 HttpOnly、SameSite=Lax Cookie，因此正式前端始终通过当前站点的同源 `/api/v1` 访问 Java API。`API_PROXY_TARGET` 是 Vite 服务器内部的转发目标，不是浏览器 API 地址。不得将 `VITE_API_BASE_URL` 设为与页面 Origin 不同的绝对 URL；前端会直接拒绝这类配置并给出同源配置提示。

## 路由

下表路径均相对于 /api/v1。字段的完整 TypeScript 定义以 domain/types.ts 为准。

### MVP 学习与管理扩展

- `GET /learner/statistics?days=7|30|90`：从正式 graded attempts 和当前 Selected Books 动态派生学习统计。
- `GET /learning/knowledge-points`：按 `query`、`bookId`、`chapterId`、`subject` 浏览 active KnowledgePoint 及 published Question 数量。
- `GET /learning/knowledge-points/{id}/guide`：读取独立维护的 Markdown/LaTeX 知识讲解。
- `GET /learning/knowledge-points/{id}/neighbors?bookId=&chapterId=`：读取同一文集章节中的前后知识点。
- `GET /learning/books/{id}`：返回单层正式 Chapter 列表；每个 Chapter 同时带目录静态值 `trainableKnowledgePointCount`、已发布题数 `publishedQuestionCount`，以及按当前 Learner 实时计算的 `availableKnowledgePointCount`。前端以 `availableKnowledgePointCount` 决定“开始章节练习”是否可点，它等于 0 时按钮禁用并显示“暂无可练正式题”，不再让用户点击后才收到 400。
- `POST /learner/practice-sessions`：以 `chapter_drill` 启动章节知识练习，或以既有 intent 启动知识点/错题练习。
- `POST /manage/questions/bulk-delete`：事务性批量删除题目资源；活动中的错题练习、未同时选择的派生题，以及**已存在 `learner_wrong_question` 错题历史（无论 active 还是 removed）**的题目都会阻止整批删除并返回 409，错误题请改为下架/归档。
- `POST /manage/imports/knowledge`：管理员导入 `global-knowledge-batch/v2`，事务性 upsert Book、Chapter、Global KnowledgePoint、alias 与 membership。
- `POST /manage/questions/export-remedial-source`、`POST /manage/imports/remedial-questions`：导出正式父题并导入 3–5 步补救子题。
- `POST /manage/knowledge-points/export-guides`、`POST /manage/imports/knowledge-guides`：导出知识上下文并导入独立知识讲解。

`GET /learner/progress` 的 Book 与 Chapter 聚合同时返回 `masteryProgress`，值为对应去重 KnowledgePoint 在同一时刻的 `effectiveMastery` 算术平均值，未开始按 0 计算。

| 方法 | 路径 | 请求体 | 成功返回 |
| --- | --- | --- | --- |
| POST | /learner/auth/register | username, displayName, password | Learner + HttpOnly Cookie |
| POST | /learner/auth/login | username, password | Learner + HttpOnly Cookie |
| POST | /learner/auth/logout | 无 | 204 |
| GET | /learner/me | 无 | 当前 Learner |
| GET/PUT | /learner/study-profile | Study Focus | StudyProfile |
| GET | /learner/knowledge-states/{knowledgePointId} | 无 | 当前 Learner 的 Knowledge State；无证据时返回未开始虚拟状态且不写库 |
| GET | /learner/knowledge-states?bookId={bookId} | 无 | enabled Book 全部 active KnowledgePoint 的批量状态 |
| GET | /learner/review-queue | 无 | 当前 Selected Books 范围内动态派生的 7 天复习安排；只读且不写库 |
| GET | /learner/progress | 无 | 当前 Selected Books 范围内动态派生的掌握分布、文集/章节聚合和近 7 日正式学习足迹 |
| GET | /bootstrap | 无 | learner、canManage、studyProfile、worlds、bankManifest、questionCatalog |
| GET | /learning/books | 无 | 可见文集 |
| GET | /learning/books/{id} | 无 | Chapter Tree 与 active KnowledgePoints |
| GET | /learning/knowledge-points/{id} | 无 | active KnowledgePoint |
| GET | /learning/knowledge-points/{id}/questions | 无 | published Questions |
| GET | /learning/questions/{id} | 无 | 只读题目、答案与解析 |
| POST | /worlds/ancient-official/initialize | characterName, gender, origin | 唯一 WorldState |
| GET | /worlds/ancient-official | 无 | 当前 Learner 的 WorldState |
| POST | /worlds/ancient-official/* | 动作参数 | 保存后的 WorldState |
| POST | /learner/history/questions | {attemptIds: UUID[]} | 当前 Learner 已完成题目快照 |

以下均为 Legacy Compatibility：

| 方法 | 路径 | 请求体 | 成功返回 |
| --- | --- | --- | --- |
| POST | /games | NewGame | Game |
| GET | /games/{id} | 无 | Game |
| POST | /games/{id}/history/questions | {attemptIds: UUID[]} | 批量恢复该存档已完成 attempt 的完整历史题目 |
| DELETE | /games/{id} | 无 | 204 |
| GET | /question-banks/{uuid} | 无 | Bank，带 revision 与缓存响应头 |
| POST | /games/{id}/answers | {attemptId, questionId, answer} | Game |
| POST | /games/{id}/answers/reveal | {attemptId, questionId} | 自评题参考答案与解析 |
| POST | /games/{id}/answers/self-assess | {attemptId, questionId, assessment} | Game；assessment 为 correct/partial/wrong |
| POST | /games/{id}/next | {attemptId, reviewOnly} | Game |
| POST | /games/{id}/choices | {eventId, choiceId} | Game |
| PUT | /games/{id}/notes | {questionId, note} | Game |
| PUT | /games/{id}/configuration | {bankIds, weights} | Game |
| POST | /games/{id}/chapter | {chapterId} | Game |
| GET | /games/{id}/export | 无 | JSON 字符串 |
| POST | /games/import | {json: "备份全文"} | 新 Game |

历史题目恢复接口先校验 `attemptId` 存在于该 Game 的 compact records 中，并且只返回 Question UUID 一致、状态为 graded 的服务器 `study_attempt` 快照；客户端导入的 records 不能单独作为读取全局题目答案的凭据。active、revealed、其他 Game 的 attempt 与未作答题目均不会返回答案。请求一次最多包含 500 个 attempt。前端优先使用 attempt 级内存和 IndexedDB 快照，缺失时发起一次服务器批量恢复；旧文集当前题面仅作最后兼容回退，仍无法恢复的单题显示占位内容，不影响整个 Game 加载。

管理写接口使用独立前缀 `/api/v1/admin`，只有服务端配置 `APP_ADMIN_KEY` 后才启用，请求头必须带 `X-Admin-Key`：

| 方法 | 路径 | 请求体 | 成功返回 |
| --- | --- | --- | --- |
| POST | /admin/question-banks/import | QuestionBankDto | 新增或修订后的 Bank |
| POST | /admin/questions/import | global-question-batch/v3 | 幂等导入全局题目批次，不写入 Book |
| POST | /admin/global-question-banks/import | global-question-bank/v1 | 已弃用；仅兼容未采用 KnowledgePoint 路径的旧文集 |
| PUT | /admin/question-banks/{uuid}/metadata | {name?,description?,enabled?,weight?} | 改名后的 Bank |

V3 导入相同 Question UUID 会原子更新题目并递增 revision，不创建或修改任何 Book；V3 的 `batch.subject` 只作为稳定元数据，不限制 KnowledgePoint 的 legacy subject。旧 V2 继续兼容，旧 V1 仅供尚未采用 KnowledgePoint 路径的兼容文集使用。普通玩家接口永远不接收管理密钥，管理端也不得把密钥保存在 localStorage。

## 全服管理后台 API

`/api/v1/manage/*` 是浏览器用户后台，与机器级 `/admin/*` 严格分开。管理后台与 Learning Hub 共用同一个 Learner Session，不存在独立 Manage 登录身份；附加权限来自 `learner_account_role`。前端先请求 `GET /manage/auth/csrf`，修改请求携带返回的 CSRF header，且始终使用 `credentials: include`。后端会在每次管理请求时复核账号状态与角色；角色移除或账号停用后，管理请求立即失去权限，但学习者身份仍按统一 Learner Session 处理。

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| GET | /manage/auth/csrf | 公开 | 取得管理写请求使用的 CSRF header |
| POST | /manage/auth/logout | 已登录 | 注销统一 Learner Session |
| GET | /manage/auth/me | 已登录 | 当前账号与服务端角色 |
| GET | /manage/knowledge-points | CONTRIBUTOR+ | 分页并按 code/name/alias/分科/章节/状态搜索 |
| GET | /manage/knowledge-points/{id} | CONTRIBUTOR+ | 知识点详情 |
| PUT | /manage/knowledge-points/{id} | REVIEWER/ADMIN | 改名、说明、alias、角色和无题目绑定时的 deprecated；code 与合并指向不可由普通表单修改 |
| POST | /manage/knowledge-points/{id}/merge | ADMIN | 按 expectedRevision 把源知识点事务合并到 active 目标并保留历史 |
| GET/POST | /manage/questions | CONTRIBUTOR+ | 查询题目或创建自己的 draft |
| GET/PUT | /manage/questions/{id} | 按资源权限 | 详情与带 expectedRevision 的编辑 |
| POST | /manage/questions/{id}/submit | 作者 | draft/rejected 提交审核 |
| POST | /manage/questions/{id}/review | REVIEWER/ADMIN | 审核他人题目并 approve/reject |
| POST | /manage/questions/{id}/archive | REVIEWER/ADMIN | 归档题目 |
| POST | /manage/imports/questions | ADMIN | 事务校验并导入 global-question-batch/v3 题目批次 |
| POST | /manage/imports/question-bank | ADMIN | 已弃用；仅兼容旧版 global-question-bank/v1 文集导入 |
| GET/POST/PUT | /manage/users | ADMIN | 账号、状态、角色和密码重置 |
| GET | /manage/audit-logs | ADMIN | 按动作、实体类型和操作者分页查询只读审计记录 |

知识点与题目修改都携带 `expectedRevision`。发生并发修改返回 409，客户端必须重新加载，不能静默覆盖。知识点合并会把源记录标为 deprecated 并写入 `merged_into_id`，逐题迁移关系；目标关系已存在时折叠为一条，任一原关系为 core 则保留 core。源记录、合并历史和审计记录均不删除。题目管理 DTO 保存作者、审核、原题型、展示类型和判题模式；这些字段不进入普通玩家作答 DTO。

知识点合并还会在同一事务中迁移 Learner Focus、attempt target、Knowledge Evidence、题目级掌握槽位与 Knowledge Guide。若源与目标同时已有状态，服务端会把题目关系与历史正式作答归一到目标知识点，并重建唯一的 V3 聚合状态。

## Learner Knowledge State V3

正式 World 发题时，`study_attempt` 固化 `targetKnowledgePointId`、`evidenceMode`（normal/training）与 1–5 级题目难度。清晰可归因的 graded attempt 只为该 target KnowledgePoint 插入一条 evidence，并更新 `(learnerId, knowledgePointId)` 唯一状态；题目关联的其他 core/auxiliary 知识点不直接更新。normal composite wrong/partial 是明确例外：raw `study_attempt` 与 `answer_record` 立即保存，根 target evidence 延迟到诊断确认。`UNIQUE(attempt_id)` 与既有 attempt 状态转换共同保证重复提交不会重复记证据。Legacy `/games/**` 没有 Learner，因此不创建长期掌握状态或诊断会话。

每个 `(learner, KnowledgePoint, formal parent Question)` 拥有一个 0–100 的题目级掌握槽位。首次答对为 30；以 Asia/Shanghai 为日界线，同一天重复答对不增加槽位分数，后续每个首次跨日答对增加 7，最高 100。wrong/partial 保留正式 attempt 与 evidence，但不直接扣减槽位分数。Remedial SubQuestion 不创建槽位，也不进入分母。

```text
Knowledge Mastery = 所有正式父题槽位分数之和 / 当前正式父题总数
```

每个题目槽位按完整 3 个 Asia/Shanghai 自然日惰性衰减 1 分。KnowledgePoint 的全部正式题均达到 100 时冻结衰减；新增正式题会扩大分母并解除冻结，既有槽位从解除当天重新开始衰减。聚合档位为 0–29 尚未稳固、30–69 基本掌握、70–99 熟练掌握、100 彻底掌握。`stabilityDays` 与 `targetDifficulty` 继续供既有 Review Queue 和 Adaptive Scheduling 使用，原参数不变。

正式 Hub 与 World 共用同一套 target-only grading、Evidence、Diagnosis、Question Rotation 和 V3 槽位。题目浏览、查看答案、reveal、发题、开始或放弃活动都不产生 evidence。

## Adaptive Scheduling V1

正式 World 开始活动时先从 Selected Books 得到冻结的 allowed KnowledgePoint scope。某个目标知识点只有在至少存在一道满足以下条件的 published Question 时才可进入本轮计划：目标是该题的 core；题目的全部 KnowledgePoint 都处于 allowed scope 且为 active；除目标本身之外的其他 core/auxiliary KnowledgePoint 均满足 `effectiveMastery >= 70`。目标自身不要求 ready，因此全新知识点仍可由单知识点题目启动。无 state 的依赖视为不 ready；空 ready set 只允许没有其他依赖的题目。

Planner 批量读取 allowed scope 内已有 state，未开始的知识点使用虚拟初始状态且不写库。正式 World 从 Selected Books 与 adaptive playable 集合的交集中随机抽取不同 target；Mastery、Review 与 Manual Focus 不参与 target 排序。target 确定后仍受相同 dependency、scope、published、difficulty 与 seen 约束；可训练的不同目标不足活动轮数时返回明确的 400，不会放宽依赖规则。

每次正式 draw 都在当前 run 冻结的 allowed scope 内重新计算 ready，并读取当前目标 state。难度上限为：未开始或有效掌握度低于 40 时为 2，40–70 为 3，70–85 为 4，85 以上为 5；`standard` 使用 `min(targetDifficulty, masteryCap)`，`gentle` 再下调一级但不低于 1。NORMAL 优先精确难度，否则选择距离最近的难度，距离相同取较低值；TRAINING 使用 `min(2, normalPreferred)`，优先不高于 2 的最近难度，没有低难题时使用全部合法候选中的最低难度。seen Question 永不因难度匹配而复用。

Legacy `/games/**` 保持 Phase C 的 scope-only dependency 与原有随机/低难训练选择，不读取 Learner mastery。

## Learner Question Rotation V1

正式 Learner World 把 `study_attempt.created_at` 作为 Question Exposure 的事实来源：题目一经发出即计入，不要求存在 `answer_record`，因此 active、revealed 和 graded attempt 都有效。Exposure 以 `(learner_id, question_id)` 聚合，不按 World 隔离；Learning Hub 的知识点或题目浏览不会创建 attempt，也不会进入 Exposure 历史。Legacy `/games/**` 继续使用原有随机选择。

当前 run 的 `seenQuestionIds` 仍是硬排除。对剩余合法候选，NORMAL 先完全沿用 Phase F 的 exact / nearest / lower tie 规则确定 difficulty；TRAINING 先沿用 `min(2, normalPreferred)`、低难优先和最低难 fallback。只有最终同 difficulty bucket 内会应用软轮换：never seen、最早 `lastExposedAt`、较少 `exposureCount`，完全相同时随机。

Exposure 不删除候选，不设置固定 cooldown 或 blacklist。所有题都见过以后会选择最久未见的题，单题题库也可在新 run 中继续返回同一题，因此不会阻断 Task 无限重试或 Diagnosis。V10 只为 `study_attempt(learner_id, question_id, created_at)` 增加查询索引，不新增 Exposure 表、状态列、Evidence mode 或前端 Exposure UI。

## Forgetting-aware Review Queue V1

Review Queue 是 `LearnerKnowledgeState` 的动态派生视图，不新增 Review 表、`next_review_at`、定时任务或 migration。只有 `evidenceCount > 0` 且最近证据后的原始 `masteryScore >= 70` 的状态具备复习资格；未开始和最新 mastery 低于 70 的知识点继续属于正常学习队列。

建议复习时间继续沿用现有 Stability 与聚合 Mastery 反推：

```text
reviewDueAt = lastEvidenceAt + stabilityDays × log2(masteryScore / 70)
```

`reviewDueAt <= now` 返回 `due`，未来 24 小时内返回 `soon`，24 小时以后至 7 天内返回 `upcoming`，更远的状态不进入默认列表。API 只读取当前 Learner 的 Selected Books，按 KnowledgePoint ID 去重，批量读取状态，并复用 Phase F 的 effective mastery readiness 与 adaptive playable 查询；`playable=false` 表示当前题库或前置状态暂时无法安全安排该目标。

Review Queue 只负责 Learning Hub 的复习安排，不改变正式 World target 的随机选择。学习者从 Review Queue 进入知识点专项后，仍使用原有 adaptive difficulty；答错仍按 Phase G diagnosis 处理，证据模式仍只有 `normal` 与 `training`。

Learning Hub 首页展示“今日巩固”摘要，`/reviews` 展示三个时间窗口并链接到知识点说明页。复习队列的读取与浏览本身不创建 attempt 或 evidence；进入 Phase J 的知识点专项后，正式作答仍通过共享 Question Engine 更新既有 Mastery，自然推迟下一次 due 或回到薄弱学习队列。

## Learning Progress Dashboard V1

`GET /learner/progress` 是只读动态派生视图，不保存 progress、completion 或 daily summary。总体范围取当前 Learner 的 Selected Books，并沿正式 `question_bank → question_bank_chapter → question_bank_knowledge` 模型读取 active KnowledgePoints；总体按 KnowledgePoint ID 去重，单本文集仍按自己的 membership 统计。状态通过一次批量查询读取，`started` 定义为 `evidenceCount > 0`，`ready` 使用 V3 惰性结算后的聚合 Mastery `>= 70`，`proficient` 表示聚合 Mastery 正好为 100。

文集响应包含按单层正式章节组织的聚合；每个章节只统计自己的直接 KnowledgePoint membership，并按 KnowledgePoint ID 去重。Review 数量直接复用 Review Queue 的 `due / soon / upcoming` 派生结果，错题数量直接读取永久错题本 `learner_wrong_question` 中该 Learner 的 `active` 记录数（不受该题后来是否答对影响）。

近 7 日足迹仅查询当前 Learner 在 UTC 最近 7 个自然日内 `status=graded` 的 `study_attempt`。Hub Practice 与 World attempts 统一计入；active、revealed、窗口外记录和 `learner_id IS NULL` 的 Legacy attempts 不计入。响应只提供正式作答数、不同知识点数、活跃学习日期数、每日活动量和最近产生 Evidence 的知识点，不提供正确率、错误率、失败次数或排名。

## Diagnostic State Machine V1

normal composite Question 的 wrong/partial 会从 root `question_snapshot_json.knowledgePointIds` 取得发题当时的依赖关系，而不查询当前 Question relation。依赖在创建会话前沿 KnowledgePoint merge chain 解析到 active canonical id、去重并排除 canonical target；按有效掌握度更低、最后证据更早、快照稳定顺序排列。`learner_diagnosis_session` 保存根 attempt、原目标、状态、resolution 与 revision，`learner_diagnosis_dependency` 保存有序 dependency 状态；WorldState 只保留 `diagnosisSessionId` 指针和题数，不复制诊断事实。

状态机使用 `diagnosing_dependencies → remediating_dependency → rechecking_target → remediating_target → resolved`，并支持 `abandoned`。dependency probe 使用 normal evidence、目标例外与 Phase F 实时 readiness，难度先按当前 Profile/状态计算再 cap 到 3；wrong/partial 立即成为该 dependency 的正常证据并停止探查其他依赖，training remediation 答对后回到原 target。所有 dependency 均通过时，才用 root 原始 assessment、grading source 与 answeredAt 延迟写入 target negative evidence；存在 unavailable dependency 时改走 target recheck，根错误永远不强行归因。用户 abandon 同样保留 raw answer 而不补根 evidence。

`study_attempt.diagnosis_session_id` 与 `diagnosis_role` 记录 `dependency_probe`、`dependency_remediation`、`target_recheck` 或 `target_remediation`。角色表达诊断目的，证据模式仍只使用既有 normal/training；Mastery V3 只改变题目级掌握聚合，Adaptive Scheduling V1 参数未改变。所有诊断题继续受冻结 Selected Book scope、published、实时 readiness 和 run seen 约束。Probe 无合法 unseen 候选时标为 unavailable；不会回退已见题、未发布题或未 ready 的依赖题。

根正式题一旦答错，本轮对应知识点的游戏分已经失去。后续 probe、remediation 和 recheck 不增加 `run.correct`；`diagnosticAnswered` 统计 probe/recheck，`trainingAnswered` 统计补强。新 run 会重置 answered、correct 与 seen，可重新取得满分。正式 World 的长期考试/任务状态不累计失败或应试次数，只让 bestScore 上升；passed/cleared 与一次性奖励保持永久、单次和单调。未完成任务可以重新开始，完成后永久关闭且不能重进。

正常响应直接返回对象，不包 data/code。错误使用非 2xx 状态及 {"message":"可读错误"}。
导出接口需要返回“经过 JSON 编码的字符串”，而不是直接返回备份对象，因为前端 request<string> 会调用 response.json()。若希望用附件下载，需同步修改适配器。

## 创建人生

~~~json
{
  "name": "折叶",
  "gender": "男",
  "origin": "寒门读书人",
  "bankIds": ["math", "cs"],
  "weights": {"数学一": 7, "408": 7},
  "pace": "normal",
  "difficulty": "gentle"
}
~~~

pace 为 normal / slow；difficulty 为 gentle / standard。此段仅描述 Legacy Game。
新建人生界面不再要求玩家选择文集或题量；客户端自动接入当前可用文集。`bankIds` 允许为空，因此没有可用文集时仍可进入世界，待题库接入后再参与答题活动。
正式 Bootstrap 为 `{learner, canManage, studyProfile, worlds, bankManifest, questionCatalog}`，不再以 saves 或 activeId 为中心。

`questionCatalog` 用于切换本地编辑题库与上线后的服务器统一题库：

~~~json
{"source":"server","canEdit":false,"revision":"2026-10-01.3"}
~~~

生产环境普通用户应返回 `source=server, canEdit=false`。前端仍可浏览文集、题目与知识点，但会隐藏导入、改名、删题等写操作。题库管理交给独立的后台管理权限；题目 Markdown、选项和解析按修订缓存在浏览器 IndexedDB 中，不随每次启动和答题重复传输。

Game 包含身份、配置、NPC 关系、当前章节、历史作答、复习状态、札记、批注、事件、当前课卷。时间统一用 ISO 字符串。

## 答题与随机选项

~~~json
{"attemptId":"课卷 UUID","questionId":"题目 UUID","answer":["A","C"]}
~~~

单选 answer 是原始键字符串，多选是原始键数组，判断是布尔值。答题提交不回传题干、选项、Markdown 或解析，只传课卷 UUID、题目 UUID 和答案。屏幕字母由前端按照 options 的顺序生成，不能按显示字母重新解释提交值。

题干、选项、题目解析与知识点解析均使用 Markdown + LaTeX；服务端只保存和返回原文，不返回预渲染 HTML。前端通过安全的 Markdown 与 KaTeX 组件渲染。

`Bank.knowledgePoints` 是文集内的知识点目录；`Question.knowledgePointIds` 必须引用其中 1–3 个知识点。PublicQuestion 不含 answer、aliases、keywords、explanation，但会附带本题对应的 `knowledgePoints`，用于题面提示与知识点解析；批卷后的 Result 再返回 standard、explanation、correct、story、changes。
当前协议以 options 对象的插入顺序表示显示顺序，键只用 A–F 或 true/false。Java 应使用保序映射（例如 LinkedHashMap）输出洗牌后的选项；不能随意按键排序。

## 后端实现应保持的行为

- 每次发卷生成新 attemptId，保存题目与答案快照；重新读取同一课卷不再洗牌。
- 普通活动开始时冻结 5 个互不相同的知识点，主线活动冻结 10 个；一轮内不能用同一知识点重复占分。
- 每个知识点首题决定该点得分。首题答错后继续返回该知识点的低难度题，答对后才推进；训练题不补回首题失分。
- ActivityRun 返回 knowledgePointIds、knowledgePointIndex、training、trainingAnswered、diagnosticAnswered、diagnosisSessionId 与 seenQuestionIds，客户端只负责展示，不自行推断诊断事实或进度。
- 同一 attemptId 重复提交不重复奖励。已换题时旧答题请求返回明确错误。
- next 的旧 attemptId 重试返回当前进度，不连续跳题。
- 未判完题、未处理际遇时不允许跳过。
- choose 按 eventId 保证同一际遇只结算一次。
- 题库修订只影响以后发卷，不影响已有快照和历史记录。
- 导入先完整校验，再原子保存；备份创建新人生并重映射题库引用。
- 晋章、复习记录、奖励、历史与下一事件一起保存，避免半套状态。
- 正式题型仅允许 `single_choice`、`multiple_choice`、`true_false`、`solution`；其中 `solution` 展示为“综合题”，使用 `presentationType=self_assessment` 与 `gradingMode=self_assessment`。`blank` 不能新建、保存、导入或发布，历史 `blank` 也不会进入正式题池。原填空题须由题目生成 AI 保留 Question UUID 并改编为单选题或多选题。综合题参考答案只在显式 reveal 后返回；评定仅接受 correct/partial/wrong，同一 attemptId 只能形成一条作答记录。

本地模式完整题库在浏览器可见，是单机体验。生产环境应由数据库统一维护 Bank、KnowledgePoint 与 Question；发题接口只返回 PublicQuestion，标准答案只在服务端判题后随 Result 返回。若加入考试排名，还需实现身份认证、事务、防重复提交与题库管理权限。


## V2—V5 新增探索接口

以下路径同样相对于 /api/v1，当前均已实现并返回 Game。Game.adventure 的契约在 domain/adventure.ts；后端读取旧存档时会补齐新增人物与考试记录。

| 方法 | 路径 | 请求体 |
| --- | --- | --- |
| POST | /games/{id}/activities | {activityId} |
| POST | /games/{id}/activities/finish | {runId} |
| POST | /games/{id}/activities/abandon | {runId} |
| POST | /games/{id}/travel | {locationId} |
| POST | /games/{id}/talk | {npcId,topicId} |
| DELETE | /games/{id}/encounter | 无 |
| POST | /games/{id}/items/use | {itemId} |
| POST | /games/{id}/items/buy | {itemId} |
| POST | /games/{id}/bonds | {npcId,milestone} |
| POST | /games/{id}/exams/register | {examId} |

创建人生现在返回 attempt=null、adventure.run=null，默认进入世界。
开始活动后才发卷；有未结束 run 时拒绝开始其他活动或移动。暂停仅为 UI 隐藏，不调用 abandon。
整轮最终题的 answers 请求中完成评分和奖励事务；finish 只结束已结算行程，不能重复发奖励。
旧 next 接口必须校验 run.status=active，且使用 run.definition.reviewOnly，不能从客户端参数绕过活动状态。
talk 校验人物所在地点和话题好感门槛，本身不给属性奖励。
bonds 校验地点、好感和已领取标记；items/use 校验库存，equipment 切换部位，consumable 消耗一件。
items/buy 校验可售价格和余额；没有 price 的副本专属物品禁止购买。
travel 校验地点属性条件，遇到满足前置的新故事时填写 adventure.encounter。

活动完整定义在开始时冻结进 run.definition。配置修改影响下一轮，后端也应保持这项承诺。

## V6 县试事务

报名接口校验考试存在、玩家位于报名地点、当前没有未结束行程与报名资格。只允许从 `unregistered` 进入 `registered`；报名只确认资格，不扣费。

正式 World 开始 `kind=exam` 的任务前要求对应考试为 `registered`，且 `clears[activityId]` 尚未完成。每个新 run 独立重置答题数、得分与 seen；开考时把 fee 扣入本轮 escrow。未满分或放下活动时原数退款，状态仍为 `registered`，可无限次重新开始；全对时费用提交，完整 `completionReward` 发放一次，`clears` 写为 1，考试状态推进到 `passed`。完成后后端拒绝再次 begin。`best` 只升不降；兼容字段 `attempts`、`lastScore` 不再记录正式任务失败履历。

HTTP 后端必须自行校验这些状态，不能只依赖前端隐藏按钮。`Game.adventure.exams` 与活动首次奖励记账一并返回，重复提交同一答题请求不得重复发取中帖或身份奖励。

## Phase J V4：统一身份与正式 Practice API

管理后台复用 Learner Session Cookie。`learner_account` 是唯一账号来源，`learner_account_role` 只附加管理权限；普通 Learner 访问 `/manage/**` 返回 403。未登录进入管理后台时，前端跳转到 `/login?next=/manage`。

| Method | Path | Request | Response / 语义 |
|---|---|---|---|
| GET | /learner/wrong-questions | 无 | 永久错题本列表：每项 `questionId`、`targetKnowledgePointId`、`knowledgePointName`、`contentMarkdown`、`lastGradedAt`、`available`、`unavailableReason` |
| POST | /learner/practice-sessions | intent, targetKnowledgePointId/sourceQuestionId | 开始知识点专项或错题练习 |
| GET | /learner/practice-sessions/{id} | 无 | 恢复 Session 与 current attempt |
| POST | /learner/practice-sessions/{id}/answers | attemptId, questionId, answer | 自动判题并进入共享学习流程 |
| POST | /learner/practice-sessions/{id}/reveal | attemptId, questionId | 查看自评题参考答案 |
| POST | /learner/practice-sessions/{id}/self-assess | attemptId, questionId, assessment | 提交自评 |
| POST | /learner/practice-sessions/{id}/next | 无 | 继续 Diagnosis/Training，或同 K 再来一道 |
| POST | /learner/practice-sessions/{id}/end | 无 | 结束 Practice Session |

V11 新增 `learner_account_role`，把旧 `app_user` 按 username 并入已有或新建 Learner，并为历史 audit/merge 增加 additive `actor_learner_id`。V12 新增 `learner_practice_session`、冻结范围的 `learner_practice_scope`、`study_attempt.practice_session_id`，并使 Diagnosis 支持 world 或 practice 两种互斥上下文。

Knowledge drill 不保存 checkpoint、固定题数、score、pass 或 fail。Wrong Queue 使用 `(learner_id, question_id)` 的 latest graded attempt 派生；Wrong Practice 首题固定 source Question。Hub Practice 与 World 在 target 确定后调用同一 AdaptiveStudyPlanner question context、KnowledgeQuestionPoolService、rotation、grading、Evidence 与 Diagnosis 服务。Hub mutation 校验 learner/session/diagnosis owner，且不写 `learner_world_state`。

World target 由 Selected Books scope 与 adaptive playable 集合求交后 shuffle，再 distinct 截取活动轮数；unstarted 不被排除，也不再按 weak/review/focus 排序。
