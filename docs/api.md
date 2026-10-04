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

## 路由

下表路径均相对于 /api/v1。字段的完整 TypeScript 定义以 domain/types.ts 为准。

| 方法 | 路径 | 请求体 | 成功返回 |
| --- | --- | --- | --- |
| POST | /learner/auth/register | username, displayName, password | Learner + HttpOnly Cookie |
| POST | /learner/auth/login | username, password | Learner + HttpOnly Cookie |
| POST | /learner/auth/logout | 无 | 204 |
| GET | /learner/me | 无 | 当前 Learner |
| GET/PUT | /learner/study-profile | Study Focus | StudyProfile |
| GET | /learner/knowledge-states/{knowledgePointId} | 无 | 当前 Learner 的 Knowledge State；无证据时返回未开始虚拟状态且不写库 |
| GET | /learner/knowledge-states?bookId={bookId} | 无 | enabled Book 全部 active KnowledgePoint 的批量状态 |
| GET | /bootstrap | 无 | learner、studyProfile、worlds、bankManifest、questionCatalog |
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
| POST | /admin/questions/import | global-question-batch/v2 | 幂等导入全局题目批次，不写入 Book |
| POST | /admin/global-question-banks/import | global-question-bank/v1 | 已弃用；仅兼容未采用 KnowledgePoint 路径的旧文集 |
| PUT | /admin/question-banks/{uuid}/metadata | {name?,description?,enabled?,weight?} | 改名后的 Bank |

V2 导入相同 Question UUID 会原子更新题目并递增 revision，不创建或修改任何 Book；旧 V1 仅供尚未采用 KnowledgePoint 路径的兼容文集使用。普通玩家接口永远不接收管理密钥，管理端也不得把密钥保存在 localStorage。

## 全服管理后台 API

`/api/v1/manage/*` 是浏览器用户后台，与机器级 `/admin/*` 严格分开。后台使用服务端 Session；前端先请求 `GET /manage/auth/csrf`，修改请求携带返回的 CSRF header，且始终使用 `credentials: include`。后端会在每次管理请求时复核账号状态；账号被停用后，已有 Session 的下一次请求返回 401 并被注销。

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| POST | /manage/auth/login | 公开 + CSRF | 建立管理 Session |
| POST | /manage/auth/logout | 已登录 | 注销 Session |
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
| POST | /manage/imports/questions | ADMIN | 事务校验并导入 global-question-batch/v2 题目批次 |
| POST | /manage/imports/question-bank | ADMIN | 已弃用；仅兼容旧版 global-question-bank/v1 文集导入 |
| GET/POST/PUT | /manage/users | ADMIN | 账号、状态、角色和密码重置 |
| GET | /manage/audit-logs | ADMIN | 按动作、实体类型和操作者分页查询只读审计记录 |

知识点与题目修改都携带 `expectedRevision`。发生并发修改返回 409，客户端必须重新加载，不能静默覆盖。知识点合并会把源记录标为 deprecated 并写入 `merged_into_id`，逐题迁移关系；目标关系已存在时折叠为一条，任一原关系为 core 则保留 core。源记录、合并历史和审计记录均不删除。题目管理 DTO 保存作者、审核、原题型、展示类型和判题模式；这些字段不进入普通玩家作答 DTO。

知识点合并还会在同一事务中迁移 Learner Focus、attempt target 和 Knowledge Evidence。若源与目标同时已有状态，服务端将两边 evidence 归一到目标知识点，按 `occurredAt, id` 使用 V1 模型重放并重建唯一目标状态。

## Learner Knowledge State V1

正式 World 发题时，`study_attempt` 固化 `targetKnowledgePointId`、`evidenceMode`（normal/training）与 1–5 级题目难度。自动判题或 self-assess 最终进入 graded 后，只为该 target KnowledgePoint 插入一条 evidence，并更新 `(learnerId, knowledgePointId)` 唯一状态；题目关联的其他 core/auxiliary 知识点不直接更新。`UNIQUE(attempt_id)` 与既有 attempt 状态转换共同保证重复提交不会重复记证据。Legacy `/games/**` 没有 Learner，因此不创建长期掌握状态。

`masteryScore` 是 `lastEvidenceAt` 时刻的基础掌握值。读取时的有效掌握度为：

```text
effectiveMastery = masteryScore × 2 ^ (-elapsedDays / stabilityDays)
```

`stabilityDays` 是 0.5–365 天的记忆半衰期。服务端没有后台衰减任务；读取时计算有效值，下一条证据到来时先计算惰性遗忘，再应用新证据。ready 阈值保持为 70；正式 World 的 Adaptive Scheduling V1 会消费 `effectiveMastery`、`targetDifficulty` 与 Study Profile，但不会修改 Mastery V1 参数。

V1 quality 为 automatic correct 1.00、automatic wrong 0、self correct 0.90、self partial 0.50、self wrong 0；source factor 为 automatic 1.00 / self 0.85，mode factor 为 normal 1.00 / training 0.55。高难题答对证据更强，低难题答错证据更强。Learning Hub 浏览题目、查看答案、reveal、发题、开始或放弃活动都不产生 evidence。

## Adaptive Scheduling V1

正式 World 开始活动时先从 Selected Books 得到冻结的 allowed KnowledgePoint scope。某个目标知识点只有在至少存在一道满足以下条件的 published Question 时才可进入本轮计划：目标是该题的 core；题目的全部 KnowledgePoint 都处于 allowed scope 且为 active；除目标本身之外的其他 core/auxiliary KnowledgePoint 均满足 `effectiveMastery >= 70`。目标自身不要求 ready，因此全新知识点仍可由单知识点题目启动。无 state 的依赖视为不 ready；空 ready set 只允许没有其他依赖的题目。

Planner 批量读取 allowed scope 内已有 state，未开始的知识点使用虚拟初始状态且不写库。自动目标优先级依次为：已有证据且有效掌握度低于 70、未开始、70–85、85 以上；同层优先有效掌握度更低、证据更早的知识点。Manual Focus 整体优先于非 Focus，但仍受相同 dependency、scope、published 与 seen 约束。可训练的不同目标不足活动轮数时返回明确的 400，不会放宽依赖规则。

每次正式 draw 都在当前 run 冻结的 allowed scope 内重新计算 ready，并读取当前目标 state。难度上限为：未开始或有效掌握度低于 40 时为 2，40–70 为 3，70–85 为 4，85 以上为 5；`standard` 使用 `min(targetDifficulty, masteryCap)`，`gentle` 再下调一级但不低于 1。NORMAL 优先精确难度，否则选择距离最近的难度，距离相同取较低值；TRAINING 使用 `min(2, normalPreferred)`，优先不高于 2 的最近难度，没有低难题时使用全部合法候选中的最低难度。seen Question 永不因难度匹配而复用。

Legacy `/games/**` 保持 Phase C 的 scope-only dependency 与原有随机/低难训练选择，不读取 Learner mastery。

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
正式 Bootstrap 为 `{learner, studyProfile, worlds, bankManifest, questionCatalog}`，不再以 saves 或 activeId 为中心。

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
- ActivityRun 返回 knowledgePointIds、knowledgePointIndex、training、trainingAnswered 与 seenQuestionIds，客户端只负责展示，不自行推断进度。
- 同一 attemptId 重复提交不重复奖励。已换题时旧答题请求返回明确错误。
- next 的旧 attemptId 重试返回当前进度，不连续跳题。
- 未判完题、未处理际遇时不允许跳过。
- choose 按 eventId 保证同一际遇只结算一次。
- 题库修订只影响以后发卷，不影响已有快照和历史记录。
- 导入先完整校验，再原子保存；备份创建新人生并重映射题库引用。
- 晋章、复习记录、奖励、历史与下一事件一起保存，避免半套状态。
- 填空题与解答题保留原始题型，使用 `presentationType=self_assessment` 与 `gradingMode=self_assessment`。参考答案只在显式 reveal 后返回；评定仅接受 correct/partial/wrong，同一 attemptId 只能形成一条作答记录。

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

报名接口校验考试存在、玩家位于报名地点、当前没有未结束行程、报名资格和银两充足。只允许从 `unregistered` 进入 `registered`，扣费、状态与札记必须一次保存。

开始 `kind=exam` 的活动前校验对应考试为 `registered`。最后一题判卷时，在同一事务内更新 attempts、lastScore、best 与状态；100 分进入 `passed`，未满分仍保持 `registered`，不扣数值、不取消资格、不再次收费，可直接无限次重试。活动中途放下不改变考试状态。

HTTP 后端必须自行校验这些状态，不能只依赖前端隐藏按钮。`Game.adventure.exams` 与活动首次奖励记账一并返回，重复提交同一答题请求不得重复发取中帖或身份奖励。
