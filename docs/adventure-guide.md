# 寒门仕途玩法与内容修改手册

本文记录 PR9 之后的 canonical 玩法。目标是让新人生围绕四项成长推进，并让旧存档安全读取。

## 1. 四项核心成长

| 成长 | 唯一主要来源 | 说明 |
| --- | --- | --- |
| 学识 | 潜心读书 | 每轮五题，60 分通关，整轮结算 `knowledge +5` |
| 银两 | 抄书谋生 | 每轮五题，60 分通关，整轮结算 `coins +4` |
| 声望 | 一次性任务 | 成功后一次发放；失败不罚，可无限重试 |
| 功名 | 科举主线 | 由配置中的 Exam 状态决定，不新增玩家数值字段 |

普通单题只更新答题历史、错题和掌握度，不直接改变这四项资源。
人物共读只增加对应人物的好感度。

功名与身份称号彼此独立。`player.title` 仍是身份称号；功名显示取已通过的最高一场考试：

| 状态 | 功名文本 |
| --- | --- |
| 未通过任何考试 | 尚无功名 |
| 县试通过 | 县试取中 |
| 府试通过 | 府试取中 |

历史存档里的 `adventure.attributes` 只作兼容保留。悟性、辞采、筹算不再参与新玩法的展示、门槛、奖励或物品效果。

## 2. 玩家怎样推进

新人生的主路径是：

1. 在旧书塾反复“潜心读书”，把学识提升到地点与考试门槛。
2. 需要银两时反复“抄书谋生”。
3. 完成青溪的一次性任务取得声望和剧情标记。
4. 达到学识 35、声望 3 后报名县试，十题全对取中。
5. 完成驿路赴府与临川抵达剧情。
6. 达到学识 50、声望 3 且拥有 `linchuan-arrived` 后报名府试。

底部基础 Activity Shelf 只展示“潜心读书”和“抄书谋生”。人物共读、地点任务、温卷与考试仍从各自场景入口进入。

## 3. 内容配置文件

内容文件位于 `frontend/src/content/`：

| 文件 | 用途 |
| --- | --- |
| `adventure.json` | 世界文案、关系等级、Activity kind 名称 |
| `activities*.json` | 日常、人物共读、一次性任务、温卷与考试活动 |
| `maps.json` | 地区、地点及学识/物品/剧情标记门槛 |
| `exams.json` | 考试资格、费用、活动与功名文本 |
| `items*.json` | 稳定物品 ID、纪念信物和任务物品 |
| `companions.json` | 人物话题、共读入口与关系心意 |
| `events.json` | 叙事选择；不得作为四项核心资源的来源 |
| `game.json` | 通用上限等规则，不含逐题资源增长 |

`activities.json`、`activities-v7.json`、`activities-v8.json` 会在运行时合并。已发布的 ID 不要改名或删除，以免破坏存档引用。

## 4. 两项基础日常

基础活动必须保持以下契约：

```json
{
  "id": "read",
  "kind": "study",
  "name": "潜心读书",
  "activityMode": "repeatable",
  "rounds": 5,
  "passScore": 60,
  "tiers": [
    {"minScore": 0, "rewards": {}},
    {"minScore": 60, "rewards": {"knowledge": 5}}
  ]
}
```

```json
{
  "id": "copy-work",
  "kind": "work",
  "name": "抄书谋生",
  "activityMode": "repeatable",
  "rounds": 5,
  "passScore": 60,
  "tiers": [
    {"minScore": 0, "rewards": {}},
    {"minScore": 60, "rewards": {"coins": 4}}
  ]
}
```

旧普通 Study 不得重新放回基础 Shelf。县试与府试的温卷活动保留稳定 ID，仅作为落榜后的考试准备入口，不发成长资源。

## 5. 一次性任务

一次性任务使用 `activityMode: "task"`。成功奖励可包含：

- `reputation`
- `favorability`
- `items`
- `flags`
- `title`

不得包含 `knowledge`、`coins` 或 `attributes`。已有声望值保持；没有声望的一次性任务至少补 `reputation +1`。

任务生命周期是稳定契约：

- 失败没有数值惩罚和失败履历，可无限重试。
- 入场费用开始时托管；失败或中止原数退回，成功才提交。
- 达标时只发一次 `completionReward`，`clears[id]` 固定为 1。
- 成功后入口永久关闭，不能重复领奖。
- 已开始的活动保存完整定义快照；之后修改 canonical 配置不得覆盖旧快照承诺。

## 6. 地点门槛

canonical 地点只使用学识、剧情标记和物品推进：

| 地点 | 门槛 |
| --- | --- |
| 旧书塾 | 无 |
| 东斋 | 无 |
| 藏书楼 | 学识 15 |
| 县衙 | 学识 20 |
| 试院前巷 | 学识 30 |
| 夜读斋 | 学识 50 |
| 清溪驿路 | `county-exam-passed` |
| 临川府驿馆 | `prefecture-road-opened` |
| 临川府试院前街 | `prefecture-road-opened` |
| 临川府清晖书院 | `prefecture-road-opened` 且学识 55 |
| 临川府河埠 | `prefecture-road-opened` 且学识 60 |
| 临川府档案库 | `prefecture-road-opened` 且 `wharf-ledger-cleared` |

地点与活动自己的 requirements 会共同检查，不能从其他入口绕过。

## 7. 科举与功名

县试资格为学识 35、声望 3；府试资格为 `linchuan-arrived`、学识 50、声望 3。两场考试都不检查旧本领。

考试配置用 `meritTitle` 声明功名文本。界面按考试配置顺序寻找已通过的最高一场，不把功名写进 `player.title`，也不新增数据库字段。

主线考试固定十题且必须全对。落榜后通过对应温卷活动恢复报名资格；温卷本身不发学识、银两或声望。

## 8. 物品与旧存档

canonical 物品不得包含 `bonuses.insight`、`bonuses.eloquence`、`bonuses.craft` 或 `use.attributes`。旧装备、消耗品的稳定 item ID 继续保留，但转为无旧属性效果的纪念信物。

读取旧存档时：

- 保留已有 `adventure.attributes` 数据，但不显示、不参与计算。
- 若 `equipped` 指向当前不存在、非 equipment 或槽位不匹配的物品，安全移除该装备引用。
- 不清空旧背包，不改写稳定 item ID。
- 旧 active run 的冻结 requirements、tiers、奖励和对白继续按开始时快照执行。

外层存档格式与数据库 schema 均不因本轮变化升级。

## 9. 运行时边界

前后端都从同一套内容配置读取规则。`AdventureEngine` 与后端 `GameActionService` 负责整轮活动结算；`ProgressionSystem.settleProgress()` 不得按单题增加学识或声望。

RANDOM、Mastery V3、错题本、Global Question Bank、Study、Progress 与 `learnerDataCache` 不属于本轮改造范围。

## 10. 修改后的最小验证

```powershell
cd frontend
npm test
npm run validate:content
npm run build
npm run lint
```

后端至少运行任务生命周期、世界状态、题库、随机目标、奖励归一、内容扩展和寒门可达性这些定向测试。涉及长期规则时还要用真实浏览器检查：HUD 只显示四项成长、基础 Shelf 只有两个入口、行囊不显示旧属性，以及旧存档载入不崩溃。
