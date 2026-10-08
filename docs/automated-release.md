# 万境书院：Windows + Linux 三步部署工具

> **仓库长期维护入口**：源文件位于 [`scripts/deploy/build-release.bat`](../scripts/deploy/build-release.bat) 和 [`scripts/deploy/deploy-release.sh`](../scripts/deploy/deploy-release.sh)。本文件是对应的使用规范。已有站点升级与 Flyway 故障处理还必须阅读 [`production-release-runbook.md`](./production-release-runbook.md)。


这是为 **2026-10-08 京东云现役部署环境** 制作的两份脚本。原生产结构：

- Windows 发布产物默认在 **`D:\Deploy\release-<sha>-<时间>\`**（纯英文目录）；项目 Git 仓库不再写死盘符，通过 `D:\Deploy\repo-path.txt` 配置；
- Linux 收件目录：`/usr/local/deploy/`，其中预先放置 `deploy-release.sh`；
- systemd：`wanjingqiuzhi`；JAR：`/usr/local/wanjingqiuzhi/backend/tihaishitu-backend.jar`；
- Nginx Docker 静态目录（宿主）：`/usr/local/docker/nginx/html`；Java Health：`http://172.17.0.1:12345/actuator/health`；
- MySQL 5.7 Docker `mysql57`；正式数据库 `tihaishitu`，Flyway 目前 V22，新的 schema 变更必须 V23+。

## 第一次使用：安装两个脚本

1. 将 `build-release.bat` 放到 **`D:\Deploy\build-release.bat`**（或任意位置），保存为 UTF-8 无 BOM。发布产物默认固定在 `D:\Deploy`，首次运行会创建该目录。
2. 将 `deploy-release.sh` 通过 SFTP 上传到 Linux **`/usr/local/deploy/deploy-release.sh`**，不必 `chmod +x`，以后每次都用 `bash /usr/local/deploy/deploy-release.sh` 调用。
3. 确认 Windows 已能使用 git、npm、Java 17、PowerShell 与仓库的 Maven Wrapper；Linux 有 curl、sha256sum、unzip、tar、systemctl、Docker、flock，且服务已经正常运行。
4. **发布前人工确认 GitHub CI 已通过**；为了速度，后端打包使用 `-DskipTests`，不是跳过必要的 CI 或业务验收。

## 首次运行的 Git 仓库位置配置（只需一次）

为避免换电脑或项目换盘符后失效，Windows 脚本**不再包含 `E:\题海仕途` 这样的硬编码项目路径**：

1. 首次双击时，如果找不到配置，会提示 `Repository full path:`；填入当前电脑实际克隆的 Git 仓库目录，例如目前可输入 `E:\题海仕途`，将来推荐使用全英文 `D:\Projects\tihaishitu`。
2. 输入合法 Git 仓库位置后，自动保存为 **`D:\Deploy\repo-path.txt`**。以后双击会自动读取，**不用每次重复输入**。也可以提前自行创建该文本文件，内容仅一行项目路径（无需引号）。
3. 换设备或移动项目时，直接编辑 / 删除 `repo-path.txt`，下次脚本会重新询问。也可设置环境变量 `WANJING_REPO` 临时覆盖项目目录。
4. 产物根目录默认 **`D:\Deploy`**；如果将来电脑没有 D 盘，可以在启动批处理前设置 `WANJING_OUTPUT_ROOT` 环境变量指定另一**纯英文**目录（同时把 `build-release.bat` 放到该目录）。

**注意**：改输出目录不意味着原 Git 仓库已经搬离中文路径；使用当前 `E:\题海仕途` 仍可以构建，但如果你希望本地整条构建链都只使用英文路径，应以后另外将仓库克隆/移动至英文目录，再更新 `repo-path.txt`。不要因为重命名输出目录而直接改动后端技术名称、仓库名或数据库名。

## 以后每次仅三步

1. **Navicat 手动备份**京东云 `tihaishitu` 整库（`结构和数据`，含 `flyway_schema_history`），保存服务器之外，并核实导出成功。存在重要写入时应停写后再制作一致性备份；高风险迁移建议先在隔离库做一次恢复演练。
2. **Windows 双击** `build-release.bat`：自动同步最新 `main`、执行 `npm ci`、构建前端、Maven 打包后端、生成 ZIP + SHA-256 清单，放到 **`D:\Deploy\release-<sha>-<时间>\`**。失败立即停止。**不要使用之前的旧 release 目录冒充成功的新版本。**
3. 手动把上述 release 子文件夹里的 **四个文件**（`tihaishitu-backend.jar`、`frontend-dist.zip`、`release.sha256`、`release.info`）上传至 Linux **`/usr/local/deploy/`** 覆盖同名旧发布包；然后 SSH 执行 `bash /usr/local/deploy/deploy-release.sh`，输入 **`YES`** 确认已手动备份数据库。脚本完成旧程序备份、JAR 切换、等待健康、前端发布及基本验证。

**说明**：你所说的“三步”中第 3 步仍包含“手动上传四个文件 + 执行一个 Linux 命令”。上传本身不能由离线 Windows 构建脚本自动完成，因为你明确希望自己上传。

## 自动保护边界

- 运行前要校验 SHA-256、`main` 提交身份、旧后端健康、Nginx 和 MySQL 容器、足够磁盘空间、已手动确认数据库备份；否则不切换。
- 每次备份旧 JAR、旧前端全目录、Nginx 配置、systemd unit、如存在则备份环境文件（权限严格设置 0600），备份目录仅 root 可访问。
- 带新 Flyway migration 的版本通过 **新 JAR 启动自动执行**，**脚本不会直接操作 MySQL 表和 `flyway_schema_history`**。新服务不健康就不发布前端。
- 新前端静态资源先复制，`index.html` 最后单文件切换；旧 hashed assets 暂不删除，避免已打开的浏览器标签加载失败。
- 出错即停止，保留错误现场；**不自动 `repair`、不清理迁移记录、不恢复旧 JAR、不自动回滚 DB**。数据库新结构可能与旧 JAR 不兼容，需要人工判断。
- 程序检查 `actuator/health` 和静态文件 HTTP，但**不等于业务功能端到端验收**。成功后仍应浏览器验证登录、知识、题库、练习、寒门仕途及管理功能，并在有新迁移时 Navicat 确认 Flyway 版本。
- 当前数据库应用账号保留 DDL 权限是用户此前选择；如以后采用独立 Flyway 账号，需同步调整手册，不应在脚本里永久扩大权限。

## 遇到错误时怎么做

如果 Windows 构建失败：停止上传；截取错误输出咨询 AI。若 Linux 失败：**不要盲目第二次执行**，也不要人工修改 Flyway 历史。先记录脚本报错、`systemctl status wanjingqiuzhi -l --no-pager`、`journalctl -u wanjingqiuzhi -n 150 --no-pager`，在发送日志前遮盖密码、Token 和 Spring 自动生成的测试口令；并根据是否执行了迁移决定恢复策略。

注意：以上脚本是基于 GitHub PR5 成功上线现场**静态构建和语法验证**的工具，没有能力在本环境连接你的京东云服务器做真实发布演练。**首次自动化上线尤其建议在你方便人工排查时执行**。

## 源码管理、更新流程（跨会话长期约定）

- GitHub 仓库 `Valleyer/tihaishitu` 中的 `scripts/deploy/build-release.bat`、`scripts/deploy/deploy-release.sh` 与本手册是**唯一长期维护来源**；之前聊天里的 ZIP/本地旧拷贝只代表历史发布，不是维护来源。
- **每次修改部署脚本必须同一个 PR 同步更新**本手册、`docs/production-release-runbook.md` 的相关内容、必要时的 `docs/PROJECT_RULES.md` 和 `README.md`；不可仅改一个已安装在服务器上的脚本。
- 开发 Agent 按 `AGENTS.md` 新建独立分支、单独 PR；除用户在该对话明确授权，不自动 push、创建 PR 或 merge。上线脚本变更尤其必须由用户人工审阅。
- 合并新的脚本 PR **不会自动更新本机已安装文件**：Windows 必须更新 `D:\Deploy\build-release.bat`，Linux 必须通过 SFTP 更新 `/usr/local/deploy/deploy-release.sh`；两边版本应来自同一 Git commit。上传前保留旧版本备份。
- Windows 运行脚本默认自动拉取最新 main。运行前明确确认**该 main 的 CI 已通过**，且 Linux 已安装的脚本与之兼容；`-DskipTests` 是仅打包跳过，不是代替测试。
- 不得把 `repo-path.txt`、真实口令、Navicat 导出的 SQL 备份、`backend.env`、SSH 私钥、`release-*` 二进制打包产物提交到 GitHub。
- 修改任何包含 `stop/start`、`cp/mv`、`tar`、`Flyway` 迁移、`backup`、`rollback` 的危险步骤，必须验证失败路径，保持**“前置检查、备份优先、出错停止、不得自动修复数据库/回滚结构”**。
- 当前自动部署脚本**并未在真实京东云生产环境完成全流程自动化实测**。Linux `bash -n` 语法与 ZIP 文件完整性可以在非生产环境验证；Windows `.bat` 需在真实 Windows CMD 上试运行，首次自动上线应安排人工现场观察。
- 如果已存在待处理的失败 Flyway migration、服务不健康、需数据库数据修补或需要变更网桥端口，**不要依靠一键脚本处理**，改用人工引导的运维手册。

### 下次咨询 Codex / ChatGPT 的话术

> 请先读 GitHub 最新 `AGENTS.md`、`docs/PROJECT_RULES.md`、`docs/automated-release.md`、`docs/production-release-runbook.md`，以及 `scripts/deploy/build-release.bat` / `scripts/deploy/deploy-release.sh`。我想迭代万境书院三步上线工具：Windows `D:\Deploy` 构建 → 手动上传 Linux `/usr/local/deploy` → Linux 一键备份并部署。先分析对现役生产的影响和回滚边界，确认改动方案后再修改脚本与配套文档。不得在未授权时触碰线上 DB 或服务器。
