# script-debugger-java

本地脚本调试服务：通过 REST 接口提交脚本，在进程内受控执行，支持断点、
条件断点、暂停、单步（进入/跳过/退出）、继续，以及调用栈、局部变量与
标准输出的快照查询。多个调试会话可并发进行，互不干扰。

## 构建与运行

```bash
mvn compile        # 编译
mvn test           # 运行全部测试
mvn spring-boot:run  # 启动服务（默认 8080 端口）
```

## 脚本语言子集

语句以换行或 `;` 结束；`#` 与 `//` 开头为注释。

| 特性 | 语法 | 说明 |
| --- | --- | --- |
| 变量声明 | `let x = 1` | 当前作用域内定义 |
| 赋值 | `x = x + 1` | 变量必须已声明，否则报运行时错误 |
| 数值运算 | `+ - * / %`、一元 `-` | 操作数必须为数值；除/取模为零报错 |
| 布尔运算 | `== != < <= > >=`、`and or not`（或 `&& || !`） | 比较操作数必须为数值；逻辑运算短路 |
| 条件分支 | `if (cond) { ... } else { ... }` | 条件必须为布尔；支持 `else if` |
| 循环 | `while (cond) { ... }` | 条件必须为布尔 |
| 函数定义 | `fn add(a, b) { ... }` | 定义在当前作用域；支持递归与闭包 |
| 函数调用 | `add(1, 2)` | 实参个数必须匹配；每次调用有独立调用帧与局部作用域 |
| 返回 | `return expr` / `return` | 无值返回为 `nil` |
| 输出 | `print(expr, ...)` | 内置函数，追加一行到会话标准输出 |

字面量仅支持数值（如 `1`、`2.5`）与布尔（`true` / `false`）。
`if` / `while` 的条件必须是布尔表达式，否则报运行时错误。

示例：

```
fn fib(n) {
  if (n < 2) {
    return n
  }
  return fib(n - 1) + fib(n - 2)
}
let r = fib(10)
print(r)
```

## 执行限制

| 限制 | 默认值 | 说明 |
| --- | --- | --- |
| 执行超时 | 10000 ms | 仅累计实际运行时间（暂停期间不计）。超限以 `TIMEOUT` 终止 |
| 步数上限 | 100000 条语句 | 每执行一条语句计一步（含循环每轮的条件重判）。超限以 `STEP_LIMIT` 终止 |

两者均可在创建会话时通过 `timeoutMs` / `maxSteps` 覆盖。
超时或步数耗尽时会话进入 `ENDED`，终止原因可区分，执行线程随之退出。
会话结束后其快照仍可查询，直到 `DELETE` 释放；执行线程为守护线程，
会话终止/回收后线程保证退出。

## 调试接口

基础路径：`/api/debug/sessions`

### 创建会话并启动执行

`POST /api/debug/sessions`

```json
{
  "source": "let x = 1\nlet y = x + 1\nprint(y)\n",
  "timeoutMs": 10000,
  "maxSteps": 100000,
  "breakpoints": [{"line": 2}, {"line": 3, "condition": "y > 1"}]
}
```

`timeoutMs`、`maxSteps`、`breakpoints` 均可省略。脚本语法错误返回
`400`（响应体含 `line`）。响应为会话快照（见下）。

### 查询会话状态

- `GET /api/debug/sessions` — 所有会话快照
- `GET /api/debug/sessions/{id}` — 单个会话快照；不存在返回 `404`

快照结构：

```json
{
  "sessionId": "1a2b3c4d",
  "state": "PAUSED",
  "pauseReason": "BREAKPOINT",
  "terminationReason": "NONE",
  "currentLine": 2,
  "callStack": [
    {"functionName": "<script>", "line": 2, "locals": {"x": 1}}
  ],
  "output": [],
  "error": null,
  "conditionError": null,
  "executedSteps": 1
}
```

- `state`：`RUNNING` / `PAUSED` / `ENDED`
- `pauseReason`（暂停时）：`BREAKPOINT` / `STEP` / `PAUSE` / `EXCEPTION`
- `terminationReason`（结束时）：`COMPLETED` / `ERROR` / `TIMEOUT` /
  `STEP_LIMIT` / `USER`
- `callStack`：最外帧在前；每帧含函数名、当前行与该帧局部变量快照。
  快照在暂停时刻采集，与执行状态一致
- `error`：脚本运行时错误（`message` 与 `line`），异常暂停与 `ERROR`
  终止后均可查询，便于事后检查
- `conditionError`：条件断点求值失败（如引用未定义变量）时的错误信息；
  会话不会因此中止，会在该断点处暂停，可修复断点后继续

### 断点管理

`PUT /api/debug/sessions/{id}/breakpoints` — 整体替换断点列表：

```json
{"breakpoints": [{"line": 5}, {"line": 8, "condition": "i == 3"}]}
```

- 行断点：执行到该行语句前暂停
- 条件断点：命中行时先在当前作用域求值条件，结果为真才暂停；
  求值失败（语法错误、未定义变量、非布尔结果）按上述 `conditionError`
  规则处理，不中止会话

### 执行控制

`POST /api/debug/sessions/{id}/commands/{command}`

| 命令 | 适用状态 | 语义 |
| --- | --- | --- |
| `continue` | `PAUSED` | 继续运行；异常暂停后继续会以 `ERROR` 结束（失败语句无法重启） |
| `pause` | `RUNNING` | 请求在下一条语句前暂停 |
| `step-into` | `PAUSED`（非异常） | 执行到下一条语句，函数调用则进入被调用函数 |
| `step-over` | `PAUSED`（非异常） | 执行到下一条同层（或更浅）语句，不进入函数 |
| `step-out` | `PAUSED`（非异常） | 执行到当前函数返回后的第一条语句 |
| `terminate` | 任意非结束状态 | 以 `USER` 原因终止会话 |

状态不允许的命令返回 `409`；未知命令返回 `400`。
暂停或单步后，快照返回当前语句位置、调用栈与局部变量。

### 异常中断

脚本运行时错误（未定义变量、类型错误、除零等）会使会话以
`EXCEPTION` 原因暂停，快照中包含错误位置、失败原因与出错时的完整
调用栈。此时可查询变量与调用栈，然后：

- `continue` — 会话以 `ERROR` 终止，错误现场保留供事后检查
- `terminate` — 会话以 `USER` 终止

### 释放会话

`DELETE /api/debug/sessions/{id}` — 终止（若仍在运行）并移除会话，
返回 `204`。执行线程与全部状态被回收。

## 并发与隔离

每个会话拥有独立的执行线程、解释器实例、作用域链、输出缓冲与断点
配置，会话间互不影响。会话线程名为 `debug-session-{id}`（守护线程）。

## 代码结构

```
src/main/java/com/github/guoyyit/scriptdebuggerjava/
├── lang/       词法（Lexer）、语法（Parser）、AST
├── runtime/    树遍历解释器：Value / Environment / Interpreter / 调试钩子
├── debug/      DebugSession 状态机、断点、快照、SessionManager
└── api/        REST 控制器与 DTO、异常映射
```

测试：`DebugSessionTests`（断点、条件断点、单步语义、异常中断、超时与
步数上限、并发会话、资源回收）与 `DebugApiTests`（REST 端到端）。
