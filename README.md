# 题海仕途

从寒门到朝堂，前程一题一题答。

React 前端初始化项目。Java 后端暂未实现，默认完全在浏览器本地运行。
已有三份设计文档保留在根目录，代表长期设计目标，不代表当前功能已经完成。

## 启动

推荐 Node.js 24 LTS（最低 22.12），npm 10+。

```powershell
cd E:\题海仕途
npm install
npm run dev
```

访问终端显示的本地地址，通常是 http://127.0.0.1:5173。
如果电脑默认仍为 Node 16，可以运行 `powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1`。
此辅助脚本优先使用符合要求的系统 Node，否则尝试本机 Codex 已有的 Node；不会安装或修改系统环境。

## 已完成

- React + TypeScript + Vite，npm workspaces，锁文件。
- 书院答题、示例题库列表、疑难旧案、学业统计四个页面。
- 四道单选示例题循环，判题、短讲解、轻量剧情反馈与学识积累。
- localStorage 自动保存当前题目、答题结果和记录。
- local / http 两套 API 适配器，共享类型接口。
- 答题和下一题的重复请求保护，基础测试，CI 构建检查。

## 当前边界

这是可运行的项目骨架，不是完整游戏。尚无自定义题库编辑/导入、多题型、
间隔复习、多存档、完整科举晋升、NPC 或后期权谋系统。
目前固定玩家折叶，四道题顺序循环，不自动升官。
本地数据按浏览器和源地址隔离，清理浏览器数据会删除存档。
本地模式仅供单标签页使用，不支持跨设备同步或多标签并发写入。

## 目录

```text
frontend/
  src/
    api/
      local/          # 示例题库与本地存档实现
      http.ts         # 将来 Java 后端的 HTTP 适配器
      index.ts        # 适配器选择
    domain/types.ts   # 数据模型和 GameApi 契约
    App.tsx           # 初始页面及交互
backend/              # 仅预留说明，没有 Java 代码
docs/api.md           # 接口和响应契约
scripts/start-local.ps1
```

## 接 Java 后端

复制 `frontend/.env.example` 为 `frontend/.env.local`：

```dotenv
VITE_API_MODE=http
VITE_API_BASE_URL=/api/v1
API_PROXY_TARGET=http://localhost:8080
```

重启开发服务器。开发时 Vite 将 /api 转发至本机 Java 服务。
生产构建需另配同源反向代理，或使用允许 CORS 的服务地址。
本地模式不会向 Java 服务发请求；HTTP 失败不会静默降级为本地数据。
现有本地存档不会自动迁移到后端。API 细节见 [接口说明](docs/api.md)。

## 检查

```sh
npm run lint
npm test
npm run build
npm run preview
```

构建产物位于 frontend/dist。脚手架采用 [Vite 官方 React + TypeScript 模板](https://vite.dev/guide/)。


