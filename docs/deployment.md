# 万境求知 · 生产部署说明

本文件描述把万境求知部署到 Linux 服务器的完整流程：Nginx 托管前端静态文件，
`/api` 反向代理到本机 Spring Boot，Spring Boot 连接 MySQL 5.7。

```text
浏览器
  → Nginx :80
      ├─ /              → /opt/wanjingqiuzhi/frontend（frontend/dist）
      └─ /api/          → http://127.0.0.1:12345/api/
  → Spring Boot（Java 17，只监听 127.0.0.1:12345）
      → MySQL 5.7（schema: tihaishitu）
```

品牌改名只影响展示层。仓库名 `tihaishitu`、Java package `cn.tihaishitu`、
Spring 技术名 `tihaishitu-backend`、数据库 schema `tihaishitu`、
World ID `ancient-official`、API 路由、数据库表名、Flyway migration 文件名、
UUID 与兼容 localStorage key 都不变。

> **不在仓库里保存任何真实密钥。** 数据库密码、`APP_ADMIN_KEY`、初始管理员密码、
> SSH 私钥只存在于服务器上的 `/etc/wanjingqiuzhi/backend.env`（`chmod 600`）。
> 仓库中只有 `.example` 占位模板。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| 操作系统 | Linux（systemd） | 示例路径按 `/opt/wanjingqiuzhi` 组织 |
| JDK | **Java 17** | 后端编译目标与运行版本都是 17 |
| MySQL | **5.7** | 禁止使用 MySQL 8 专属语法；本项目在 5.7.26 上验证 |
| Nginx | 任意稳定版 | 托管静态文件 + 反向代理 `/api` |
| Node.js | 22.12+（推荐 24） | **只在构建机需要**，服务器运行时不安装 |
| Maven | 3.9.9（Wrapper 自带） | 使用仓库内 `backend/mvnw`，不依赖系统 Maven |

服务器目录规划（示例）：

```text
/opt/wanjingqiuzhi/frontend     前端构建产物（Nginx root）
/opt/wanjingqiuzhi/backend     后端 jar
/opt/wanjingqiuzhi/releases    历史版本，用于回滚
/etc/wanjingqiuzhi/backend.env 生产环境变量（600，不属于仓库）
```

数据库迁移期间额外需要 `mysqldump` / `mysql` 客户端（随 MySQL 5.7 客户端包安装）。

---

## 2. 前端构建

在构建机（或本地）执行，不要在服务器上装 Node：

```powershell
cd E:\题海仕途
npm install
npm run build
```

`npm run build` 会先跑 `scripts/validate-content.mjs` 校验内容引用与图片路径，
再执行 `tsc -b` 类型编译与 `vite build`，产物在 `frontend/dist`。

生产构建必须使用同源 API 地址。在 `frontend/.env.production`（不提交真实环境差异时可临时用环境变量）中确认：

```dotenv
VITE_API_MODE=http
VITE_API_BASE_URL=/api/v1
```

**不要**把 `VITE_API_BASE_URL` 写成 `http://<SERVER_IP>:12345/api/v1` 这类绝对地址：
浏览器必须访问同源 `/api/v1` 并交给 Nginx 反向代理，否则页面 Origin 与
Learner Session Cookie 的站点会分离，登录态无法生效。

发布产物：

```bash
# 在服务器上
sudo mkdir -p /opt/wanjingqiuzhi/frontend
sudo rsync -a --delete frontend/dist/ /opt/wanjingqiuzhi/frontend/
sudo chown -R <APP_USER>:<APP_GROUP> /opt/wanjingqiuzhi/frontend
```

---

## 3. 后端 package / jar

```powershell
cd E:\题海仕途\backend
.\mvnw.cmd -DskipTests clean package
```

产物为 `backend/target/tihaishitu-backend-0.1.0-SNAPSHOT.jar`
（可执行 Spring Boot fat jar）。`pom.xml` 会把 `../frontend/src/content/*.json`
一并打进 `content/` 资源目录，因此构建前请确认前端内容文件是期望版本。

上传并放置：

```bash
sudo mkdir -p /opt/wanjingqiuzhi/releases
sudo cp tihaishitu-backend-0.1.0-SNAPSHOT.jar \
  /opt/wanjingqiuzhi/releases/tihaishitu-backend-0.1.0-SNAPSHOT.jar
sudo ln -sfn /opt/wanjingqiuzhi/releases/tihaishitu-backend-0.1.0-SNAPSHOT.jar \
  /opt/wanjingqiuzhi/backend/tihaishitu-backend.jar
sudo chown -R <APP_USER>:<APP_GROUP> /opt/wanjingqiuzhi
```

> 生产环境**不要**用 `mvnw spring-boot:run` 启动，也不要在服务器上跑 Maven。

---

## 4. MySQL 准备

创建 schema（名称保持 `tihaishitu`，不因产品改名而变更）：

```sql
CREATE DATABASE tihaishitu
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

创建独立应用账号，不长期使用 `root`，只授予本 schema 的必要权限：

```sql
CREATE USER 'wanjing_app'@'127.0.0.1' IDENTIFIED BY '<CHANGE_ME>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON tihaishitu.* TO 'wanjing_app'@'127.0.0.1';
FLUSH PRIVILEGES;
```

说明：

- 应用包含 Flyway，首次启动需要 `CREATE / ALTER / INDEX / REFERENCES` 才能建表；
  如果采用第 5 节的整库 dump 导入方案，schema 已由 dump 建好，可以把账号收紧为
  `SELECT, INSERT, UPDATE, DELETE`，需要新 migration 时再临时放开 DDL 权限。
- `REFERENCES` 用于建外键；MySQL 5.7 的 `GRANT` 语法与 8.0 不同，以上按 5.7 书写。
- 生产库必须继续兼容 MySQL 5.7，不写 MySQL 8 专属语法（窗口函数、CTE、
  `JSON_TABLE`、`CHECK` 约束语义等）。

---

## 5. 数据库迁移

### 5.1 推荐方案：整库 dump + 导入

本地与远程都是 MySQL 5.7，最稳妥的方式是完整 dump 本地库的 schema + data，
导入服务器空库，再启动生产后端。**本轮不清理 legacy schema。**

必须一起迁移 `flyway_schema_history`。否则远程库虽然已有 V1–V20 建出的表，
但 Flyway 认为一个 migration 都没执行过，启动时会出现 migration 冲突或重复执行。

### 5.2 迁移顺序（严格遵守）

```text
1. 停止 / 避免本地继续写入（关闭本地后端，确认没有正在答题的会话）
2. 运行只读审计  scripts/predeploy-db-audit.sql  并保存输出
3. mysqldump 完整库
4. 服务器创建空 tihaishitu DB
5. 导入 dump
6. 运行只读验证  scripts/verify-prod-db.sql  并与第 2 步输出逐项比对
7. 确认通过后，再启动 Spring Boot（Flyway validate / migrate）
8. 做第 9 节健康检查
```

**不要**反过来：先启动生产后端让 Flyway / seed 初始化空库，再覆盖导入 dump。

### 5.3 审计脚本（导入前）

```bash
mysql -h 127.0.0.1 -P 3306 -u <LOCAL_USER> -p \
  --default-character-set=utf8mb4 tihaishitu \
  < scripts/predeploy-db-audit.sql > predeploy-audit-$(date +%F).txt
```

脚本只包含 `SELECT` / `SHOW`，不会修改任何表、任何行、任何账号。输出包含：
MySQL 版本与 charset/collation、全部表、`flyway_schema_history`、失败 migration 数量、
核心表精确行数、legacy 表行数、smoke 测试账号、外键与索引统计、审计结论。

### 5.4 mysqldump 模板

```bash
mysqldump \
  -h 127.0.0.1 \
  -P 3306 \
  -u <LOCAL_USER> -p \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  --default-character-set=utf8mb4 \
  --set-gtid-purged=OFF \
  tihaishitu > tihaishitu-predeploy.sql
```

按本地 `mysqldump` 实际版本调整兼容参数：

- MySQL 5.7 客户端支持 `--set-gtid-purged=OFF`；
- 若本地客户端是 8.0 而服务器是 5.7，导出时加 `--column-statistics=0`
  并避免 `--set-gtid-purged` 之外的新参数；
- 若报 `Unknown table 'COLUMN_STATISTICS'`，同样加 `--column-statistics=0`。

### 5.5 导入与验证

```bash
# 服务器：导入
mysql -h 127.0.0.1 -P 3306 -u <PROD_USER> -p \
  --default-character-set=utf8mb4 tihaishitu < tihaishitu-predeploy.sql

# 服务器：只读验证
mysql -h 127.0.0.1 -P 3306 -u <PROD_USER> -p \
  --default-character-set=utf8mb4 tihaishitu \
  < scripts/verify-prod-db.sql > verify-prod-$(date +%F).txt
```

`scripts/verify-prod-db.sql` 同样只有 `SELECT` / `SHOW`，检查项：

- `flyway_schema_history` 全部 `success = 1`；
- 最新 migration 为 V18（含 Java/JDBC 的 V13）；
- 核心表与 legacy 表都存在；
- 关键表行数可与本地审计输出人工对比；
- `question_resource` / `global_knowledge_point` 数量非零、非异常；
- `learner_account` 等 learner 数据已迁入；
- 不是意外空库或 seed-only 状态。

脚本不硬编码业务数量，只做结构与数量级判断；业务数量由人工对照两份输出。

### 5.6 legacy 表：本轮不删除

以下表虽然“看起来旧”，但**当前仍被运行时代码或启动迁移器直接使用**，
直接 `DROP` 会导致应用启动或读取 SQL 报错：

| 表 | 仍被谁使用 |
| --- | --- |
| `knowledge_point`、`question_item`、`question_option`、`question_knowledge_point` | `LegacyCatalogMigrator` 在 `ApplicationReadyEvent` 启动阶段读取；`CatalogStore` 读取旧题库正文 |
| `legacy_knowledge_map` | `KnowledgeQuestionPoolStore` 的兼容 scope；**`LearnerPracticeStore` 的练习 scope 判定也读取它** |
| `legacy_question_map` | `LegacyCatalogMigrator` 的旧题目映射 |
| `question_bank_item` | `CatalogStore`、`QuestionManagementStore`、`KnowledgeManagementStore`、`LegacyCatalogMigrator`、`GlobalQuestionBankImportService` |
| `game_save` | `GameStore` 与 Legacy `/games/**` 兼容接口 |
| `app_user` | `AuditLogStore`、`QuestionManagementStore` 的 `LEFT JOIN app_user` 历史 creator / actor fallback；`ManageUserDeletionService` 仍按 id 查找与清理 |
| `app_user_role` | `ManageUserDeletionService` 仍会清理该表角色行；本轮同样不手工删除 |

原则：**没有正式代码清理 + V19+ migration，就人工删表制造 schema drift。**

未来的 Legacy Schema Cleanup 必须单独成 PR，顺序为：

```text
1. 去掉 legacy runtime 依赖
2. 去掉 startup migrator 依赖
3. 处理相关 FK
4. 完整备份
5. 新增 V19+ migration
6. 正式 DROP
```

### 5.7 smoke 测试账号

审计脚本会列出 `username LIKE 'smoke%'` 的账号。本轮**不自动删除**。
如果上线前决定清理：先备份 → 用户明确批准 → 再单独事务清理。

---

## 6. Spring 环境变量

完整模板见 [`backend/.env.production.example`](../backend/.env.production.example)。
关键项：

```dotenv
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=12345

DB_URL=jdbc:mysql://127.0.0.1:3306/tihaishitu?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false
DB_USERNAME=wanjing_app
DB_PASSWORD=<CHANGE_ME>
DB_POOL_SIZE=10

CATALOG_SEED_ENABLED=false
MATH1_KNOWLEDGE_SEED_ENABLED=false

APP_ADMIN_KEY=<CHANGE_ME>
CORS_ALLOWED_ORIGINS=<PRODUCTION_ORIGIN>

SESSION_COOKIE_SECURE=false
LEARNER_COOKIE_SECURE=false
```

要点：

- **`SERVER_ADDRESS=127.0.0.1`**：只允许 Nginx 本机回源，避免 Spring Boot 的 12345
  端口直接暴露公网。`application.yml` 中该项默认留空，Spring Boot 按默认行为
  监听所有网卡，本地开发不受影响。
- **生产 seed 必须关闭**：`CATALOG_SEED_ENABLED=false` 与
  `MATH1_KNOWLEDGE_SEED_ENABLED=false`，避免向已有生产数据补写演示内容。
  注意后者即使为 `true` 也是幂等导入，但生产仍建议显式关闭。
- **`APP_ADMIN_KEY`**：至少 24 位随机字符串，只用于脚本 / 导入接口的
  `X-Admin-Key`；留空时题库写接口整体返回 404。
- **`CORS_ALLOWED_ORIGINS`**：同源部署（Nginx 同时托管前端与 `/api`）时浏览器
  不会发跨域请求，仍建议填正式访问来源，避免误配。
- **Cookie `secure`**：目前 HTTP 明文部署保持 `false`；启用 HTTPS 后改为 `true`。
- 管理后台首个管理员只在数据库尚无有效 ADMIN 时创建，需要
  `APP_INITIAL_ADMIN_USERNAME` + `APP_INITIAL_ADMIN_PASSWORD`；创建完成后
  建议把这两个变量从 env 文件中移除。
- 时区统一使用 `Asia/Shanghai`（业务日口径），JDBC 连接串已带
  `serverTimezone=Asia/Shanghai`。

---

## 7. systemd

模板：[`deploy/wanjingqiuzhi.service.example`](../deploy/wanjingqiuzhi.service.example)。

```bash
sudo cp deploy/wanjingqiuzhi.service.example /etc/systemd/system/wanjingqiuzhi.service
sudo nano /etc/systemd/system/wanjingqiuzhi.service   # 填 <APP_USER> / <APP_GROUP> / jar 名
sudo install -d -m 750 -o <APP_USER> -g <APP_GROUP> /etc/wanjingqiuzhi
sudo cp backend/.env.production.example /etc/wanjingqiuzhi/backend.env
sudo nano /etc/wanjingqiuzhi/backend.env              # 填真实值
sudo chmod 600 /etc/wanjingqiuzhi/backend.env
sudo systemctl daemon-reload
sudo systemctl enable --now wanjingqiuzhi
sudo systemctl status wanjingqiuzhi
sudo journalctl -u wanjingqiuzhi -f
```

模板中只放占位用户与 `EnvironmentFile` 路径，不写任何密码。
建议使用专用非 root 用户运行；`application.yml` 已开启 `server.shutdown=graceful`，
systemd 单元用 `SIGTERM` + `TimeoutStopSec=30` 配合优雅停机。

---

## 8. Nginx

模板：[`deploy/nginx.conf.example`](../deploy/nginx.conf.example)。

```bash
sudo cp deploy/nginx.conf.example /etc/nginx/conf.d/wanjingqiuzhi.conf
sudo nano /etc/nginx/conf.d/wanjingqiuzhi.conf   # 按需替换 server_name
sudo nginx -t
sudo systemctl reload nginx
```

核心配置要点：

- `root /opt/wanjingqiuzhi/frontend` + `try_files $uri $uri/ /index.html`
  支撑 `/study`、`/progress`、`/manage` 等前端路由的直接访问与刷新；
- `location /api/ { proxy_pass http://127.0.0.1:12345/api/; }`
  保持 `/api/v1` 同源契约；
- 传递 `X-Real-IP` / `X-Forwarded-For` / `X-Forwarded-Proto`，便于后端日志与
  未来 HTTPS 切换；
- 带哈希的构建产物可长期缓存，`index.html` 不缓存，保证发布后立即生效；
- `/actuator/health` 默认只允许 `127.0.0.1` 访问。

模板使用 `server_name _;` 默认站点写法，不写死服务器 IP。

---

## 9. health check

后端起来后按顺序检查：

```bash
# 1. 后端进程与端口（应只监听 127.0.0.1）
sudo systemctl status wanjingqiuzhi
sudo ss -lntp | grep 12345

# 2. Actuator 健康检查（在服务器本机执行）
curl -fsS http://127.0.0.1:12345/actuator/health

# 3. 经 Nginx 的对外检查
curl -fsS http://<SERVER_IP>/                 # 200，返回前端 index.html
curl -fsS http://<SERVER_IP>/actuator/health  # 若已放开该 location
```

功能验收清单（用户人工执行）：

```text
GET /actuator/health
首页 /
登录
万境中枢加载（品牌文案显示“万境求知 / 万境中枢”）
Study 章节练习
KnowledgePoint 专项
错题快练
寒门仕途开始一次活动
管理后台
数据库写入后刷新仍存在
```

其中“数据库写入后刷新仍存在”用于确认反向代理、Learner Session Cookie
与数据库连接都工作正常：完成一次练习 → 刷新页面 → 进度与作答记录仍在。

---

## 10. 发布 / 回滚

### 发布

```bash
# 1. 备份当前生产库（每次发布前必做）
mysqldump -h 127.0.0.1 -P 3306 -u <PROD_USER> -p \
  --single-transaction --routines --triggers --events \
  --default-character-set=utf8mb4 --set-gtid-purged=OFF \
  tihaishitu > /opt/wanjingqiuzhi/backup/tihaishitu-$(date +%F-%H%M).sql

# 2. 上传新 jar 到 releases，切换软链接
sudo cp tihaishitu-backend-<NEW>.jar /opt/wanjingqiuzhi/releases/
sudo ln -sfn /opt/wanjingqiuzhi/releases/tihaishitu-backend-<NEW>.jar \
  /opt/wanjingqiuzhi/backend/tihaishitu-backend.jar

# 3. 发布前端
sudo rsync -a --delete frontend/dist/ /opt/wanjingqiuzhi/frontend/

# 4. 重启后端
sudo systemctl restart wanjingqiuzhi
sudo journalctl -u wanjingqiuzhi -n 100 --no-pager

# 5. 健康检查
curl -fsS http://127.0.0.1:12345/actuator/health
```

发布注意：

- 新版本如果带 **V19+** migration，Flyway 会在启动时自动执行；执行前务必已有
  第 1 步的库备份，且确认 migration 兼容 MySQL 5.7；
- Flyway 配置为 `validate-on-migrate: true` + `clean-disabled: true`，
  不会自动清库；hash 不匹配时启动失败，这是预期保护；
- 前端 `rsync --delete` 会清理旧构建产物，请确认 `frontend/dist` 是本机刚构建的版本。

### 回滚

```bash
# 1. 切回上一个 jar
sudo ln -sfn /opt/wanjingqiuzhi/releases/tihaishitu-backend-<OLD>.jar \
  /opt/wanjingqiuzhi/backend/tihaishitu-backend.jar
sudo systemctl restart wanjingqiuzhi

# 2. 前端回滚：把上一版 dist 重新 rsync 到 /opt/wanjingqiuzhi/frontend
```

数据库回滚原则：

- 若本次发布**没有**新 migration：直接回滚 jar + 前端即可，不动数据库；
- 若本次发布**执行了** V19+ migration：Flyway 没有自动 down migration。
  回滚需要人工评估，必要时用发布前的 dump 恢复到临时库后比对，**不要**在生产库上
  手写 `DROP` / `ALTER` 反向脚本；
- 任何数据库层面的回滚都属于高危操作，必须先在备份副本上演练并取得用户明确批准。

### 备份保留

```text
/opt/wanjingqiuzhi/backup/
  tihaishitu-<date>-<time>.sql
```

建议至少保留最近 7 次发布备份，并定期验证备份可导入到临时库。

---

## 11. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 页面能开，接口 502 | 后端未启动或端口不对 | `systemctl status wanjingqiuzhi`，`ss -lntp \| grep 12345` |
| 登录后刷新掉登录态 | 前端 API 用了绝对地址导致跨站 | 确认 `VITE_API_BASE_URL=/api/v1` 并重新构建 |
| `Unknown table 'COLUMN_STATISTICS'` | 8.0 客户端导出到 5.7 | mysqldump 加 `--column-statistics=0` |
| 启动报 Flyway migration 冲突 | 导入时漏了 `flyway_schema_history` | 重新整库导入，不要只导业务表 |
| 启动报 legacy 表不存在 | 手工删过 legacy 表 | 从 dump 恢复这些表；本轮不要删表 |
| `APP_ADMIN_KEY` 未生效 | 未配置或为空格 | 配置至少 24 位随机串并重启 |
