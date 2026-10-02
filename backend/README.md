# 题海仕途后端

后端采用 Java 17、Spring Boot 3.5、Maven 和 MySQL 5.7，使用经典的注解式 MVC 分层：`Controller -> Service -> Store`。当前接口统一挂在 `/api/v1` 下，服务端口为 `12345`。

## 本地环境

- JDK 17
- MySQL 5.7，端口 `3306`
- IDEA 可直接导入本目录的 `pom.xml`
- Maven Wrapper 已固定为 Maven 3.9.9，无需依赖系统 Maven 版本

首次运行前创建数据库：

```sql
CREATE DATABASE tihaishitu
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

数据库连接通过环境变量提供。仓库中的 `.env.example` 只列变量名，不保存真实密码：

```powershell
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_NAME = "tihaishitu"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "你的本地密码"
```

在 IDEA 中可把这些变量填入 Spring Boot 运行配置的 Environment variables。首次启动会执行 `src/main/resources/schema.sql`，并将前端内置题库同步为服务端初始题库。

## 启动和验证

PowerShell：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

健康检查地址：`http://localhost:12345/actuator/health`

测试使用 H2 的 MySQL 兼容模式，不会改动本机 MySQL：

```powershell
.\mvnw.cmd test
```

## 前端联调

在 `frontend/.env.local` 中配置：

```dotenv
VITE_API_MODE=http
VITE_API_BASE_URL=/api/v1
API_PROXY_TARGET=http://localhost:12345
```

然后启动前端开发服务器。Vite 会把 `/api/v1` 请求代理到本地后端。

## 当前接口

- `GET /api/v1/bootstrap`：返回存档摘要和服务端题库目录
- `GET /api/v1/question-banks`：返回文集、题目、选项和细分知识点
- `POST /api/v1/games`：创建新存档
- `GET /api/v1/games/{id}`：读取完整存档
- `DELETE /api/v1/games/{id}`：删除存档

后续游戏行为、答题提交、知识点训练、题库导入与管理接口会继续沿用同一分层和 `/api/v1` 版本前缀。MCP 更适合给外部 AI 工具调用，不替代网页游戏本身所需的 REST API；需要 AI 管理题库时可以在此服务之上增加 MCP 适配层。
