# plan.md · ChatBI Agent 自然语言查库出图出数(设计与实施计划)

- 版本:1.0 · 日期:2026-11-20 · 分支:`feat/2000005-chatbi-agent`
- 配套:`deliverables/chatbi/spec.md`(需求规格)
- 数据源:`platform-seekdb-1` / MySQL 协议 / `ainative_v2` / `5.7.25-OceanBase seekdb-v1.4.0.0`

---

## 1. 架构总览

```
   python3 -m chatbi "最近 7 天每天新增需求多少条"
                          │
                          ▼
        ┌──────────────────────────────────────────┐
        │                 agent.py                  │
        │  提问 → ①生成SQL → ②校验 → ③执行 → ④自检  │
        └───────┬──────────┬──────────┬────────────┘
                │          │          │
   ┌────────────▼───┐  ┌───▼────────┐ │  ┌──────────────┐
   │      llm.py    │  │ sql_guard  │ │  │ mysql_client │
   │ Anthropic 兼容 │  │  防线 A     │ │  │ 自研 wire 协议│
   │ 端点读取顺序:  │  │ 白名单实时 │ │  │  mysql_      │
   │ CHATBI_* >     │  │ 读        │ │  │  native_     │
   │ ANTHROPIC_* >  │  │ information│ │  │  password    │
   │ runtime.env    │  │ _schema    │ │  └──────┬───────┘
   └───────┬────────┘  └───┬────────┘ │         │
           │               │          │         ▼
           │        ┌──────▼───────┐  │  ┌──────────────┐
           │        │  被拒 / 报错  │  │  │ 防线 B: 只读  │
           │        │  带错误回灌   │  │  │ chatbi_ro    │
           │        │  上限 3 次    │  │  │ GRANT SELECT │
           │        └──────────────┘  │  │ 仅 SELECT    │
           │                          │  └──────────────┘
           ▼                          ▼
   ┌───────────────────────────────────────────┐
   │  ⑤ 图表 + 结论                            │
   │  chart.py: 内联 ECharts → 单文件离线 HTML  │
   │  行数少→表格 │ 时序→折线 │ 分类→柱状        │
   └───────────────────────────────────────────┘
```

**数据流一句话**:LLM 拿着「实时 schema 上下文 + 表名白名单 + 口径约束」生成 SQL → 校验器在**发往 DB 之前**逐条过闸 → 只读账号执行 → 结果判定图表形态 → 出图出结论。

## 2. 模块职责与文件清单

| 模块 | 职责 | 关键设计 |
|---|---|---|
| `config.py` | 配置加载、日志脱敏 | 端点读取顺序 `CHATBI_*` > 进程 `ANTHROPIC_*` > 兜底解析 `runtime.env`;口令/令牌一律不出现在日志 |
| `errors.py` | 异常类型 | `ChatBIError` / `ConfigError` / `LLMError` / `SQLRejected` / `MySQLError` |
| `mysql_client.py` | MySQL wire protocol 客户端 | 纯标准库;握手 → `mysql_native_password` 鉴权 → `COM_QUERY` → 结果集解析 |
| `sql_guard.py` | SQL 校验器(防线 A) | 白名单 + 拒绝列表;表名白名单实时读 `information_schema` |
| `llm.py` | Anthropic 兼容客户端 | `urllib.request`;提示词含 schema/白名单/口径/示例 |
| `chart.py` | HTML 图表渲染 | 读 `vendor/echarts.min.js` 内联;HTML 转义 |
| `agent.py` | 主循环 | 生成→校验→执行→自检重试→图表+结论 |
| `__main__.py` | CLI | `python3 -m chatbi "<问题>"`,可选 `--workspace/--out/--sql-only` |

## 3. 关键技术实现细节

### 3.1 自研 MySQL 客户端(`mysql_client.py`)

**协议流程**:

```
TCP connect
  → 读 Initial Handshake (protocol v10)
  → 算 native password 摘要,发 HandshakeResponse41
  → 读 OK / ERR / AuthSwitchRequest
  → COM_QUERY(sql)
  → OK | ERR | 结果集(column count → N×ColumnDef → EOF → rows → EOF)
```

**能力位**(`client_flags`):

| 位 | 名称 | 用途 |
|---|---|---|
| 0x00000001 | `CLIENT_LONG_PASSWORD` | 长口令 |
| 0x00000004 | `CLIENT_LONG_FLAG` | 长标志 |
| 0x00000008 | `CLIENT_CONNECT_WITH_DB` | 指定库 |
| 0x00000200 | `CLIENT_PROTOCOL_41` | 4.1 协议(**必需**) |
| 0x00002000 | `CLIENT_TRANSACTIONS` | 事务 |
| 0x00008000 | `CLIENT_SECURE_CONNECTION` | 安全连接 |
| 0x00080000 | `CLIENT_PLUGIN_AUTH` | 插件鉴权 |

不启用 `CLIENT_DEPRECATE_EOF`,按经典 EOF 包处理,减少分支。

**`mysql_native_password` 摘要**:

```
stage1 = SHA1(password)
stage2 = SHA1(stage1)
token  = SHA1(scramble ‖ stage2) XOR stage1        # 20 字节
```

`ob_native_password`(OceanBase 同算法别名)走同一条路径;其他插件报明确错误。

**长度编码整数**:`<0xFB` 1 字节;`0xFC` 2 字节;`0xFD` 3 字节;`0xFE` 8 字节;行数据中 `0xFB` 表示 `NULL`。

**能力边界**:只实现读路径需要的能力。不支持 `COM_QUIT` 之外的连接管理、不支持 TLS、不支持预处理语句、不支持多结果集。够用且可测。

**交叉验证**:实现完成后,用同一 SQL 分别经 `mysql` CLI 与自研客户端执行,比对行数与首行内容一致。

### 3.2 SQL 校验器(`sql_guard.py`)

判定顺序(短路,任一步失败即抛 `SQLRejected` 并给出**人类可读的中文原因**——原因会回灌给 LLM 做重试):

1. 去首尾空白后为空 → 拒绝
2. 长度超限 → 拒绝
3. 含注释标记 `--` / `#` / `/*` → 拒绝
4. **多语句**:按 `;` 切分,去掉末尾空段后若段数 > 1 → 拒绝
5. 关键字扫描:命中拒绝清单 → 拒绝(大小写不敏感,单词边界匹配)
6. 危险函数 / `INTO OUTFILE` / `INTO DUMPFILE` / `@@` → 拒绝
7. 首关键字必须为 `SELECT` 或 `WITH`(`WITH` 需额外确认内部只有 `SELECT`)
8. **表名白名单**:用正则抽取 `FROM` / `JOIN` / `INTO`(禁)/ `UPDATE`(禁) 后的标识符,与 `information_schema.tables` 实时白名单比对;白名单外的表 → 拒绝
9. 标识符合法性:表名/列名只允许 `[A-Za-z0-9_]`,反引号内允许同样字符集

**白名单实时性**:每次会话连接后读一次 `information_schema.tables WHERE table_schema='ainative_v2'`,存为 `frozenset`;校验器持有该集合。**不写死表名**——需求原文明确要求。

**为什么禁注释**:`--` 之后的内容在部分驱动/模式下会被当作注释吞掉,`/* */` 可与前置语句拼接,两者都是注释注入的载体。直接禁掉是最省事且最严的口径。

### 3.3 LLM 接入(`llm.py`)

- 端点:读取顺序 `CHATBI_ANTHROPIC_BASE_URL` → `ANTHROPIC_BASE_URL` → 解析执行器 `runtime.env` 的 `ANTHROPIC_BASE_URL`;模型同理(`CHATBI_ANTHROPIC_MODEL` → `ANTHROPIC_MODEL` → `runtime.env`);令牌同理。
- `runtime.env` 解析:简单 `KEY=VALUE` 逐行解析,**只取白名单键**,不整体加载(避免把执行器凭据带进内存/日志)。
- 请求:`POST {base}/v1/messages`,`{"model":..., "max_tokens":..., "messages":[...], "system":...}`。
- 响应:从 `content[]` 里取 `type=="text"` 的文本。
- **不硬编码密钥**;`Authorization` 头不打印;异常信息里若含令牌要替换为 `***`。

**提示词结构**:

```
system:
  角色:你是 CodeFlow 平台的 BI 分析助手。
  数据源:MySQL 协议库 ainative_v2(只读)。
  铁律:只输出单条 SELECT;禁注释;禁 DML/DDL;表名只能用白名单里的。
  可用表(白名单,实时读自 information_schema):…
  表结构:表名 → 列名(类型,注释)…
  口径规范:…
  输出格式:先给 ```sql 代码块,再给一行结论。
user:
  问题:…
```

### 3.4 图表渲染(`chart.py`)

**选型规则**(确定性,不靠 LLM 猜):

| 条件 | 形态 |
|---|---|
| 行数 ≤ `CHATBI_TABLE_MAX_ROWS`(默认 20) | **表格**(HTML `<table>`) |
| 结果含日期/时间列(列名含 `date`/`day`/`time`/`dt`/`month`/`日期`/`时间` 或列为 DATE/DATETIME 类型) | **折线图** |
| 其余(单列聚合值、分类维度) | **柱状图** |

- ECharts 以 `vendor/echarts.min.js` **内联**进 `<script>` 标签 → 单文件、离线可看。
- 单元格内容、列名、表名全部 HTML 转义,防注入。
- 图表落盘路径:`deliverables/chatbi/charts/<序号>-<slug>.html`。
- 同时输出一个**数据摘要**(行数、列名、样本量)供结论生成使用。

### 3.5 自检重试(`agent.py`)

```
for attempt in 1..3:
    sql = llm.generate(question, schema, whitelist, last_error)
    try:
        sql = guard.check(sql)              # 防线 A
    except SQLRejected as e:
        last_error = e.reason; continue     # 带着原因回灌重生成
    try:
        rows = db.query(sql)                # 防线 B(只读账号)
    except MySQLError as e:
        last_error = str(e); continue
    if rows:
        break                             # 有数据 → 正常出图
    else:
        break                             # 0 行 → 不重试,直接出「无数据」结论
```

- 重试上限 **3 次**,超限抛错并打印**最后一次 SQL**与全部拒绝原因(便于人工接手)。
- **0 行不重试**(拍板默认项):空结果往往是口径写对了但数据没有,重试只会空转。

### 3.6 只读账号与 `.env`

```sql
CREATE USER 'chatbi_ro'@'%' IDENTIFIED WITH mysql_native_password BY '<随机>';
GRANT SELECT ON ainative_v2.* TO 'chatbi_ro'@'%';
```

- 随机口令由 `secrets.token_urlsafe(24)` 生成,写入 `chatbi/.env`(**不入库**,`.gitignore` 覆盖)。
- `chatbi/.env.example` 入库,只写键名与说明,不含真实值。
- `config.py` 解析 `.env` 时**只取白名单键**。

## 4. 实施步骤与完成门

| 步骤 | 内容 | 完成门 |
|---|---|---|
| S1 | 分支 + 骨架 + `config.py`/`errors.py` | `python3 -m chatbi --help` 可跑;端点读取顺序有单测 |
| S2 | `mysql_client.py` 自研协议 | 能连 seekdb 并 `SELECT 1`;与 `mysql` CLI 交叉验证结果一致 |
| S3 | `sql_guard.py` 校验器 | 注入样例全部被拦;白名单实时读 `information_schema` 生效 |
| S4 | `chatbi_ro` 只读账号 | B 层:`chatbi_ro` 执行 `UPDATE` 被 DB 拒;A 层:校验器拦下同一语句 |
| S5 | `llm.py` 接入 | 能拿到一次真实 SQL 生成;密钥不落日志 |
| S6 | `chart.py` 图表 | 三种形态各生成一个 HTML;离线可看 |
| S7 | `agent.py` 主循环 | `python3 -m chatbi "<问题>"` 端到端出图出数 |
| S8 | `tests/` 单测 | ≥5 用例全绿,含注入样例 |
| S9 | 3 个演示问答真实跑通 | 3 张真实图表 + `demo-session.md` |
| S10 | 文档链 01-07 + 测试报告 | 无缺项 |
| S11 | 平台侧写回澄清结论 + 撤销 MCP 令牌 | 平台可见结论;令牌 1000003 已撤销 |
| S12 | 提交 | 只含 `chatbi/` 与 `deliverables/chatbi/` |

## 5. 测试策略

| 文件 | 覆盖 |
|---|---|
| `tests/test_sql_guard.py` | **注入攻击样例**(多语句 `--` 注释、`/* */` 注释、`;` 分号拼接、`UNION` 越权表、`INSERT/UPDATE/DROP/DELETE`、`INTO OUTFILE`、未知表名、`@@` 系统变量、危险函数) + 合法 SQL 放行 |
| `tests/test_mysql_client.py` | 长度编码整数编解码往返、native password 摘要向量、包序列号、`mysql_native_password` 插件名解析 |
| `tests/test_config.py` | 端点读取顺序(四级优先级)、`.env` 白名单解析、日志脱敏 |
| `tests/test_chart.py` | 选型规则(表格/折线/柱状)、HTML 转义、内联 ECharts 存在 |
| `tests/test_agent.py` | 重试上限 3 次、0 行不重试、拒绝原因回灌 |

**注入样例必须直喂校验器**,不经过 LLM——保证可重复、可回归(见 spec R-5)。

## 6. 平台侧动作

| 动作 | 方式 | 时机 |
|---|---|---|
| 写回澄清结论 | `PUT /api/workitems/2000005/clarification` | 交付完成后 |
| 留痕评论 | `ainative.add_workitem_comment`(MCP) | 同上 |
| 撤销 MCP 令牌 | `DELETE /api/mcp/tokens/1000003` | **最后一步,写回之后** |

## 7. 提交纪律

- 分支:`feat/2000005-chatbi-agent`。
- **只 add `chatbi/` 与 `deliverables/chatbi/`**,不带入 10020 的未提交改动(`platform/`、`runtime/`、`scripts/` 等一律不碰)。
- `chatbi/.env` 由 `.gitignore` 拦截,提交前用 `git status` 核对。
- 交付物目录内 10020 文档不纳入本次提交(原地保留)。
