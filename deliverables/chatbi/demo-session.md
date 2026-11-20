# Demo Session · ChatBI Agent 端到端实录

- 日期:2026-11-20 · 分支:`feat/2000005-chatbi-agent`
- 数据源:`chatbi_ro@127.0.0.1:33060/ainative_v2`(OceanBase seekdb,MySQL 协议)
- 数据空间:`workspace_id = 2` · LLM:Anthropic 兼容端点(密钥自 `runtime.env` 读,不硬编码)
- 配套:`06-测试报告.md`(自动化测试)· `07-缺陷修复记录.md`(缺陷闭环)

三问均以 `python3 -m chatbi "<问题>"` 单命令跑完「提问 → 选表 → 生成 SQL → 只读校验 → 执行 → 自检 → 出图 → 结论」全链路,无人工干预。下列 SQL / 行数 / 图表路径均取自**落盘产物**(`deliverables/chatbi/charts/`),可逐一复核。

---

## Q1 · 最近 7 天每天新增需求多少条

**命令**

```bash
python3 -m chatbi "最近 7 天每天新增需求多少条"
```

**SQL**(实际执行)

```sql
WITH RECURSIVE date_series AS (
    SELECT CURDATE() AS stat_date
    UNION ALL
    SELECT DATE_SUB(stat_date, INTERVAL 1 DAY)
    FROM date_series
    WHERE stat_date > DATE_SUB(CURDATE(), INTERVAL 6 DAY)
)
SELECT
    ds.stat_date,
    COUNT(wt.id) AS new_req_count
FROM date_series ds
LEFT JOIN wi_ticket wt
    ON wt.workspace_id = 2
   AND wt.type = 'REQ'
   AND wt.gmt_create >= ds.stat_date
   AND wt.gmt_create < DATE_ADD(ds.stat_date, INTERVAL 1 DAY)
GROUP BY ds.stat_date
ORDER BY ds.stat_date
```

**行数**:7

**图表路径**:`deliverables/chatbi/charts/01-最近-7-天每天新增需求多少条.html`(折线图)

**数据**

| stat_date | new_req_count |
|---|---:|
| 2026-11-14 | 0 |
| 2026-11-15 | 0 |
| 2026-11-16 | 0 |
| 2026-11-17 | 5 |
| 2026-11-18 | 0 |
| 2026-11-19 | 0 |
| 2026-11-20 | 3 |

**结论**

> 查询结果共 7 行,覆盖 CURDATE() 往前推 6 天至今天的 7 个自然日,新增需求数依次为 0、0、0、5、0、0、3。SQL 按日期分组并补齐无数据的日期,且 `workspace_id=2`、`type='REQ'` 的需求口径及本地墙钟时间口径均符合问题要求。

**要点**
- 递归 CTE 造出完整 7 天日期序列再 `LEFT JOIN`,没有数据的日子补 0 —— 否则折线图会缺点。
- 用 `gmt_create >= 当日 00:00 AND < 次日 00:00` 卡区间,而非 `DATE(gmt_create) = ?`,避免时间列上套函数导致索引失效。
- 口径:需求 = `wi_ticket.type='REQ'`;时间列存的就是 Asia/Shanghai 本地墙钟时间,**未**使用 `CONVERT_TZ` 平移。

---

## Q2 · 哪个数字员工执行任务最多、成功率多少

**命令**

```bash
python3 -m chatbi "哪个数字员工执行任务最多、成功率多少"
```

**SQL**(实际执行)

```sql
SELECT
    a.id AS agent_id,
    a.name AS agent_name,
    COUNT(r.id) AS execution_count,
    SUM(CASE WHEN r.status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded_count,
    SUM(CASE WHEN r.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
    SUM(CASE WHEN r.status = 'PENDING' THEN 1 ELSE 0 END) AS pending_count,
    ROUND(
        100.0 * SUM(CASE WHEN r.status = 'SUCCEEDED' THEN 1 ELSE 0 END)
        / NULLIF(
            SUM(CASE WHEN r.status IN ('SUCCEEDED', 'FAILED') THEN 1 ELSE 0 END),
            0
        ),
        2
    ) AS success_rate_pct
FROM ag_agent AS a
JOIN rt_run AS r
    ON r.agent_id = a.id
   AND r.workspace_id = 2
WHERE a.workspace_id = 2
GROUP BY a.id, a.name
ORDER BY execution_count DESC, a.id
```

**行数**:1

**图表路径**:`deliverables/chatbi/charts/01-哪个数字员工执行任务最多-成功率多少.html`(柱状图)

**数据**

| agent_id | agent_name | execution_count | succeeded_count | failed_count | pending_count | success_rate_pct |
|---|---|---:|---:|---:|---:|---:|
| 1 | 前后端全栈开发 | 4 | 3 | 1 | 0 | 75.00 |

**结论**

> 共 1 行:数字员工「前后端全栈开发」(`agent_id=1`)执行任务最多,共 4 次执行记录,其中成功 3 次、失败 1 次、待定 0 次;按成功率口径(SUCCEEDED ÷ (SUCCEEDED + FAILED),PENDING 不计入分母)成功率 **75.00%**。结果集共 1 行、仅 4 条执行记录,样本量过小,结论仅供参考,不能当作统计结论。

**要点**
- 成功率分母用 `NULLIF(...,0)` 兜底,避免零终态任务时除零报错。
- `PENDING` 单独计列、**不进分母**,并在结论里说明排除量(本次为 0 条)。
- `ORDER BY execution_count DESC` 返回全部候选对象而不是 `LIMIT 1` —— 折叠成一行就画不出图,第一名由结论点出。
- 注意:本问的「任务」= `rt_run` 的一行(一次执行)。若按「需求是否流转到 DONE 状态」计成功,同一批数据会得到不同的成功数——该口径已在澄清结论中固化。

---

## Q3 · 已完成需求的平均交付耗时

**命令**

```bash
python3 -m chatbi "已完成需求的平均交付耗时"
```

**SQL**(实际执行)

```sql
WITH done_completion AS (
    SELECT
        ticket_id,
        MAX(gmt_create) AS completed_at
    FROM wi_journal
    WHERE workspace_id = 2
      AND event_type = 'STATUS_CHANGE'
      AND to_val IN ('已发布', '已关闭', '已修复')
    GROUP BY ticket_id
),
completed_requirements AS (
    SELECT
        t.id,
        t.workspace_id,
        TIMESTAMPDIFF(SECOND, t.gmt_create, d.completed_at) / 3600.0 AS delivery_hours
    FROM wi_ticket AS t
    INNER JOIN done_completion AS d
        ON d.ticket_id = t.id
    WHERE t.workspace_id = 2
      AND t.type = 'REQ'
)
SELECT AVG(delivery_hours) AS avg_delivery_hours
FROM completed_requirements
GROUP BY workspace_id
```

**行数**:1

**图表路径**:`deliverables/chatbi/charts/01-已完成需求的平均交付耗时.html`(表格)

**数据**

| avg_delivery_hours |
|---|
| 0.63611111 |

**结论**

> 平均交付耗时为 **0.63611111 小时(约 38.17 分钟)**,结果集共 1 行。SQL 在 `workspace_id=2` 内筛选 `type='REQ'` 的需求,以进入「已发布 / 已关闭 / 已修复」等完成态的**最新**流转时间计算创建至完成的耗时并求平均,符合「已完成需求」的口径;题目未要求按维度分组,因此仅返回 1 行合理。结果仅 1 行,样本量过小,且未输出实际参与平均的需求条数,无法核验底层样本量,结论稳定性有限。

**要点**
- 「已完成」= 流转到 `wi_flow_state.category='DONE'` 的节点;本空间 DONE 状态名实测为 **已发布 / 已关闭 / 已修复**(运行时从 `wi_flow_state` 读出,没写死)。
- 完成时刻取 `wi_journal` 中 `event_type='STATUS_CHANGE'` 且 `to_val` 等于状态名的**最后一条** `gmt_create`(同一工单多次流转取 `MAX`)。
- 交付耗时 = `gmt_create` → 进入 DONE 的耗时,`TIMESTAMPDIFF(SECOND, ...)/3600.0` 转小时。
- 无维度可分组,图表选型规则判为「表格」而不是硬画一根柱子。

---

## 产物清单

| 文件 | 大小 | 形态 | 内容 |
|---|---:|---|---|
| `charts/01-最近-7-天每天新增需求多少条.html` | ~1.0 MB | 折线 | 7 天日新增需求数 |
| `charts/01-哪个数字员工执行任务最多-成功率多少.html` | ~1.0 MB | 柱状 | 各数字员工执行次数与成功率 |
| `charts/01-已完成需求的平均交付耗时.html` | ~1.9 KB | 表格 | 平均交付耗时 |

三张图均为**单文件 HTML**,ECharts 已内联(`chatbi/vendor/echarts.min.js`),双击离线可看,无外网依赖。

> 命名说明:文件名前缀 `01-` 是**进程内**计数器,每次 `python3 -m chatbi` 调用都从 1 开始,因此三张图前缀相同;靠后面的问题 slug 保证互不重名。此项已作为非阻断观察项 OB-02 记入 `06-测试报告.md` §7。

---

## 复现方式

```bash
cd /Users/summer/CodeFlow-private

# 1. 单测(150 条,全绿)
python3 -m pytest chatbi/tests -q

# 2. 三个演示问题(依次执行,各自落一张图)
python3 -m chatbi "最近 7 天每天新增需求多少条"
python3 -m chatbi "哪个数字员工执行任务最多、成功率多少"
python3 -m chatbi "已完成需求的平均交付耗时"

# 3. 看图
open deliverables/chatbi/charts/
```

环境依赖:`chatbi/.env`(或 `runtime.env`)需给出 `CHATBI_DB_*` 与 `ANTHROPIC_*`;密钥不落库、不进日志,异常出口统一经 `redact()` 脱敏。

单问耗时 2–7 分钟(瓶颈在 LLM 端点,含扩展思考;自检打回会重跑整条链路),详见 `06-测试报告.md` §7 OB-01。
