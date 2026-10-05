# Phase D：Learning Hub（知境中枢）与多世界基础

正式产品入口现在以联机学习者为中心：登录后先进入 Learning Hub（知境中枢），再从世界大厅进入 `ancient-official`。Learning Hub 管理学习范围、Study Focus 与全服只读学习资源；游戏世界只包装玩法，不拥有 Book、KnowledgePoint 或 Question。

## 数据边界

- `learner_account` 与管理账号 `app_user` 完全分离。
- `learner_session` 只保存 SHA-256 token hash；原始 256-bit token 仅放在 HttpOnly、SameSite=Lax Cookie。
- `learner_selected_book` 决定题池的完整知识范围及依赖允许范围。
- `learner_focus_knowledge` 只影响手动模式的目标知识点优先顺序。
- `learner_world_state` 以 `(learner_id, world_id)` 唯一定位并使用 revision 乐观锁。
- 新正式作答在 `study_attempt` 与 `answer_record` 写入 `learner_id/world_id`，`game_id` 为空。
- `game_save` 与 `/games/**` 保留为 Legacy Compatibility，新正式流程不读写它们。

## 正式入口

- `/login`、`/register`：联机学习身份。
- `/`：Learning Hub（知境中枢）与世界大厅。
- `/study`：Selected Books、Study Focus、节奏与难度。
- `/books`、`/books/:id`：文集与 Chapter Tree。
- `/knowledge/:id`、`/questions/:id`：只读知识和题目浏览。
- `/worlds/ancient-official`：寒门仕途，由固定 WorldShell 提供“返回知境中枢”。

浏览 API 不创建 attempt、answer record 或 WorldState 变更。世界 action 只接收动作参数，服务端从会话识别 Learner 并加载唯一 WorldState。

本阶段不包含掌握度、遗忘、诊断、黑名单或第二个真实世界。
