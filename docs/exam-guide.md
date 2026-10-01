# 科举系统配置说明

县试从 V6 起是一条独立的世界进阶线。玩家先在世界中积累学识、声望、三门本领与报名银，再主动递帖；报名完成后才会出现正式答题。正式考试落榜后必须完成一次短备考，才能再次应试。取中会改变身份、发放信物并开放新地图。

## 一、相关文件

| 文件 | 作用 |
| --- | --- |
| `frontend/src/content/exams.json` | 考试名称、报名地点、费用、资格、状态对白及关联活动 |
| `frontend/src/content/activities.json` | 正试题数、及格线、成绩档奖励、落榜备考与后续剧情 |
| `frontend/src/content/items.json` | 取中帖、簪花、府试投牒等功名物品 |
| `frontend/src/content/maps.json` | 试院地点与取中后开放的府城驿路 |
| `frontend/src/domain/adventure.ts` | 考试配置和存档字段类型 |
| `frontend/src/engine/AdventureEngine.ts` | 报名扣费、应试资格、揭榜和重考状态机 |

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
    "preparing": "落榜后需要温卷",
    "passed": "取中后的说明"
  }
}
~~~

- `id`：稳定编号，存档用它记录考试进度，发布后不要改旧 id。
- `activityId`：必须指向 `kind: "exam"` 的正式考试活动。
- `preparationActivityId`：落榜后恢复应试资格的活动。
- `fee`：只在第一次报名时扣除。落榜后完成备考，不会再次收费。
- `requirements`：报名资格，支持学识、声望、属性、好感、物品与剧情标记。
- `dialogues`：四种状态在县试面板和侧栏目标中显示。

## 三、正式考试与奖励

正式考试仍使用活动的 `rounds`、`passScore` 和 `tiers`。县试当前为十题、七十分取中。每档只发该档的 `rewards`，所有已经达到且未领过的 `firstRewards` 都会补发，因此身份、取中帖和开放标记应放在及格档的 `firstRewards`：

~~~json
{
  "minScore": 70,
  "label": "取中",
  "rewards": { "reputation": 8, "coins": 20 },
  "firstRewards": {
    "title": "县试取中",
    "items": { "county-pass-note": 1 },
    "flags": ["county-exam-passed"]
  }
}
~~~

`title` 会直接更新人物志中的身份称号。若满分档还要授予“案首”，可以在满分档的 `firstRewards.title` 再写新称号。首次奖励有独立记账，不会因刷新、重复提交或以后补考而重复取得。

## 四、落榜与重考

正式考试低于及格线时，考试状态变为 `preparing`。此时：

1. 正试入口关闭。
2. 县试面板改为引导玩家完成 `preparationActivityId`。
3. 备考活动达到自己的 `passScore` 后，状态恢复为 `registered`。
4. 玩家可以再次进入正试，报名费不重复扣除。

备考失败会继续保持 `preparing`，可再次复盘。中途放下正试不会判落榜，也不会消耗资格；只有答完全部题并结算才改变考试状态。

## 五、取中后开放新地图

县试及格档发放 `county-exam-passed`。`maps.json` 的府城驿路把该标记写进地点门槛：

~~~json
"requirements": { "flags": ["county-exam-passed"] }
~~~

这样取中前舆图会显示前置要求，取中后可以直接前往。驿路上的 `prefecture-departure` 是当前的后续主线入口，完成后获得府试投牒。以后增加府试时，建议新建第二个 exam 配置和两项活动，继续沿用同一套状态机。

## 六、存档字段

`Game.adventure.exams` 以考试 id 保存：

~~~json
{
  "county-exam": {
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
- 正试题数可以修改为 5–50，及格线可以修改为 0–100；前端会按实际正确率四舍五入计分。
- 正试抽取玩家已启用的题库，仍只支持判断、单选和多选，选项每次出现都会随机排列。
- 新增考试后，界面目前默认重点展示 `exams.json` 第一项；多场考试的状态机和存档已支持，未来制作府试时再增加考试列表选择界面。
