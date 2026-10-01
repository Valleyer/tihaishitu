# 题海仕途

先过一段古风人生，再为想要的前程读书。

React + TypeScript 本地游戏。Java 后端未实现，前端通过统一 GameApi 预留对接位置。

## 现在怎么玩

默认进入可探索的世界，题面不会自动出现。

- **读书**：三页一卷，提升学识、悟性、辞采、筹算并赚银两。
- **访友**：与四位人物交谈、共读；按整轮成绩增加好感，解锁话题与关系信物。
- **游历**：六个地图地点可点击前往，属性或道具帮助开启新区域。
- **挑战**：五个副本，根据成绩获取奖励与专属宝物。
- **故事**：旅行中遇到邀请，回应后进入答题；四段连环委托逐步展开。
- **养成**：13 件装备、消耗品和信物可收入行囊，装备加成实际参与解锁条件。

判断、单选、多选继续支持，选项每次发卷打乱。题库导入编辑、错题复习、多存档与统计保留。

本轮按 V2 → V3 → V4 → V5 连续实现以上主干，[具体里程碑](docs/releases.md)。
当前仍在青溪求学阶段，正式科举、官场、战争与称帝尚未实现。长期设计文档不等于已经完成的功能。

## 启动

需要 Node.js 22.12+，推荐 Node.js 24。

~~~powershell
cd E:\题海仕途
npm install
npm run dev
~~~

打开终端显示的本地地址，一般为 http://127.0.0.1:5173。

本机系统 Node 版本过旧时：

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
~~~

辅助脚本使用本机已有合适运行时，不改系统配置。

## 自己修改

| 想改什么 | 文档 |
| --- | --- |
| 读书、奖励、副本、地图门槛、人物互动、装备 | [探索玩法修改手册](docs/adventure-guide.md) |
| 题库、世界观、章首、素材等基础配置 | [基础配置手册](docs/configuration-guide.md) |
| 代码分工、状态流 | [开发说明](docs/development.md) |
| 后期 Java 接口 | [接口契约](docs/api.md) |
| 本地背景与立绘 | [素材说明](docs/art-assets.md) |
| 可直接导入的三题型题库 | [示例 JSON](frontend/public/examples/题库示例.json) |

内容在 frontend/src/content，素材在 frontend/public/art，规则在 frontend/src/engine。
关键代码配中文注释，JSON 字段说明见手册。

## 存档与配置

新旧人生都默认进入世界。V1 升级时保留历史、钱、学识和人物关系，退出旧版未交卷面；新活动可以暂停、读档继续。

当前活动开始时冻结规则；改配置影响下一轮。题库的浏览器编辑优先于内置文件；当前题和历史答卷仍保持快照。

数据按浏览器及地址隔离，localhost、127.0.0.1、不同端口不共用存档。固定同一地址，定期从“存牍”导出。当前只支持单标签页写入。

主世界与题面采用视口布局；复杂编辑器、长正文与互动面板可以局部滚动。

## 最小验证

~~~powershell
npm run build
npm test
~~~

build 包含配置检查与类型编译，产物位于 frontend/dist。
只改配置时可单独 npm run validate:content，不重复运行全套检查。

接 Java 时在 frontend/.env.local 设置 VITE_API_MODE=http；默认 local 完全本地运行，详见接口契约。
