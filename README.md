# 题海仕途

从寒门书生开始，用一道道课业推进人生。React + TypeScript 前端，默认完全在浏览器本地运行；Java 后端仅保留接口位置。

## 启动

需要 Node.js 22.12+，推荐本机已有的 Node.js 24。

~~~powershell
cd E:\题海仕途
npm install
npm run dev
~~~

打开终端显示的地址，一般是 http://127.0.0.1:5173。系统 Node 版本较旧时，本机可以直接运行：

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
~~~

辅助脚本优先使用合适的系统 Node，否则使用本机已有的 Codex Node，不改系统配置。

## V1 已有内容

- 游戏首页、男女立绘、章节序幕、人物对话、中央大题面、答题反馈、人生札记。
- 6 个章节、30 个初次剧情场景、5 段选择际遇、3 位有持续关系的 NPC、6 个地图地点。
- 40 道示例题，涵盖数学一、408、政治、英语一。仅支持判断、单选、多选。
- 选项每次抽到时打乱，答题、存档、解析始终按原始选项键关联。
- 学识与信任推动章节；错题重审、间隔复习、科目权重、学习统计与批注。
- 多个人生存档、自动保存、存档导入导出；JSON/CSV 题库导入、编辑、启停、导出。
- 右上角设置与全屏；主游戏按视口布局，小屏收起侧栏，长题干与选项分页。
- 世界观、人物、地图、剧情、题库、成长与复习参数集中配置，关键代码配中文注释。

目前实现第一卷“寒门求学”至县试备考，最后一章可继续修习。正式科举、为官治理、朝堂与称帝尚未实现。根目录三份设计文档描述长期目标，不能把其中全部功能视为已完成。示例题也不是完整考研题库。

## 自己修改，从这里开始

| 目的 | 入口 |
| --- | --- |
| 改人物、章节、对白、题库、数值、图片 | [配置修改手册](docs/configuration-guide.md) |
| 理解代码分工与核心流程 | [开发说明](docs/development.md) |
| 后期接入 Java API | [接口契约](docs/api.md) |
| 查看立绘与背景素材来源、生成提示词 | [素材说明](docs/art-assets.md) |
| 直接导入三题型示例 | [示例题库](frontend/public/examples/题库示例.json) |

~~~text
frontend/src/content/    世界与玩法 JSON 配置
frontend/public/art/    游戏本地背景、人物立绘
frontend/src/engine/    判题、选题、复习、剧情、成长、存档规则
frontend/src/components/ 页面部件
frontend/src/api/local/ 本地存档与 API 实现
frontend/src/api/http.ts Java HTTP 适配器
frontend/src/domain/types.ts 统一数据契约
docs/                   中文修改与开发文档
backend/                仅预留，未编写 Java 代码
~~~

## 配置生效与存档

编辑 JSON 后，开发模式会重载模块；手动刷新页面可确保应用更新，发布版需要重新构建。

未在藏书阁改过的内置题库跟随文件配置；在浏览器编辑过的题库优先保留本地版本。当前卷面及历史答卷保持快照，下一次发卷才使用新配置。旧数据库首次升级保守保留原有题库，具体操作见修改手册。

数据按浏览器和地址隔离。localhost 与 127.0.0.1、不同端口不是同一份存档；请固定一个地址，并使用“存牍 → 导出”备份。只支持单标签页写入，暂不支持云同步。旧原型存储键不会被覆盖。

主答题页面不依赖整页滚动；题库编辑、完整正文等辅助弹窗在内容较多时保留内部滚动。

## 最小验证与构建

~~~powershell
npm run build
npm test
~~~

build 已包含配置引用检查和 TypeScript 编译，产物在 frontend/dist。
只改文案时可单独运行 npm run validate:content；无需反复跑全套检查。

接后端时配置 frontend/.env.local：

~~~dotenv
VITE_API_MODE=http
VITE_API_BASE_URL=/api/v1
API_PROXY_TARGET=http://localhost:8080
~~~

修改后重启开发服务器。默认 local 模式不请求 Java 服务。详见接口契约。
