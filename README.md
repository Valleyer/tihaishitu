# 题海仕途

先过一段古风人生，再为想要的前程读书。

React + TypeScript 前端与 Java 17 / Spring Boot 后端已经完成首轮对接。题库正文按修订号缓存在浏览器，作答和存档通过 UUID 与本地服务交互。

## 现在怎么玩

默认进入可探索的世界，题面不会自动出现。

- **读书**：三页一卷，提升学识、悟性、辞采、筹算并赚银两。
- **访友**：与六位人物交谈、共读；按整轮成绩增加好感，解锁话题与关系信物。
- **游历**：青溪县、临川府两张大地图共十二个地点，统一显示“大地图名 · 小地点名”。
- **挑战**：普通活动按五个不同知识点考核，60 分基础过关、100 分完美过关；首题答错会进入同知识点训练。
- **主线**：县试、府试和核心剧情按十个知识点考核，必须全对；未全对无惩罚并可无限重试。
- **养成**：26 件装备、消耗品和信物可收入行囊，装备加成实际参与解锁条件。

判断、单选、多选继续支持，选项每次发卷打乱。题库导入编辑、错题复习、多存档与统计保留。

当前已完成青溪求学、县试、赴府、府城游历与府试主干，共 44 项可配置活动。[具体里程碑](docs/releases.md)。

## 启动

前端需要 Node.js 22.12+，推荐 Node.js 24。

~~~powershell
cd E:\题海仕途\frontend
npm install
npm run dev
~~~

打开终端显示的本地地址，一般为 http://127.0.0.1:5173。

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
| 可直接导入的三题型题库 | [示例 JSON](frontend/public/examples/题库示例.json) |

内容在 frontend/src/content，素材在 frontend/public/art，规则在 frontend/src/engine。
关键代码配中文注释，JSON 字段说明见手册。

## 存档与配置

新旧人生都默认进入世界。V1 升级时保留历史、钱、学识和人物关系，退出旧版未交卷面；新活动可以暂停、读档继续。

当前活动开始时冻结规则；改配置影响下一轮。服务端统一维护题库，浏览器按文集 revision 缓存 Markdown、公式、选项与解析；作答只提交课卷、题目 UUID 和答案。

主世界与题面采用视口布局；复杂编辑器、长正文与互动面板可以局部滚动。

## 最小验证

~~~powershell
cd E:\题海仕途\frontend
npm run build
npm test
~~~

build 包含配置引用、图片路径、题目知识点数量检查与类型编译，产物位于 frontend/dist。
只改配置时可单独 npm run validate:content，不重复运行全套检查。

接 Java 时在 frontend/.env.local 设置 VITE_API_MODE=http；默认 local 完全本地运行，详见接口契约。
