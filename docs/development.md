# 开发说明

## 当前 V2—V5 主干

默认世界由 components/Exploration.tsx 渲染，题面只有主动开始或恢复活动时展示。WorldMap 负责选择目的地，真正的门槛校验在引擎与 API。

engine/AdventureEngine.ts 统一负责活动门槛、发起、整轮评分、基础奖励、首档奖励、地图移动、人物交流、关系礼物和道具。domain/adventure.ts 是新契约；详细配置见 [探索手册](adventure-guide.md)。

一次操作仍由 api/local/store.ts 读取、结算并保存。每题保留学习与学识结算；整轮最后一题才调用 settleRunAnswer 发好感、银两与道具。run.status=settled 后重复请求直接返回，finishActivity 只清理界面行程，不再发奖。

新建人生不发卷；beginActivity 冻结活动配置并发第一题。next 只能展开该轮未完成的下一页。暂停只隐藏 UI，放下才清除 run 与当前题；已答历史保留。

hydrateAdventure 升级旧存档并补入新增人物。parseBackup 验证新探索结构；run 中没有题库键，原有题库重映射继续作用于当前卷面、历史、批注和学习记录。

以下为基础模块说明，V1 的自动发卷和题数际遇流程由上述新入口取代。

# 基础模块

## 主干工作流

理解目标 → 修改对应配置或最小模块 → 一次相关验证 → 交付。
不为改文案增加新测试，不运行长时间随机抽题统计。当前自动测试仅保护关键状态和判题边界。

## 代码阅读顺序

1. content/*.json：先看游戏设定与内容。
2. domain/types.ts：理解 Game、Question、Attempt、GameApi。
3. App.tsx：了解首页、章首页、游戏主界面和弹窗如何切换。
4. api/local/store.ts：了解一次答题如何保存。
5. engine：需要调整具体规则时再进入对应文件。

主要文件已配中文注释，说明状态、边界和为什么这样处理；JSON 的字段注释集中在配置手册。

| 模块 | 职责 |
| --- | --- |
| QuestionBankManager | JSON/CSV 导入、标准化、导出 |
| QuestionEngine | 权重抽题、错题优先、间隔过滤 |
| OptionShuffler | 保留键的洗牌、按本次顺序显示答案 |
| AnswerValidator | 判断、单选、多选判分 |
| StoryEngine | 游戏日历、当前场景、对白与记忆 |
| ProgressionSystem | 学识、信任、声望与晋章 |
| EventEngine | 际遇触发与选项后果 |
| SpacedRepetitionEngine | 每道题的学习记录及下次优先复习时间 |
| StatisticsSystem | 从历史记录汇总现实日期统计 |
| SaveSystem | 存档与题库的结构检查 |
| api/local/store | 串联上述规则并持久化 |

## 从一次点击看数据流

~~~text
新建人生
  → 校验题库与配置
  → 按 JSON 创建角色初值
  → 抽题 + 洗牌 + 创建剧情
  → 保存完整题目快照
  → 返回不含答案的卷面

落笔呈卷
  → 校验 attemptId
  → 已判过则直接返回原结果
  → 按快照判题
  → 写学习记录与复习间隔
  → 结算成长与章节条件
  → 填充人物反馈、检查际遇
  → 一次写回 localStorage
  → UI 展示批卷结果

继续此生
  → 先处理未完成际遇
  → 发下一份课卷
  → 若新章未读，先展示章首对话
~~~

当前题目与历史答案是快照，不会因后续编辑题库而变化。出题后刷新不应洗牌，否则玩家已经选中的内容会变。

## 本地数据

存储键 tihaishitu:world:v2，包含 saves、banks、snapshots、activeId、editedBankIds 等。

- saves：按人生 id 保存 Game。
- banks：本地编辑过或导入的题库及最近保存的默认题库数据。
- snapshots：每份人生当前课卷的完整标准答案。
- editedBankIds：浏览器覆盖的题库 id，删除也留标记，防止默认配置自动恢复已删库。
- choiceVersion：旧题型迁移标记。
- Game.flags：章节介绍已读、事件已完成、共同经历等。
- Game.learning：按 题库id::题目id 保存每题累计作答、答对、答错、错误率及复习记录；旧存档读取时自动补算错误率。
- Game.revision：每次保存递增；当前没有多标签并发冲突处理。

旧原型键 tihaishitu:save:v1 保留；三题型升级前可备份到 tihaishitu:before-choice-update。
旧简答历史可能仍存在原浏览器数据库；当前备份导入要求题目符合现行三题型，旧格式不保证可导入。不会自动删除旧浏览器数据。

导入当前格式的备份会创建新人生，并给题库重新分配 id，同时重映射学习记录、批注和当前题，避免覆盖原人生。

## 页面布局与样式

App.css 提供古风基础视觉，screen-fit.css 在后加载，覆盖主界面的视口布局。index.css 设置全局基础样式。

主游戏使用顶栏 / 中间内容 / 底部操作栏。中间题目占据剩余空间；屏宽缩小时先隐藏右栏、再隐藏左栏。中央长题干及选项使用分页，长正文可展开。不要仅用 overflow:hidden 掩盖新增内容溢出，应给玩家可操作的翻页或展开入口。

题库编辑、札记和全文弹窗仍允许局部滚动。极小窗口、系统大字号与自定义超长文本可能还需后期微调。

### 文字与背景对比度

弹窗正文使用浅色纸面，主世界使用深色背景，两套文字颜色不能直接混用。`adventure.css` 末尾的“弹窗统一对比度契约”定义了 `--paper-text`、`--paper-muted`、`--paper-soft`、`--paper-accent`、`--ink-panel`、`--ink-text` 和 `--ink-muted`。新增弹窗卡片时应优先使用这些变量：浅色纸面放深色文字，深色卡片放米白文字。

禁用态仍须能看清按钮名称和解锁条件，不能只靠降低整块 `opacity` 表示不可用。通用弹窗按钮可能覆盖组件背景；地图标记、标签页等专用按钮应使用足够明确的选择器，并在浅色纸面与深色图片区分别检查。任何新界面至少人工确认标题、正文、辅助说明、禁用状态四类文字都能一眼辨认。

## 后续扩展位置

- 新科目与题目：通常只改题库及默认权重。
- 新角色、剧情、地点：改对应 JSON 和关联 id。
- 正式科举：新增考试状态模型与结算逻辑，不能只把 playerTitle 改成官职。
- 更复杂剧情树：扩展场景条件和游玩节点状态，替代当前按累计题数取场景的方式。
- Java 对接：实现 docs/api.md 中契约，页面继续调用 api，不在组件内散写 fetch。
- 服务端判题：snapshot、answer、explanation 的作答前隔离由后端真正执行。本地版不能防玩家查看静态题库答案。

## 最小验证

修改配置：npm run validate:content。
改 TypeScript 或发布：npm run build（已包含配置检查）。
修改答题、存档、洗牌或导入逻辑：再运行一次 npm test。

测试只覆盖核心回归，不覆盖完整浏览器适配、每段剧情和未来系统。
