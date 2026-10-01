# Java API 预留契约

当前只实现本地版，不启动 Java 服务。统一接口在 frontend/src/domain/types.ts 的 GameApi，HTTP 路由映射在 frontend/src/api/http.ts。

## 切换方式

frontend/.env.local：

~~~dotenv
VITE_API_MODE=http
VITE_API_BASE_URL=/api/v1
API_PROXY_TARGET=http://localhost:8080
~~~

重启开发服务器。Vite 开发代理把 /api 请求交给 Java；部署时自行配置同源代理或 CORS。
默认 local，不请求后端；HTTP 失败会直接展示错误，不自动降级或迁移存档。

## 路由

下表路径均相对于 /api/v1。字段的完整 TypeScript 定义以 domain/types.ts 为准。

| 方法 | 路径 | 请求体 | 成功返回 |
| --- | --- | --- | --- |
| GET | /bootstrap | 无 | Bootstrap |
| POST | /games | NewGame | Game |
| GET | /games/{id} | 无 | Game |
| DELETE | /games/{id} | 无 | 204 |
| POST | /games/{id}/answers | {attemptId, answer} | Game |
| POST | /games/{id}/next | {attemptId, reviewOnly} | Game |
| POST | /games/{id}/choices | {eventId, choiceId} | Game |
| PUT | /games/{id}/notes | {questionId, note} | Game |
| PUT | /games/{id}/configuration | {bankIds, weights} | Game |
| POST | /games/{id}/chapter | {chapterId} | Game |
| PUT | /question-banks/{id} | Bank | Bank |
| DELETE | /question-banks/{id} | 无 | 204 |
| GET | /games/{id}/export | 无 | JSON 字符串 |
| POST | /games/import | {json: "备份全文"} | 新 Game |

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

pace 为 normal / slow；difficulty 为 gentle / standard。
新建人生界面不再要求玩家选择文集或题量；客户端自动接入当前可用文集。`bankIds` 允许为空，因此没有可用文集时仍可进入世界，待题库接入后再参与答题活动。
Bootstrap 为 `{saves, banks, questionCatalog, activeId, legacyNotice}`；saves 中每项为 `{id,name,title,total,updatedAt}`。

`questionCatalog` 用于切换本地编辑题库与上线后的服务器统一题库：

~~~json
{"source":"server","canEdit":false,"revision":"2026-10-01.3"}
~~~

生产环境普通用户应返回 `source=server, canEdit=false`。前端仍可浏览文集、题目与知识点，但会隐藏导入、改名、删题等写操作。题库管理交给独立的后台管理权限；游戏客户端无需为每个用户保存一份题库。

Game 包含身份、配置、NPC 关系、当前章节、历史作答、复习状态、札记、批注、事件、当前课卷。时间统一用 ISO 字符串。

## 答题与随机选项

~~~json
{"attemptId":"一次发卷的唯一编号","answer":["A","C"]}
~~~

单选 answer 是原始键字符串，多选是原始键数组，判断是布尔值。屏幕字母由前端按照 options 的顺序生成，不能按显示字母重新解释提交值。

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
- selfAssessment 是早期兼容字段，新三题型不再使用；后端可以只实现客观题流程。

本地模式完整题库在浏览器可见，是单机体验。生产环境应由数据库统一维护 Bank、KnowledgePoint 与 Question；发题接口只返回 PublicQuestion，标准答案只在服务端判题后随 Result 返回。若加入考试排名，还需实现身份认证、事务、防重复提交与题库管理权限。


## V2—V5 新增探索接口

以下路径同样相对于 /api/v1，成功返回完整 Game。Game.adventure 的契约在 domain/adventure.ts；
HTTP 实现必须返回已迁移的探索数据，UI 不负责猜测缺失字段。

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

开始 `kind=exam` 的活动前校验对应考试为 `registered`。最后一题判卷时，在同一事务内更新 attempts、lastScore、best 与状态；及格进入 `passed`，未及格进入 `preparing`。落榜备考活动达标后恢复 `registered`，不再次扣费。活动中途放下不改变考试状态。

HTTP 后端必须自行校验这些状态，不能只依赖前端隐藏按钮。`Game.adventure.exams` 与活动首次奖励记账一并返回，重复提交同一答题请求不得重复发取中帖或身份奖励。
