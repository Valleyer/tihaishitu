# API 对接约定

基础路径：/api/v1。当前没有运行中的 Java 后端。
前端通过 GameApi 接口访问数据；默认使用本地适配器，所有方法返回 Promise。
类型定义以 frontend/src/domain/types.ts 为准。

| 方法 | 路径 | 请求体 | 成功响应 |
| --- | --- | --- | --- |
| GET | /session | 无 | Session |
| POST | /session/answers | SubmitAnswer | 更新后的 Session |
| POST | /session/next | { attemptId: string } | 更新后的 Session |
| GET | /question-banks | 无 | QuestionBank[] |
| GET | /mistakes | 无 | Mistake[] |
| GET | /statistics | 无 | Statistics |

所有成功响应均为 200 + JSON 对象/数组，直接返回数据，不包 data。
错误响应使用非 2xx 状态和 { "code": "INVALID_ANSWER", "message": "请选择有效的答案。" }。
客户端显示 message；网络、超时错误由页面捕获。请求超时 15 秒。
尚未定义认证、用户管理和多存档。未来增加这些能力时应扩展接口，不把密钥放入 VITE_ 环境变量。

## Session 示例

```json
{
  "id": "local-save",
  "player": { "name": "折叶", "title": "寒门书生", "knowledge": 0 },
  "question": {
    "id": "os-001",
    "subject": "408",
    "chapter": "操作系统 · 虚拟内存",
    "type": "single_choice",
    "prompt": "在请求分页系统中，页面大小增大后，哪一项通常会增加？",
    "options": [
      { "id": "A", "text": "页表项数量" },
      { "id": "B", "text": "页内碎片" },
      { "id": "C", "text": "页号位数" },
      { "id": "D", "text": "缺页次数一定增加" }
    ]
  },
  "attemptId": "由服务生成的唯一作答标识",
  "result": null
}
```

提交答案：{ "attemptId": "...", "questionId": "os-001", "answer": "B" }。
返回同一 Session，并更新 player.knowledge 和 result：

```json
{
  "questionId": "os-001",
  "selected": "B",
  "correctAnswer": "B",
  "correct": true,
  "explanation": "页面越大，页表项通常越少，页内碎片通常越大。",
  "story": "先生看过你的答卷，轻轻颔首。",
  "knowledgeGain": 3
}
```

题目读取接口不返回答案；提交后才返回正确答案与解析。
本地示例题数据包含答案，仅用于本地演示，不具备防作弊能力。
统计 total/correct/today/streak 均为整数；正确率由页面计算，无记录时显示破折号。
today 按设备本地日期统计；Java 对接时需约定用户时区。

## 幂等与恢复

- attemptId 表示一次作答机会。同一题下一轮出现时必须生成新值。
- 同一个 attemptId 重复提交应返回首次结果，不重复累加学识和统计。
- 不匹配当前题目或作答标识的提交应拒绝（建议 409）。
- 未作答不能请求下一题。
- 下一题请求携带旧 attemptId；重复请求返回已更新的当前 Session，不能连续跳题。
- 刷新页面读取 Session，恢复已经判定的 result。
- 后端应在事务中完成作答、记录、成长状态更新，确保上述语义在并发下也成立。

## 本地存储

键名 tihaishitu:save:v1，保存结构包含 version、index、session、records。
损坏或不兼容的存档报错，不自动覆盖。无服务端同步，当前仅支持单标签页。
后续修改存档结构时应添加版本迁移。

