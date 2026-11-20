# spec.md · ChatBI Agent 自然语言查库出图出数(需求规格)

- 版本:1.0 · 日期:2026-11-20 · 分支:`feat/2000005-chatbi-agent`
- 需求来源:平台需求 #2000005「开发一个 ChatBI agent:自然语言查库出图出数」
- 数据源:`platform-seekdb-1` 容器 · MySQL 协议 · `5.7.25-OceanBase seekdb-v1.4.0.0` · 库 `ainative_v2`
- 配套:`deliverables/chatbi/plan.md`(设计与实施计划)

---

## 1. 问题与目标

平台自身没有「用自然语言查自己的业务库」的入口。需求方要一个可独立运行的 ChatBI agent:

```
自然语言提问 → LLM 生成 SQL → 只读执行 → 自检重试 → 图表 + 结论
```

硬性要求(取自需求原文「验收标准」):

| 编号 | 验收项 | 可执行判据 |
|---|---|---|
| AC-1 | 端到端出图出数 | `python3 -m chatbi "最近 7 天每天新增需求多少条"` 直接出图出数,无需人工改 SQL |
| AC-2 | 单测全绿 | `chatbi/tests/` ≥5 个用例全绿;SQL 校验器用例必须含注入攻击样例,且全部被拦截 |
| AC-3 | 只读拒写 | 错误账号连库执行 `UPDATE` 必须被拒(验收文字按 **DB 层**落,见 §5.3) |
| AC-4 | 演示可复现 | 3 个真实业务问题端到端跑通,命令写进 README |

## 2. 澄清拍板结论(2026-11-20 需求方逐条确认)

| 编号 | 议题 | 结论 | 理由 |
|---|---|---|---|
| Q1 | MySQL 连接方式 | **A — 自研 MySQL wire protocol 客户端**,纯标准库,支持 `mysql_native_password` | 零第三方依赖是仓库规范(`docs/00-开发规范与流程.md` §4),**不许豁免**。已实测 `default_authentication_plugin=mysql_native_password`,协议前提成立 |
| Q2 | ECharts 怎么进单文件 HTML | **A — 内联 vendor ECharts**,真单文件、离线可看 | CDN 版离网即白屏;演示环境不可依赖外网。已内联 `echarts@5.5.1` 1,030,855 字节 |
| Q3 | 数据隔离 | 默认 `workspace_id=2`(需求所在空间),**可配置** | 库内 11 条工单跨 5 个 workspace,不隔离则 Q1 数字无法解释 |
| Q4 | 只读防线验收 | **两层都测**:A 校验器在发往 DB 前拦注入;B 只读账号 `chatbi_ro` 直连执行 `UPDATE` 被 DB 拒 | 验收文字按 B 落;A 才是产品自己的防线。`chatbi_ro` 认可,密码随机写 `.env` 不入库,`GRANT SELECT ON ainative_v2.*` |
| Q5 | 交付物清单 | **① 全套补齐 01-07**,放 `deliverables/` | 但优先级:代码能跑 + 单测全绿 + 3 个问答真实出图 三项硬验收先行;文档链可压缩篇幅,不可缺项 |
| Q6 | 代码与产物位置 | 代码真身只在仓库根 `chatbi/`;`deliverables/` 放文档副本 + HTML 图表 + `demo-session.md` + 测试报告 | **不放第二份代码**,避免两份漂移 |

默认项(需求方全部认可):分支 `feat/2000005-chatbi-agent`、自检重试上限 3 次、0 行不重试、LLM 端点读取顺序、日志脱敏。

## 3. 范围

### 3.1 做

- **FR-01 自然语言问答主循环**:`python3 -m chatbi "<问题>"` 一条命令走完 生成 SQL → 校验 → 执行 → 自检 → 出图出结论。
- **FR-02 SQL 生成**:LLM 走 Anthropic 兼容端点;提示词注入**实时 schema 上下文**与**表名白名单**。为控制提示词体积,先做一次**表选择**(从紧凑表名清单里挑 ≤6 张),生成阶段只带选中表的字段结构;未选中表只保留表名,字段不进入提示词。
- **FR-03 SQL 校验器(防线 A)**:仅允许单条 `SELECT`;禁 DML/DDL/多语句/注释注入/危险函数;表名白名单实时读 `information_schema`。
- **FR-04 只读执行(防线 B)**:以 `chatbi_ro` 账号连接,DB 层再拒一次写操作。
- **FR-05 自检重试**:三类失败各自带**可读的修正线索**回灌 LLM 重生成——① SQL 被校验器拒绝;② SQL 执行报错;③ **SQL 跑通但结果不对**(如漏 `GROUP BY` 导致结果被折叠成一行、把「执行成功」当成「需求已完成」)。**上限 3 次**;成功但结果 **0 行不重试**,直接出「无数据」结论。
- **FR-06 图表渲染**:单文件 HTML,**内联** ECharts,离线双击即可看。选型:有时间维度+数值 → 折线图;有分类维度+数值 → 柱状图;其余(汇总结果)→ 表格。`id`/`xxx_id` 是标识符不是指标,不画成系列。
- **FR-07 结论生成**:除图表外,给出可读的自然语言结论(数字 + 口径说明 + 数据边界)。
- **FR-08 配置与密钥**:端点读取顺序 `CHATBI_*` > 进程环境 `ANTHROPIC_*` > 兜底读执行器 `runtime.env`;**不硬编码密钥**,日志脱敏。
- **FR-09 单测**:≥5 个用例,含 SQL 注入攻击样例。
- **FR-10 演示记录**:`demo-session.md` 记录 3 个真实问题的 问题 → 生成的 SQL → 行数 → 图表路径 → 结论。

### 3.2 不做(明确划界)

- 不做写操作、不做多表 JOIN 之外的复杂建模(不建视图、不建物化表)。
- 不做 BI 语义层/指标库;口径靠提示词 + 文档约束。
- 不做多轮对话记忆;每次调用是独立的一次问答。
- 不做鉴权/多用户;ChatBI 以本地只读账号运行,面向开发与演示。
- 不改平台前后端任何代码;本次改动只落在 `chatbi/` 与 `deliverables/chatbi/`。
- 不把代码同步进对外基线仓 `CodeFlow`(那是独立的发布动作,不在本次范围)。

## 4. 数据口径规范

口径写死在提示词与文档里,保证结论可解释。**这是本需求最容易出错的地方**:「交付耗时」「成功率」「新增需求」三个词都有多种合理算法,必须先定义再查询。

### 4.1 Q1「最近 7 天每天新增需求多少条」

- 对象:`wi_ticket` 中 `type='REQ'` 的工单(即「需求」,非缺陷/任务)。
- 时间字段:`gmt_create`(创建时间),**不是** `gmt_modified`。
- 区间:`gmt_create >= CURDATE() - INTERVAL 7 DAY`,按 `DATE(gmt_create)` 分组,按日升序。
- 过滤:`workspace_id = 2`(可配置);`deleted_at IS NULL`。
- 补零:缺失的自然日必须补 0,否则折线图会把「没建需求的那天」画成断层。

### 4.2 Q2「哪个数字员工执行任务最多、成功率多少」

- 对象:`rt_run`(每次派发执行)关联 `ag_agent`(数字员工定义)。
- 「执行任务最多」= `rt_run` 记录条数,倒序取首位。
- 「成功率」分母**只算终态**(`SUCCEEDED` + `FAILED`),`PENDING` **排除**并在结论中显式注明。
- 成功率 = `SUCCEEDED / (SUCCEEDED + FAILED)`。
- 过滤:`workspace_id = 2`。
- **已知数据现状**:`rt_run` 共 6 条,agent 1 占 4 条(3 SUCCEEDED / 1 FAILED = 75%),另 2 条 PENDING 属其他 agent。结论需说明样本极小。

### 4.3 Q3「已完成需求的平均交付耗时」

- 「已完成」= 工单流转到 **`category='DONE'`** 的状态节点。`DONE` 状态名**运行时从 `wi_flow_state` 读出**(当前为 `已发布`),不硬编码中文标签。
- 完成时刻 = `wi_journal` 中 `event_type='STATUS_CHANGE'` 且 `to_val` 命中该 DONE 状态名的事件,取**最后一条**的 `gmt_create`。
- 起点 = `wi_ticket.gmt_create`。
- 耗时 = 完成时刻 − 创建时刻,单位小时,`AVG`。
- 过滤:`type='REQ'`、`workspace_id=2`。
- **已知数据现状**:全库仅 **1** 个需求到达过「已发布」,其 3 次状态流转时间戳完全相同(`2026-11-17 02:43:34`)。**平均耗时样本 n=1**,结论必须标注这一点,不得表述为统计结论。

### 4.4 通用口径约束

- 所有查询默认注入 `workspace_id` 过滤。
- **时间口径(极易踩坑)**:库里的时间列(`gmt_create` / `gmt_modified` / `started_at` 等)存的就是 **Asia/Shanghai 本地墙钟时间**,直接用 `DATE(gmt_create)` 取日期,**不得用 `CONVERT_TZ` 做时区平移**——那会把时间整体平移 8 小时,16:00 之后创建的记录会被算到第二天。「今天」用 `CURDATE()`(服务器时区即 +08:00)。
  - 判定依据(实证):`id_user.username` 中的 epoch 后缀 `persona_e2e_1795128081` 对应 UTC `2026-11-19 22:41:21`,渲染成 +08:00 为 `2026-11-20 06:41:21`,与该行 `gmt_create` 逐字吻合。
- 任何结论必须附带**样本量**(n)。n ≤ 3 时结论需显式声明「样本量过小,仅供参考」。

## 5. 安全设计

### 5.1 防线 A — SQL 校验器(应用层,发往 DB **之前**)

校验器是一道**白名单 + 拒绝列表**的双重闸门,任一条不满足即整条 SQL 拒绝:

| 类别 | 规则 |
|---|---|
| 语句类型 | 必须且只能是单条 `SELECT`(可带 `WITH` CTE,但 CTE 内只能是 `SELECT`) |
| 多语句 | 分号后不允许再有内容(`;` 结尾的单个空语句除外) |
| 注释 | 禁止 `--`、`#`、`/* */` 注释(注释是注释注入的主要载体) |
| 关键字 | 禁止 `INSERT/UPDATE/DELETE/REPLACE/DROP/CREATE/ALTER/TRUNCATE/GRANT/REVOKE/CALL/LOAD/OUTFILE/DUMPFILE/SET/USE/LOCK/UNLOCK/HANDLER/PREPARE/EXECUTE` 等 |
| 危险函数 | 禁止 `LOAD_FILE/INTO OUTFILE/INTO DUMPFILE/SLEEP/BENCHMARK/EXTRACTVALUE/UPDATEXML/@@` 等 |
| 表名 | 必须命中 `information_schema` 实时读取的白名单;白名单外的表一律拒绝 |
| 列/表名 | 仅允许 `[A-Za-z0-9_]`,拒绝反引号包裹以外的怪字符 |
| 长度 | SQL 长度上限(防超长注入) |

白名单**每次会话实时读 `information_schema.tables`**,不写死表名清单——需求原文明确要求。

### 5.2 防线 B — 只读账号(数据库层)

- 账号 `chatbi_ro`,`IDENTIFIED WITH mysql_native_password BY '<随机>'`。
- `GRANT SELECT ON ainative_v2.* TO 'chatbi_ro'@'%'` — **只有 SELECT**,无 INSERT/UPDATE/DELETE/GRANT。
- 密码随机生成,写入 `chatbi/.env`(在 `.gitignore` 中),**不入库、不入 git、不进文档**。
- 聊天记录与日志中该口令一律脱敏。

### 5.3 验收口径(按 Q4 拍板)

两层**都测**,但**验收文字按 B 层落**:

- **A 层测试**:以高权账号构造 `UPDATE`/`DROP` 等语句,证明被**校验器**拦在发往 DB 之前(不产生任何 DB 侧痕迹)。
- **B 层测试**:`chatbi_ro` 直连执行 `UPDATE` → **MySQL 返回权限拒绝错误**(`1044 Access denied` / `1142 UPDATE command denied`),证明权限收口生效。

### 5.4 其他安全约束

- 密钥零硬编码:LLM 端点、令牌、DB 口令全部来自环境/`.env`。
- 日志脱敏:口令、令牌、完整的 `Authorization` 头一律不打印;SQL 打印时截断到合理长度。
- HTML 输出做转义,防止表名/单元格内容注入 `<script>`。

## 6. 非功能约束

| 项 | 约束 |
|---|---|
| 运行时依赖 | **零第三方**。Python 3.11+ 标准库(`socket/struct/hashlib/json/urllib/argparse/datetime/os/re`) |
| 测试依赖 | `pytest` 允许(测试不属运行时) |
| MySQL 协议 | 自研客户端,`socket` + `struct` + `hashlib`;支持 `mysql_native_password`(及 OceanBase 同算法别名 `ob_native_password`) |
| LLM 协议 | Anthropic 兼容端点 `POST {base}/v1/messages`,`urllib.request` |
| 图表 | 单文件 HTML,**内联** ECharts;**离线可看** |
| Python 版本 | 宿主实测 3.14.0;代码兼容 3.11+ |

## 7. 交付物清单

### 7.1 代码(真身,仓库根 `chatbi/`)

```
chatbi/
  __init__.py
  __main__.py          # CLI 入口:python3 -m chatbi "<问题>"
  config.py            # 配置加载(端点读取顺序)、日志脱敏
  errors.py            # 异常类型
  mysql_client.py      # 自研 MySQL wire protocol 客户端
  sql_guard.py         # SQL 校验器(防线 A)
  llm.py               # Anthropic 兼容 LLM 客户端
  chart.py             # HTML 图表渲染(内联 ECharts)
  agent.py             # 主循环:生成→校验→执行→自检→出图→结论
  vendor/echarts.min.js
  tests/               # ≥5 个单测
  README.md
  demo-session.md
  .env                 # 只读账号口令(不入库)
  .env.example         # 配置样例(可入库)
```

### 7.2 文档与产物(`deliverables/chatbi/`)

`spec.md`、`plan.md`、`01-立项说明.md` … `07-缺陷修复记录.md`(七件套)、`demo-session.md`、`README.md`(副本)、`测试报告.md`、`charts/*.html`(真实生成的图表)。

> 说明:仓库根 `deliverables/` 目录现存 10020 需求的未入库文档。直接覆盖会永久丢失(该目录从未进过 git),故 ChatBI 产物统一落在 `deliverables/chatbi/`,10020 文件原地不动。

## 8. 已知约束与风险

| 编号 | 风险 | 应对 |
|---|---|---|
| R-1 | **数据量极薄**:`wi_ticket` 11 条、`rt_run` 6 条、`wi_journal` 38 条、`ag_agent` 7 个 | 结论必须附样本量;n ≤ 3 显式声明「样本量过小」 |
| R-2 | **Q3 平均耗时 n=1** | 结论标注 n=1,不表述为统计结论;文档 §4.3 写明口径 |
| R-3 | 自研 MySQL 客户端是最大不确定性 | 协议实现逐包对照;先以 `mysql` CLI 交叉验证同一 SQL 结果一致;失败即报,不降级换方案(Q1 拍板:不许自己换 B) |
| R-4 | LLM 端点 `127.0.0.1:15723` 是本机代理,代理不在线则不可用 | 明确报错退出并提示,不静默降级;端点可配置指向任意 Anthropic 兼容服务 |
| R-5 | 注入样本可能被 LLM「写对了」而掩盖 | 单测直接对**校验器**喂攻击样本,不经过 LLM,保证可重复 |
| R-6 | `.env` 含口令,误提交即泄露 | `.gitignore` 覆盖 `chatbi/.env`;提交前 `git status` 核对 |

## 9. 验收清单(交付前逐项过)

- [ ] AC-1 `python3 -m chatbi "最近 7 天每天新增需求多少条"` 出图出数
- [ ] AC-2 `pytest chatbi/tests/` 全绿,≥5 用例,含注入样例且全部被拦
- [ ] AC-3a 校验器拦下 `UPDATE`/`DROP`/`多语句`/注释注入,未触达 DB
- [ ] AC-3b `chatbi_ro` 直连 `UPDATE` 被 DB 拒绝
- [ ] AC-4 `demo-session.md` 3 个问答命令可复现,README 写明
- [ ] 3 个图表 HTML 生成成功,离线双击可看(内联 ECharts)
- [ ] 单测与端到端全流程**零第三方运行时依赖**
- [ ] `chatbi/.env` 未进 git;日志无明文口令
- [ ] 文档链 01-07 无缺项
