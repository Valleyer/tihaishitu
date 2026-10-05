# 知境后端

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
$env:APP_ADMIN_KEY = "至少 24 位随机题库管理密钥"
```

在 IDEA 中可把这些变量填入 Spring Boot 运行配置的 Environment variables。数据库结构由 `src/main/resources/db/migration` 下的 Flyway 版本脚本维护；已有非空数据库会从版本 0 建立基线后按顺序迁移，不删除旧题库和存档。首次启动还会幂等导入正式数学一知识点源。

管理后台首个管理员只在数据库尚无有效 ADMIN，且以下两个变量同时存在时创建：

```powershell
$env:APP_INITIAL_ADMIN_USERNAME = "你的管理员用户名"
$env:APP_INITIAL_ADMIN_PASSWORD = "足够长的随机密码"
```

密码只以 BCrypt 哈希入库。浏览器管理后台位于 `/manage`，使用服务端 Session、HttpOnly Cookie 和 CSRF 防护；`APP_ADMIN_KEY` 仍只供脚本、Codex、初始化导入和后续 MCP 使用，不用于浏览器登录。管理员停用账号后，后端会在下一次管理请求时立即注销该账号已有 Session；停用账号也无法重新登录。

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
- `GET /api/v1/question-banks/{uuid}`：按文集 UUID 下载可长期缓存的题库正文
- `POST /api/v1/games`：创建新存档
- `GET /api/v1/games/{id}`：读取完整存档
- `DELETE /api/v1/games/{id}`：删除存档
- `POST /api/v1/games/{id}/activities`：开始五知识点副本或十知识点主线
- `POST /api/v1/games/{id}/answers`：只提交课卷 UUID、题目 UUID 与答案
- `POST /api/v1/games/{id}/answers/reveal`、`/self-assess`：综合题显式揭示答案并自评掌握程度
- `POST /api/v1/games/{id}/next`：当前知识点完成后领取下一题
- 地图移动、人物对话、考试报名、物品购买/使用、札记批注、活动结算等游戏行为接口
- `POST /api/v1/admin/question-banks/import`：原子校验并新增或修订一部文集
- `POST /api/v1/admin/questions/import`：按 `global-question-batch/v2` 幂等导入全局题目资源，不写入 Book
- `POST /api/v1/admin/global-question-banks/import`：已弃用的 V1 兼容接口；Knowledge-driven Book 会拒绝旧版覆盖导入
- `PUT /api/v1/admin/question-banks/{uuid}/metadata`：改名、改简介、启停与调整权重
- `POST /api/v1/manage/auth/login`、`POST /logout`、`GET /me`：管理后台 Session
- `/api/v1/manage/knowledge-points`：全服知识点分页、code/name/alias 搜索和审核者维护
- `POST /api/v1/manage/knowledge-points/{uuid}/merge`：管理员事务迁移知识点关系并保留旧知识点
- `/api/v1/manage/questions`：独立题目草稿、知识点绑定、提交、审核和归档工作流
- `POST /api/v1/manage/imports/questions`：管理员批量导入 `global-question-batch/v2` 题目批次
- `POST /api/v1/manage/imports/question-bank`：已弃用的 V1 兼容接口
- `/api/v1/manage/users`：管理员创建、禁用账号和分配角色
- `GET /api/v1/manage/audit-logs`：管理员分页检索内容和权限变更记录

`/bootstrap` 不传完整题库，只返回文集清单和 revision。浏览器把完整文集存入 IndexedDB，仅在 revision 变化时重新下载；历史答题记录在网络响应中只携带题目 UUID，前端用本地缓存补回 Markdown 展示数据。JSON 响应超过 1KB 时还会启用压缩。

题库管理写接口仅在配置 `APP_ADMIN_KEY` 后启用，请求必须携带 `X-Admin-Key`。留空时接口返回 404，避免误把本地开发管理能力暴露给普通玩家。导入会完整校验 UUID、知识点引用、题型、选项和答案，并在事务中替换单部文集、递增 revision；浏览器下一次启动会只重新下载这部发生变化的文集。管理密钥只放服务端环境变量，不写入仓库或普通游戏前端。

MCP 更适合给外部 AI 工具调用，不替代网页游戏本身所需的 REST API；需要 AI 自动管理题库时可以在这组受保护 REST 服务之上增加 MCP 适配层。

## 全服内容模型

- `global_knowledge_point` 使用 UUID 主键和唯一稳定 `code`；`M1-H06-035` 不因改名、排序或迁移变化。
- `knowledge_alias` 独立存储可搜索别名。正式 Math1 源为 469 条：高等数学 198、线性代数 136、概率论与数理统计 135。
- `question_resource` 保存独立原题，分别记录 `question_type`、`presentation_type`、`grading_mode`。
- `question_resource_knowledge` 保存题目与知识点的多对多关系及 core/auxiliary 角色。
- `question_bank_chapter/question_bank_knowledge` 让 Book 按章节组织 KnowledgePoint；Question 是 KnowledgePoint 的全局训练资源。
- `question_bank_item` 仅作为旧游戏和 LegacyCatalog 的兼容关系保留，新版导入不会写入该表。旧 `knowledge_point/question_item` 表暂时保留，不会在迁移中删除。
- `app_user/app_user_role/content_audit_log` 支撑 CONTRIBUTOR、REVIEWER、ADMIN 和内容审计。
- `knowledge_merge_history` 永久记录源/目标、迁移与折叠关系数、操作者和原因。合并不会删除旧知识点；旧 code、名称与 alias 会加入目标知识点检索词，受影响文集 revision 会递增。

全服题目批次导入先在内存中校验整批数据，再在单一事务中写入。它只接受 active 且与批次科目一致的全局知识点稳定 code，每题绑定 1–3 个知识点且至少一个 core；任一引用、答案或题型组合非法都会整批回滚。相同 Question UUID 再次导入执行更新，真题还会校验科目、年份和题号的自然身份，防止不同 UUID 重复入库。正式格式以 `docs/题库生成提示词.md` 和 `frontend/public/examples/题库示例.json` 为准。

知识点列表支持科目、分科、章节和状态组合筛选。题目编辑器允许调整 1–3 个知识点的 core/auxiliary 角色及展示顺序，保存时会把当前顺序归一化为连续的 `sortOrder`。
