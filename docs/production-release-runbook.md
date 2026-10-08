# 万境书院｜京东云生产发布与数据库迁移操作手册

> **长期运维入口**：未来在任意新会话咨询“万境书院上线 / 更新 / 迁移 / 回滚”时，先读取本文件、[`AGENTS.md`](../AGENTS.md)、[`PROJECT_RULES.md`](./PROJECT_RULES.md)、[`deployment.md`](./deployment.md) 与**仓库最新代码 / migration**，并以服务器当时实际状态为准。  
> 本文件包含 **2026-10-08 PR5（`399295a`）从 V18 → V22 的成功上线记录**，以及今后可重复执行的标准流程。**历史路径与数字是已核对快照，不代表未来始终不变。**

## 1. 现役环境（2026-10-08 现场确认）

| 项目 | 当时实际值 | 注意 |
| --- | --- | --- |
| 产品 / 首页 | 万境书院 / 万境中枢 | 技术仓库、JAR、数据库仍叫 `tihaishitu` |
| 服务器 | 京东云 Linux，systemd + Docker | 实际目录不是旧手册示例的 `/opt` |
| Java | 17.0.11 | Windows 构建机 Java 17 |
| systemd 服务 | `wanjingqiuzhi.service` | 服务用户 `wanjingqiuzhi` |
| 正式 JAR | `/usr/local/wanjingqiuzhi/backend/tihaishitu-backend.jar` | `WorkingDirectory=/usr/local/wanjingqiuzhi/backend` |
| Java HTTP | `172.17.0.1:12345` | **不要盲目改为 `127.0.0.1`**：Docker Nginx 通过宿主网桥访问 |
| 前端 Nginx 宿主目录 | `/usr/local/docker/nginx/html` | Docker 内为 `/usr/share/nginx/html` |
| Nginx Docker | 容器 `nginx`，宿主 `:80` | 配置宿主 `/usr/local/docker/nginx/conf` |
| Nginx 反代 | `proxy_pass http://172.17.0.1:12345/api/;` | 现役 HTTP；部署不擅自改网络拓扑 |
| MySQL Docker | `mysql57`，MySQL 5.7 | 宿主 `127.0.0.1:3306` |
| 数据库 | `tihaishitu` | **线上 DB 是事实源，不用本地库覆盖** |
| 应用账户 | `'wanjing_app'@'172.17.0.1'` | Host 必须核实，不能直接猜 `%` |
| 生产 env | `/etc/wanjingqiuzhi/backend.env` | **不得上传 / 贴出实际密钥** |
| 发布存放目录 | `/usr/local/wanjingqiuzhi/releases/` | 独立暂存后再切换 |
| 备份存放目录 | `/usr/local/wanjingqiuzhi/backup/` | 不要放进对外 Nginx root |
| PR5 上线代码 | `main 399295a4792173a4a1b0fc8219401765ebb654b9` | 后端 JAR 与前端从同一 `main` 构建 |
| PR5 上线 Flyway | V22，V19–V22 `success=1` | **下一次新迁移应使用 V23+** |

现场确认命令（只读）：

```bash
java -version
systemctl status wanjingqiuzhi --no-pager -l
systemctl show wanjingqiuzhi -p WorkingDirectory -p ExecStart -p User --no-pager
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'
docker inspect nginx --format '{{range .Mounts}}{{println .Source " -> " .Destination}}{{end}}'
docker exec nginx sh -c 'nginx -T 2>/dev/null | grep -nE "root |alias |proxy_pass " | head -40'
ss -lntp | grep -E ':(80|12345|3306)\b' || true
df -h
```

> **不要照抄“现役快照”覆盖配置。** 每次先查真实 `systemd`、Nginx 与 MySQL，再确认路径和端口。旧 `docs/deployment.md` 中 `/opt/wanjingqiuzhi`、`127.0.0.1:12345` 是另一种首次部署示例，不是本机实际配置。

## 2. 先判断发布类型

| 发布类型 | 做什么 | 不能做什么 |
| --- | --- | --- |
| A. 无新 Flyway migration | 备份 → 构建 → 上传 → 更新 JAR → 启动 / 健康检查 → 更新前端 → 验收 | 不导入数据库、不修改 Flyway 历史 |
| B. 有 V23+ 新 migration | 除 A 外，先读每个迁移 SQL/Java → 确认 DDL 权限 → **一致性备份** → 维护窗口 → Flyway 按序执行 → 结构/数据核查 | 不修改冻结的 V1–V22，不在旧库上盲跑旧脚本 |
| C. 首次全新安装 / 全库搬迁 | 另按 `docs/deployment.md` 的首次空库导入流程执行 | 不可将“首次导入”流程用于已运行的生产库 |

**高优先级事实**：一个数据库的 Flyway 版本若已经领先于准备安装的旧 JAR，不保证旧 JAR 兼容。**DB 迁移后不能只靠换回旧 JAR 完整回滚。**

## 3. 发布前检查和安全备份

1. 确认仓库最新 main、GitHub CI、变更范围，以及新增 migration 列表；确认没有未解决的失败 migration。
2. 记录当前 DB 版本、关键表数量、账号数和作答数。**本次 PR5 迁移前的历史基线**是 V18、1453 题、2 个 learner、84 条 attempt；迁移后数量保持不变。未来的实际数据会增长，不应要求等于这些数字。
3. 每次发布保存**结构 + 数据**的完整数据库备份，含 `flyway_schema_history`，并尽可能测试恢复到隔离库。已有线上写入时，应安排停写窗口生成最终一致性备份，避免半途数据变化；不要仅以“备份文件不为 0”判定可恢复。
4. 备份旧 JAR、整套 Nginx 静态目录、Nginx 配置和 systemd 服务配置；切勿将 `backend.env`、口令、Token 放进仓库。
5. 预留维护窗口：若有 DB schema/答案字段变更（例如历史 V20），必须按“可能无法零停机”规划。

Navicat（推荐已熟悉的操作）：右键生产库 `tihaishitu` → **转储 SQL 文件 → 结构和数据**，保存到安全的本地目录。检查包含 `flyway_schema_history` 的建表及数据；最好在测试库演练导入。不要用“数据传输”覆盖生产库。

Navicat 只读快照 SQL：

```sql
SELECT installed_rank, version, description, success
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
SELECT COUNT(*) AS failed_migrations FROM flyway_schema_history WHERE success <> 1;
SELECT
  (SELECT COUNT(*) FROM question_resource) AS questions,
  (SELECT COUNT(*) FROM learner_account) AS learners,
  (SELECT COUNT(*) FROM study_attempt) AS attempts;
```

服务器备份（在 `bash` 执行，不要在 Navicat SQL 窗口执行）：

```bash
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP="/usr/local/wanjingqiuzhi/backup/pre-release-$STAMP"
mkdir -p "$BACKUP"
\cp -L /usr/local/wanjingqiuzhi/backend/tihaishitu-backend.jar "$BACKUP/tihaishitu-backend.jar"
tar -czf "$BACKUP/frontend-old.tar.gz" -C /usr/local/docker/nginx/html .
tar -czf "$BACKUP/nginx-conf-old.tar.gz" -C /usr/local/docker/nginx conf
\cp -a /etc/systemd/system/wanjingqiuzhi.service "$BACKUP/"
ls -lh "$BACKUP"
tar -tzf "$BACKUP/frontend-old.tar.gz" | grep -m1 '^\./index.html$'
```

如果将来采用 `mysqldump`：使用与生产兼容的客户端、`--single-transaction`（仅能为事务表提供一致快照）、`--routines --triggers --events`，导出期间不要并行 DDL；密码交互输入，不放进命令历史和仓库。**数据库备份优先保留一份不在服务器上的副本。**

## 4. Windows 构建：前后端来自同一 main

在 Windows PowerShell（本地目录：`E:\题海仕途`）：

```powershell
cd E:\题海仕途
git status --short
git switch main
git pull --ff-only origin main
git log -1 --oneline
node -v
java -version

npm ci
$env:VITE_API_MODE="http"
$env:VITE_API_BASE_URL="/api/v1"
npm run build

cd E:\题海仕途\backend
.\mvnw.cmd -DskipTests clean package
```

只有此前 CI / 针对性测试已经通过时才可在打包阶段 `-DskipTests`。应确认：

- `frontend/dist/index.html` 与 `frontend/dist/assets` 存在，标题、Logo、manifest 等为预期版本；
- `backend/target/tihaishitu-backend-0.1.0-SNAPSHOT.jar` 来自 Spring Boot `repackage`，不是 `.jar.original`；
- 前端 base URL 必须为同源 `/api/v1`，不要写 `http://localhost:12345` 或绝对生产 IP。

制作发布包（`<release-id>` 建议用 Git short SHA，而非只用日期）：

```powershell
$release="E:\pr5-deploy"   # 示例：以后按 release-id 改目录
New-Item -ItemType Directory -Force -Path $release | Out-Null
Copy-Item "E:\题海仕途\backend\target\tihaishitu-backend-0.1.0-SNAPSHOT.jar" "$release\tihaishitu-backend.jar"
Compress-Archive -Path "E:\题海仕途\frontend\dist\*" -DestinationPath "$release\frontend-dist.zip" -Force
Get-FileHash "$release\tihaishitu-backend.jar" -Algorithm SHA256
Get-FileHash "$release\frontend-dist.zip" -Algorithm SHA256
```

## 5. 服务器暂存发布包，校验后再切换

通过 SFTP 上传两个文件到：

```text
/usr/local/wanjingqiuzhi/releases/<release-id>/
  tihaishitu-backend.jar
  frontend-dist.zip
```

Linux：

```bash
RELEASE=/usr/local/wanjingqiuzhi/releases/<release-id>
ls -lh "$RELEASE"
sha256sum "$RELEASE/tihaishitu-backend.jar" "$RELEASE/frontend-dist.zip"
unzip -t "$RELEASE/frontend-dist.zip" | tail -n 2
mkdir -p "$RELEASE/frontend"
unzip -oq "$RELEASE/frontend-dist.zip" -d "$RELEASE/frontend"
test -f "$RELEASE/frontend/index.html" && test -d "$RELEASE/frontend/assets"
grep '<title>' "$RELEASE/frontend/index.html"
```

与 Windows SHA-256 **逐字匹配**才能发布。Windows `Compress-Archive` 的 ZIP 在某些 Linux `unzip` 下会提示路径反斜线 warning；是否可用以**实际解压成功、`index.html` 和 `assets` 都存在**为准，而不是忽略错误。

**暂存阶段不能覆盖正在运行的 JAR / Nginx root。**

## 6. 有数据库迁移时：先读迁移、权限和停机条件

本次 V18→V22 的四个文件：

```text
V19__question_source_registry.sql          建来源表、source_id、外键及索引
V20__formal_question_answer_contract.java  严格校验题目并迁移旧答案配置
V21__practice_selection_draw_mode.sql      新增 attempt 抽题来源列与索引
V22__question_report.sql                   新建反馈表及外键
```

**这四个是本次历史记录，不是以后还要重复执行的 SQL。** Flyway 依靠 `flyway_schema_history` 追踪已成功迁移的版本；新表更改用 V23+，不要回改 V1–V22。

本次 V20 在启动前做过两项初步只读校验（正式题型/判题方式、选项数量/正确数），均为 0 异常；但是迁移还校验题目选项 key/文本等更严格的规则。未来涉及数据转换的 migration **必须查看其实际实现**，不能把过去的两项 SQL 视为全覆盖。

本次应用账户经核查是：

```text
'wanjing_app'@'172.17.0.1'
```

先用有授权能力的 **Navicat MySQL 管理员 SQL 查询窗口**（**不是 Linux bash**）：

```sql
SHOW GRANTS FOR 'wanjing_app'@'172.17.0.1';
```

如果新 migration 所需 DDL 权限不足，经审核后才临时授权本次所需范围。例如 V19–V22 确实需要：

```sql
GRANT CREATE, ALTER, INDEX, REFERENCES
ON tihaishitu.*
TO 'wanjing_app'@'172.17.0.1';
```

迁移后建议收回不必要 DDL 权限，长期采用“应用运行账号仅 DML + 独立迁移账号”的最小权限方案。本次 2026-10-08 上线后，用户选择暂时保留这四项 DDL 权限以便频繁部署；**这是当前风险例外，不是推荐的长期安全基线**。下次迁移仍应 `SHOW GRANTS` 实查。**不要预先永久授予 `DROP` 或 `ALL PRIVILEGES`。**

## 7. 维护窗口：先后端/DB，再前端

只有在 **备份完成、发布包校验通过、迁移计划确认** 后：

```bash
systemctl stop wanjingqiuzhi

install -m 644 \
  /usr/local/wanjingqiuzhi/releases/<release-id>/tihaishitu-backend.jar \
  /usr/local/wanjingqiuzhi/backend/tihaishitu-backend.jar.new
mv -f /usr/local/wanjingqiuzhi/backend/tihaishitu-backend.jar.new \
      /usr/local/wanjingqiuzhi/backend/tihaishitu-backend.jar

systemctl start wanjingqiuzhi
systemctl status wanjingqiuzhi --no-pager -l
journalctl -u wanjingqiuzhi -n 120 --no-pager
curl -fsS http://172.17.0.1:12345/actuator/health
```

成功信号：systemd `active (running)`、Flyway 迁移成功（若存在）、`{"status":"UP"}`。Spring Security 启动日志有时包含自动生成的临时密码，**分享日志或截图前先检查并遮蔽密码、Token 与敏感信息**。

Flyway 在应用启动时自动迁移，无需人工挨条执行 V19–V22。若迁移失败，**停止服务、保留原始失败日志并检查数据库当前状态，绝不能连续反复 `start` 或直接 `repair`**。

Navicat 只读验收：

```sql
SELECT version, description, success
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;
SELECT * FROM flyway_schema_history WHERE success <> 1;
SELECT
  (SELECT COUNT(*) FROM question_resource) AS questions,
  (SELECT COUNT(*) FROM learner_account) AS learners,
  (SELECT COUNT(*) FROM study_attempt) AS attempts;
```

确认新版本按预期成功，前后关键行数差异合理，再进行前端切换。

前端发布（Nginx 静态文件为宿主机 bind mount，无需重启容器）：

```bash
RELEASE=/usr/local/wanjingqiuzhi/releases/<release-id>
test -f "$RELEASE/frontend/index.html" && test -d "$RELEASE/frontend/assets" || exit 1
\cp -a "$RELEASE/frontend/." /usr/local/docker/nginx/html/
curl -fsS http://127.0.0.1/ | grep '<title>'
curl -I http://127.0.0.1/favicon.ico
docker ps --filter name=nginx
```

**重要**：部分 CentOS/root 环境把 `cp` 别名设为 `cp -i`，会对每个同名文件都问 `overwrite?`。已备份并核对目录时，用 **`\cp -a` 绕过别名**，不要逐个回答 `y`。以上命令只覆盖同名文件，**不会删除旧 hash assets**，可先保留避免缓存用户加载失败；禁止在不确认目录时直接 `rm -rf` / `rsync --delete`。将来若要真正原子化前端切换，应单独设计 releases + symlink，而非假定当前已支持。

## 8. 上线验证（不以 HTTP 200 代替真实业务）

```bash
systemctl is-active wanjingqiuzhi
curl -fsS http://172.17.0.1:12345/actuator/health
curl -fsS http://127.0.0.1/ | grep '<title>'
curl -I http://127.0.0.1/favicon.ico
docker ps --filter name=nginx
```

浏览器使用实际站点 URL，建议 `Ctrl+Shift+R` / 无痕模式排除 favicon 与 JS 缓存，至少验收：

- [ ] 首页“万境中枢”、网站“万境书院”和 Logo/favicon 正常；
- [ ] 旧账号可登录/登出；刷新后登录态仍正常；
- [ ] 知识、全平台题库、题目详情、Markdown/公式可正常浏览；
- [ ] World 寒门仕途答题、解析、自评、奖励正常；
- [ ] “我没思路”能够答错、进入永久错题本；
- [ ] “题目有误？”能提交，管理端反馈收件箱可处理；
- [ ] 练习后刷新确认 Attempt / Mastery / Wrong Book 仍持久；
- [ ] Nginx 无 502，后端日志无新报错。

SPA 路由返回 200 只证明 Nginx 提供了 `index.html`，**不证明所有按钮、权限与数据链都正确**。

## 9. 故障排查与回滚红线

| 现象 | 最先查看 | 原则 |
| --- | --- | --- |
| Java `activating (auto-restart)`、`status=1/FAILURE` | `journalctl -u wanjingqiuzhi --since ... --no-pager` 中**第一次** `Caused by` / `SQL State` | 先 `systemctl stop`，不要反复重启 |
| `FlywayValidateException`、`Detected failed migration` | `flyway_schema_history` 中 `success=0`，最初一轮 `FlywayMigrateException` | 单看再次启动的 validate 报错会掩盖原始 SQL 错误 |
| `Error Code: 1142` / `CREATE command denied` | `SHOW GRANTS FOR 'wanjing_app'@'172.17.0.1'` | 只补 migration 确实需要的权限 |
| Flyway migration 执行到一半失败 | `information_schema.TABLES/COLUMNS`、外键、索引和 history | **MySQL 5.7 DDL 不是整段自动回滚**，检查半成品 |
| Nginx 返回 502 | `systemctl status`、`ss -lntp`、`proxy_pass` | 不能无故把 `172.17.0.1:12345` 改为 `127.0.0.1:12345` |
| `cp: overwrite?` 连续出现 | shell `alias cp` | `Ctrl+C`，确认目标路径后使用 `\cp -a` |
| 新版本打不开、仍看到旧界面 | HTML title、hash JS/CSS、浏览器缓存 | 检查是否复制到 **`/usr/local/docker/nginx/html`** |
| ZIP 解压提示 backslashes | 实际文件树与 `index.html` / `assets` | 检查解压产物，不能仅凭 warning 判断失败 |

### 本次 V19 真实故障（**仅为案例，不是通用 repair 命令**）

首次尝试迁移到 V19 时：

```text
Error Code: 1142
CREATE command denied to user 'wanjing_app'@'172.17.0.1'
V19__question_source_registry.sql 第 1 条 CREATE TABLE question_source
```

后端第一次启动留下了 `flyway_schema_history` 的 V19 `success=0`，随后自动重启只看到 `Validate failed`。排查后确认：

```text
question_source 表不存在
question_resource.source_id 列不存在
无 V19 结构副作用
```

因此这次在管理员明确知情、数据库备份已存在、服务停止的情况下，**只针对性清除这条失败记录**，补充所需 DDL 权限后重启。重启成功，日志显示 `Successfully applied 4 migrations ... now at version v22`，健康检查 UP。

**未来切勿直接照抄删除 Flyway 历史记录！** 若任何部分 DDL 已执行，必须先盘点所有副作用并决定恢复备份、修正半成品或用经审核的 Flyway repair。**Flyway repair 只修历史/校验记录，不会撤销已经执行的建表、修改列和数据操作。**

### 回滚判断

- **未迁移数据库**：可以先用备份 JAR + 前端恢复，再健康检查（仍须注意会话中的变更）。
- **已执行结构/数据迁移**：**不保证**旧 JAR 能在新 DB 上运行。先停写并评估迁移向后兼容性；不兼容时恢复**同一时间点**的数据库、JAR、前端备份，优先在隔离库演练；恢复生产 DB 属高危操作，须用户单独批准。不要手写逆向 `DROP/ALTER`，不要仅回滚 Java。
- 恢复旧前端可在确认目标目录后将 `frontend-old.tar.gz` 展开回 Nginx root；这不会主动删除新版本的额外 hash 资源，旧 index 引用旧资源时通常可继续工作。恢复旧服务需与 DB 兼容性一并判断。
- 数据库恢复会丢失备份时点之后的新答题记录，必须先向用户明确说明。

## 10. PR5 已验证的历史发布记录

```text
日期                   2026-10-08（Asia/Shanghai）
main                   399295a (PR #28 合并后)
前端                   frontend/dist → frontend-dist.zip
后端                   backend/target/tihaishitu-backend-0.1.0-SNAPSHOT.jar
服务器发布暂存          /usr/local/wanjingqiuzhi/releases/pr5-399295a/
旧版程序备份            /usr/local/wanjingqiuzhi/backup/pre-pr5-20261008-081032/
数据库历史版本          V18 → V19 → V20 → V21 → V22（全部成功）
原有数据核查            question_resource=1453、learner_account=2、study_attempt=84
后端健康                {"status":"UP"}
Nginx/favicon            首页 <title>万境书院</title>，favicon HTTP 200
最终人工浏览器          用户确认可正常使用
```

Navicat 完整 SQL 导出成功（结构+数据，0 错误）；**当次因赶时间，没有在隔离数据库实际做导入恢复演练**。下次高风险迁移应补足验证。本次升级保留了应用账号 DDL 权限，属于用户决定的暂时例外。

## 11. 给未来 ChatGPT / Codex 的操作口令

> 我准备更新 GitHub `Valleyer/tihaishitu` 的万境书院京东云生产站点。请先读取最新 `AGENTS.md`、`docs/PROJECT_RULES.md`、`docs/production-release-runbook.md`、`docs/deployment.md`，确认当前线上 Git SHA、Flyway 版本、systemd / Docker / Nginx 路径，再判断本次是否有新的 migration。**每次只让我执行一小步，等我反馈结果后再继续；不覆盖线上 DB、不默认改网络配置、不直接运行 destructive SQL；备份、构建、SHA 校验、迁移、JAR 与前端切换、功能验收逐步完成。**如果实际环境与 2026-10-08 快照不同，以实测为准。

---

### 文件边界

- `docs/production-release-runbook.md`：**已经上线后的常规升级 / 迁移 / 回滚（优先读）**；
- `docs/deployment.md`：首次部署及通用配置示例；
- `docs/automated-release.md` 与 `scripts/deploy/`：Windows `D:\Deploy` → Linux `/usr/local/deploy` 的三步发布脚本及长期维护规则；异常或高风险迁移需回到本手册人工处理；
- `scripts/predeploy-db-audit.sql`、`scripts/verify-prod-db.sql`：原本为**V18 初次导入**写的只读脚本，内部仍有固定 `V18` 判断。**不要把它们当前的 `OK_V18` 断言用于 V22 或未来版本验收**；需要使用时先更新脚本并以实际 migration 为准。