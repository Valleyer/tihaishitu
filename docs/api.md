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

- `GET /learner/progress`：**进度与统计的唯一首选读接口**（PR7 进度统计 V3）。保留原有 `summary` / `bands` / `books` / `recent` 字段语义，并新增 `recentContacts` 与 `activity`。可选查询参数 `days=7|30|90`（缺省 7，非法值 400）只控制 `activity.windowDays` 与 `activity.daily` 的活动趋势窗口，其余口径不变。**一个请求只读取一次全历史有效 Attempt**，三个视图来自同一份快照。
  - `recent` 是**保留的旧契约**：`gradedAttempts7d` / `distinctKnowledgePoints7d` / `activeStudyDays7d` / `daily`（近 7 个上海业务日，字段名与类型不变）为 **graded-only 且按 `answered_at` 评分日**归属业务日，不含仅查看答案，**不随 `days` 变化**；`knowledgePoints` 是 **Mastery Evidence 投影列表**（`knowledgePointId` / `name` / `bookName` / `chapterName` / `band` / `effectiveMastery` / `stabilityDays` / `lastEvidenceAt`），按 `lastEvidenceAt` 倒序、`knowledgePointId` 次序稳定，最多 10 条并限当前学习范围，仅查看答案不出现在该列表中。
  - `recentContacts` 是最近接触知识点，来源是有效 Attempt（含仅查看答案），按 `lastEffectiveContactAt`（reveal 或 graded 的首次有效行动）倒序，最多 10 条。行为标签判据是 `lastOutcomeRevealedOnly` / `lastGraded`，**不是** `evidenceCount`：`lastOutcomeRevealedOnly=true` 表示最近一次是仅查看答案（`evidenceCount=0` 时不得展示百分比）；`lastGraded=true` 且 `evidenceCount=0` 表示已真实评分但暂无掌握证据（例如当天首答 wrong 不新增证据），此时也不得展示百分比；`evidenceCount>0` 时按 Mastery 真值展示掌握度，接触时间仍单独显示。`lastEffectiveContactAt` 与 `lastEvidenceAt` 是两个不同概念。
  - `activity`：`windowDays`（等于请求的 `days`，缺省 7）、`generatedAt`、`metrics`（`activeStudyDays7d` / `todayEffectiveAttempts` / `totalKnowledgePoints` / `touchedKnowledgePoints` / `totalEffectiveAttempts` / `totalCorrectAttempts`）、`outcomes`（`correct` / `partial` / `wrong` / `revealedOnly`）、`daily`（`windowDays` 个上海业务日，从早到晚，零值日期保留）。**`metrics` 与 `outcomes` 固定口径，不随 `days` 变化**：累计类是当前范围全历史事实，`activeStudyDays7d` 永远反映近 7 天。`daily` 的日期取**首次有效行动日**，与旧 `recent` 的评分日语义并存：同一个跨天 reveal→自评 Attempt 会在 `activity.daily[reveal 日]` 计 1 次、在 `recent.daily[评分日]` 计 1 次，这是两个字段各自的正确含义，不是重复计数。统一口径见 `PROJECT_RULES.md` §14.1。
- `GET /learner/statistics?days=7|30|90`：**旧接口兼容层**。`summary` 的 `gradedAttempts` / `activeStudyDays` / `distinctKnowledgePoints` / `correct` / `partial` / `wrong` / `revealedOnly` 与 `/learner/progress.activity` 同源（含 reveal-only）；`days` 只影响 `daily` 曲线长度，不再影响 summary 语义。`knowledgeDrillAttempts` / `wrongReviewAttempts`（同时统计 `wrong_review` 与 `wrong_drill`）/ `worldAttempts` 是**graded-only 出场分布**，不含 reveal-only。进度页不再调用本接口。
- `GET /learning/knowledge-points`：按 `query`、`bookId`、`chapterId`、`subject` 浏览 active KnowledgePoint 及 published Question 数量（知识目录，技术 route 与 `/books` 一致，仍是“知识”而不是“题库”）。
- `GET /learning/questions`：**全平台题库**。分页浏览所有 published Formal Parent Question，与 Learner 当前 selected Books 解耦。参数：`query`、`sourceId`、`examYear`、`questionType`、`difficulty`、`bookId`、`chapterId`、`knowledge`、`page`、`size`（前端正式页面固定 `size=20`，后端上限 100）。只返回 `status='published'` + `parent_question_id IS NULL` + 四个正式题型；`totalElements` 是 `COUNT(DISTINCT q.id)`，多 KP / 多 Book 关联不会让卡片或总数重复。分页 ID 查询只做 `question_resource LEFT JOIN question_source`（一对一），Book / Chapter / Knowledge 都通过 EXISTS 参与，因此使用 `SELECT q.id ... ORDER BY ... LIMIT/OFFSET` 且不用 `DISTINCT`——`DISTINCT` 与“ORDER BY 引用未出现在 SELECT list 的表达式”组合在真实 MySQL 5.7 严格 sql_mode 下有报错风险。`knowledge` 匹配 KnowledgePoint 的 `id` / `code` / `name`。默认排序是“来源 → `exam_year` → `question_number` 自然排序 → `question_id`”，在 DB 级用 MySQL 5.7 安全表达式形成，分页稳定；只有题号确实带“与 `exam_year` 相同的年份前缀”时才剥离该前缀，因此 `2020 + "7"` 与 `2020 + "2020-7"` 得到同一个自然题号 7，而 `A-3` / `21A` / `3(1)` 与年份不一致的前缀题号都归入最后一档，不会被猜成数字。浏览不创建 Attempt、不计 Exposure、不影响 Mastery / Wrong Book / RANDOM 每日额度。
- `GET /learning/questions/facets`：题库过滤 UI 的只读事实。`sources` 只列实际有 published 正式题的来源，`examYears` 只列实际存在的年份并降序，`books` 是全平台 enabled Books（不受 selected Books 限制）且章节按 `sort_order`。题型与难度由前端固定，不为此增加数据库查询，也不新建表。
- `GET /learning/knowledge-points/{id}/guide`：读取独立维护的 Markdown/LaTeX 知识讲解。
- `GET /learning/knowledge-points/{id}/neighbors?bookId=&chapterId=`：读取同一文集章节中的前后知识点。
- `GET /learning/books/{id}`：返回单层正式 Chapter 列表；每个 Chapter 同时带目录静态值 `trainableKnowledgePointCount`、已发布正式父题数 `publishedQuestionCount`，以及 `availableKnowledgePointCount`。`availableKnowledgePointCount` 等于“该 Chapter 内（当前学习范围内）存在至少一道正式父题的知识点数”，只随题库内容与学习范围变化，不随每日答题情况、Review 到期或依赖 readiness 变化。前端以它决定“开始章节练习”是否可点，它等于 0 时按钮禁用并显示“暂无可练正式题”，不再让用户点击后才收到 400。
- `POST /learner/practice-sessions`：intent 允许 `knowledge_drill` / `chapter_drill` / `wrong_review` / `wrong_drill`，分别启动知识点专项、章节练习、单题错题重做与错题快练（见 Phase J 一节）。
- `GET /learner/practice-sessions/recent-chapter`：Study 页“最近章节”快捷入口。`status=active` 时返回 `activeSessionId`，前端恢复同一 Session；`status=last` 时返回 `lastSessionId` 与 `bookId` / `chapterId`，前端用相同范围以 `chapter_drill` 新建 Session，不恢复已结束的 Session；`status=none` 表示从未做过章节练习。响应同时包含 `bookName`、`chapterName` 与可空的 `currentKnowledgePointId`、`currentKnowledgePointIndex`、`knowledgePointCount`、`updatedAt`；后两者是当前题在章节确定性题序中的位置与题序长度。
- `GET /learner/practice-sessions/active-chapter`：返回当前 active 的章节 Session；没有时返回 204。
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
| GET | /learner/progress | 无 | 当前 Selected Books 范围内动态派生的掌握分布、文集/章节聚合、保留的 graded-only `recent`（含 Evidence 投影 `knowledgePoints`）、最近接触 `recentContacts`，以及统一的 `activity` 指标 / 结果分布 / 活动趋势曲线；可选 `days=7\|30\|90` 只切换 `activity.windowDays` 与 `activity.daily`；单请求只读一次全历史有效 Attempt |
| GET | /bootstrap | 无 | learner、canManage、studyProfile、worlds、bankManifest、questionCatalog |
| GET | /learning/books | 无 | 可见文集 |
| GET | /learning/books/{id} | 无 | Chapter Tree 与 active KnowledgePoints |
| GET | /learning/knowledge-points/{id} | 无 | active KnowledgePoint |
| GET | /learning/knowledge-points/{id}/questions | 无 | published Questions |
| GET | /learning/questions | 无 | 全平台 published Formal Parent Question 分页浏览；`query`（含 `2020-7` 结构化题号）/`sourceId`/`examYear`/`questionType`/`difficulty`/`bookId`/`chapterId`/`knowledge`/`page`/`size`；`totalElements` 按 Question ID 去重，分页 ID 查询不用 DISTINCT |
| GET | /learning/questions/facets | 无 | 题库过滤事实：有正式题的 `sources`、实际存在的 `examYears`（降序）、全平台 enabled `books` + `chapters` |
| GET | /learning/questions/{id} | 无 | 全平台 published Formal Question 只读详情；不再要求该题属于当前 selected Books，客观题 `correctAnswer` 由 `option.correct_option` 派生，综合题只有 `analysisMarkdown` |
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
| POST | /games/{id}/answers/reveal | {attemptId, questionId} | 自评题参考解析（单一 `explanation`，不再返回 separate standard） |
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
| POST | /admin/questions/import | global-question-batch/v4 | 幂等导入全局题目批次，不写入 Book；v3/v2 仅 compatibility |
| POST | /admin/global-question-banks/import | global-question-bank/v1 | 已弃用；仅兼容未采用 KnowledgePoint 路径的旧文集 |
| PUT | /admin/question-banks/{uuid}/metadata | {name?,description?,enabled?,weight?} | 改名后的 Bank |

V4 导入相同 Question UUID 会原子更新题目并递增 revision，不创建或修改任何 Book；V4 的 `batch.subject` 只作为稳定元数据，不限制 KnowledgePoint 的 legacy subject。旧 V3 / V2 继续兼容，旧 V1 仅供尚未采用 KnowledgePoint 路径的兼容文集使用。普通玩家接口永远不接收管理密钥，管理端也不得把密钥保存在 localStorage。

### Question Contract V2 与导入边界

正式父题（`parent_question_id IS NULL`）不再保存独立标准答案配置：

```text
客观题 single_choice / multiple_choice / true_false → 唯一答案事实 option.correct_option
综合题 solution                                     → 唯一内容事实 analysis_markdown
question_resource.standard_answer_json              → Formal Parent 一律为 NULL
study_attempt.standard_answer_json                  → Attempt 创建时冻结的判题快照，必须保留
```

`/admin/questions/import` 与 `/manage/imports/questions` 的 canonical 格式都是
`global-question-batch/v4`：

```text
v4 客观题：options[].correct 是唯一答案，禁止 standardAnswer
v4 综合题：analysis 是唯一内容，禁止 standardAnswer
v3 / v2 兼容：客观题 standardAnswer 只做一致性校验，不持久化；
              综合题 standardAnswer + analysis 合并进 analysis_markdown
```

导入响应中的 `schemaVersion` 回显本次实际接受的批次版本。管理后台浏览器导入 UI
允许 v4 与 v3，并把 v4 作为推荐格式。

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
| POST | /manage/question-images | CONTRIBUTOR/REVIEWER/ADMIN | multipart 上传题干图片，返回新的 immutable asset |
| GET | /question-images/{assetId} | 放行（只按 UUID 寻址） | 按 asset id 读取图片字节（同源 `<img>` 使用） |
| GET | /manage/sources | CONTRIBUTOR+ | 按 query、sourceType、status 分页查询全局题目来源；来源管理页仍仅 ADMIN 可见 |
| GET | /manage/sources/{id} | CONTRIBUTOR+ | 读取来源详情、revision 与绑定题目数 |
| POST | /manage/sources | ADMIN | 新建全局来源；`(sourceType, canonicalName)` 唯一 |
| PUT | /manage/sources/{id} | ADMIN | 按 expectedRevision 修改正式名、展示名、类型和状态 |
| POST | /manage/questions/{id}/submit | 作者 | draft/rejected 提交审核 |
| POST | /manage/questions/{id}/review | REVIEWER/ADMIN | 审核他人题目并 approve/reject |
| POST | /manage/questions/{id}/archive | REVIEWER/ADMIN | 归档题目 |
| POST | /manage/imports/questions | ADMIN | 事务校验并导入 `global-question-batch/v4` 题目批次；v3 为历史兼容 |
| POST | /manage/imports/question-bank | ADMIN | 已弃用；仅兼容旧版 global-question-bank/v1 文集导入 |
| GET/POST/PUT | /manage/users | ADMIN | 账号、状态、角色和密码重置 |
| GET | /manage/audit-logs | ADMIN | 按动作、实体类型和操作者分页查询只读审计记录 |

知识点与题目修改都携带 `expectedRevision`。发生并发修改返回 409，客户端必须重新加载，不能静默覆盖。知识点合并会把源记录标为 deprecated 并写入 `merged_into_id`，逐题迁移关系；目标关系已存在时折叠为一条，任一原关系为 core 则保留 core。源记录、合并历史和审计记录均不删除。题目管理 DTO 保存作者、审核、原题型、展示类型和判题模式；这些字段不进入普通玩家作答 DTO。

题目管理列表与审核中心共用同一套搜索与排序规则（见 [`PROJECT_RULES.md`](./PROJECT_RULES.md) §10.6）：

```text
query 支持 “年份-题号” 结构化搜索：2020-7 / 2020 - 7 / 2020—7
  → 命中 exam_year=2020 且 question_number ∈ {"7","2020-7"}
默认排序：来源 → exam_year → question_number 自然排序（1 < 2 < 7 < 10 < 22）→ question_id
展示题号：displayQuestionNumber = QuestionNumberFormatter.display(questionNumber, examYear)
  exam_year=2020 + raw "2020-7" → display "7"，列表显示 "2020-7"（不是 "2020-2020-7"）
  编辑器输入框仍使用并保存原始 questionNumber，display 值不回写数据库
```

普通题目管理页（`reviewOnly=false`）按 `questionType` 筛选，不再发送 `status`；审核中心继续固定 `status=pending_review`，因此后端 `status` 参数必须保留。默认排序的 sort key 由共享 `QuestionNumberSort` 在 DB 级形成（MySQL 5.7 兼容，接收题号与 `exam_year` 两个表达式以判断同年份前缀），Management 与全平台题库复用同一规则。

题目管理请求与响应包含 `sourceId`；响应另带 `sourceType`、`sourceName`（当前展示名）和
`sourceCanonicalName`。保存时服务端按 `sourceId` 重新读取来源，忽略客户端伪造的类型和名称，
并同步 `question_resource.source_type / source_name` 兼容快照。新绑定只接受 active 来源；
已绑定 disabled 来源的历史题仍可读取和编辑其他字段。浏览器 Question create/update 必须绑定已有
`sourceId`，不会再按客户端提交的 `sourceType / sourceName` 隐式创建来源；ADMIN 的
`global-question-batch/v4` 导入仍可按兼容字段解析或创建来源。管理前端不提供自由输入来源名，
来源统一在“来源管理”创建。已有题目绑定的来源禁止修改 `sourceType`，未绑定来源可以修改类型。

实时 Question API 与新 attempt metadata 按 `question_source.display_name → legacy source_name → 全服题库`
解析来源。`study_attempt.question_snapshot_json` 是创建 attempt 时的冻结快照，来源后来改名不会修改历史快照。

知识点合并还会在同一事务中迁移 Learner Focus、attempt target、Knowledge Evidence、题目级掌握槽位与 Knowledge Guide。若源与目标同时已有状态，服务端会把题目关系与历史正式作答归一到目标知识点，并重建唯一的 V3 聚合状态。

### 题干图片 API（PR11）

正式 Question 可选绑定 1 张 immutable 题干图片；长期规则见 [`PROJECT_RULES.md`](./PROJECT_RULES.md) §6.2。

```http
POST /api/v1/manage/question-images
Content-Type: multipart/form-data
file=<binary>

200 → { id, url, originalName, contentType, byteSize }
```

```http
GET /api/v1/question-images/{assetId}

200 → image bytes
      Content-Type: image/png | image/jpeg
      ETag: "<sha256>"
      Cache-Control: public, max-age=31536000, immutable
404 → { message: "题目图片不存在。" }
```

契约：

```text
只接受 PNG / JPEG，按 magic bytes 判定，不信任扩展名与原始 Content-Type
单张最大 5MB；超限统一 413 { message: "图片不能超过 5MB。" }
非图片统一 400 { message: "只允许上传 PNG 或 JPEG 图片。" }
asset id 与 storage name 均为 UUID；originalName 只作为 metadata 保存
读取只按 asset id，不接受任意路径
asset 是 immutable 的：同一个 id 的字节与 ETag 永不改变
```

`GET /api/v1/question-images/{assetId}` 由 `anyRequest().permitAll()` 放行，因此 Hub / World
的 `<img>` 在未登录时也能取到图；它只按不可猜测的 asset UUID 寻址，不接受任意路径。
本 PR 不做 CDN / OSS / 签名 URL，也不把图片变成可枚举的公开资源。

DTO 字段：

```text
Question Management 读 / 写：QuestionView.stemImageId、QuestionView.stemImageUrl
                              QuestionRequest.stemImageId（null 表示移除绑定）
正式 Question 与 Attempt 快照：QuestionDto.stemImageUrl
Learning Hub 浏览：BrowseQuestion.stemImageUrl
错题本：WrongQuestion.stemImageUrl
专项练习：PracticeAttempt.question.stemImageUrl
```

`stemImageId` 必须指向已存在的 asset，否则 create / update 返回
`400 { message: "题目引用了不存在的题干图片。" }`。URL 一律由 asset id 现场生成，
不入库；替换或移除当前 Question 的图片都不会删除旧 asset，也不会改变历史 Attempt。
`global-question-batch/v4` 批量导入仍然只处理文本题，不接受图片或 asset id。

## Learner Knowledge State V3

正式 World 发题时，`study_attempt` 固化 `targetKnowledgePointId`、`evidenceMode`（普通正式训练一律 `normal`）与 1–5 级题目难度。清晰可归因的 graded attempt 只为该 target KnowledgePoint 插入一条 evidence，并更新 `(learnerId, knowledgePointId)` 唯一状态；题目关联的其他 core/auxiliary 知识点不直接更新。一次 attempt 只有一个 `targetKnowledgePointId`，一题绑定多个知识点不会同时给多个知识点加分。Practice Selection V2 之后，普通正式训练（RANDOM / CHAPTER / KNOWLEDGE / WRONG）的 wrong / partial 也**立即**对 root target 写 evidence，不再延迟到诊断确认，也不再创建诊断会话。`UNIQUE(attempt_id)` 与既有 attempt 状态转换共同保证重复提交不会重复记证据。Legacy `/games/**` 没有 Learner，因此不创建长期掌握状态。

每个 `(learner, KnowledgePoint, formal parent Question)` 拥有一个 0–100 的题目级掌握槽位。首次答对为 30；以 Asia/Shanghai 为日界线，同一天重复答对不增加槽位分数，后续每个首次跨日答对增加 7，最高 100；当天首答 wrong/partial 当天 +0（正式 attempt 与 evidence 仍保留），错误不直接扣减槽位分数。Remedial SubQuestion 不创建槽位，也不进入分母。

正式题集合只有一套口径（`KnowledgeQuestionCoveragePolicy`）：与该 KnowledgePoint 存在 `question_resource_knowledge` 关系的全部 published Formal Parent Question，**core 与 auxiliary 同等计入**，按 Question ID 去重。`relation_role` 只用于知识标签主次显示与诊断/内容解释，不决定题目能不能做，也不决定是否进入分母。

```text
Knowledge Mastery = 所有正式父题槽位分数之和 / 当前正式父题总数（core ∪ auxiliary，按题目 ID 去重）
```

例如某 KnowledgePoint 关联 3 道正式父题（core / core / auxiliary），Learner 只做对其中 1 道且首答正确：

```text
30 / 3 = 10.0%
```

每个题目槽位按完整 3 个 Asia/Shanghai 自然日惰性衰减 1 分。KnowledgePoint 的全部正式题均达到 100 时冻结衰减；新增正式题会扩大分母并解除冻结，既有槽位从解除当天重新开始衰减。聚合档位为 0–29 尚未稳固、30–69 基本掌握、70–99 熟练掌握、100 彻底掌握。`stabilityDays` 与 `targetDifficulty` 继续供 Review Queue 与 Adaptive Scheduling 的难度软提示使用，原参数不变。

正式 Hub 与 World 共用同一套 Question Contract、Attempt Variant、target-only grading、Wrong Book、Evidence、Question Rotation 与 V3 槽位。普通正式训练的选题分别由 RANDOM / CHAPTER / KNOWLEDGE / WRONG 四套独立策略负责，**不自动进入** Diagnosis / Training / Remedial。题目浏览、查看答案、reveal、发题、开始或放弃活动都不产生 evidence。

## Adaptive Scheduling V1

正式 World / 副本的普通正式题走 **RANDOM KP-first**：开始活动时冻结 selected Book scope，先在该范围内的 active KnowledgePoint 中选 target KP，再在该 KP 内按 oldest / wrong lane 选题。

```text
selected Book(s)
→ 这些 Book 下全部 active KnowledgePoint（当天仍有可出正式题者）
→ 与这些 KnowledgePoint 有关系的 published Formal Parent Question（core + auxiliary 都算覆盖）
→ 排除今天已 RANDOM 出过的 Question 与 本 run 的 seenQuestionIds
→ 先选 target KP，再在该 KP 内选题
```

仍然保留的 Book-level 题池 API（`KnowledgeQuestionPoolService.candidatesForBooks` / `selectBookQuestion`、`KnowledgeQuestionPoolStore.candidatesForBooks`）继续按 `question_id` DISTINCT 去重，并保持 store / service 层能力测试；它们已不在现代 Learner World 的发题路径上。

不存在任何前置 KP gating：dependency readiness、`effectiveMastery`、Review due、preferred difficulty 与 exposure 都不参与某道正式题能否被抽到。同一道题关联多个 KP、或同时属于多本 selected Book 时都只出现一次。run 冻结 `allowedBookIds`、`allowedKnowledgePointIds`（RANDOM 的 KP scope）、`plannedRounds` 与 `seenQuestionIds`；**不再预选 rounds 个不同 KnowledgePoint**，因此 knowledgePointIds 可以为空，知识点数量也不会限制副本能否开始。

`rounds` 的新含义是“本轮最多完成多少道正式题”。现代 Learner World 取 `plannedRounds = min(rounds, 当天剩余可出的随机题数)`，`plannedRounds` 在 run 上返回；为 0 时开始活动直接返回 400 业务提示，不会先启动 run 再报错。每完成一道 Formal Parent 的 grading（correct / wrong / partial 都算）推进一个正式题 slot。

RANDOM 是 KP-first：先选 target KnowledgePoint，再在该 KP 内按 oldest / wrong lane 选题；该 KP 就是本次 attempt 冻结的 target，不再重新解析。仍然保留的按题目 ID 发题路径（Book-level 题池 API、`wrong_review` 等）继续按“scope 内 core 优先、其次 auxiliary”稳定解析 target。一次 attempt 不会给该题的全部 core + auxiliary 同时加分。

正式题发题条件只有：published + `parent_question_id IS NULL` + 四个正式题型 + 当前上下文范围 + 本 run `seenQuestionIds` 排除；RANDOM 另加“同一 Asia/Shanghai 业务日同一 Question 最多出一次”（`study_attempt.draw_mode='random'` 为事实来源，`active` / `revealed` / `graded` 都占额度）。当天首次 RANDOM 发题与上一 RANDOM 正确后的 KP 切换，按 Learner 跨天持久交替使用 ALL / active 错题关联 KP 池；WRONG 池回退 ALL 仍消费该槽，wrong / partial、assessment=null 与非 RANDOM 模式不消费。上一 RANDOM Attempt 由服务端持久指针确定，不用秒级 `created_at` 并列后的 UUID 排序；升级前最大秒并列且无指针时按上一题未知处理。普通正式题答错后**不再**补救训练、不再 retry 同一道题，也不再创建诊断会话。KnowledgePoint 专项仍限定当前 KP，Chapter Practice 仍限定当前 Book + Chapter，Wrong Drill 仍限定 active 错题。完整策略见 [`question-practice-policy.md`](./question-practice-policy.md)。

难度仍作为软提示保留并随 `QuestionContext.preferredDifficulty` 传递：未开始或有效掌握度低于 40 时为 2，40–70 为 3，70–100 为 4，100 为 5；`standard` 使用 `min(targetDifficulty, cap)`，`gentle` 再下调一级但不低于 1。它不阻止任何正式题被抽中。只有保留的 TRAINING 模式（Remedial / 诊断补强流程，普通正式训练已不再进入）仍优先 `difficulty <= 2` 的低难候选，没有低难题时取合法候选中的最低难度。

Legacy `/games/**` 没有 Learner，仍按启动时冻结的 KnowledgePoint 顺序出题，不读取 Mastery，也不参与现代 RANDOM 选题。它的 `plannedRounds` 等于 `planKnowledgePoints(...).knowledgePointIds().size()`，**不受**题池题数影响；两条路径的轮数必须在各自分支内独立计算。

Learner World 开始活动时 `plannedRounds = min(activity rounds, 当天剩余可出的随机题数)`。剩余为 0 时直接返回 400：范围里根本没有正式题时是 `当前学习范围内没有可用的正式题。`，只是今天已经出完时是 `今天学习范围内的随机题已经全部出过了，明天再来吧。`，都不会先给出 `plannedRounds=1` 再在发题时失败。

## Learner Question Rotation V1

正式 Learner World 把 `study_attempt.created_at` 作为 Question Exposure 的事实来源：题目一经发出即计入，不要求存在 `answer_record`，因此 active、revealed 和 graded attempt 都有效。Exposure 以 `(learner_id, question_id)` 聚合，不按 World 隔离；Learning Hub 的知识点或题目浏览不会创建 attempt，也不会进入 Exposure 历史。Legacy `/games/**` 继续使用原有随机选择。

Exposure 只是事实记录，不参与跨 run 软排序：正式题不按 exposure、preferred difficulty 或最久未见排序，只在当前 Session 内用 `seenQuestionIds` 做硬排除。新 Session 从空 seen 开始，全部题目重新进入随机池，因此单题知识点或全部题目都见过时仍能继续出题。

Exposure 不删除候选，不设置固定 cooldown 或 blacklist，也不阻断 Task 重试。V10 只为 `study_attempt(learner_id, question_id, created_at)` 增加查询索引，不新增 Exposure 表、状态列、Evidence mode 或前端 Exposure UI。

## Forgetting-aware Review Queue V1

Review Queue 是 `LearnerKnowledgeState` 的动态派生视图，不新增 Review 表、`next_review_at`、定时任务或 migration。只有 `evidenceCount > 0` 且最近证据后的原始 `masteryScore >= 70` 的状态具备复习资格；未开始和最新 mastery 低于 70 的知识点继续属于正常学习队列。

建议复习时间继续沿用现有 Stability 与聚合 Mastery 反推：

```text
reviewDueAt = lastEvidenceAt + stabilityDays × log2(masteryScore / 70)
```

`reviewDueAt <= now` 返回 `due`，未来 24 小时内返回 `soon`，24 小时以后至 7 天内返回 `upcoming`，更远的状态不进入默认列表。API 只读取当前 Learner 的 Selected Books，按 KnowledgePoint ID 去重，批量读取状态，并复用“是否存在正式题”的可练判定；`playable=false` 只表示该知识点当前不存在可发的正式题。

Review Queue 只负责 Learning Hub 的复习安排，不改变正式 World target 的选择。学习者从 Review Queue 进入知识点专项后，仍走同一套正式题随机抽取，不新增前置限制；答错按单层化规则直接对 target 记 evidence，**不再**进入 Phase G diagnosis。证据模式对普通正式训练一律是 `normal`，`training` 只出现在保留的补救能力里。

Learning Hub 首页展示“今日巩固”摘要，`/reviews` 展示三个时间窗口并链接到知识点说明页。复习队列的读取与浏览本身不创建 attempt 或 evidence；进入 Phase J 的知识点专项后，正式作答仍通过共享 Question Engine 更新既有 Mastery，自然推迟下一次 due 或回到薄弱学习队列。

## Learning Progress Dashboard V1

`GET /learner/progress` 是只读动态派生视图，不保存 progress、completion 或 daily summary。总体范围取当前 Learner 的 Selected Books，并沿正式 `question_bank → question_bank_chapter → question_bank_knowledge` 模型读取 active KnowledgePoints；总体按 KnowledgePoint ID 去重，单本文集仍按自己的 membership 统计。状态通过一次批量查询读取，`started` 定义为 `evidenceCount > 0`，`ready` 使用 V3 惰性结算后的聚合 Mastery `>= 70`，`proficient` 表示聚合 Mastery 正好为 100。

文集响应包含按单层正式章节组织的聚合；每个章节只统计自己的直接 KnowledgePoint membership，并按 KnowledgePoint ID 去重。Review 数量直接复用 Review Queue 的 `due / soon / upcoming` 派生结果，错题数量直接读取永久错题本 `learner_wrong_question` 中该 Learner 的 `active` 记录数（不受该题后来是否答对影响）。

近 7 日足迹与六个核心指标统一由 `LearnerActivityStatsService` 派生（PR7 进度统计 V3），SQL 只在 `LearnerActivityStore`，口径见 `PROJECT_RULES.md` §14.1。`graded`（`assessment ∈ {correct, partial, wrong}`）与已 reveal 的 `revealed_only` 都算有效答题；`active`、窗口外记录与 `learner_id IS NULL` 的 Legacy attempts 不算。一次 Attempt 先 `reveal` 再自评只计 1 次，业务日归到首次有效行动所在日。所有指标受当前 Selected Books 范围约束，并按冻结 `target_knowledge_point_id` 归属；取消文集只隐藏历史，不删除任何 Attempt。

「最近接触知识点」（`recentContacts`）按 `lastEffectiveContactAt`（有效接触时间）排序，因此仅查看参考解析也能正确出现；`lastEvidenceAt`（Mastery 最后证据时间）与 `evidenceCount` 只在真实评分后才有值。行为标签由最近一次 Attempt 的真实状态决定：`lastOutcomeRevealedOnly` 才是「仅查看答案」，`lastGraded && evidenceCount == 0` 是「已作答 · 暂无掌握证据」（`LearnerKnowledgeStateService.apply()` 在 `!mastery.effective() && !migrated` 时会提前返回，这是合法状态），两者都不展示百分比。

旧 `recent` 的 `gradedAttempts7d / distinctKnowledgePoints7d / activeStudyDays7d / daily` 保持 graded-only 且按 `answered_at` 评分日归属业务日，窗口同时检查起点与终点。同一个跨天 reveal→自评 Attempt 在 `activity`（首次有效行动日）与旧 `recent`（评分日）分别计一次，属于两个字段各自的正确含义。

`activity.daily` 的窗口由可选 `days=7|30|90` 决定（缺省 7），只影响该数组长度与 `windowDays`；`activity.metrics`、`activity.outcomes`、`recent` 与 `recentContacts` 都不随它变化，进度页顶部的 7/30/90 天分段选择器只驱动两张活动趋势图。响应不提供正确率、错误率、失败次数或排名。

## Diagnostic State Machine V1（保留能力，普通正式训练不再自动进入）

> Practice Selection V2 之后，RANDOM / CHAPTER / KNOWLEDGE / WRONG 都不再调用
> `DiagnosticLearningService`。下面这套状态机、相关表与字段**全部保留**，
> 供未来重新设计诊断时复用，也保证历史数据仍然可读；但它不再由普通正式训练自动触发。
> 本节描述的是这套保留能力自身的行为，不是当前普通训练的推进规则。

保留的状态机在**被显式调用**时（服务级 API / 未来新入口）行为如下：

normal composite Question 的 wrong/partial 会从 root `question_snapshot_json.knowledgePointIds` 取得发题当时的依赖关系，而不查询当前 Question relation。依赖在创建会话前沿 KnowledgePoint merge chain 解析到 active canonical id、去重并排除 canonical target；按有效掌握度更低、最后证据更早、快照稳定顺序排列。`learner_diagnosis_session` 保存根 attempt、原目标、状态、resolution 与 revision，`learner_diagnosis_dependency` 保存有序 dependency 状态；WorldState 只保留 `diagnosisSessionId` 指针和题数，不复制诊断事实。

状态机使用 `diagnosing_dependencies → remediating_dependency → rechecking_target → remediating_target → resolved`，并支持 `abandoned`。dependency probe 使用 normal evidence，难度先按当前 Profile/状态计算再 cap 到 3；wrong/partial 立即成为该 dependency 的正常证据并停止探查其他依赖，training remediation 答对后回到原 target。所有 dependency 均通过时，才用 root 原始 assessment、grading source 与 answeredAt 写入 target negative evidence；存在 unavailable dependency 时改走 target recheck，根错误永远不强行归因。用户 abandon 同样保留 raw answer 而不补根 evidence。

`study_attempt.diagnosis_session_id` 与 `diagnosis_role` 记录 `dependency_probe`、`dependency_remediation`、`target_recheck` 或 `target_remediation`。角色表达诊断目的，证据模式只使用 `normal` / `training`；Mastery V3 只改变题目级掌握聚合，Adaptive Scheduling 的难度软提示不改变状态机。诊断题受冻结 Selected Book scope、published 与 seen 约束。Probe 没有合法 unseen 候选时标为 unavailable，不回退未发布题或无题的依赖。`target_recheck`、`target_remediation` 与 `dependency_remediation` 允许复用本轮已见题：知识点只有一道正式题、或核验/补救所需题已被 root 用过时，仍重新发卷，避免单题知识点导致整轮诊断失败。

部署前遗留的旧 diagnosis 在现代流程中会被标记为 `abandoned`，不阻塞下一道普通正式题。旧 World run 上的 `training` / `trainingAnswered` / `diagnosticAnswered` / `diagnosisSessionId` 字段只作为 Legacy 状态兼容保留：现代 Learner World 每完成一道正式题（correct / wrong / partial）都推进一个 slot，并保持 `training=false`、`retryQuestionId=null`、`diagnosisSessionId=null`。Legacy `/games/**` 仍保留“答错后继续练同一题”的兼容分支。新 run 会重置 answered、correct 与 seen；正式 World 的长期考试/任务状态不累计失败或应试次数，只让 bestScore 上升；passed/cleared 与一次性奖励保持永久、单次和单调。未完成任务可以重新开始，完成后永久关闭且不能重进。

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

`Bank.knowledgePoints` 是文集内的知识点目录；`Question.knowledgePointIds` 必须引用其中 1–3 个知识点。PublicQuestion 不含 answer、aliases、keywords、explanation，但会附带本题对应的 `knowledgePoints`，用于题面提示与知识点解析；批卷后的 Result 再返回 standard（仅客观题）、explanation、correct、story、changes。正式题的 `Question.answer` 是后端 runtime-derived grading value，不是题库保存的配置答案（见上文 Question Contract V2）。
当前协议以 options 对象的插入顺序表示显示顺序，键只用 A–F 或 true/false。Java 应使用保序映射（例如 LinkedHashMap）输出洗牌后的选项；不能随意按键排序。

### Attempt Variant（正式客观题）

正式 `single_choice` / `multiple_choice` / `true_false` 的选项排列在**创建 Attempt 时**完成一次，
Hub Practice 与 Learner World / 副本共用同一套 `QuestionAttemptVariantService` 规则：

```text
correct_option → 后端 runtime derived answer → variant 排列 + remap
→ 冻结 question_snapshot_json 与 study_attempt.standard_answer_json → 判题
```

边界：

```text
作答前 DTO 不得暴露 correct_option，也不得暴露 derived standard
刷新同一 attempt：选项顺序与 standard 不变化
再次做到同一 Question：可重新排列，并尽量避免与上次完全相同
true_false 交换选项文字时 boolean standard 同步 remap
作答后客观题 result 可返回 Attempt-specific standard 用于批卷
solution reveal / self-assessment 不返回 separate standard
Legacy /games/** 保持既有兼容行为
```

`study_attempt.standard_answer_json` 是服务器内部冻结的判题事实，不是客户端可提交的配置；
题目浏览（`GET /learning/questions/{id}`）不创建 attempt，也不计入 Exposure。

## 全平台题库（`/questions`）

Hub 顶栏把两个概念分开：**知识**（技术 route 仍是 `/books`，展示 Book → Chapter →
KnowledgePoint 目录）与**题库**（`/questions`，展示全平台 published Formal Parent
Question）。`/books` 不改名、不改 API，只调整展示名与用户文案。

全平台题库是**全局只读浏览**，与 Learner 当前 selected Books 解耦：

```text
没有选中某本文集也能浏览平台已发布的正式题与题目详情
浏览题目不创建 study_attempt，不计 Exposure
不影响 Mastery / Wrong Book，也不消耗 RANDOM 每日额度
训练入口（RANDOM / CHAPTER / KNOWLEDGE / WRONG）的 scope 一律不放宽
```

题目卡片展示 `sourceName`、`examYear`、`displayQuestionNumber`、`questionType`、
`difficulty`、`contentMarkdown` 与 KnowledgePoint 标签，支持 inline preview 与
“查看答案与解析”进入 `/questions/{id}`。前端每页固定 20 条。

题目详情里的知识点标签**默认只展示不跳转**：题目可能关联 Learner 尚未选择的 Book
下的 KnowledgePoint，跳 `/knowledge/{id}` 会 404。`KnowledgePage` / Practice 等已确定
学习范围的上下文继续使用可点击标签。

正确答案继续走 Question Contract V2：

```text
question_resource_option.correct_option
→ QuestionAnswerDeriver
→ correctAnswer
→ 前端 AnswerDisplay（单选 C 显示为 C，不带 JSON 引号）
solution → 只有 analysis_markdown，显示为“参考解析”
```

`standard_answer_json` 不参与读取；历史脏数据（例如缺少选项的正式题）会让
`correctAnswer` 明确为 `null`，不会让整个详情返回 500。

## 后端实现应保持的行为

- 每次发卷生成新 attemptId，保存题目与答案快照；重新读取同一课卷不再洗牌。
- 普通活动以配置的 rounds 为上限，并按当天剩余 RANDOM Question 数收敛 `plannedRounds`；运行时逐题选择 target KP，不预先冻结一组互不相同的知识点。
- 每道正式题只占一个进度 slot；correct / wrong / partial 都推进。wrong / partial 后尽量留在原 KP，correct 后切换 KP，不再自动追加低难训练题。
- ActivityRun 返回 knowledgePointIds、knowledgePointIndex、plannedRounds、training、trainingAnswered、diagnosticAnswered、diagnosisSessionId 与 seenQuestionIds，客户端只负责展示，不自行推断进度。现代 Learner World 的 `training` / `trainingAnswered` / `diagnosticAnswered` / `diagnosisSessionId` 只作为 Legacy 状态兼容存在，始终是 `false` / `0` / `0` / `null`；进度分母使用 `plannedRounds`。
- 同一 attemptId 重复提交不重复奖励。已换题时旧答题请求返回明确错误。
- next 的旧 attemptId 重试返回当前进度，不连续跳题。
- 未判完题、未处理际遇时不允许跳过。
- choose 按 eventId 保证同一际遇只结算一次。
- 题库修订只影响以后发卷，不影响已有快照和历史记录。
- 导入先完整校验，再原子保存；备份创建新人生并重映射题库引用。
- 晋章、复习记录、奖励、历史与下一事件一起保存，避免半套状态。
- 正式题型仅允许 `single_choice`、`multiple_choice`、`true_false`、`solution`；其中 `solution` 展示为“综合题”，使用 `presentationType=self_assessment` 与 `gradingMode=self_assessment`。`blank` 不能新建、保存、导入或发布，历史 `blank` 也不会进入正式题池。原填空题须由题目生成 AI 保留 Question UUID 并改编为单选题或多选题。正式综合题只有一份 `analysis_markdown`（包含答案、过程与解析），不再有独立参考答案；显式 reveal 只返回这一份内容，self-assessment 也不再返回 separate standard。评定仅接受 correct/partial/wrong，同一 attemptId 只能形成一条作答记录。
- 正式题数量、Mastery 分母、专项候选与 Chapter 可练数共用同一口径：与该 KnowledgePoint 有关系的全部 published Formal Parent Question，core 与 auxiliary 同等计入并按题目 ID 去重。`relation_role` 只表达知识标签主次，不决定题目能不能做，也不决定是否进入分母。
- 正式做题页的题目 metadata 在发题时冻结进 `question_snapshot_json.examMetadata`：`subjectName`、`sourceName`、`examYear`、`questionNumber`（数据库原始题号）、`displayQuestionNumber`（UI 使用）、`examLabel`，以及 `knowledgePoints[{id,name,role}]`（core / auxiliary 都返回，role 只表达主次）。刷新或重新读取同一 attempt 结果稳定。
- 该 metadata 由共享的 `QuestionExamMetadataBuilder` 统一生成：Learning Hub Practice（`LearnerPracticeService`）与 World / 副本（`GameActionService`）调用同一个 builder，不存在两套规则。World 的正式发题同样把 metadata 冻结进 `study_attempt` 快照，前端不重新查库拼接。
- 题号格式化由 `QuestionNumberFormatter` 统一实现：只有 `questionNumber` 确实以 `examYear + "-"` 开头时才剥离年份（`2014-1` + year 2014 → `displayQuestionNumber = "1"`）；`3`、`2021-3`（年份不同）、`A-3`、`3(1)`、`21A` 一律原样保留。UI 只使用 `displayQuestionNumber` 生成“第 N 题”，原始 `questionNumber` 仅作为数据事实。
- `examLabel` 由后端按 `question_resource.exam_year` 与 `subject_name` 动态生成，不新增 `question_tag` 冗余表：数学一 + 2021 → `2021年考研数学一真题`；408 + 2024 → `2024年408考研真题`。年份缺失或科目未知时不生成标签。

本地模式完整题库在浏览器可见，是单机体验。生产环境应由数据库统一维护 Bank、KnowledgePoint 与 Question；发题接口只返回 PublicQuestion，作答前不暴露 correct_option 或 derived standard，Attempt 判题标准在创建 attempt 时冻结进 `study_attempt.standard_answer_json`，只在服务端判题后随 Result 返回。若加入考试排名，还需实现身份认证、事务、防重复提交与题库管理权限。


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
| GET | /learner/wrong-questions | 无 | 当前学习范围内可练的永久错题列表：每项仍返回 `questionId`、历史 `targetKnowledgePointId`、`knowledgePointName`、`contentMarkdown`、`subjectName`、`examYear`、`questionNumber`（原始）、`displayQuestionNumber`（UI）、`examLabel`、`knowledgePoints[{id,name,role}]`、`lastGradedAt`、兼容字段 `available=true`、`unavailableReason=null` |
| DELETE | /learner/wrong-questions/{questionId} | 无 | 204；Learner 手动移出错题本 |
| POST | /learner/practice-sessions | intent, targetKnowledgePointId/sourceQuestionId/targetBookId/targetChapterId | 开始练习；intent 允许 `knowledge_drill` / `chapter_drill` / `wrong_review` / `wrong_drill` |
| GET | /learner/practice-sessions/active-chapter | 无 | 当前 active 章节 Session；没有时 204 |
| GET | /learner/practice-sessions/recent-chapter | 无 | Study 页最近章节入口：`status=active|last|none`、`activeSessionId?`、`lastSessionId`、`bookId`、`bookName`、`chapterId`、`chapterName`、`currentKnowledgePointId?`、`currentKnowledgePointIndex?`、`knowledgePointCount?`、`updatedAt`。`currentKnowledgePointIndex` / `knowledgePointCount` 是“当前题在章节确定性题序中的位置 / 题序长度”（Question 粒度） |
| GET | /learner/practice-sessions/{id} | 无 | 恢复 Session 与 current attempt |
| POST | /learner/practice-sessions/{id}/answers | attemptId, questionId, answer | 自动判题，写 Wrong Book / Mastery / Evidence；不再触发 Diagnosis |
| POST | /learner/practice-sessions/{id}/reveal | attemptId, questionId | 查看自评题参考解析（单一内容，无 separate standard） |
| POST | /learner/practice-sessions/{id}/self-assess | attemptId, questionId, assessment | 提交自评，同样只写 Wrong Book / Mastery / Evidence |
| POST | /learner/practice-sessions/{id}/no-idea | attemptId, questionId | 正式“我没思路”：不伪造答案，直接 graded wrong，写 Wrong Book / Mastery / Evidence |
| POST | /learner/practice-sessions/{id}/next | 无 | 进入该模式的下一道普通正式题：`knowledge_drill` 本 Session 随机池耗尽返回 409；`chapter_drill` 走确定性题序 successor（末尾 wrap，不永久 complete）；`wrong_drill` active 错题池耗尽返回 409；`wrong_review` graded 后返回 409 |
| POST | /learner/practice-sessions/{id}/end | 无 | 结束 Practice Session；顺带把遗留未完成 diagnosis 标记为 abandoned |

`POST /learner/question-reports` 接收 `attemptId`、固定枚举 `reason` 与最多 1000 字的可选
`comment`。learner / question 均从当前 Learner 自己的 Formal Parent Attempt 派生；同一
Learner + Attempt 重复提交返回 409。`GET /manage/question-reports` 与
`PATCH /manage/question-reports/{id}/status` 仅 REVIEWER / ADMIN 可用，固定每页 20 条，
状态只能从待处理收口为 `resolved` 或 `dismissed`。

World 对应动作是 `POST /worlds/ancient-official/answers/no-idea`，请求只含
`attemptId` / `questionId`，语义与 Hub 完全一致。

V11 新增 `learner_account_role`，把旧 `app_user` 按 username 并入已有或新建 Learner，并为历史 audit/merge 增加 additive `actor_learner_id`。V12 新增 `learner_practice_session`、冻结范围的 `learner_practice_scope`、`study_attempt.practice_session_id`，并使 Diagnosis 支持 world 或 practice 两种互斥上下文。V21 为 `study_attempt` 增加 `draw_mode` / `draw_reason` 与索引 `(learner_id, draw_mode, created_at, question_id)`。V23 新增 `learner_random_kp_rotation`，按 Learner 事务性保存 RANDOM 第一层请求池与消费次数；V24 新增 `learner_random_attempt_cursor`，保存严格的最近 RANDOM Attempt 指针。两者都不回填旧 Attempt，已执行 V23 的开发库按正常 Flyway 顺序升级 V24。

Knowledge drill 不保存 checkpoint、固定题数、score、pass 或 fail。Knowledge drill 与 Wrong drill 都是 Session 内随机且不重复，候选耗尽即本轮完成，新开 Session 重新洗牌。章节练习走固定的确定性题序（Chapter 内 KnowledgePoint `sort_order` → 稳定 Source identity → `exam_year` → `question_number` 自然排序 → `question_id`），跨 Session 持久 cursor，末尾 wrap。三者都只受 published 正式父题 + 上下文范围 + Session 内 seen 约束，今天已答对、Review 未到期或已掌握都不阻止再练，也不会因此返回“当前没有待练题”。完整策略见 [`question-practice-policy.md`](./question-practice-policy.md)。

Wrong Book 使用 `learner_wrong_question` 持久化，不按 latest graded attempt 派生：Formal Parent Question 出现 wrong / partial 即 upsert 为 `active`，之后 correct 不自动移除，只有 Learner 手动移出才置为 `removed`，以后再次 wrong / partial 重新回到 `active`。列表只返回当前 selected Books 范围内、通过至少一个有效 core / auxiliary 关系可练的错题；取消文集只隐藏并同步减少学习页数量，重新选择立即恢复历史。`wrong_review` 的 `sourceQuestionId` 必填：历史 target 当前仍合法时沿用，否则选择当前合法稳定绑定作为新 Attempt target，历史错题归因不改写。`wrong_drill` 按 Session 冻结 KP scope 从相同多 KP 关系事实中随机连续刷 active 错题，Session 内不重复；本轮耗尽后 `POST .../next` 返回 409，0 道可练错题时启动返回 400 友好提示。快速练习中答对不会自动移出错题本。`GET /learner/progress.summary.wrongQuestions`、错题列表、两种错题练习和 RANDOM 错题 KP 池共享上述范围口径；`GET /learner/statistics` 的 `wrongReviewAttempts` 同时统计 `wrong_review` 与 `wrong_drill`，且是 **graded-only 出场分布**；该接口范围判定复用 `LearnerActivityStatsService#scopedKnowledgePointIds`，累计指标与 `/learner/progress.activity` 同源（含 reveal-only），两者语义不同但不会出现两套范围口径。

Hub Practice 与 World 共用同一套 Formal candidate 查询、Question Contract V2、Attempt Variant、grading、Wrong Book 与 Mastery / Evidence 逻辑，但选题与推进由各自独立的 strategy 负责（`KnowledgePracticeSelector` / `ChapterPracticeSelector` / `WrongPracticeSelector` / `RandomPracticeSelector`）。四套策略都不再调用 Diagnosis / Remedial。Hub mutation 校验 learner/session owner，且不写 `learner_world_state`。

World / 副本的普通正式题走 RANDOM 策略：先选 target KnowledgePoint，再在该 KP 内按 oldest / wrong lane 选题，并把 `targetKnowledgePointId` 直接冻结到该 KP。同一 Learner × 同一 Asia/Shanghai 业务日，同一 Question 最多创建一次 RANDOM Attempt（`active` / `revealed` / `graded` 都算占用）；Chapter / Knowledge / Wrong 的作答不消耗这个额度，RANDOM 也不阻止它们练到同一题。开始活动时 `plannedRounds = min(activity rounds, 当天剩余可出的随机题数)`，为 0 时返回 400“今天学习范围内的随机题已经全部出过了，明天再来吧。”（范围内根本没有正式题时返回 400“当前学习范围内没有可用的正式题。”）。`Game.adventure.run.knowledgePointIndex` 表示本轮已完成的正式题数，correct / wrong / partial 都推进一个 slot。
