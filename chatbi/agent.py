"""Agent 主循环:提问 → 生成 SQL → 校验 → 执行 → 自检重试 → 图表 + 结论。"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from .chart import ChartSpec, choose_chart, render_chart
from .config import AppConfig, redact
from .errors import ChatBIError, MySQLError, SQLRejected
from .llm import LLMClient
from .mysql_client import MySQLClient, QueryResult
from .sql_guard import SqlGuard, extract_sql, fetch_allowed_tables

_SLUG_RE = re.compile(r"[^\w-]+")
_AGG_RE = re.compile(r"\b(count|sum|avg|min|max)\s*\(", re.IGNORECASE)
_GROUP_BY_RE = re.compile(r"\bgroup\s+by\b", re.IGNORECASE)

SYSTEM_TEMPLATE = """你是一个只读 BI 分析助手,连接 CodeFlow 平台的业务库(MySQL 协议,库名 {database})。

铁律(违反任何一条,你的 SQL 都会被拒绝并要求重写):
1. 只输出**一条** SELECT 语句。可以用 WITH ... AS (...) 写 CTE,但 CTE 内部也只能是 SELECT。
2. **禁止任何注释**:不要出现 -- 、# 、/* */。
3. **禁止** INSERT / UPDATE / DELETE / DROP / CREATE / ALTER / TRUNCATE / GRANT / CALL / SET 等一切非 SELECT 关键字。
4. **禁止** INTO OUTFILE / INTO DUMPFILE / @变量 / @@系统变量 / LOAD_FILE / SLEEP / BENCHMARK。
5. 表名只能用下面白名单里的表,不许跨库(schema 前缀只能是 {database})。
6. 不要写分号,一条 SQL 就是全部。

SQL 正确性铁律(写错会被自检打回):
7. 只要用了 COUNT/SUM/AVG 等聚合函数,就**必须**配 GROUP BY;否则整张表会被折叠成一行,
   「每天/每个/分别」的题目会只返回一行,这是最常见的错误。
8. JOIN 之后要检查行数是否被放大;必要时用 COUNT(DISTINCT 主键)。
9. 「每天/每个/各」这类分组题,要覆盖完整区间(没有数据的日子补 0),可以用 WITH ... AS (...) 造日期序列再 LEFT JOIN。
10. 排名类问题(「哪个/谁/最多的是谁」)要返回**全部候选对象并按指标排序**,
    不要用 LIMIT 1 或 RANK()=1 只取第一名——结果被折叠成一行就画不出图了。
    第一名是谁,由结论里指出。

可用表白名单(共 {table_count} 张):
{table_list}

表结构(每行一张表,列名:类型):
{schema}

口径规则(平台业务约定,必须遵守):
- 「需求」指 wi_ticket 中 type = 'REQ' 的工单。
- 「新增 / 创建时间」用 wi_ticket.gmt_create;「修改时间」用 gmt_modified。
- 「已完成 / 已交付」= 工单流转到了状态分类 category = 'DONE' 的节点。当前本空间的 DONE 状态名是:{done_states}。
  完成时刻取 wi_journal 中 event_type = 'STATUS_CHANGE' 且 to_val 等于上述状态名的**最后一条** gmt_create。
- 「交付耗时」= 工单创建(gmt_create)到进入 DONE 状态的耗时,单位小时。
- 「数字员工」= ag_agent;「执行了一次任务」= rt_run 的一行。
- 「成功率」= SUCCEEDED 行数 / (SUCCEEDED 行数 + FAILED 行数)。**PENDING 不计入分母**,但要在结论里说明排除了多少条 PENDING。
- 所有查询都要限定 workspace_id = {workspace_id};若表没有 workspace_id 列则不加这个过滤条件。
- **时间口径(极易踩坑)**:库里所有时间列(gmt_create / gmt_modified / started_at 等)存的就是
  **Asia/Shanghai 本地墙钟时间**,直接用 DATE(gmt_create) 取日期即可。
  **绝对不要用 CONVERT_TZ(gmt_create,'+00:00','+08:00')**——那会把时间整体平移 8 小时,
  16:00 之后创建的记录会被算到第二天。「今天」用 CURDATE() 即可(服务器时区就是 +08:00)。

输出格式(严格):
先输出一个 ```sql 代码块,里面**只有**那一条 SQL;
代码块之后另起一行,写一句中文说明你选了哪些口径(不要在这里下结论,结论会在拿到数据后再生成);
除此之外不要输出任何内容。"""

# 平台表名前缀的领域含义——给选表阶段当路标。
PREFIX_HINTS = {
    "ag_": "数字员工(智能体)定义、AI 会话与轮次、执行器、MCP 令牌、版本",
    "as_": "交付产物、知识资产、代码仓库、环境变量、任务数据",
    "cv_": "需求澄清会话与结论、计划、用量、分享",
    "ex_": "外部系统集成、外部条目映射、通知与偏好",
    "id_": "用户、组织、工作空间、身份与登录",
    "mm_": "记忆、笔记、导入快照",
    "pf_": "平台配置、备份、审计、定时任务、通知",
    "rt_": "运行时派发与执行记录、事件、检查点、决策",
    "sq_": "流程模板、成员、快照、SDLC 步骤",
    "wi_": "工单(需求/缺陷/任务)、评论、订阅、日志、流程状态",
}

# 口径规则引用到的核心表,无论选表结果如何都带上,保证口径可落地。
CORE_TABLES = ("wi_ticket", "wi_journal", "wi_flow_state", "ag_agent", "rt_run")

TABLE_SELECT_TEMPLATE = """你是一个数据仓库的表选择器。给定一个问题,从下面的表清单里选出回答它**最可能用到的表**。

{prefix_hints}

表清单:
{table_list}

要求:
- 最多选 {max_tables} 张表,宁少勿滥;只输出表名,英文逗号分隔;
- 不要输出任何解释、标点或空格;
- 选不到的表不要硬凑。"""

SELF_CHECK_TEMPLATE = """你是一个严格的数据分析审校员。请复核一条 SQL 是否真正回答了用户的问题。

用户问题:{question}

执行的 SQL:
{sql}

查询返回 {row_count} 行,列是:{columns}
{preview}
{signal}

复核要点:
- 这条 SQL 的聚合/分组/JOIN/时间区间/workspace 过滤是否真的能回答这个问题?
- 题目里的「每天/每个/各」是否真的按维度分组了?结果是该维度多行,还是被折叠成了一行?
- 口径是否符合平台约定(需求=type 'REQ';已完成=流转到 DONE 状态;成功率分母只算终态)?
- **时间口径有没有搞错**:库里时间列存的就是 Asia/Shanghai 本地墙钟时间,
  如果 SQL 里出现了 CONVERT_TZ(时间列,'+00:00','+08:00') 之类的平移,就是错的(会把时间平移 8 小时);
  「今天」应当用 CURDATE()。
- 返回的行数是否与题目的预期吻合?

如果**正确**,给出结论;如果**不正确**,说明问题出在哪、应该怎么改。

只输出一个 JSON 对象,不要有多余文字:
{{"ok": true, "problem": "", "conclusion": "2-4 句中文结论,先给关键数字再给解读,必须点明样本量(共 {row_count} 行);样本量不超过 3 行时必须说明样本量过小"}}
或
{{"ok": false, "problem": "具体问题与修法", "conclusion": ""}}"""

CONCLUSION_TEMPLATE = """下面是针对用户问题的一次查询结果。请写一句中文结论。

用户问题:{question}

执行的 SQL:
{sql}

查询返回 {row_count} 行,列是:{columns}
{preview}

要求:
- 直接给结论,先给关键数字,再给一句解读;
- 必须点明样本量(共 {row_count} 行);
- 如果样本量很小(不超过 3 行),必须说明「样本量过小,仅供参考」;
- 不要臆造没有出现过的数字;
- 不要重复 SQL,不要解释你做了什么,直接给结论;
- 用 2-4 句话,不要用列表。"""


@dataclass
class QueryOutcome:
    question: str
    sql: str
    row_count: int
    columns: list[str]
    chart_kind: str
    chart_path: Path
    conclusion: str
    attempts: int
    notes: list[str]
    self_check_history: list[str]


def _slug(text: str) -> str:
    cleaned = _SLUG_RE.sub("-", text.strip())
    return cleaned.strip("-")[:36] or "chart"


_UNKNOWN_OBJECT_RE = re.compile(
    r"unknown (table|column)|doesn't exist|not found in table|unknown database",
    re.IGNORECASE,
)


def _is_unknown_object_error(message: str) -> bool:
    """数据库报「表/列不存在」——通常是选表阶段漏了,值得换全量 schema 重试。"""
    return bool(_UNKNOWN_OBJECT_RE.search(message))


class ChatBIAgent:
    def __init__(self, cfg: AppConfig, db: MySQLClient, llm: LLMClient) -> None:
        self.cfg = cfg
        self.db = db
        self.llm = llm
        self._guard: SqlGuard | None = None
        self._allowed_tables: frozenset[str] = frozenset()
        self._schema: dict[str, list[str]] | None = None
        self._done_states: list[str] | None = None
        self._system_prompt: str = ""
        self._full_schema_prompt: str | None = None
        self._chart_index = 0

    # ---------------------------------------------------------------- 上下文

    def _ensure_context(self) -> None:
        """拉取白名单与全量 schema(只拉一次,供选表与提示词复用)。"""
        if self._guard is not None:
            return
        tables = fetch_allowed_tables(self.db, self.cfg.database)
        self._guard = SqlGuard(tables, self.cfg.database)
        self._allowed_tables = tables

        rows = self.db.query(
            "SELECT table_name, column_name, data_type FROM information_schema.columns "
            f"WHERE table_schema = '{self.cfg.database}' ORDER BY table_name, ordinal_position"
        )
        schema: dict[str, list[str]] = {}
        for table, column, data_type in rows.rows:
            schema.setdefault(str(table), []).append(f"{column}:{data_type}")
        self._schema = schema

    def _select_tables(self, question: str) -> list[str]:
        """先选表:把大 schema 挡在生成阶段之外,否则模型会把思考预算耗光。"""
        assert self._schema is not None
        table_list = "\n".join(sorted(self._schema))
        hints = "\n".join(f"- {prefix} → {desc}" for prefix, desc in PREFIX_HINTS.items())
        try:
            raw = self.llm.complete(
                TABLE_SELECT_TEMPLATE.format(
                    prefix_hints=hints, table_list=table_list, max_tables=6),
                f"问题:{question}",
            )
        except ChatBIError:
            return list(CORE_TABLES)

        picked = [
            name.strip().strip("`")
            for name in re.split(r"[,\s;、]+", raw)
            if name.strip().strip("`")
        ]
        known = {name.lower(): name for name in self._schema}
        selected: list[str] = []
        for name in picked:
            real = known.get(name.lower())
            if real and real not in selected:
                selected.append(real)
        if not selected:
            selected = list(CORE_TABLES)
        return selected[:6]

    def _build_system_prompt(self, tables: list[str] | None = None) -> str:
        """tables 为 None 时给全量 schema(选表失败后的兜底)。"""
        assert self._schema is not None
        wanted = tables if tables is not None else sorted(self._schema)
        schema_lines = [
            f"{table}({', '.join(self._schema[table])})"
            for table in wanted if table in self._schema
        ]

        done_states = self._done_state_names()
        done_text = "、".join(done_states) if done_states else "(未取到,请用 wi_flow_state.category='DONE' 自行判断)"

        return SYSTEM_TEMPLATE.format(
            database=self.cfg.database,
            table_count=len(self._schema),
            table_list=", ".join(sorted(self._schema)),
            schema="\n".join(schema_lines),
            done_states=done_text,
            workspace_id=self.cfg.workspace_id,
        )

    def _done_state_names(self) -> list[str]:
        if self._done_states is not None:
            return self._done_states
        rows = self.db.query(
            "SELECT DISTINCT name FROM wi_flow_state WHERE category = 'DONE' "
            f"AND workspace_id = {int(self.cfg.workspace_id)}"
        )
        self._done_states = [str(row[0]) for row in rows.rows if row and row[0]]
        return self._done_states

    # ---------------------------------------------------------------- 主流程

    def ask(self, question: str) -> QueryOutcome:
        self._ensure_context()
        assert self._guard is not None

        selected = self._select_tables(question)
        prompt_tables = list(dict.fromkeys(list(CORE_TABLES) + selected))
        self._system_prompt = self._build_system_prompt(prompt_tables)
        self._full_schema_prompt = None

        user_message = f"问题:{question}"
        sql = ""
        result: QueryResult | None = None
        attempts = 0
        history: list[str] = []
        self_check_history: list[str] = []
        conclusion = ""

        while attempts < self.cfg.max_retries:
            attempts += 1
            attempt_message = user_message
            if history:
                attempt_message += "\n\n" + "\n".join(history[-2:])
            raw = self.llm.complete(self._system_prompt, attempt_message)
            try:
                sql = self._guard.check(extract_sql(raw))
            except SQLRejected as exc:
                history.append(f"上一次生成的 SQL 被拒绝:{exc.reason}。请重新生成一条符合铁律的 SELECT。")
                continue
            try:
                result = self.db.query(sql)
            except MySQLError as exc:
                message = redact(exc.message)
                history.append(
                    f"上一次生成的 SQL 执行失败:{message}。"
                    f"请检查表名、列名与函数是否正确,重新生成一条 SELECT。"
                )
                if _is_unknown_object_error(message) and self._full_schema_prompt is None:
                    # 选表阶段漏了某张表:换成全量 schema 再试一次。
                    self._full_schema_prompt = self._build_system_prompt(None)
                    self._system_prompt = self._full_schema_prompt
                    history.append("已切换到全量表结构,请基于下方的完整表结构重新生成。")
                continue

            if result.row_count == 0:
                # 0 行不重试(拍板默认项):空结果通常是口径写对了但数据没有。
                conclusion = self._empty_conclusion(sql)
                break

            ok, problem, checked_conclusion = self._self_check(question, sql, result)
            if ok:
                conclusion = checked_conclusion
                break
            self_check_history.append(problem)
            history.append(f"上一次生成的 SQL 执行成功,但自检不通过:{problem}。请修正后重新生成一条 SELECT。")

        if result is None:
            detail = "\n".join(history) if history else "(没有拿到可执行的 SQL)"
            raise ChatBIError(
                f"连续 {self.cfg.max_retries} 次都没能拿到可用的结果。\n{detail}\n"
                f"最后一次尝试的 SQL:{sql or '(无)'}"
            )

        notes = self._build_notes(result)
        chart_kind, chart_path = self._render(question, sql, result, notes)

        return QueryOutcome(
            question=question,
            sql=sql,
            row_count=result.row_count,
            columns=result.columns,
            chart_kind=chart_kind,
            chart_path=chart_path,
            conclusion=conclusion,
            attempts=attempts,
            notes=notes,
            self_check_history=self_check_history,
        )

    # ---------------------------------------------------------------- 自检与结论

    def _self_check(self, question: str, sql: str, result: QueryResult) -> tuple[bool, str, str]:
        preview_lines = [
            " | ".join("NULL" if v is None else str(v) for v in row)
            for row in result.rows[:20]
        ]
        preview = "前几行数据:\n" + "\n".join(preview_lines)

        signal = ""
        if _AGG_RE.search(sql) and not _GROUP_BY_RE.search(sql):
            signal = ("\n注意:这条 SQL 用了聚合函数但没有 GROUP BY,结果会被折叠成一行——"
                      "请重点核对是否漏了分组维度。")
        elif result.row_count == 1:
            signal = "\n注意:这次只返回了 1 行,如果题目要求按维度分组,可能漏了 GROUP BY。"

        message = SELF_CHECK_TEMPLATE.format(
            question=question,
            sql=sql,
            row_count=result.row_count,
            columns=", ".join(result.columns),
            preview=preview,
            signal=signal,
        )
        try:
            raw = self.llm.complete(
                "你是一个严格的数据分析审校员,只依据给定的 SQL 与结果判断,不要臆造。",
                message,
            )
        except ChatBIError as exc:
            # 自检本身失败不阻塞出数,退回独立的结论生成。
            return True, "", self._fallback_conclusion(question, sql, result, str(exc))

        verdict = self._parse_verdict(raw)
        if verdict is None:
            return True, "", self._fallback_conclusion(question, sql, result, "自检输出无法解析")
        ok, problem, conclusion = verdict
        if ok:
            return True, "", conclusion or self._fallback_conclusion(question, sql, result, "")
        return False, problem or "自检认为结果不符合问题要求", ""

    @staticmethod
    def _parse_verdict(raw: str) -> tuple[bool, str, str] | None:
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        if not match:
            return None
        try:
            data = json.loads(match.group(0))
        except json.JSONDecodeError:
            return None
        if "ok" not in data:
            return None
        return bool(data.get("ok")), str(data.get("problem") or ""), str(data.get("conclusion") or "")

    def _fallback_conclusion(self, question: str, sql: str, result: QueryResult,
                             reason: str) -> str:
        suffix = f"(结论生成降级:{redact(reason[:120])})" if reason else ""
        message = CONCLUSION_TEMPLATE.format(
            question=question,
            sql=sql,
            row_count=result.row_count,
            columns=", ".join(result.columns),
            preview="\n".join(
                " | ".join("NULL" if v is None else str(v) for v in row)
                for row in result.rows[:20]
            ),
        )
        try:
            return self.llm.complete(
                "你是一个严谨的数据分析助手,只依据给定的查询结果作答,绝不编造数字。",
                message,
            ).strip()
        except ChatBIError:
            return f"查询返回 {result.row_count} 行,列:{', '.join(result.columns)}。{suffix}"

    def _empty_conclusion(self, sql: str) -> str:
        return (
            f"查询执行成功,但没有符合条件的数据(0 行)。\n"
            f"使用的 SQL:{sql}\n"
            f"可能的口径偏差:时间区间、workspace_id、状态名或 JOIN 条件。"
        )

    def _build_notes(self, result: QueryResult) -> list[str]:
        notes = [f"数据空间 workspace_id = {self.cfg.workspace_id}"]
        notes.append(f"本次查询返回 {result.row_count} 行")
        if result.row_count <= 3:
            notes.append("样本量过小(≤3 行),结论仅供参考,不能当作统计结论")
        return notes

    # ---------------------------------------------------------------- 图表

    def _render(self, question: str, sql: str, result: QueryResult,
                notes: list[str]) -> tuple[str, Path]:
        kind = choose_chart(result.columns, result.column_types, result.rows,
                            self.cfg.table_max_rows)
        self._chart_index += 1
        charts_dir = self.cfg.output_dir / "charts"
        filename = f"{self._chart_index:02d}-{_slug(question)}.html"
        spec = ChartSpec(
            kind=kind,
            title=question,
            columns=result.columns,
            rows=result.rows,
            subtitle=f"{sql} · {result.row_count} 行 · workspace_id={self.cfg.workspace_id}",
            notes=notes,
        )
        path = render_chart(spec, charts_dir / filename, result.column_types)
        return kind, path
