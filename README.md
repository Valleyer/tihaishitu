# 知境

以统一学习进度为核心，在不同世界中学习、练习与成长。

React + TypeScript 前端与 Java 17 / Spring Boot 后端已经完成联机 Learning Hub（知境中枢）与多世界底座。登录后先选择学习范围与 Study Focus，再进入共享学习身份下的游戏世界。当前开放世界为“寒门仕途”。

本次改名仅调整产品展示名。仓库名 `tihaishitu`、Java package `cn.tihaishitu`、World ID `ancient-official`、既有数据库结构与路由继续保持稳定，不参与展示名迁移。

## 现在怎么玩

默认进入知境中枢，题面不会自动出现。`寒门仕途` 是知境当前开放的第一个游戏世界：

- **读书**：三页一卷，提升学识、悟性、辞采、筹算并赚银两。
- **访友**：与六位人物交谈、共读；按整轮成绩增加好感，解锁话题与关系信物。
- **游历**：青溪县、临川府两张大地图共十二个地点，统一显示“大地图名 · 小地点名”。
- **一次性任务**：Main 必须 10/10；Side 与 Dungeon 均为五题答对至少三题完成。任务只有完成档，完成时一次性领取完整奖励并永久关闭；未完成无惩罚且可无限重试，完成后不可再刷。
- **可重复活动**：普通 Study、Companion 等 Activity 与一次性 Task 分开管理，完成一轮后仍可再次进行。
- **养成**：26 件装备、消耗品和信物可收入行囊，装备加成实际参与解锁条件。
- **Learning Hub**：统一管理学习范围与重点知识点，查看学习进度和复习安排，并进行知识点专项练习与错题练习。
- **长期掌握状态**：正式作答只归因到当次目标知识点；Learning Hub 展示有效掌握度、记忆稳定度与目标难度，所有 World 共用同一份 Learner + KnowledgePoint 状态。
- **自适应学习 V1**：正式 World 从 Selected Books 范围内可玩的知识点随机确定目标；目标确定后，再按当前掌握状态与学习设置选择合适难度并校验前置知识。
- **遗忘感知复习 V1**：Learning Hub 的“今日巩固”和“复习安排”从现有掌握度与记忆稳定度动态推导，并可直接进入相应知识点的专项练习。
- **跨轮次题目轮换 V1**：正式 World 在维持自适应难度的前提下优先选择 Learner 尚未见过或更久未见的同难度题；题量不足时允许自然重用，不阻断任务重试。
- **综合题诊断 V1**：综合题答错时先保留原始作答，再核验最可疑的前置知识、补强薄弱点并复核目标知识点；只有诊断确认后才把根错误归因到目标。
- **正向挑战记录**：同一轮补救不追回首题失分，新一轮仍可重新拿满分；正式 World 只保留最高分、已通关和首次奖励等正向成就，不累计失败或应试次数。

判断、单选、多选继续支持，选项每次发卷打乱。正式联机流程中每位 Learner 在每个 World 只有一份服务端权威状态；旧多存档只保留兼容数据。

掌握度 V3 为 Learner × KnowledgePoint × 正式父题维护独立强化槽位：首次答对 30，跨自然日首次答对 +7，每满 3 天惰性衰减 1；KnowledgePoint 掌握度按当前正式父题总数聚合，全部 100 时冻结衰减，新增正式题会自动扩大分母。子题只用于补救教学，不产生 Mastery、Wrong Queue 或 Review Queue 数据。题目浏览、查看答案和 reveal 不产生证据。

Adaptive Scheduling V1 使用惰性遗忘后的有效掌握度判断题目依赖是否 ready。World target 从当前 Selected Books 的 playable KnowledgePoints 中随机抽取，Mastery、Review 与 Manual Focus 不决定 target 优先级；target 确定后，题目关联的其他 core/auxiliary 知识点必须达到基本掌握，并继续使用自适应难度、发布状态、本轮去重与题目轮换规则。`standard` 按目标难度与掌握上限选题，`gentle` 在此基础上下调一级；旧 `/games/**` 继续使用兼容的 scope-only 随机选题规则。

Forgetting-aware Review Queue V1 不保存 `nextReviewAt` 或第二套复习状态。它根据 `reviewDueAt = lastEvidenceAt + stabilityDays × log2(masteryScore / 70)` 在读取时生成当前 Selected Books 范围内的待巩固、24 小时内和未来 7 天安排；多本文集共享的知识点只出现一次。Learning Hub 展示与解释计划，并可进入共享 Question Engine 的正式专项练习；Review 与 Focus 不改变 World target 的随机选择。

Learner Question Rotation V1 将正式发题产生的 `study_attempt` 作为 Question Exposure 事实源，并按 Learner 跨 run、跨 World 共用。当前 run 的 `seenQuestionIds` 仍绝对排除；服务端先沿用 Phase F 规则确定难度，再在同难度候选中按“未曝光 → 最久未见 → 曝光更少 → 随机”选择。Exposure 是软排序，不设置固定 cooldown 或永久 blacklist；全部题都见过或题库只有一题时仍允许旧题再次出现。Learning Hub 的只读题目浏览不创建 attempt，因此不计入正式 Exposure。

Diagnostic State Machine V1 将 raw answer 与 KnowledgePoint 归因分开。单知识点错误仍立即归因目标；normal composite wrong/partial 会建立 Diagnosis Session，依次执行 dependency probe、必要的 dependency remediation、target recheck 与 target remediation。Probe 使用 normal evidence 且难度不超过 3，补强使用 training evidence。诊断题都遵守本轮冻结 Book scope、实时 readiness、published 和 seen 约束。用户放弃或依赖无题时，不会把含糊的根错误强行扣到目标知识点。

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
| 读书、奖励、副本、地图门槛、人物互动、装备 | [探索玩法修改手册](docs/adventure-guide.md) |
| 题库、世界观、章首、素材等基础配置 | [基础配置手册](docs/configuration-guide.md) |
| 代码分工、状态流 | [开发说明](docs/development.md) |
| Java 接口与低流量缓存 | [接口契约](docs/api.md) |
| 后端实施进度 | [后端开发记录](docs/后端开发记录.md) |
| 本地背景与立绘 | [素材说明](docs/art-assets.md) |
| 可直接导入的全局题目批次 | [V2 示例 JSON](frontend/public/examples/题库示例.json) |

内容在 frontend/src/content，素材在 frontend/public/art，规则在 frontend/src/engine。
关键代码配中文注释，JSON 字段说明见手册。

## 存档与配置

新旧人生都默认进入世界。V1 升级时保留历史、钱、学识和人物关系，退出旧版未交卷面；新活动可以暂停、读档继续。

当前活动开始时冻结规则；改配置影响下一轮。服务端统一维护题库，浏览器按文集 revision 缓存 Markdown、公式、选项与解析；作答只提交课卷、题目 UUID 和答案。

寒门仕途主界面与题面采用视口布局；复杂编辑器、长正文与互动面板可以局部滚动。

## 最小验证

~~~powershell
cd E:\题海仕途\frontend
npm run build
npm test
~~~

build 包含配置引用、图片路径、题目知识点数量检查与类型编译，产物位于 frontend/dist。
只改配置时可单独 npm run validate:content，不重复运行全套检查。

前端默认使用 HTTP 联机模式；旧本地兼容模式需显式设置 `VITE_API_MODE=local`，详见接口契约。

## Phase J V4：统一账号与正式 Hub Practice

学习与管理现在共用唯一的 `learner_account` 身份和 Learner Session；`CONTRIBUTOR / REVIEWER / ADMIN` 只是附加权限，管理后台不再维护第二套登录页或管理账号来源。

Learning Hub 新增 KnowledgePoint 专项练习与错题练习。专项没有固定题数、checkpoint、score 或 pass/fail；每道正式题及其 Diagnosis/Training 结束后，学习者可以再来一道同 KnowledgePoint 或结束。错题队列按 Learner + Question 最近一次 graded attempt 动态派生，latest wrong/partial 进入，later correct 移除，Wrong Practice 首题固定为原 Question。

World target 从 Selected Books 范围内 active、adaptive playable 的 KnowledgePoint 随机抽取，包含 unstarted、weak 与 proficient，不使用 Review、Mastery 或 Manual Focus 排序。target 确定后，Hub 与 World 共用 dependency readiness、Adaptive Difficulty、Question Pool、Question Rotation、grading、Evidence、Diagnosis、Training 和 Mastery 更新。
