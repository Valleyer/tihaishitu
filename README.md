# 万境求知

以统一学习进度为核心，在不同世界中学习、练习与成长。

React + TypeScript 前端与 Java 17 / Spring Boot 后端已经完成联机 Learning Hub（万境中枢）与多世界底座。登录后先选择学习范围与 Study Focus，再进入共享学习身份下的游戏世界。当前开放世界为“寒门仕途”。

本次改名仅调整产品展示名（旧展示名“知境 / 知境中枢”统一改为“万境求知 / 万境中枢”）。仓库名 `tihaishitu`、Java package `cn.tihaishitu`、Spring 技术名 `tihaishitu-backend`、数据库 schema `tihaishitu`、World ID `ancient-official`、API 路由、数据库表名、Flyway migration 文件名、UUID 与兼容 localStorage key 都继续保持稳定，不参与展示名迁移。

> **开始开发前先读**：[`AGENTS.md`](AGENTS.md)（Agent 开发流程与文档纪律）与 [`docs/PROJECT_RULES.md`](docs/PROJECT_RULES.md)（跨 PR 长期规则的唯一主入口）。本仓库采用“一个 PR = 一个独立 Agent 对话”，长期规则不依赖聊天记录。

## 现在怎么玩

默认进入万境中枢，题面不会自动出现。`寒门仕途` 是万境求知当前开放的第一个游戏世界：

- **读书**：三页一卷，提升学识、悟性、辞采、筹算并赚银两。
- **访友**：与六位人物交谈、共读；按整轮成绩增加好感，解锁话题与关系信物。
- **游历**：青溪县、临川府两张大地图共十二个地点，统一显示“大地图名 · 小地点名”。
- **一次性任务**：Main 必须 10/10；Side 与 Dungeon 均为五题答对至少三题完成。任务只有完成档，完成时一次性领取完整奖励并永久关闭；未完成无惩罚且可无限重试，完成后不可再刷。
- **可重复活动**：普通 Study、Companion 等 Activity 与一次性 Task 分开管理，完成一轮后仍可再次进行。
- **养成**：26 件装备、消耗品和信物可收入行囊，装备加成实际参与解锁条件。
- **Learning Hub**：统一管理学习范围与重点知识点，查看学习进度和复习安排，并进行知识点专项练习与错题练习。
- **知识 / 题库分工**：顶栏的**知识**（技术 route 仍是 `/books`）展示 `Book → Chapter → KnowledgePoint` 目录与知识讲解；**题库**（`/questions`）展示全平台所有已发布正式题目，支持关键词、来源、年份、题型、难度、文集、章节、知识点筛选与固定每页 20 条的分页，可 inline 预览并进入完整题目详情查看答案与解析。题库是全局只读浏览，与当前学习范围解耦：浏览题目不创建 Attempt、不影响掌握度与错题本、也不消耗随机题每日额度。
- **长期掌握状态**：正式作答只归因到当次目标知识点；Learning Hub 展示有效掌握度、记忆稳定度与目标难度，所有 World 共用同一份 Learner + KnowledgePoint 状态。
- **正式训练选题 V2**：正式训练固定为四套**互相独立**的选题策略。RANDOM（寒门仕途普通随机正式题）改为 KP-first：先随机选目标知识点，再在该知识点内按 oldest / wrong 交替 lane 选题；同一学习者同一 `Asia/Shanghai` 业务日内同一道题最多随机出现一次，题目一经发出就占用当天额度。CHAPTER（章节练习）改成一条确定的题序并跨 Session 记住进度。KNOWLEDGE（知识点专项）与 WRONG（错题快练）都是 Session 内随机且不重复。章节 / 知识点 / 错题练过的题不消耗 RANDOM 额度。详见 [正式训练选题策略](docs/question-practice-policy.md)。
- **普通训练单层化**：RANDOM / CHAPTER / KNOWLEDGE / WRONG 做完一题就直接判题、记录掌握度与错题本，然后进入下一道普通正式题或结束流程；不再自动进入诊断 / 补救嵌套，也不再答错后原地重做同一道父题。诊断与补救相关表、服务与管理端能力仍然保留，供未来重新设计。
- **遗忘感知复习 V1**：Learning Hub 的“今日巩固”和“复习安排”从现有掌握度与记忆稳定度动态推导，并可直接进入相应知识点的专项练习。
- **正向挑战记录**：同一轮补救不追回首题失分，新一轮仍可重新拿满分；正式 World 只保留最高分、已通关和首次奖励等正向成就，不累计失败或应试次数。

判断、单选、多选继续支持，选项每次发卷打乱。正式联机流程中每位 Learner 在每个 World 只有一份服务端权威状态；旧多存档只保留兼容数据。

**正式题答案契约 V2**：正式父题不再保存独立的“标准答案 JSON”。客观题（单选 / 多选 / 判断）的唯一答案事实是 `question_resource_option.correct_option`，后端在发题时派生运行时答案并随选项排列一起 remap；综合题只有一份 `analysis_markdown`，包含答案、过程与解析，不再有独立参考答案。每次《综合题》作答只 reveal 这一份内容并自评。题库管理后台不再编辑标准答案 JSON，改为直接标记选项正确性并预览解析；批量导入的正式格式是 `global-question-batch/v4`（v3 仅作历史兼容输入）。代码内仍保留 `study_attempt.standard_answer_json`，它是每次 Attempt 创建后冻结的技术判题快照，与题库配置答案无关，历史 Attempt 永不回写。

掌握度 V3 为 Learner × KnowledgePoint × 正式父题维护独立强化槽位，按 `Asia/Shanghai` 业务日结算（详见 [项目长期规则 §7.1](docs/PROJECT_RULES.md)）：每个业务日只有当天第一次正式 graded 作答有资格决定该题当天的熟练度奖励。当天首答正确时，历史第一次有效正确记 30，之后其他业务日首答正确 +7，最高 100；当天首答为 wrong / partial 时该题当天不增加熟练度，之后同日即使重新答对也不奖励。错误不直接扣熟练度，熟练度下降只来自自然衰减：每满 3 天无有效强化惰性衰减 1。KnowledgePoint 掌握度按当前与该知识点有关系的全部正式父题（core + auxiliary，按题目 ID 去重）聚合，例如 3 道相关题只做了 1 道且第一次正确就是 `30 / 3 = 10.0%`；全部达到 100 时进入“彻底掌握”并冻结自然衰减，新增正式题会自动扩大分母并退出 100%。子题只用于补救教学，不产生 Mastery、错题本或 Review Queue 数据。题目浏览、查看答案和 reveal 不产生正式学习证据。

能不能练与能不能获得 Mastery 奖励已经分离：今天已经答对过的题仍然可以再练，只是同一业务日后续正确不再增加熟练度。KnowledgePoint 的 `relation_role`（core / auxiliary）只用于标签主次显示与诊断解释，既不影响题目能不能做，也不影响它是否进入 Mastery 分母。正式题发题条件只剩 `status=published`、`parent_question_id IS NULL`、正式题型与当前训练上下文范围，加上当前 Session / run 的 `seenQuestionIds` 排除。

Adaptive Scheduling V1 仍然按惰性遗忘后的有效掌握度计算“软提示难度”（未开始或有效掌握度低于 40 为 2，40–70 为 3，70–100 为 4，100 为 5；`standard` 取目标难度与掌握上限的较小值，`gentle` 再下调一级但不低于 1），但难度只作为保留的 Remedial / training 能力的软提示，普通四模式的正式题选题一律不看它。World / 副本的普通正式题走 RANDOM：先选目标知识点，再在该知识点内按 oldest / wrong lane 选题并把该知识点冻结为本次 target；`plannedRounds = min(activity rounds, 当天剩余可出的随机题数)`，题目不够就自然提前完成，当天全部出过时开始活动直接给出明确提示。旧 `/games/**` 继续使用兼容的 scope-only 随机选题规则。

Forgetting-aware Review Queue V1 不保存 `nextReviewAt` 或第二套复习状态。它根据 `reviewDueAt = lastEvidenceAt + stabilityDays × log2(masteryScore / 70)` 在读取时生成当前 Selected Books 范围内的待巩固、24 小时内和未来 7 天安排；多本文集共享的知识点只出现一次。Learning Hub 展示与解释计划，并可进入共享 Question Engine 的正式专项练习；Review 与 Focus 不改变 World target 的随机选择。

Permanent Wrong Book V1 把“错题”定义为长期学习资产而不是临时待办：正式父题出现 wrong 或 partial 即写入 `learner_wrong_question`，此后的正确答案不会删除该记录，只有学习者在错题本中手动移出才置为 `removed`，再次做错会自动重新激活。错题卡返回 `available` 与 `unavailableReason`（`out_of_scope` / `question_unavailable` / `knowledge_unavailable`），离开学习范围的错题会提前标记为不可练并禁用入口，避免点击后才报错；错题记录本身仍然永久保留。子题只用于父题拆解教学，永远不进入错题本、Mastery、复习队列或正式题数量。

Study 页的错题区域提供**快速练习错题**（intent `wrong_drill`）：从当前 selected Books 覆盖范围内的 active 错题中随机连续出题，Session 内不重复，全部做完后本轮结束；0 道错题时按钮禁用。错题本中单题“重做这道题”仍是 `wrong_review`，做完这一题即流程完成。两者都计入学习统计的错题练习作答，并且**快速练习中答对不会自动移出错题本**。

所有正式做题页（知识点专项、章节练习、单题错题重做、错题快练、World / 副本）都在题面顶部显示来源与真题标签，并显示全部知识点标签（core / auxiliary 都显示，用紫 / 青区分）：

```text
2022年考研数学一真题 · 第3题
[数列极限计算] [函数奇偶性、周期性与单调性]
```

标签由后端按 `exam_year` + `subject_name` 动态生成（数学一 + 2021 → `2021年考研数学一真题`；408 + 2024 → `2024年408考研真题`），不为显示文字新增 tag 表，也不把年份建成 KnowledgePoint。题面 metadata 在发题时冻结进 `question_snapshot_json`，刷新同一 attempt 结果稳定。真题 `exam_year` 只做确定性回填，已有值不覆盖；无法可靠推断的一律不猜。

目录与练习分工固定为 `Book → Chapter → KnowledgePoint` 三层（Chapter 只有一层，不再有“高等数学”这类中间 Section）：Study 负责按章节攻克，知识目录负责单点查看与 KnowledgePoint 专项，两者共享同一份 Formal Question Mastery 与错题本。顶栏的“知识”对应技术 route `/books`（文集 → 章节 → 知识点目录），“题库”对应 `/questions`（全平台已发布正式题目浏览）；两者都不创建 Attempt、不计入 Exposure，训练范围仍由当前 selected Books 决定。章节练习使用**固定确定性题序**（Chapter 内知识点 `sort_order` → 稳定来源标识 → 年份 → 题号自然排序 → 题目 ID），跨 Session 记住进度并在末尾回到第一题；同一道题关联本章多个知识点时只出现一次，归属于按知识点顺序第一次遇到它的位置。章节入口的“可练知识点数”等于该章节内存在正式题的知识点数，不再随今日答题情况、Review 到期或依赖 readiness 变化。Study 页顶部还会显示“最近练习章节”，结束一轮后仍可对同一 Book + Chapter 一键再次练习。用户界面只使用 `question_bank` / `question_bank_chapter` / `question_bank_knowledge` 提供的书名与章节名，数据库中的 legacy `subject_name` / `section_name` / `chapter_name` 仅保留兼容，不再作为展示路径。

Learner Question Rotation V1 仍以正式发题产生的 `study_attempt` 作为 Question Exposure 事实源，并按 Learner 跨 run、跨 World 共用。当前 Session / run 的 `seenQuestionIds` 是硬排除；RANDOM 另有“同一业务日同一题只出一次”的硬去重，其事实来源是 `study_attempt.draw_mode='random'`。不再使用曝光次数、preferred difficulty、Mastery 或 Review due 软排序，也不设置固定 cooldown 或永久 blacklist；全部题都出过时本轮结束，新 Session / 新业务日重新进入随机池。Learning Hub 的只读题目浏览不创建 attempt，因此不计入正式 Exposure。

Diagnostic State Machine V1 的代码与数据表继续保留，但普通正式训练不再自动进入诊断：RANDOM / CHAPTER / KNOWLEDGE / WRONG 答完一题就只记 Mastery / Evidence / Wrong Book 并继续下一道普通题。Legacy `/games/**` 与旧的历史诊断数据仍然可读。

当前已完成青溪求学、县试、赴府、府城游历与府试主干，共 44 项可配置活动。[具体里程碑](docs/releases.md)。

## 启动

前端需要 Node.js 22.12+，推荐 Node.js 24。

~~~powershell
cd E:\题海仕途\frontend
npm install
npm run dev
~~~

打开终端显示的本地地址，一般为 http://127.0.0.1:5173。

Learner Session 使用 HttpOnly SameSite Cookie。浏览器端必须使用同源 `VITE_API_BASE_URL=/api/v1`，本地由 Vite 将 `/api` 转发到 Java，部署时由反向代理转发。不要把 `VITE_API_BASE_URL` 设为 `http://localhost:12345/api/v1` 等绝对地址，否则页面 Origin 和 Session Cookie 站点会分离。

本机系统 Node 版本过旧时：

~~~powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\start-local.ps1
~~~

后端使用 Java 17，默认端口 `12345`：

~~~powershell
cd E:\题海仕途\backend
$env:JAVA_HOME='D:\Java\jdk-17.0.2'
.\mvnw.cmd spring-boot:run
~~~

数据库连接、环境变量和 HTTP 模式见 [后端说明](backend/README.md)。仓库不保存数据库密码。

## 自己修改

| 想改什么 | 文档 |
| --- | --- |
| 项目长期规则、领域模型、业务算法（先读这个） | [项目长期规则](docs/PROJECT_RULES.md) |
| 正式训练四套选题策略（RANDOM / CHAPTER / KNOWLEDGE / WRONG） | [正式训练选题策略](docs/question-practice-policy.md) |
| Agent 开发流程与文档纪律 | [Agent 开发规则](AGENTS.md) |
| 读书、奖励、副本、地图门槛、人物互动、装备 | [探索玩法修改手册](docs/adventure-guide.md) |
| 题库、世界观、章首、素材等基础配置 | [基础配置手册](docs/configuration-guide.md) |
| 代码分工、状态流 | [开发说明](docs/development.md) |
| Java 接口与低流量缓存 | [接口契约](docs/api.md) |
| 后端实施进度 | [后端开发记录](docs/后端开发记录.md) |
| 生产部署、数据库迁移、发布与回滚 | [生产部署说明](docs/deployment.md) |
| 本地背景与立绘 | [素材说明](docs/art-assets.md) |
| 可直接导入的全局题目批次 | [V4 示例 JSON](frontend/public/examples/题库示例.json) |

内容在 frontend/src/content，素材在 frontend/public/art，规则在 frontend/src/engine。
关键代码配中文注释，JSON 字段说明见手册。

## 存档与配置

新旧人生都默认进入世界。V1 升级时保留历史、钱、学识和人物关系，退出旧版未交卷面；新活动可以暂停、读档继续。

当前活动开始时冻结规则；改配置影响下一轮。服务端统一维护题库，浏览器按文集 revision 缓存 Markdown、公式、选项与解析；作答只提交课卷、题目 UUID 和答案。

寒门仕途主界面与题面采用视口布局；复杂编辑器、长正文与互动面板可以局部滚动。

## 最小验证

Agent 默认只跑最小必要测试（完整规则见 [`AGENTS.md`](AGENTS.md) 的“Agent 测试边界”）：

~~~powershell
cd E:\题海仕途\frontend
npm run build
npm test
~~~

build 包含配置引用、图片路径、题目知识点数量检查与类型编译，产物位于 frontend/dist。
只改配置时可单独 `npm run validate:content`，不重复运行全套检查。
完整回归由用户本地人工验收与 push 后的 GitHub Actions 承担。

前端默认使用 HTTP 联机模式；旧本地兼容模式需显式设置 `VITE_API_MODE=local`，详见接口契约。

## Phase J V4：统一账号与正式 Hub Practice

学习与管理现在共用唯一的 `learner_account` 身份和 Learner Session；`CONTRIBUTOR / REVIEWER / ADMIN` 只是附加权限，管理后台不再维护第二套登录页或管理账号来源。

Learning Hub 提供 KnowledgePoint 专项练习与错题练习。专项没有固定题数、checkpoint、score 或 pass/fail；每道正式题判题结束后，学习者可以再来一道同 KnowledgePoint 或结束。KnowledgePoint 专项候选包含 core 与 auxiliary 关联的全部正式父题，Session 内随机不重复，不再因为其他知识点未 ready 而卡住。错题本为**永久错题本**：正式父题一旦 wrong 或 partial 即进入错题本，之后即使重做正确也**不会自动移出**，只有学习者确认掌握并手动移出才消失；以后再次做错会自动重新加入。错题本与 Mastery 相互独立，允许 KnowledgePoint 掌握度 100% 的同时仍保留历史错题。Wrong Practice 首题固定为原 Question，做完即完成；快速练习错题则在 active 错题中随机连续出题，Session 内不重复。

World / 副本的普通正式题走 RANDOM KP-first：先选目标知识点，再在该知识点内按 oldest / wrong lane 选题，不使用 Review、Mastery 或 Manual Focus 排序，也按 `Asia/Shanghai` 业务日对同一道题硬去重；`plannedRounds = min(本轮题数上限, 当天剩余可出的随机题数)`，题目不足时自然提前完成。target 确定后，Hub 与 World 共用 Question Contract V2、Question Pool、选项排列、grading、Wrong Book 与 Mastery / Evidence 更新；普通正式训练不再有 dependency readiness gating，也不再自动进入诊断 / 补救。KnowledgePoint 专项、Chapter Practice 与 Wrong Drill 仍保持各自的范围与策略。
