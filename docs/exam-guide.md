# 科举系统配置说明

县试从 V6 起是一条独立的主线进阶，V8 已用同一状态机接入临川府试。玩家先在世界中取得报名资格，再主动递帖；县试、府试、乡试、会试、殿试以及以后标记为主线任务的核心答题，统一为十个不同知识点且必须全部首答正确。没有全对只记录题目结果，不扣属性、银两或资格，玩家可以直接无限次重试。

## 一、相关文件

| 文件 | 作用 |
| --- | --- |
| `frontend/src/content/exams.json` | 考试名称、报名地点、费用、资格、状态对白及关联活动 |
| `frontend/src/content/activities.json`、`activities-v8.json` | 县试、府试正试内容、满分奖励与后续主线剧情 |
| `frontend/src/content/adventure.json` | 全局普通答题与主线答题的题数、过关线 |
| `frontend/src/content/items.json`、`items-v8.json` | 取中帖、府试投牒等功名物品 |
| `frontend/src/content/maps.json` | 多张大地图、试院地点与取中后开放区域 |
| `frontend/src/domain/adventure.ts` | 考试配置和存档字段类型 |
| `frontend/src/engine/AdventureEngine.ts` | 报名资格、开考费用托管、揭榜和重考状态机 |

JSON 中不能写注释。修改后先运行 `npm run validate:content`，配置引用错误会直接指出所属考试或活动。

## 二、exams.json 字段

~~~json
{
  "id": "county-exam",
  "name": "青溪县试",
  "subtitle": "童试第一关 · 十题定榜",
  "locationId": "exam-street",
  "activityId": "county-exam-paper",
  "preparationActivityId": "county-exam-prep",
  "fee": 12,
  "requirements": {
    "knowledge": 35,
    "reputation": 3,
    "attributes": { "insight": 6, "eloquence": 4, "craft": 4 }
  },
  "dialogues": {
    "unregistered": "报名之前的说明",
    "registered": "已经报名、等待应试",
    "preparing": "旧存档兼容状态，读取后转为 registered",
    "passed": "取中后的说明"
  }
}
~~~

- `id`：稳定编号，存档用它记录考试进度，发布后不要改旧 id。
- `activityId`：必须指向 `kind: "exam"` 的正式考试活动。
- `preparationActivityId`：旧存档兼容字段；当前规则下未全对可直接重试，不再强制进入备考活动。
- `fee`：报名时不扣；每个新 run 开始时暂收，未完成或放下时退款，完成时提交。
- `requirements`：报名资格，支持学识、声望、属性、好感、物品与剧情标记。
- `dialogues`：四种状态在县试面板和侧栏目标中显示。

## 三、主线考试与奖励

`kind: "exam"` 或 `quest: "main"` 的活动都会按 `adventure.json.answerRules` 统一成十题、满分过关。界面只展示满分奖励；未满分的内部兜底档没有奖励，也不会造成任何数值惩罚。身份、取中帖和开放标记应放在满分档的 `firstRewards`：

~~~json
{
  "minScore": 100,
  "label": "全对取中",
  "rewards": { "reputation": 8, "coins": 20 },
  "firstRewards": {
    "title": "县试取中",
    "items": { "county-pass-note": 1 },
    "flags": ["county-exam-passed"]
  }
}
~~~

`title` 会直接更新人物志中的身份称号。旧成绩档的 `rewards` 与 `firstRewards` 会折叠成一个 `completionReward`：同一数值键取最大值，不同键和 flags 合并，称号取最高档。完成奖励只发一次。题目从藏书阁当前启用的题库中抽取，并继续服从题库权重、掌握题排除和选项随机规则。

## 四、未全对与重考

十题中只要有一题错误，本次便不取中。结算后：

1. 已答题目正常累计次数、错误次数与错误率。
2. 错题会进入旧案，并参与藏书阁的掌握题过滤。
3. 考试状态仍为 `registered`，正试入口立即可用。
4. 不扣属性、声望、道具或报名资格；本轮暂收的报名费原数退回。
5. 重试次数不设上限。

中途放下正试不会判定失败，也不会消耗资格，暂收费用会原数退回。取中后任务永久结案，入口禁用且后端拒绝再次开始。旧存档若保存了 `preparing` 状态，读取时会自动迁移为 `registered`。

## 五、取中后开放新地图

县试满分档发放 `county-exam-passed`。`maps.json` 的府城驿路把该标记写进地点门槛：

~~~json
"requirements": { "flags": ["county-exam-passed"] }
~~~

这样取中前地图会显示前置要求。驿路上的 `prefecture-departure` 是赴府主线，十个知识点全对后发放 `prefecture-road-opened`，地图便可切换到临川府。完成 `story-linchuan-arrival` 后可以报名 `prefecture-exam`。以后增加院试、乡试、会试、殿试时，新建 exam 配置并把对应活动设为 `kind: "exam"` 或 `quest: "main"`，便会自动沿用十题全对规则。

## 六、存档字段

`Game.adventure.exams` 以考试 id 保存：

~~~json
{
  "prefecture-exam": {
    "status": "registered",
    "attempts": 1,
    "best": 60,
    "lastScore": 60
  }
}
~~~

`status` 只能是 `unregistered`、`registered`、`preparing`、`passed`。旧 V5 存档首次读取时会自动补齐该字段，原有题目历史、关系、物品和副本成绩不变。

## 七、调整数值时的建议

- 报名门槛决定玩家先体验多少读书、人物和副本内容；提高前应确认各属性确实有可重复的获取来源。
- 报名费应低于玩家通过数轮普通活动能稳定取得的银两，避免被迫重复刷同一活动。
- 主线题数和过关线集中配置在 `adventure.json.answerRules.mainRounds` 与 `mainPassScore`。当前产品规则固定为 10 和 100。
- 正试抽取玩家已启用的题库，仍只支持判断、单选和多选，选项每次出现都会随机排列。
- 科举面板按 `exams.json` 生成阶段标签，并默认打开第一场尚未取中的考试；侧栏主线任务也会自动指向它。
- Java 后端读取旧存档时会补齐新增考试记录和新增人物，保留原有关系、物品、成绩和作答历史。
