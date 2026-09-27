# script-debugger-java

本地脚本调试服务：通过 REST API 提交脚本，在进程内受控执行，支持断点、
单步、状态观察、异常中断与多会话并发。

## 构建与运行

```bash
mvn spring-boot:run        # 启动服务（默认 8080 端口）
mvn test                   # 运行全部测试
```

## 脚本语言子集

一种简单的类 C 语法动态语言，语句以 `;` 结尾，支持 `//` 行注释。

### 数据类型

| 类型      | 字面量示例          | 说明                     |
|-----------|---------------------|--------------------------|
| `number`  | `42`, `3.14`        | 双精度浮点               |
| `boolean` | `true`, `false`     |                          |
| `string`  | `"hello"`           | 支持 `\n` `\t` `\"` 转义 |
| `nil`     | `nil`               | 空值                     |

### 变量与表达式

```js
let x = 1 + 2 * 3;        // 算术: + - * / %
let b = x > 5 && x < 10;  // 比较: < <= > >= == !=，逻辑: && || !
let s = "x=" + x;         // + 可拼接字符串
x = x + 1;                // 赋值（必须先 let 声明）
```

真值规则：`false` 与 `nil` 为假，其余皆为真。

### 控制流

```js
if (x > 5) { print("big"); } else { print("small"); }

let i = 0;
while (i < 10) {
    i = i + 1;
}
```

### 函数

```js
fn add(a, b) {
    let sum = a + b;   // 每次调用拥有独立的局部作用域
    return sum;        // 无 return 时返回 nil
}
print(add(1, 2));      // 内置 print(value) 写入会话标准输出
```

支持递归与闭包；每次调用在调用栈上创建独立帧。

### 运行时错误

未定义变量、对未声明变量赋值、类型不匹配（如 `1 + true`）、除零、
实参个数不符等都会以带行号的运行时错误中断执行（见下文错误中断语义）。

## 执行限制

| 限制       | 默认值     | 上限        | 说明                                       |
|------------|-----------|-------------|--------------------------------------------|
| 执行超时   | 10 000 ms | 300 000 ms  | 只累计实际执行时间，暂停期间不计           |
| 语句步数   | 100 000   | 10 000 000  | 每执行一条语句计一步                       |

创建会话时 `timeoutMs` / `maxSteps` 传 `0`（或不传）使用默认值，传负数
表示不限制。超限后会话进入 `TERMINATED`，终止原因分别为
`TIMEOUT` / `STEP_LIMIT`，执行线程随即被回收。

## 调试接口

所有接口位于 `/api/debug/sessions` 下。典型流程：
**创建会话 → （设置断点）→ start → （暂停后 resume/step）→ … → delete**。

### 会话生命周期状态

```
CREATED → RUNNING ⇄ PAUSED → FINISHED      (COMPLETED / ERROR)
                 ⇄ PAUSED_ON_ERROR ↗
                 └───────────────→ TERMINATED (TIMEOUT / STEP_LIMIT / USER_TERMINATED)
```

- `PAUSED`：因断点、单步或暂停请求挂起，可检查快照后继续。
- `PAUSED_ON_ERROR`：运行时错误中断，可检查变量与调用栈；
  `resume` 会以 `ERROR` 原因结束会话，`terminate` 以 `USER_TERMINATED` 结束。
- 控制命令（`resume`/`step/*`）是**同步**的：阻塞到下一次暂停或会话结束才返回，
  返回的即为最新的会话状态。

### 接口一览

| 方法 & 路径                          | 说明                                       |
|--------------------------------------|--------------------------------------------|
| `POST /api/debug/sessions`           | 创建会话（可附带断点）                     |
| `POST /{id}/breakpoints`             | 启动前替换全部断点                         |
| `POST /{id}/start`                   | 启动执行                                   |
| `POST /{id}/resume`                  | 继续运行（阻塞至下次暂停/结束）            |
| `POST /{id}/pause`                   | 异步请求暂停（在下一条语句生效）           |
| `POST /{id}/step/into`               | 单步进入（任意深度的下一条语句）           |
| `POST /{id}/step/over`               | 单步跳过（同层或更浅的下一条语句）         |
| `POST /{id}/step/out`                | 单步退出（当前函数返回后的第一条语句）     |
| `POST /{id}/terminate`               | 终止会话                                   |
| `GET  /{id}`                         | 查询状态、暂停快照、标准输出               |
| `GET  /{id}/stdout`                  | 查询标准输出                               |
| `DELETE /{id}`                       | 销毁会话并回收资源                         |

错误码：`404` 会话不存在；`409` 当前状态不允许该操作（如未暂停时 resume）；
`400` 脚本为空或断点条件无法解析。

### 创建会话

```http
POST /api/debug/sessions
Content-Type: application/json

{
  "script": "let x = 1;\nfn double(n) {\n  return n * 2;\n}\nlet y = double(x);\nprint(y);\n",
  "timeoutMs": 10000,
  "maxSteps": 100000,
  "breakpoints": [{"line": 5}, {"line": 6, "condition": "y > 1"}]
}
```

### 断点

```json
{"line": 5}                          // 行断点
{"line": 6, "condition": "i == 3"}   // 条件断点：命中行时先求值，为真才暂停
```

- 条件在**命中断点时**于当前作用域求值，可使用任意表达式。
- 条件求值失败（如依赖未定义变量）时，会话以
  `pause.reason = "CONDITION_ERROR"` 暂停并在 `pause.errorMessage` 中
  返回错误详情，**会话不会中止**，可继续执行。

### 状态响应

```json
{
  "sessionId": "1a2b3c4d",
  "state": "PAUSED",
  "terminationReason": null,
  "error": null,
  "pause": {
    "reason": "BREAKPOINT",          // BREAKPOINT | CONDITION_ERROR | STEP | PAUSE_REQUEST | ERROR
    "line": 5,
    "errorMessage": null,
    "callStack": [                    // 栈顶在前；每帧含独立局部变量
      {"function": "double", "line": 3, "locals": {"n": 1}},
      {"function": "<script>", "line": 5, "locals": {"x": 1}}
    ]
  },
  "stdout": ["..."],
  "stepCount": 12
}
```

暂停快照在暂停时刻生成，与执行状态严格一致；`callStack` 每一帧的
`locals` 是该帧可见的局部变量（内层作用域遮蔽外层）。

### 异常中断

运行时错误使会话进入 `PAUSED_ON_ERROR`：`error` 为失败原因，
`pause.line` 为出错行，`pause.callStack` 为出错时的完整调用栈。
此时可任意查询状态；`resume` 结束会话（`terminationReason = "ERROR"`），
`terminate` 亦可主动终止。

## 并发与资源回收

- 会话之间相互独立：各自的执行线程、断点、变量与输出互不影响，可并发调试。
- 每个会话在专用守护线程上执行；结束（完成/出错/终止）后线程自动退出。
- `DELETE` 或会话终止后资源立即回收；已结束会话保留 60 秒供查询，
  之后由定时任务（每 30 秒）自动清理。

## 代码结构

```
lang/    脚本语言：Lexer → Parser(AST) → Interpreter（带执行钩子）
debug/   调试核心：DebugSession（断点/单步/暂停/限制）+ SessionManager（并发与回收）
api/     REST 层：DebugController + DTO
```

## 测试

```bash
mvn test
```

覆盖：解释器语义（11）、断点/条件断点/单步/异常/超时/步数/暂停（14）、
并发会话与资源回收（7）、REST 接口（9）、应用上下文（1），共 42 例。
