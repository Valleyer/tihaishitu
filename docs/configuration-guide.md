# 基础配置修改手册

需要让其他 AI 从试卷 PDF、教材或讲义生成全服题目时，使用 [题库生成提示词](./题库生成提示词.md)。该规范对应管理中台正式的 `global-question-batch/v2` 导入格式，包含 Markdown/LaTeX、客观题与解答自评题，以及每题 1–3 个全局细分知识点关系。题目批次不创建或修改 Book。

> 当前探索玩法请先阅读 [V2—V5 探索手册](adventure-guide.md)。本页保留 V1 的题库、素材与基础章节字段；人物关系奖励、地图移动、默认卷面和际遇触发已由新手册中的探索循环替代。

本手册对应当前 V1。大多数日常内容修改在 frontend/src/content 中完成，无需改 React 页面。
路径以下均以项目根目录 E:\题海仕途 为起点。

## 一、先记住这几条

1. 配置使用 UTF-8 JSON。字符串要用英文双引号，最后一项后面不要多逗号；JSON 本身不能写 // 注释，所以字段说明集中在本手册，读取代码中另有中文注释。
2. id 是关联编号，不是展示名称。改名字、对白、说明尽量保留 id。
3. 题库题目 id 在同一题库内唯一；人物、地点、章节 id 在各自文件内唯一。
4. 改完运行 npm run validate:content，一次即可检查常见的引用和题型错误。

大型内容扩充可以按版本拆成 `activities-v7.json`、`items-v7.json` 这类文件，再在 `content/index.ts` 合并导出；对应校验文件也要加入 `scripts/validate-content.mjs`，避免新增配置绕过引用检查。
5. 开发模式下改配置后刷新页面；正式发布要重新 npm run build。
6. 当前题目、历史答卷与正在发生的际遇会写入存档，不会因你改文件而整段重演。新开人生最适合验证结构性改动。

## 二、修改入口速查

| 文件（均在 frontend/src/content） | 内容 |
| --- | --- |
| game.json | 标题、时代、开局、科目默认比例、日历、成长、复习参数、长度上限 |
| chapters.json | 章节顺序、晋章条件、章首对白、目标、身份、场景组和地点 |
| characters.json | 人物名称、身份、简介、关系初值、立绘引用 |
| scenes.json | 日常场景、对白、答对答错反馈、重复场景、共同经历 |
| events.json | 选择际遇的题数门槛、选项文字、数值和记忆后果 |
| maps.json | 地点名称、介绍、地图坐标、场景背景和取景位置 |
| portraits.json | 人物立绘图集路径、列数、每种立绘的列号 |
| question-banks.json | 内置题库及题目 |
| index.ts | 统一导入与类型入口，新增配置文件时才需要调整 |

### 示例：只想改先生的名字

在 characters.json 找到 id 为 lu 的对象，修改 name；再修改章节介绍、场景正文和际遇中写死的姓名。{npc} 占位符会自动使用人物名，普通正文中的“陆承明”不会自动替换。新开人生能使用完整的新人物设定，已存在人生保留创建时的人物资料和关系。

## 三、game.json：全局设定与数值

### 世界与开局

| 字段 | 用法 |
| --- | --- |
| title、titleSeal | 游戏名称与首页小印字 |
| dynasty、era、startYear | 朝代、年号、起始年份 |
| volume | 当前卷名称；仅展示，不自动增加后续玩法 |
| titleBackground | 首页背景，路径如 /art/academy.png |
| titleEyebrow、titleDialogue、tagline、footer | 首页眉题、开场对白数组、题句、底部文字 |
| prologue | 开局短文数组；当前留名表单展示最后一句引导 |
| initialJournalTitle、initialJournal | 新人生的第一条札记 |
| origins | 出身数组，name 是名称，coins 是初始铜钱 |
| road | 人生远景的展示节点；后续节点尚未实现，不等于可进入关卡 |
| defaultWeights | 新人生各科默认抽取权重 |
| subjectTasks、defaultTask | 科目对应的游戏任务名和兜底任务名 |

defaultWeights 是比例，不要求加起来等于 100。例如数学一 7、408 7、政治 3、英语一 3，对应大致 35%、35%、15%、15%。复习优先、可用题数等会影响短期实际分布。0 表示不修习该科。新增科目未填写默认权重时按 1 处理。已有存档可在“行囊”修改自己的比例。

### 日历 calendar

- normal、slow：完成多少题算一个游戏日，默认 8 和 12。
- daysPerYear：一个游戏年的天数，默认 360。
- seasons：季节名称数组；一年按数组长度均分。
- times：时段文案数组，当前每两次课业切换一个时段。
- 游戏日不等于真实学习日期；修业统计按电脑自然日期计数。

### 成长 growth

答对获得：min(maxGain, correctBase + ceil(难度 / difficultyDivisor) + 高频奖励)。
题目 frequency 达到 highFrequencyThreshold 时，额外加 highFrequencyBonus。
答错获得 wrongGain 学识。

| 字段 | 含义 |
| --- | --- |
| correctBase、difficultyDivisor | 答对基础值和难度系数的除数 |
| highFrequencyThreshold、highFrequencyBonus | 高频加成条件与数值 |
| maxGain、wrongGain | 单题答对上限与答错收益 |
| reputationEvery | 累计题数到该值的整数倍且本题答对时，声望 +1 |
| trustGain | 答对时当前 NPC 的信任增量 |
| trustLossOnRepeatedWrong | 持重模式同题再次答错时的信任扣减 |
| relationshipMax | 信任和好感的上限 |

### 复习 review

| 字段 | 含义 |
| --- | --- |
| normalGap、wrongGap | 常规题和有错题记录题目的最小间隔 |
| firstWrongMin、firstWrongMax | 第一次答错后随机等待的完成题数范围 |
| repeatWrongMin、repeatWrongMax | 再次答错后的范围 |
| duePriority | 到期错题优先抽取概率，0–1 |
| masteredBaseGap、masteredMaxGap | 连续答对后复习间隔的基础值和封顶值 |
| masteryRules | 题目停止抽取的掌握档位；attempts 是累计作答次数，maxWrong 是最多累计错误次数 |

默认掌握档位为 3 次全对、5 次最多错 1 次、10 次最多错 3 次。达到任一档位后，该题不再进入普通抽题或旧案重审。可以直接修改 `masteryRules`，也可以增删档位；旧存档会按当前配置重新判断。

到期表示进入优先队列，不代表第 N 次必然出现。题太少时允许放宽间隔，避免无法继续游戏。频率、薄弱章节、连续答对和距离上次作答的时间也影响题目抽取；算法在 QuestionEngine.ts 中有注释。

limits.questionsPerBank 是题库题数上限，limits.noteLength 是每题批注字数上限。导入文件大小等技术限制仍由入口代码约束。

## 四、chapters.json：章节与晋章

章节数组的排列就是主线顺序。每项包含：

| 字段 | 含义 |
| --- | --- |
| id | 稳定编号，例 chapter-0 |
| title、subtitle、place | 标题、副题与地点文字；实际地点优先关联 maps |
| threshold | 进入本章要求的累计完成题数，不是本章内题数 |
| knowledge、trust | 进入本章要求的学识与所有 NPC 信任总和 |
| playerTitle | 进入本章后的玩家身份 |
| goal | 在本章页面展示的下一阶段目标文字，需与下一章门槛保持一致 |
| sceneGroup | 引用 scenes.groups 内的数字 id |
| locationId | 引用 maps.locations 的 id |
| intro.npcId、intro.speaker | 章首出场人物 id 和显示姓名 |
| intro.lines | 章首逐句对话数组 |
| intro.action | 最后一句后的进入按钮文字 |

晋章必须同时达到下一章的 threshold、knowledge、trust。每次答题最多前进一章。际遇加成后会在下一次答题结算时检查晋章。章首介绍显示在下一份课卷开始前，避免打断本题批卷结果。

例：希望第 2 章在 20 题开始，把该章 threshold 改成 20，并同步修改前一章 goal 及相关对白里的“十二页”。不要只改文字，数字门槛不会跟着文字自动推断。

新增章节：复制最后一项，给出新 id 和递增 threshold，关联有效 sceneGroup、locationId，并补齐对白。可以复用地图和剧情组。修改章节顺序、插入中间章节会影响旧存档的数字章序，推荐新开人生。

## 五、characters.json：人物

每位 NPC 有 id、name、role、description、affinity、trust、met、portrait。

- affinity：好感初值；trust：信任初值；met：开局是否已经相识。
- portrait 是 portraits.json 中 portraits 的键，例如 tutor、friend。
- 多个人可以引用同一立绘，新增人物不一定要新增图片。
- 在 scenes 中让人物出场后，met 自动为 true；际遇关系奖励也会让玩家认识该人物。
- 新增人物后还需在场景或际遇中引用，否则只是配置了一位没有出场的角色。
- 人物初值用于新人生，旧存档保留自己的关系数值。删除旧存档中用到的人物或更换 id 需要迁移，V1 建议新开人生。

## 六、scenes.json：对白驱动的日常剧情

groups 是剧情组数组，每组用数字 id 与章节对应。组内 scenes 按顺序提供日常场景。

~~~json
{
  "id": "scene-new-0",
  "title": "窗前借卷",
  "npcId": "gu",
  "context": "顾怀安将手边的旧卷移到你面前。",
  "dialogue": "“{player}，这一页我们各自作答，再互相印证。”",
  "success": "“与你的答案相同。此处的道理，算是读明白了。”",
  "failure": "“先别收卷。我们把意见不同的地方再推一遍。”"
}
~~~

context 是“前情”里的背景补充；dialogue 是主要显示的 NPC 对话。success、failure 是批卷之后的剧情反馈。优先把关键推进写进对白，避免把必须阅读的长篇背景塞进 context。

支持 {player} 和 {npc} 两个占位符。想插入真实换行，在 JSON 字符串里写 \n。

初次场景用完后，repeatContext、repeatDialogues、repeatSuccess、repeatFailure 接管日常续读，避免每次循环都表现成第一次认识。复习优先使用 reviewContext、reviewDialogue。

memories 的每项用 npcId + flag 关联人物共同经历，dialogue 是满足条件后的对白。效果优先级：复习对白 > 已命中共同经历 > 日常重复对白 > 首次场景对白。多段记忆同时命中时使用数组里第一项。

剧情组按“累计完成题数 - 本章 threshold”定位，若学识或信任导致延后入章，可能跳过组内部分初次场景；这是当前 V1 的轻量推进方式。复杂剧情树、必须逐个完成的关卡需要后续扩展引擎。

## 七、events.json：选择与后果

每项是一个际遇：id、at（累计题数门槛）、title、speaker、npcId、text、options。默认达到题数时，按数组顺序逐一触发未完成际遇。当前只支持题数触发，不自动解析自定义条件表达式。

~~~json
{
  "id": "lend-book",
  "at": 50,
  "title": "一卷相托",
  "speaker": "顾怀安",
  "npcId": "gu",
  "text": "“这卷旧书我已经读过，你若愿意，便带回去。”",
  "options": [
    {
      "id": "accept",
      "text": "接卷道谢，约好日后归还",
      "hint": "学识 +2，顾怀安好感 +1",
      "effects": { "knowledge": 2, "affinity": { "gu": 1 }, "flags": ["borrowed-gu-book"] }
    },
    {
      "id": "decline",
      "text": "请他先留着，自己把旧卷读完",
      "hint": "顾怀安信任 +1",
      "effects": { "trust": { "gu": 1 } }
    }
  ]
}
~~~

effects 支持 knowledge、coins、reputation、trust、affinity、flags。
前三项直接填增减数；trust 和 affinity 用人物 id 对应增减数；flags 是共同经历字符串数组。
数值最低为 0，人物关系封顶取 growth.relationshipMax。

hint 只是给玩家看的说明，**真正结算读取 effects**。改奖励时两者都要改。
事件完成后保存 event:事件id 防止重复；想新增一段际遇，应新增 id，而不是拿旧事件换全文。
flags 可被 scenes.memories 使用，暂不支持任意脚本或动态表达式。

## 八、地图与立绘

### maps.json

`regions` 配置大地图的 id、name、description、background、position 与 requirements；`locations` 每项包含 id、regionId、name、description、background、position、x、y。

- name：统一使用“大地图名 · 小地点名”，例如 `青溪县 · 旧书塾`、`青溪县 · 东斋`。地图节点只显示点号后的短名；以后新增任何地点也必须沿用此格式。
- regionId：引用 `regions` 中的大地图 id；地图弹窗只在当前大地图底图上显示属于它的小地点。
- background：本地图片路径，/art/青溪.jpg 对应 frontend/public/art/青溪.jpg。
- position：CSS 背景取景位置，例如 70% 50%。
- x、y：地图点的百分比坐标，建议保持在 10–90，避免贴边。
- 点击大地图标签只切换查看区域；点击具体地点并确认后才调用移动 API。
- 当前十二个地点各自使用 `public/art/locations/` 下的独立背景；图片文件名、建议尺寸与完整生成提示词统一登记在 `docs/map-background-assets.md`。

### portraits.json

当前使用 `public/art/portraits/` 下的八张独立方形头像。每项包含 `src`、`label` 与 `position`；替换图片后通常保持文件名不变，只需在必要时调整 `position`。

`playerMale`、`playerFemale` 是玩家默认键，请保留。NPC 头像键为 `lu`、`gu`、`shen`、`lin`、`pei`、`su`，人物仍可重复引用已有键。所有图片的尺寸、路径、生成提示词和替换要求统一登记在 `docs/map-background-assets.md`。

## 九、题库：三种题型与正确答案

### 不改代码的方式

游戏首页“整理书卷”或游戏内“藏书阁”：
导入 JSON/CSV → 选择文件 → 校验并收录 → 在新人生或行囊中选用。

可以直接导入 frontend/public/examples/题库示例.json。
藏书阁支持新建、添题、修订、删除、启停、权重、筛选和导出。修订后当前课卷保持不变。

### 修改内置题库

question-banks.json 是题库数组，每库包含 id、name、description、enabled、weight、questions。
新增内置题库要追加一个库对象；导入按钮则接受“单库对象的 questions”或“题目数组”，不接受整个多库配置数组。

每题字段：

| 字段 | 格式及说明 |
| --- | --- |
| id | 本题稳定编号，不与同库其他题重复 |
| subject、category、chapter | 科目、分类、学习章节；chapter 不是游戏人生章节 |
| type | single_choice / multiple_choice / true_false |
| question | 题干，最多 6000 字 |
| options | 单选/多选为 A–F 键的对象，2–6 个非空选项 |
| answer | 单选用原始键字符串；多选用原始键数组；判断用布尔值 |
| explanation | 解析 |
| difficulty、frequency | 1–5 的整数 |
| tags | 标签字符串数组 |
| enabled | true 启用、false 停用 |
| aliases、keywords | 兼容字段，新三题型可写空数组，不参与判分 |

三题型示例：

~~~json
{
  "name": "我的入门卷",
  "questions": [
    {
      "id": "demo-single", "subject": "数学一", "chapter": "基础", "type": "single_choice",
      "question": "2 + 3 = ?", "options": {"A": "4", "B": "5", "C": "6"}, "answer": "B",
      "explanation": "2 + 3 = 5。", "difficulty": 1, "frequency": 3
    },
    {
      "id": "demo-multi", "subject": "数学一", "chapter": "基础", "type": "multiple_choice",
      "question": "以下哪些是偶数？", "options": {"A": "2", "B": "3", "C": "4"}, "answer": ["A", "C"],
      "explanation": "2 与 4 可被 2 整除。", "difficulty": 1, "frequency": 3
    },
    {
      "id": "demo-judge", "subject": "数学一", "chapter": "基础", "type": "true_false",
      "question": "1 等于 2。", "answer": false, "explanation": "两者不相等。", "difficulty": 1, "frequency": 3
    }
  ]
}
~~~

多选必须全部选对，不给部分分。判断题 JSON 推荐直接写 true / false，不加引号。
导入器会为判断题生成 true/false 键；手写内置 JSON 时填 options: {"true":"正确","false":"错误"}。
本节描述玩家本地藏书阁的旧兼容格式。全服管理中台导入会保留填空、简答和公式题的原始题型，并以解答自评方式展示；新全服题库请以 `docs/题库生成提示词.md` 为准。

### 选项乱序的重要约定

原始键保持不变，屏幕 A/B/C 按当次顺序重新显示。例如题库答案 B 对应“5”，随机后“5”出现在屏幕 A，玩家点 A 时内部提交的仍然是原始 B，因此判分不会错位。题库答案不要按截图上的随机字母修改。

每次重新抽到该题都会重新排列；刷新正在作答的题不会重新排列。不要写“以上 A 和 B”“以上全对”等依赖选项位置的文本，改成具体内容后再导入。

### CSV

最方便的做法：先在藏书阁导出一个 CSV，保留表头后填数据。
主要列：id,subject,category,chapter,type,difficulty,frequency,question,optionA,...,optionF,answer,explanation,tags,enabled。
多选答案写 A|C；tags 用分号或竖线分隔；判断答案写 true/false；不需要的选项留空。
单元格含逗号、换行时用双引号包围，内容中的双引号写成两个。推荐由表格软件保存 UTF-8 CSV。

## 十、为什么改了文件好像没变化

| 修改对象 | 生效位置 |
| --- | --- |
| 标题、地图、立绘、全局规则 | 刷新后读取配置 |
| 新对白与新题目 | 下一次发卷生成；当前题和历史记录保留快照 |
| 章首对白 | 新章节或未确认读完的章首页 |
| 人物初始关系、出身、默认科目比例 | 新建人生；已有存档保持自己的值 |
| 内置题库（从未在藏书阁编辑） | 跟随 JSON；刷新后下一次发卷使用 |
| 在藏书阁修改过的题库 | 浏览器本地版本优先，避免文件刷新覆盖玩家编辑 |
| 旧数据库首次升级 | 无法判断哪些题被手改，因此保守保留已有题库 |
| 生产部署的配置 | 重新构建并更新 dist 才生效 |

要使用文件修订后的题库而保留旧人生：从 question-banks.json 复制目标库的单个对象到新 JSON → 藏书阁导入 → 行囊切换为新库。导入会生成独立题库，不覆盖原库。原库题目与新库题目被视为不同学习对象，错题进度不会自动合并。

不要为了看到新配置直接清空浏览器数据。先导出存档，再试新人生或新题库。

## 十一、哪些修改仍需要代码

现有字段覆盖世界内容和主要数值，但不是通用无代码引擎。以下仍需 TypeScript：
新增题型、官职授予规则、任意分支条件、多人同步、全新 UI 布局、抽题算法公式。
这些入口在 docs/development.md 列明。新增字段不能只写 JSON，还需在读取它的引擎中实现含义。

正式县试已经由 `exams.json`、考试活动和地图标记共同配置，完整说明见 `docs/exam-guide.md`。调整报名资格、费用、题数、及格线、奖励、对白和取中后路线不需要修改 TypeScript。
