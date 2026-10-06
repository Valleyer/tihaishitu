# 题海仕途 · Codex 工作交接文档

> 生成日期：2026-10-04（Asia/Shanghai）  
> 仓库：`Valleyer/tihaishitu`  
> 本文依据当前仓库、当前分支、PR #9、Flyway migration、关键前后端代码与 GitHub Actions 实际状态生成。它面向完全没有旧对话历史的新 Codex 对话。

## 1. 项目一句话定位

题海仕途不是“游戏里塞题”，而是一个以 KnowledgePoint、Question、长期学习进步和高效刷题为核心的联机学习平台；游戏世界只是为了提高学习兴趣、持续性和沉浸感的体验容器。

永久原则：

```text
Learner = 唯一长期主体
KnowledgePoint = 学习主线
Question = 训练资源
Learning Hub = 学习控制中心
World = 游戏化体验容器
```

主世界负责“我要学什么、我学到了什么”；子世界负责“让我愿意继续学下去”。

## 2. 当前 Git / PR 状态

以下值均在生成本文时实时核实：

| 项目 | 当前值 |
| --- | --- |
| Repository | `https://github.com/Valleyer/tihaishitu.git` |
| Default branch | `main` |
| Current working branch | `codex/learning-hub-world-foundation` |
| Current HEAD | `746439038e1be40cc2f0308a3ff38ee5180a3be5` |
| Current PR | `#9` |
| PR title | `feat: establish Learning Hub and world foundation` |
| PR URL | `https://github.com/Valleyer/tihaishitu/pull/9` |
| PR state | `OPEN` |
| Mergeable | `MERGEABLE` |
| Merge state | `CLEAN` |
| Base branch / SHA | `main` / `1b59e5e706e60bdaebacbb7a6773fb8200a5bf39` |
| Head branch / SHA | `codex/learning-hub-world-foundation` / `746439038e1be40cc2f0308a3ff38ee5180a3be5` |
| PR commit count | 3 |
| PR diff | 48 files, about 1995 insertions and 114 deletions |
| Backend CI | Green (`test`, SUCCESS) |
| Frontend CI | Green (`check`, SUCCESS) |

PR 三个提交：

1. `6a448c74b62a0a889ca1c3b437de48c5ba6828cb` — `feat: add learning hub and world foundation`
2. `8f044ffdb6116c5bacc72d7edb2198de0a8cd1f4` — `fix: expose only active learning books`
3. `746439038e1be40cc2f0308a3ff38ee5180a3be5` — `fix: strengthen learning hub platform boundaries`

生成本文前，工作树 clean，本地 HEAD 与远程 `origin/codex/learning-hub-world-foundation` 一致，没有未 push commit。PR #9 的四个 merge blocker 已全部完成，没有已知 blocker。本文本身是交接产物，不改变上述代码 HEAD。

## 3. 当前开发阶段总览

### Phase A：Book → Chapter → KnowledgePoint foundation

- 状态：已合并到 `main`，PR #6，merge commit `3afce08`。
- 目标：把正式文集建模从 Book 直接拥有 Question 调整为 Book 组织 Chapter 与 KnowledgePoint。
- 关键 migration：`V6__book_chapter_model.sql`。
- 关键表：`question_bank_chapter`、`question_bank_knowledge`。
- 结果：Book 的正式范围由 Chapter 下的 KnowledgePoint 定义；Question 仍是全局资源，通过 KnowledgePoint 与 Book 发生动态关系。

### Phase B：Global Question Batch Import V2

- 状态：已合并到 `main`，PR #7，merge commit `a8ecd7b`。
- 目标：以 `global-question-batch/v2` 批量导入全局 Question，而不创建或覆盖 Book。
- 关键类：`GlobalQuestionBatchImportController`、`GlobalQuestionBatchImportService`。
- 结果：Question UUID 是稳定身份；导入写 `question_resource`、`question_resource_option`、`question_resource_knowledge`，不写 `question_bank_item`；未知字段、UUID 冲突、自然身份冲突和非法知识点关系被严格拒绝。

### Phase C：KnowledgePoint-driven Global Question Pool

- 状态：已合并到 `main`，PR #8，当前 `main` HEAD/merge commit `1b59e5e`。
- 目标：游戏抽题从 Book→Question 旧关系迁移为 Study Scope→KnowledgePoint→Global Question。
- 关键类：`KnowledgeQuestionPoolService`、`KnowledgeQuestionPoolStore`。
- 结果：抽题要求当前目标 KP 为 core，Question 的全部 KP 都处于 allowed scope，只使用 published Question；同一 run 排除已见题；训练题优先简单题。历史题面采用 attempt snapshot、浏览器缓存和服务器恢复三层 fallback。

### Phase D：Online Learner + Learning Hub + Study Focus + Multi-World foundation

- 状态：已完成开发与合并前补强，仍在 PR #9，尚未 merge。
- 关键 migration：`V7__learning_hub_world_foundation.sql`。
- 关键包：`learner`、`learning`、`world`。
- 结果：建立联机 Learner、Study Profile、Learning Hub、World Registry、每 Learner/World 一份服务端 WorldState，以及 learner/world 归属的 attempt/answer。
- 当前停点：等待用户和 ChatGPT review/决定是否 merge。不得自行进入 Phase E。

## 4. 当前最终架构

```text
Online Learner
│
├─ Learning Hub（主世界）
│  ├─ Study Profile
│  ├─ Selected Books
│  ├─ Study Focus
│  ├─ Book / Chapter / KnowledgePoint browse
│  ├─ Question browse
│  └─ Future mastery / review（尚未实现）
│
└─ Worlds（游戏化体验容器）
   ├─ ancient-official（当前唯一开放世界，UI 名“题海仕途”）
   ├─ cultivation（注册表占位，未开放）
   └─ cyber-scholar（注册表占位，未开放；旧设计有时称 cyberpunk）
```

学习资源层：

```text
Book
→ Chapter
→ KnowledgePoint
↔ Question
```

边界：

- Book 不拥有 Question；Book 只组织 KnowledgePoint。
- World 不拥有 Question。
- World 不拥有 KnowledgePoint。
- World 不拥有 Selected Books。
- World 在需要发卷时调用平台学习服务，读取 current Learner 的 Study Profile。
- Question 与 KnowledgePoint 是全局资源，不复制到每个 Learner 或 World。

## 5. 数据模型现状

### 正式资源模型

| 表 | 职责与关键字段 | 写入方 / 读取方 | 状态 |
| --- | --- | --- | --- |
| `question_bank` | Book 元数据；UUID `id`，`name`、`description`、`enabled`、`weight_value`、`revision` | 管理/种子写；Bootstrap、Learning Hub、Study Profile 读 | 正式 |
| `question_bank_chapter` | Book 内章节树；UUID `id`，`bank_id`、`parent_id`、`chapter_code`、`sort_order` | Book 编排写；Learning Browse 读 | 正式 |
| `question_bank_knowledge` | Book/Chapter 对全局 KP 的编排；复合主键 `(bank_id, knowledge_point_id)`，含 `chapter_id`、`sort_order` | Book 编排写；题池、浏览、题量统计读 | 正式 |
| `global_knowledge_point` | 全局知识点；UUID `id`、唯一稳定 `code`、subject/section/chapter、status、默认关系角色、说明与解析、revision | 种子/管理写；所有学习服务读 | 正式 |
| `knowledge_alias` | 全局知识点可检索别名 | 管理/合并写；管理搜索读 | 正式 |
| `question_resource` | 全局题目；UUID `id`、来源、真题自然身份、原题型、展示类型、判题模式、Markdown、标准答案 JSON、解析、难度、status、revision、审核字段 | V2 导入/管理工作流写；题池和浏览读 | 正式 |
| `question_resource_option` | 结构化选项；`question_id`、option key/text/correct/sort | 导入/管理写；题池与浏览读 | 正式 |
| `question_resource_knowledge` | Question↔KnowledgePoint 多对多；复合主键，含 `relation_role` (`core`/`auxiliary`) 与 `sort_order` | 导入/管理写；题池、浏览、题量统计读 | 正式 |

### Learner 与 World

| 表 | 职责与关键字段 | 写入方 / 读取方 | 状态 |
| --- | --- | --- | --- |
| `learner_account` | 普通学习者；UUID `id`、唯一 username、display name、BCrypt password hash、status、revision | `LearnerAuthService` / `LearnerStore` | 正式 |
| `learner_session` | opaque session；UUID `id`、`learner_id`、唯一 SHA-256 `token_hash`、expires/revoked/last_seen | `LearnerAuthService` / `LearnerStore` | 正式 |
| `learner_study_profile` | Learner 的学习设置；PK=`learner_id`，pace、difficulty、focus_mode、revision | `StudyProfileService/Store` | 正式 |
| `learner_selected_book` | Learner 所选 Book 与权重；复合 PK `(learner_id, bank_id)` | Study Profile 写；题池读 | 正式 |
| `learner_focus_knowledge` | Learner 手动重点 KP 与顺序；复合 PK `(learner_id, knowledge_point_id)` | Study Profile 写；题池计划读 | 正式 |
| `learner_world_state` | 每个 Learner 在每个 World 的服务端权威 JSON 状态；**PK=`learner_id + world_id`**，另有 revision | World 服务写读 | 正式 |

一个 Learner 在一个 World 只有一份在线状态。`WorldStateStore.save()` 使用 revision 条件更新；并发冲突返回 409。

### 学习事实

| 表 | 职责与关键字段 | 写入方 / 读取方 | 状态 |
| --- | --- | --- | --- |
| `study_attempt` | 每次发题快照；UUID `id`，`game_id`（legacy，可空）、`learner_id`、`world_id`、question UUID、题目/标准答案 snapshot、status、grading mode/source、assessment | `QuestionAttemptStore` 写；作答和 History 读 | 正式事实源，兼容 legacy game |
| `answer_record` | 一次完成作答的不可重复记录；UUID `id`，唯一 attempt、game 或 learner/world 归属、submitted answer、correct、grading source、assessment | `QuestionAttemptStore` 写 | 正式事实源，兼容 legacy game |

正式 World 中 `game_id = null`，归属由 `learner_id + world_id` 表达；旧 `/games/**` 仍使用 `game_id`。

### 管理与审计

| 表 | 职责 | 状态 |
| --- | --- | --- |
| `app_user` / `app_user_role` | CMS/管理后台账号、BCrypt 密码与 CONTRIBUTOR/REVIEWER/ADMIN 角色 | 正式管理身份，与 Learner 完全分离 |
| `content_audit_log` | 内容与权限操作审计 | 正式 |
| `knowledge_merge_history` | 永久记录 KP 合并、迁移/折叠数与操作者 | 正式 |

### Legacy / compatibility

| 表 | 用途 | 状态 |
| --- | --- | --- |
| `game_save` | 旧多存档完整 Game JSON | Legacy；旧 `/games/**` 仍读写，正式 World 不写 |
| `question_bank_item` | 旧 Book↔Question 直接关系 | Legacy compatibility；V2 不写；旧 catalog projection/V1 导入仍用 |
| `knowledge_point` / `question_item` / `question_option` / `question_knowledge_point` | V1 旧文集内嵌资源模型 | Legacy；保留以兼容旧数据 |
| `legacy_knowledge_map` / `legacy_question_map` | 旧资源到全局资源的稳定映射 | Compatibility |

## 6. Flyway migration 历史

| Migration | 作用 |
| --- | --- |
| `V1__initial_schema.sql` | 创建旧 `question_bank`、文集内知识点/题目/选项/关系、`game_save`、`study_attempt`、`answer_record`。 |
| `V2__global_content_platform.sql` | 创建全局 KP/alias、全局 Question/Option/Question↔KP、legacy `question_bank_item`、管理账号/角色/审计。 |
| `V3__user_revision_and_self_assessment.sql` | 为管理用户加 revision，为 attempt/answer 增加 grading mode/source、assessment 与 reveal 时间。 |
| `V4__legacy_catalog_mapping.sql` | 创建旧知识点/题目到全局资源的映射表。 |
| `V5__knowledge_merge_history.sql` | 创建知识点合并历史及索引。 |
| `V6__book_chapter_model.sql` | 创建 `question_bank_chapter` 和 `question_bank_knowledge`，建立 Book→Chapter→KP 正式模型。 |
| `V7__learning_hub_world_foundation.sql` | 创建 Learner、session、Study Profile、Selected Book、Focus KP、WorldState；为 attempt/answer 增加 learner/world 归属并允许 legacy `game_id` 为空。 |

规则：历史 migration 禁止修改；后续 schema 变化只能新增 migration。Flyway 配置 `validate-on-migrate=true`、`clean-disabled=true`、baseline version 0。V7 不删除 `game_save` 或旧题库数据，正式联机流程与 legacy 并存。

## 7. Learner 联机身份

当前实现：

- 普通学习者保存在 `learner_account`，用户名密码登录，密码使用 BCrypt。
- 登录/注册生成 32-byte secure random opaque token；浏览器只收到 `THS_LEARNER_SESSION` HttpOnly、SameSite=Lax Cookie。
- 数据库只保存 raw token 的 SHA-256 hex hash，绝不保存 raw token。
- session 带到期时间、撤销时间和 last-seen；默认 max age 2,592,000 秒，可配置。
- logout 把当前 hash 标记 revoked，并清除 Cookie。
- `LearnerSessionFilter` 解析 Cookie，把可信身份放入 `LearnerContext`；Bootstrap、Learner、Learning、World 路径要求有效 Learner。
- `app_user` 是 CMS/管理后台身份；`learner_account` 是普通学习者。两套账号、session、权限完全分离。

CSRF：

- 未登录可先请求 `GET /api/v1/learner/auth/csrf`。
- `CookieCsrfTokenRepository` 写可读的 `XSRF-TOKEN` Cookie。
- 前端 `api/http.ts` 对 POST/PUT/DELETE 统一读取该 Cookie，并发送 `X-XSRF-TOKEN`。
- register、login、logout、Study Profile PUT、Learner History POST、World initialize 和全部 World mutating actions 均受 CSRF 保护。
- learner session Cookie 仍是 HttpOnly，前端 JS 无法读取 raw session token。
- `/api/v1/games/**` 与 `/api/v1/admin/**` 目前仍在 CSRF ignore 列表中：前者是 legacy API，后者用机器密钥。
- 管理 SPA 继续使用 masked token；`SecurityConfiguration.CookieAndMaskedCsrfTokenRequestHandler` 同时兼容 learner SPA 的 raw cookie token 与 manage SPA 的 masked token。

## 8. Learning Hub 当前实现

前端没有 `react-router`；`main.tsx` 和 `PlatformApp.tsx` 直接检查 `window.location.pathname`，使用普通 `<a>` 与 `window.location.assign()` 导航。

| 路由 | 页面/用途 | API | 学习证据 |
| --- | --- | --- | --- |
| `/login` | Learner 登录 | POST `/learner/auth/login` | 不产生 |
| `/register` | Learner 注册 | POST `/learner/auth/register` | 创建账号与默认 Study Profile |
| `/` | Learning Hub 首页、学习摘要、World Gallery | GET `/bootstrap` | 不产生 |
| `/study` | 选择 Books、auto/manual Focus、保存 Study Profile | GET `/bootstrap`、GET Book detail、PUT Study Profile | 只改设置，不产生 attempt/answer |
| `/books` | 可用文集清单 | Bootstrap manifest | 不产生 |
| `/books/:bookId` | Chapter Tree 与 KP 浏览 | GET `/learning/books/{id}` | 不产生 |
| `/knowledge/:id` | KP 说明、解析与相关 published Question | GET KP、GET KP questions | 不产生 |
| `/questions/:id` | 只读 Question、选项、答案与解析浏览 | GET `/learning/questions/{id}` | 不产生 |
| `/account` | 当前 Learner 信息与 logout | Bootstrap、POST logout | 不产生 |
| `/worlds/ancient-official` | WorldShell 包裹旧 `App.tsx` 的正式古风世界 | World API | 世界内作答会产生事实 |
| `/manage...` | 独立 CMS | Manage API | 管理审计，不是学习证据 |

Question Browse 是“查阅”，不创建 `study_attempt`，不创建 `answer_record`，也不更新 mastery。当前 mastery 尚未实现。

## 9. Study Profile / Study Focus

当前字段：

```text
selectedBookIds
weights
pace              slow | normal
difficulty        gentle | standard
focusMode         auto | manual
focusedKnowledgePointIds
revision
```

规则：

- Selected Books 定义整体学习范围与 dependency allowed scope。
- Focused KPs 只是 target priority；不代表掌握，也不等于 allowed dependency scope。
- 保存时至少选一本 enabled Book；权重范围 1–1000；focused KP 必须在所选 Book 的有效范围内。
- `revision` 用于乐观锁；并发修改返回 409。
- `manual`：有效 focused KP 会优先排进计划，其余可用 KP 仍可进入。
- `auto`：当前没有 mastery/遗忘调度，只对可玩 KP 随机计划。
- 注册时 `LearnerStore.create()` 暂时把所有 enabled Book 加入 `learner_selected_book`。这是当前 onboarding 临时策略，不是不可变产品规则；PR #9 明确没有调整它。
- 当前 `pace`、`difficulty` 已保存，但 Phase D 没有进入 adaptive difficulty；不要把它们误写成已完成的自适应算法。

## 10. Knowledge-driven Question Pool

`KnowledgeQuestionPoolStore` 负责 SQL 范围和候选集：

- 从 enabled selected Books 的 `question_bank_knowledge` 取得 active KP scope。
- 纯 legacy Book 才通过 `legacy_knowledge_map` 提供兼容 scope。
- playable target 必须至少存在一题 published Question，且 target KP 在该题关系中为 `core`。
- Question 的所有 KP 必须存在、为 active，且全部位于 allowed scope。
- 候选题只取 published Question。

`KnowledgeQuestionPoolService` 负责计划和选择：

- 一次计划冻结指定数量的不同 KP；普通活动通常 5 个，主线/考试通常 10 个。
- manual Focus 中可玩的 KP 优先，然后用其他可玩 KP 补足。
- 同一 run 的 `seenQuestionIds` 会排除已经出现的题目。
- NORMAL 从合法候选随机选题。
- TRAINING 优先 `difficulty <= 2`；没有简单题时选当前最低难度。
- 训练题答对后才推进下一个 KP，但不会补回该 KP 首题失分。

正式 World 的 `GameActionService` 通过 `StudyProfileService.plan(rounds)` 获取 current Learner 的 Book scope 与 Focus；legacy Game 才从 `game.config.bankIds` 读取范围。

## 11. World 架构

当前唯一正式 World：

```text
world_id = ancient-official
```

组件职责：

- `WorldRegistry`：代码式世界注册表。当前注册 `ancient-official`（enabled）、`cultivation` 和 `cyber-scholar`（disabled）。
- `WorldCatalogService`：把注册表与当前 Learner 的 WorldState 存在性、更新时间组合成 World Gallery DTO。
- `WorldStateStore`：按 `(learner_id, world_id)` 读写 JSON state，执行 revision 乐观锁。
- `WorldController`：正式 World HTTP 边界，提供 initialize/get 及全部 action endpoint。
- `AncientOfficialWorldService`：初始化与读取古风世界；初始化走 `GameFactory.createAncientOfficialState()`，不会读取 Catalog/Book/Question/StudyProfile。
- `WorldActionContext`：在执行复用的 `GameActionService` 时绑定可信 learnerId/worldId，使 attempt/answer 和状态保存落到正式 World 归属。
- `GameActionService`：仍承载古风玩法动作，但在 World context 下读 Study Profile、写 WorldState，而非 `game_save`。

WorldState 是 server authoritative：客户端提交动作，服务端校验、结算并持久化，再返回新状态。`WorldStateStore.save()` 通过 expected revision 更新，冲突返回 409。

统一“返回主世界”位于 `PlatformApp.tsx` 的 `world-shell`：进入 `/worlds/ancient-official` 时，在复用的 `<App />` 外显示 `← 主世界` 链接。

## 12. 当前古风世界兼容层

| 组件 | 当前定位 |
| --- | --- |
| `GameFactory` | 正式仍用于构造古风 World 初始 state，同时继续支持 legacy NewGame。World 专用方法不会加载 Catalog。 |
| `GameActionService` | 正式古风 World 与 legacy Game 共用的 compatibility service；通过 `WorldActionContext` 切换持久化/身份语义。 |
| `Game` domain types | 正式古风 UI 仍使用的 compatibility contract。 |
| `game.learning` | 旧题目级复习/错题状态；仍供兼容引擎和 UI 使用，不是未来 mastery。 |
| `records` | 旧 Game/World 展示历史的 compact records；不是平台级学习事实源。 |
| `player.knowledge` | 古风角色数值“学识”；不是 KnowledgePoint mastery。 |
| `App.tsx` | 当前古风世界 UI，正式 World 仍复用；内部仍含部分旧多存档面板代码。 |
| `GameApi` | 当前 App 与 local/http adapter 的兼容接口。 |
| `/api/v1/games/**` | deprecated/legacy 多存档 REST，仍保留兼容测试和旧数据访问。 |

正式长期学习事实源正在转向：

```text
study_attempt
answer_record
未来 learner_knowledge_state（尚未实现）
```

不要把 `game.learning`、`records` 或 `player.knowledge` 升格为平台 mastery。

## 13. 学习历史与 History Question Recovery

- 发题时创建 UUID attempt，并把完整题目与标准答案写入 `study_attempt` snapshot；以后题库修订不会改变已发题。
- 自动判题：`active → graded`，写 `answer_record`，grading source=`automatic`。
- 自评题：`active → revealed → graded`；reveal 后允许 `correct/partial/wrong`，grading source=`self`。
- 同一 attempt 状态条件更新和 answer 唯一约束阻止重复结算。

平台级恢复：

```text
POST /api/v1/learner/history/questions
```

`recoverForLearner()` 不再依赖 `ancient-official` WorldState、game records 或特定 world。服务器只按以下可信条件读取 snapshot：

```text
study_attempt.learner_id = current learner
attempt id IN requested IDs
status = graded
```

每批最多 500 UUID。Learner B 无法恢复 Learner A 的 attempt。前端优先使用内存/IndexedDB 已答题缓存，cache miss 才批量请求服务器；最终无法恢复的单题显示占位内容，不让整个 Game 崩溃。

legacy `/games/{id}/history/questions` 仍要求 attempt 同时出现在该 Game compact records 中，并按 game_id、question UUID 与 graded 状态校验。

## 14. Learning Browse API

均要求有效 Learner，均为 GET、只读，不创建学习证据：

| Endpoint | 返回语义 |
| --- | --- |
| `GET /api/v1/learning/books` | enabled Books；KP 数与 published Question 数 |
| `GET /api/v1/learning/books/{id}` | enabled Book 的 Chapter Tree 与 active KP |
| `GET /api/v1/learning/knowledge-points/{id}` | active KP 详情及所属 enabled Books |
| `GET /api/v1/learning/knowledge-points/{id}/questions` | 关联的 published Questions |
| `GET /api/v1/learning/questions/{id}` | published Question、选项和 active KP 详情 |

正式 Book `questionCount` 语义：

```text
question_bank_knowledge
→ question_resource_knowledge
→ DISTINCT published question_resource
```

`LearningBrowseStore` 完全使用这条知识点路径。Bootstrap `CatalogStore.findManifests()` 对存在 `question_bank_knowledge` 的 Book 使用该路径；只有纯 legacy Book 才回退旧 `question_item` 统计。不会创建新的 Book↔Question 关系。

## 15. 当前正式 API 总表

所有路径均以 `/api/v1` 为前缀。

### Learner Auth

| Method | Path | Learner | CSRF | 用途 |
| --- | --- | --- | --- | --- |
| GET | `/learner/auth/csrf` | 否 | 否 | 取得 XSRF token |
| POST | `/learner/auth/register` | 否 | 是 | 注册并建立 session |
| POST | `/learner/auth/login` | 否 | 是 | 登录并建立 session |
| POST | `/learner/auth/logout` | 是 | 是 | revoke session |
| GET | `/learner/me` | 是 | 否 | 当前 Learner |

### Study / Bootstrap / Browse / History

| Method | Path | Learner | CSRF | 用途 |
| --- | --- | --- | --- | --- |
| GET | `/bootstrap` | 是 | 否 | Learner、Study Profile、Worlds、Bank manifest、catalog descriptor |
| GET | `/learner/study-profile` | 是 | 否 | 当前设置 |
| PUT | `/learner/study-profile` | 是 | 是 | 乐观锁更新设置 |
| POST | `/learner/history/questions` | 是 | 是 | 恢复当前 Learner 的 graded snapshots |
| GET | `/learning/books` | 是 | 否 | Book 列表 |
| GET | `/learning/books/{id}` | 是 | 否 | Book/Chapter/KP |
| GET | `/learning/knowledge-points/{id}` | 是 | 否 | KP 详情 |
| GET | `/learning/knowledge-points/{id}/questions` | 是 | 否 | KP 相关题 |
| GET | `/learning/questions/{id}` | 是 | 否 | 题目浏览详情 |

### World

所有 mutating endpoint 都要求 Learner + CSRF。

| Method | Path | 用途 |
| --- | --- | --- |
| GET | `/worlds` | 当前 Learner 的 World Gallery |
| GET | `/worlds/ancient-official` | 当前 WorldState |
| POST | `/worlds/ancient-official/initialize` | 初始化唯一 WorldState |
| POST | `/worlds/ancient-official/travel` | 移动 |
| POST | `/worlds/ancient-official/talk` | 对话 |
| POST | `/worlds/ancient-official/exams/register` | 报名考试 |
| POST | `/worlds/ancient-official/activities` | 开始活动/发卷 |
| POST | `/worlds/ancient-official/activities/finish` | 完成行程 |
| POST | `/worlds/ancient-official/activities/abandon` | 放下行程 |
| POST | `/worlds/ancient-official/answers` | 自动判题提交 |
| POST | `/worlds/ancient-official/answers/reveal` | 自评题揭示 |
| POST | `/worlds/ancient-official/answers/self-assess` | 自评 |
| POST | `/worlds/ancient-official/next` | 下一题 |
| DELETE | `/worlds/ancient-official/encounter` | 关闭际遇 |
| POST | `/worlds/ancient-official/items/use` | 使用物品 |
| POST | `/worlds/ancient-official/items/buy` | 购买物品 |
| PUT | `/worlds/ancient-official/notes` | 题目批注 |
| POST | `/worlds/ancient-official/chapter` | 确认章节 |
| POST | `/worlds/ancient-official/bonds` | 领取关系信物 |
| POST | `/worlds/ancient-official/choices` | 选择际遇 |

### Manage（浏览器 CMS）

- `/manage/auth/csrf` GET 公开；login POST 公开但需 CSRF；logout/me 需管理 Session。
- `/manage/knowledge-points`：GET list/detail；PUT 需 REVIEWER/ADMIN；merge POST 需 ADMIN。
- `/manage/questions`：GET list/detail；POST/PUT/submit 需 CONTRIBUTOR/REVIEWER/ADMIN；review/archive 需 REVIEWER/ADMIN。
- `/manage/imports/questions` POST：ADMIN，V2。
- `/manage/imports/question-bank` POST：ADMIN，deprecated V1。
- `/manage/users` GET/POST/PUT：ADMIN。
- `/manage/audit-logs` GET：ADMIN。
- Manage 使用服务端 Session、HttpOnly Cookie、CSRF，并在每次请求复核账号状态。

### Admin（机器接口）

只有配置 `APP_ADMIN_KEY` 后启用；请求头 `X-Admin-Key`，无需浏览器 CSRF：

- `POST /admin/questions/import`：V2 全局 Question batch。
- `POST /admin/global-question-banks/import`：deprecated V1。
- `POST /admin/question-banks/import`：旧 Book DTO 导入。
- `PUT /admin/question-banks/{id}/metadata`：Book 元数据。

### Catalog 与 Legacy Games

- `GET /question-banks/{id}`：旧 Bank projection，公开 compatibility endpoint。
- `/games/**`：旧多存档 API，包括 create/get/delete、history recovery、travel/talk/exam/activity/answer/reveal/self-assess/next/encounter/items/notes/configuration/chapter/bonds/choices/export/import。当前无 Learner session 要求且整体忽略 CSRF，只能视为 legacy compatibility，不能用于新的正式产品架构。

## 16. 前端架构

- `frontend/src/main.tsx`：入口；pathname 以 `/manage` 开头时加载 `ManagementApp`，否则加载 `PlatformApp`。
- `frontend/src/platform/PlatformApp.tsx`：登录、Learning Hub、Study、Book/KP/Question Browse、Account、World Gallery 和 WorldShell。使用 `window.location.pathname`，没有 react-router。
- `frontend/src/platform/api.ts`：Learning Hub/Learner/Study/Browse 的类型与 HTTP 调用。
- `frontend/src/App.tsx`：古风 World 的现有 UI；在 `/worlds/ancient-official` 中被 WorldShell 复用。
- `frontend/src/api/http.ts`：正式 HTTP `GameApi` adapter、CSRF、World endpoints、历史题缓存恢复和不可恢复占位。
- `frontend/src/api/catalog-cache.ts`：已答题 Question IndexedDB 缓存。
- `frontend/src/api/index.ts`：`VITE_API_MODE` 在 `http` 与 `local` adapter 间切换；代码默认 `http`。
- `frontend/src/api/local/store.ts`：旧浏览器单机多存档实现，仅 local/dev compatibility。
- `frontend/src/manage/ManagementApp.tsx` 与 `manage/api.ts`：独立 CMS 与管理 CSRF/session。

`VITE_API_MODE=local` 仍可显式启用，但不是正式联机产品路径。`.env.example` 仍写 `local`，而代码默认值和正式文档已是 `http`，这是一个已知文档/示例漂移点。

## 17. 当前 PR #9 补强结果

### 1. Learner / World CSRF — 已完成

- 文件：`SecurityConfiguration.java`、`LearnerAuthController.java`、`LearnerSessionFilter.java`、`frontend/src/api/http.ts`，以及既有测试适配。
- 删除 `/learner/**`、`/worlds/**` 的 CSRF ignore。
- 新增公开 `GET /learner/auth/csrf`。
- 前端 POST/PUT/DELETE 统一带 `X-XSRF-TOKEN`。
- raw Learner session token 仍仅存在于 HttpOnly Cookie。
- 测试：`LearnerCsrfIntegrationTest` 覆盖无 token 拒绝与有 token 成功。

### 2. Learner History 跨 World — 已完成

- 文件：`HistoryQuestionService.java`、`QuestionAttemptStore.java`。
- `recoverForLearner` 不再读取 `WorldRegistry.ANCIENT_OFFICIAL` 或 `learner_world_state.records`。
- SQL 直接限定 current learner + requested IDs + graded。
- 测试：`LearnerHistoryPlatformIntegrationTest` 覆盖 other-world 恢复与跨 Learner 拒绝。

### 3. Knowledge-driven Book question count — 已完成

- 文件：`CatalogStore.java`、`LearningBrowseStore.java`。
- 正式 Book 通过 Book KP scope→Question KP relation 统计 DISTINCT published Questions。
- Learning Hub 不再通过 `question_bank_item` 统计正式题量；Bootstrap 仅为纯 legacy Book 保留 fallback。
- 测试：`KnowledgeDrivenBookCountIntegrationTest` 明确不创建 bank item，Bootstrap 与 Browse 都统计到 Q1。

### 4. ancient-official initialize 不加载完整 Catalog — 已完成

- 文件：`GameFactory.java`、`AncientOfficialWorldService.java`。
- 新增 `createAncientOfficialState(name, gender, origin)`，只创建角色、NPC、records/learning、journal、flags、attempt/event、notes、chapter 和 adventure。
- initialize 不再通过空 `bankIds` 调 `catalog.findAll()`，也不再创建后删除 config。
- 按任务书只做代码检查，没有增加第四个集成测试。

本轮本地最小验证：上述 3 个定向集成测试、backend `mvnw compile`、frontend `tsc -b` 均通过。GitHub Actions Backend/Frontend 完整 CI 均为绿色。无 BLOCKER。

## 18. 测试策略

长期策略：功能优先 + 最小必要验证 + 阶段性完整测试。

- 日常 Codex 本地只跑与当前变更直接相关的 1–3 个高价值测试。
- 普通 UI/CRUD/文案/布局只做必要 compile、typecheck 或静态检查。
- migration、数据完整性、认证、权限、题目导入、幂等、Question↔KP、未来 mastery/adaptive 属高风险，必须有针对性测试。
- 不主动为每个小任务跑完整 `npm test`、完整 `mvnw test` 或完整 build。
- 完整回归交给 GitHub Actions：Backend workflow 跑 `./mvnw test`；Frontend workflow 跑 lint、Vitest 和 build。
- CI 失败时只诊断真实失败项，不无原因重跑全部套件。
- 不得通过删核心测试、降低断言、`skipTests`、压制错误或伪造成功来使 CI 变绿。

## 19. 构建 / 运行命令

### Frontend

要求 Node.js `>=22.12.0`；GitHub Actions 使用 Node 24。

```powershell
cd E:\题海仕途
npm install
npm run dev
npm run lint
npm test
npm run build
```

只做 TypeScript 检查：

```powershell
cd E:\题海仕途\frontend
npx tsc -b --pretty false
```

### Backend

Java 17、Spring Boot 3.5.16、Maven Wrapper 3.9.9；默认端口 12345。

```powershell
cd E:\题海仕途\backend
.\mvnw.cmd spring-boot:run
.\mvnw.cmd compile
.\mvnw.cmd '-Dtest=SomeIntegrationTest' test
.\mvnw.cmd test
```

最后一条是完整本地回归，只有用户明确要求时才运行。健康检查：`http://localhost:12345/actuator/health`。

数据库目标为 MySQL 5.7，默认 `127.0.0.1:3306/tihaishitu`；测试使用 H2 MySQL mode。API base 默认 `/api/v1`。Vite dev proxy target 默认约定为 `http://localhost:12345`。

## 20. 配置与环境变量

只列变量名和用途，不在文档中保存任何真实 secret。

| 变量 | 用途 |
| --- | --- |
| `SERVER_PORT` | Backend HTTP port，默认 12345 |
| `DB_URL` | JDBC URL |
| `DB_USERNAME` | DB 用户名 |
| `DB_PASSWORD` | DB 密码 |
| `DB_POOL_SIZE` | Hikari 最大连接数，默认 10 |
| `SESSION_COOKIE_SECURE` | Spring 管理 Session Cookie 是否仅 HTTPS |
| `APP_INITIAL_ADMIN_USERNAME` | 首个管理账号用户名 |
| `APP_INITIAL_ADMIN_PASSWORD` | 首个管理账号密码，仅启动引导使用 |
| `APP_INITIAL_ADMIN_DISPLAY_NAME` | 首个管理员显示名 |
| `MATH1_KNOWLEDGE_SEED_ENABLED` | 是否启用数学一 KP 种子 |
| `CATALOG_SEED_ENABLED` | 是否启用旧 catalog seed |
| `APP_ADMIN_KEY` | 机器级 `/admin/**` 写接口密钥 |
| `CORS_ALLOWED_ORIGINS` | 允许的前端 origin 列表 |
| `LEARNER_COOKIE_SECURE` | Learner session Cookie 是否仅 HTTPS |
| `LEARNER_SESSION_MAX_AGE` | Learner session 最大秒数 |
| `VITE_API_MODE` | `http` 正式模式或 `local` legacy/dev mode |
| `VITE_API_BASE_URL` | 前端 API base，默认 `/api/v1` |
| `API_PROXY_TARGET` | Vite 开发代理的后端地址 |

## 21. 当前已知 Legacy / Technical Debt

1. `game_save`：旧多存档表；正式 World 不写，但 `/games/**` 和兼容测试仍用。等旧存档迁移/废弃策略明确后才能删除。
2. `question_bank_item`：V2 不写；旧 Catalog projection、V1 importer、legacy 管理逻辑仍读写。只有所有 legacy Book 和 API 退役后才能删除。
3. `/games/**`：公开且忽略 CSRF 的 legacy API。正式产品必须继续走 Learner/World；后续应有单独退役计划，不能顺手删除旧数据。
4. `GameFactory` / `GameActionService`：正式古风 World 仍复用，不能直接删除；需要未来把 World domain 独立后再收敛 compatibility 分支。
5. `GameApi` / `App.tsx`：正式古风 UI 仍依赖；其中保存面板、import/export 等旧多存档代码仍存在。HTTP adapter 返回空 saves 并拒绝 import/export，但代码债务尚未清除。
6. `game.learning`：旧题目级复习状态仍被 UI/engine 引用，不是正式 mastery。
7. `player.knowledge`：古风角色属性仍被剧情门槛使用，不是知识点掌握度。
8. World `records`：用于古风 UI 历史展示和兼容恢复，不是跨 World 的平台事实源。
9. local mode：浏览器本地多存档仍可通过 `VITE_API_MODE=local` 启用，仅用于 legacy/dev compatibility。
10. `.env.example` 仍默认 `local`，与代码默认 `http` 不一致；属于低风险文档债务。
11. `backend/README.md` 部分接口描述仍以旧 saves/bootstrap 为中心；正式契约应以 Controller、`docs/api.md` 和本文件核实结果为准。
12. 旧 import/export code：前后端均保留给 legacy Game，正式联机 World 禁用。

## 22. 永久禁止 / 架构红线

- 不要让 Book 拥有 Question。
- 不要让 World 拥有 Question。
- 不要复制 Question / KnowledgePoint 到每个 World。
- 不要让 `app_user` 充当 Learner。
- 不要把 `player.knowledge` 当 mastery。
- 不要把 `game.learning` 当正式长期 mastery。
- 不要把 World `records` 当跨 World 学习事实源。
- 不要重新引入多存档作为正式主架构。
- 不要把 Selected Books 放回 WorldState。
- 不要让普通用户修改全局内容。
- 不要把 raw session token 暴露给 JS 或存入数据库。
- 不要修改历史 Flyway migration；只新增 migration。
- 不要删除旧数据来逃避 migration。
- 不要在一个 PR 顺手进入多个 Phase。
- 不要每个小任务跑完整本地测试。
- 不要在 PR #9 review/merge 前自行开始 Phase E。

## 23. 下一阶段：Phase E

当前 PR #9 合并后，下一阶段不是继续扩展世界玩法，而是正式建立全局 Learner Knowledge State。

建议目标表：

```text
learner_knowledge_state
```

建议至少考虑：

```text
learner_id
knowledge_point_id
mastery_score
stability_days
last_evidence_at
target_difficulty
correct_streak
wrong_streak
revision
```

这些字段只是下一阶段设计输入，当前仓库尚未实现。开始前必须等待正式 Phase E 任务书。

## 24. Phase E 的重要产品原则

所有 World 共用同一份 LearnerKnowledgeState：

```text
ancient-official 做题
cultivation 做题
cyber-scholar 做题
platform practice 做题
        ↓
Learner + KnowledgePoint
```

绝不能建成：

```text
Learner + World + KnowledgePoint
```

World 是证据来源和体验容器，不是掌握度所有者。

## 25. 已讨论但尚未实现的 mastery 方向

未来可能包含：

- `masteryScore`：0–100 的知识点掌握估计。
- `stabilityDays`：记忆稳定时长。
- `effectiveMastery`：按时间 lazy decay 得到的当前有效掌握度。
- `targetDifficulty`：独立的出题难度目标。

掌握度、稳定度、目标难度是三个不同概念，不能合并为一个字段。当前仓库没有 `learner_knowledge_state`，没有正式 mastery/forgetting 实现。

## 26. 未来诊断方向

Composite Question 答错时，不能直接认为所有绑定 KP 都不会。未来诊断状态机可以考虑：

```text
NORMAL
DIAGNOSE_TARGET
DIAGNOSE_DEPENDENCY
REMEDIATE
RESUME
INTEGRATION_ERROR
UNRESOLVED
```

这是未来 Phase，当前尚未实现。PR #9 只完成身份、Hub、Focus、World 与题池边界。

## 27. 未来多 World 扩展原则

未来新 World 应按以下方式接入：

```text
新 World
→ 自己的 WorldState
→ 自己的剧情 / UI / 资源
→ 调用平台 Learning Services
→ 产生共享 Learner 学习证据
```

新 World 不得复制题库、KnowledgePoint 或 mastery；不保存 Selected Books；通过 current Learner 的 Study Profile 与统一题池获得题目。每个 World 可以有自己的角色、资产、地图和剧情状态，但不能拥有平台学习资源。

## 28. 下一位 Codex 的第一步

PR #9 当前尚未 merge，因此第一步必须是：

```text
1. 检查 PR #9 最新 CI。
2. review 最终 diff。
3. 确认四项补强：CSRF、跨 World History、Knowledge-driven Book count、World init 不加载 Catalog。
4. 不进入 Phase E。
5. 等待用户/ChatGPT 决定 merge。
```

如果之后确认 PR #9 已 merge，则：

```text
checkout main
git pull --ff-only origin main
确认 merge SHA
等待 Phase E 正式任务书
```

## 29. 给新 Codex 的 2 分钟快速启动

1. 产品核心是联机学习平台，游戏只是体验容器。
2. Learner 是唯一长期主体，KP 是学习主线，Question 是全局训练资源。
3. 主世界是 Learning Hub；子世界消费学习服务。
4. 正式资源链是 Book→Chapter→KP↔Question。
5. Book 和 World 都不拥有 Question。
6. 当前分支 `codex/learning-hub-world-foundation`。
7. 当前 Head `746439038e1be40cc2f0308a3ff38ee5180a3be5`。
8. PR #9 OPEN、MERGEABLE、CLEAN，base=`main@1b59e5e`。
9. Backend/Frontend GitHub Actions 当前全绿。
10. PR #9 有 3 个 commit，四项 merge blocker 已全部完成。
11. Phase A/B/C 已 merge；Phase D 仍在 PR #9。
12. 最新 migration 是 V7；旧 migration 永远不要改。
13. `learner_world_state` PK 是 learner_id + world_id。
14. 一个 Learner 在一个 World 只有一份服务端权威状态。
15. Learner 使用 BCrypt + opaque HttpOnly Cookie；DB 只存 token SHA-256。
16. learner/world 写接口需要 CSRF；先取 `/learner/auth/csrf`。
17. Study Profile 属于 Learner，不属于 World。
18. Selected Books 是 allowed scope；Focus 只是优先级，不代表掌握。
19. 注册时暂时默认选择所有 enabled Books。
20. 题池要求 target KP 为 core、全依赖在 allowed scope、published only、run 内不重复。
21. 正式 World ID 是 `ancient-official`；其他世界仅占位未开放。
22. 正式 World 初始化不会加载完整 Catalog。
23. History recovery 只按 current learner + graded attempt 恢复，可跨 World。
24. `study_attempt` 与 `answer_record` 是正式学习事实；`records` 不是。
25. `game.learning` 与 `player.knowledge` 都不是未来 mastery。
26. `game_save`、`question_bank_item`、`/games/**`、local mode 仍是 legacy compatibility。
27. 本地只做 1–3 个定向测试和必要 compile/tsc，完整回归交给 CI。
28. 当前无 blocker，但 PR #9 尚未 merge。
29. 不要开始 mastery、forgetting、diagnosis、blacklist 或第二个真实 World。
30. 下一步只 review/merge PR #9；merge 后等待正式 Phase E 任务书。
