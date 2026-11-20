# ChatBI · 自然语言查库出图出数

用自然语言提问 → LLM 生成 SQL → 只读执行 → 自检重试 → 图表 + 结论。

数据源是 CodeFlow 平台自己的业务库(seekdb,MySQL 协议,库 `ainative_v2`)。
**零第三方运行时依赖**,只用 Python 3.11+ 标准库——包括 MySQL 协议客户端和图表渲染。

---

## 快速开始

```bash
cd /Users/summer/CodeFlow-private          # 必须在仓库根,chatbi 才是可导入的包
python3 -m chatbi "最近 7 天每天新增需求多少条"
```

输出:

```
问题:最近 7 天每天新增需求多少条
SQL:WITH RECURSIVE ...
行数:7
图表形态:line
图表文件:.../deliverables/chatbi/charts/01-最近-7-天每天新增需求多少条.html
尝试次数:1
结论:
最近 7 天(共 7 行)每天新增需求数依次为:……
```

常用参数:

| 参数 | 说明 |
|---|---|
| `--workspace N` | 覆盖数据空间 ID(默认取配置,默认 2) |
| `--out DIR` | 覆盖产物输出目录 |
| `--json` | 以 JSON 输出结果,便于脚本消费 |

## 三个演示问题(可复现)

```bash
cd /Users/summer/CodeFlow-private

python3 -m chatbi "最近 7 天每天新增需求多少条"        # → 时序折线图
python3 -m chatbi "哪个数字员工执行任务最多、成功率多少"  # → 分类柱状图
python3 -m chatbi "已完成需求的平均交付耗时"             # → 单行表格
```

每次运行会在 `deliverables/chatbi/charts/` 下生成一张**单文件 HTML**
(ECharts 已内联,断网双击也能看)。完整演示记录见 `chatbi/demo-session.md`。

## 配置

配置读取顺序(自高到低):

1. 进程环境 `CHATBI_*`(如 `CHATBI_ANTHROPIC_BASE_URL`)
2. 进程环境 `ANTHROPIC_*`(`ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_MODEL`)
3. `chatbi/.env`
4. 执行器 `runtime.env`(兜底,只读取白名单键)

| 键 | 默认 | 说明 |
|---|---|---|
| `CHATBI_DB_HOST` | `127.0.0.1` | 数据库地址 |
| `CHATBI_DB_PORT` | `33060` | seekdb 宿主端口(容器内 2881) |
| `CHATBI_DB_USER` | `chatbi_ro` | **只读**账号 |
| `CHATBI_DB_PASSWORD` | (无) | 只读账号口令,写在 `chatbi/.env`,**不入库** |
| `CHATBI_DB_NAME` | `ainative_v2` | 库名 |
| `CHATBI_WORKSPACE_ID` | `2` | 数据空间隔离 |
| `CHATBI_ANTHROPIC_BASE_URL` | 取自 `ANTHROPIC_BASE_URL` | Anthropic 兼容端点 |
| `CHATBI_ANTHROPIC_AUTH_TOKEN` | 取自 `ANTHROPIC_AUTH_TOKEN` | 令牌,**不硬编码** |
| `CHATBI_ANTHROPIC_MODEL` | 取自 `ANTHROPIC_MODEL` | 模型名 |
| `CHATBI_ANTHROPIC_MAX_TOKENS` | `32768` | 该端点的模型会输出扩展思考,预算过小会被截断 |
| `CHATBI_TABLE_MAX_ROWS` | `20` | 小结果集出表格的阈值 |
| `CHATBI_MAX_RETRIES` | `3` | 自检重试上限 |

`.env` 只读取白名单键,执行器凭据不会被整体载入内存;口令与令牌在日志里一律脱敏。

## 架构一页图

```
python3 -m chatbi "<问题>"
        │
        ▼
  ┌───────────────────────────────────────────────┐
  │ agent.py  ①生成 → ②校验 → ③执行 → ④自检 → ⑤出图 │
  └─┬─────────┬──────────┬───────────┬──────────┬──┘
    │         │          │           │          │
 llm.py   sql_guard.py  mysql_     ④自检      chart.py
Anthropic  防线A        client.py   LLM复核    内联ECharts
兼容端点   白名单+拒绝表  自研wire   不过则回灌  单文件HTML
                     防线B:只读账号
```

| 模块 | 职责 |
|---|---|
| `agent.py` | 主循环、提示词、自检重试、结论生成 |
| `sql_guard.py` | **防线 A**:仅允许单条 SELECT;禁 DML/DDL/多语句/注释/危险函数;表名白名单实时读 `information_schema` |
| `mysql_client.py` | 自研 MySQL wire protocol 客户端(`socket`+`struct`+`hashlib`),支持 `mysql_native_password` |
| `llm.py` | Anthropic 兼容端点(`urllib.request`) |
| `chart.py` | 单文件 HTML 渲染,ECharts 内联,单元格转义 |
| `config.py` | 配置读取顺序、日志脱敏 |

### 自检重试

SQL 被校验器拒绝 → 带**中文拒绝原因**回灌 LLM 重写;SQL 执行报错 → 带数据库错误回灌重写;
SQL 跑通但**结果不对**(例如用了聚合却漏了 `GROUP BY`,结果被折叠成一行)→ 自检拦下,带问题描述回灌重写。
**上限 3 次**;**0 行不重试**,直接出「无数据」结论。

### 图表选型

1. 含日期/时间列且有数值列 → **折线图**(时序优先于「行数少」,否则「最近 7 天每天…」只会出表格)
2. 行数 ≤ `CHATBI_TABLE_MAX_ROWS` → **表格**
3. 含分类列且有数值列 → **柱状图**
4. 其余 → **表格**

## 只读:两道防线

| 层 | 手段 | 证据 |
|---|---|---|
| **A 应用层** | SQL 校验器在**发往 DB 之前**拦掉一切非只读语句 | `chatbi/tests/test_sql_guard.py` 里 30+ 条注入样例全部被拦 |
| **B 数据库层** | 账号 `chatbi_ro` 只授 `GRANT SELECT ON ainative_v2.*` | 直连执行 `UPDATE` → MySQL 报 `1142 UPDATE command denied` |

`chatbi_ro` 账号的创建(口令随机生成,请自行替换):

```sql
CREATE USER 'chatbi_ro'@'%' IDENTIFIED WITH mysql_native_password BY '<随机口令>';
GRANT SELECT ON ainative_v2.* TO 'chatbi_ro'@'%';
```

## 测试

```bash
cd /Users/summer/CodeFlow-private
python3 -m pytest chatbi/tests/ -v
```

覆盖:SQL 校验器(含注入攻击样例)、MySQL 协议编解码、配置读取顺序与脱敏、
图表选型与转义、主循环的重试/自检/0 行行为。

## 依赖

- 运行时:**无第三方依赖**。Python 3.11+ 标准库。
- 测试:`pytest`(测试依赖不属运行时)。
- 图表:ECharts 5.5.1 **已 vendor** 在 `chatbi/vendor/echarts.min.js`,不联网。

## 目录

```
chatbi/
  __init__.py  __main__.py     # 包与 CLI 入口
  agent.py  sql_guard.py       # 主循环、SQL 校验器
  mysql_client.py  llm.py      # MySQL 协议客户端、LLM 客户端
  chart.py  config.py  errors.py
  vendor/echarts.min.js        # 内联的 ECharts
  tests/                       # pytest 用例
  demo-session.md              # 3 个问答的端到端演示记录
  .env                         # 只读账号口令(不入库)
  .env.example                 # 配置样例
```
