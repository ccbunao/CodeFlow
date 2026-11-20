"""Agent 主循环的行为测试:选表、重试上限、自检回灌、0 行不重试。

调用序列(每个问题):
    LLM#1 选表 → LLM#2 生成 SQL → LLM#3 自检(有数据时)
"""
from __future__ import annotations

from pathlib import Path

import pytest

from chatbi.agent import ChatBIAgent
from chatbi.config import AppConfig, DBConfig, LLMConfig
from chatbi.errors import ChatBIError, MySQLError
from chatbi.mysql_client import QueryResult

ALLOWED_TABLES = ("wi_ticket", "rt_run", "ag_agent", "wi_journal", "wi_flow_state")
TABLES_PICK = "wi_ticket, rt_run, ag_agent, wi_journal, wi_flow_state"


def _sql(text: str) -> str:
    return f"```sql\n{text}\n```"


class FakeDB:
    """按 SQL 内容分派,上下文查询走固定桩,业务查询走脚本队列。"""

    def __init__(self, script: list) -> None:
        self.script = list(script)
        self.queries: list[str] = []

    def query(self, sql: str) -> QueryResult:
        self.queries.append(sql)
        if "information_schema.tables" in sql:
            return QueryResult(["table_name"], [253], [(t,) for t in ALLOWED_TABLES])
        if "information_schema.columns" in sql:
            return QueryResult(["table_name", "column_name", "data_type"], [253, 253, 253], [
                ("wi_ticket", "id", "bigint"),
                ("wi_ticket", "workspace_id", "bigint"),
                ("wi_ticket", "type", "varchar"),
                ("wi_ticket", "gmt_create", "datetime"),
                ("rt_run", "agent_id", "bigint"),
                ("rt_run", "status", "varchar"),
                ("ag_agent", "name", "varchar"),
                ("pf_setting", "setting_key", "varchar"),
                ("pf_setting", "setting_value", "text"),
            ])
        if "wi_flow_state" in sql:
            return QueryResult(["name"], [253], [("已发布",)])
        if not self.script:
            raise AssertionError(f"FakeDB 没有为这条 SQL 准备结果:{sql}")
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class FakeLLM:
    def __init__(self, script: list) -> None:
        self.script = list(script)
        self.calls: list[tuple[str, str]] = []

    def complete(self, system: str, user: str) -> str:
        self.calls.append((system, user))
        if not self.script:
            raise AssertionError("FakeLLM 的脚本已耗尽")
        return self.script.pop(0)


def _config(tmp_path: Path, max_retries: int = 3) -> AppConfig:
    return AppConfig(
        db=DBConfig(host="h", port=3306, user="chatbi_ro", password="p", database="ainative_v2"),
        llm=LLMConfig(base_url="http://x", auth_token="t", model="m"),
        workspace_id=2,
        output_dir=tmp_path,
        table_max_rows=20,
        max_retries=max_retries,
    )


def _ok_verdict(conclusion: str = "共 7 行。") -> str:
    return '{"ok": true, "problem": "", "conclusion": "%s"}' % conclusion


# ------------------------------------------------------------------ 正常路径

def test_successful_path_produces_chart(tmp_path: Path) -> None:
    db = FakeDB([QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")])])
    llm = FakeLLM([TABLES_PICK,
                   _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY d"),
                   _ok_verdict()])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("最近 7 天每天新增需求多少条")

    assert outcome.row_count == 1
    assert outcome.attempts == 1
    assert outcome.chart_kind == "line"
    assert outcome.chart_path.exists()
    assert "共 7 行" in outcome.conclusion


def test_table_selection_narrows_the_schema(tmp_path: Path) -> None:
    """选表阶段的意义:别把 106 张表的**字段**全塞进生成提示词。

    未被选中的表只出现在「表名清单」里(模型还能看见它存在),
    但它的字段结构不应进入生成阶段。
    """
    db = FakeDB([QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")])])
    llm = FakeLLM(["rt_run, ag_agent",
                   _sql("SELECT DATE(gmt_create) AS d FROM wi_ticket GROUP BY d"),
                   _ok_verdict()])
    agent = ChatBIAgent(_config(tmp_path), db, llm)
    agent.ask("每天新增多少")

    select_system = llm.calls[0][0]
    assert "表选择器" in select_system
    assert "pf_setting" in select_system, "选表阶段应看到全部表名"

    gen_system = llm.calls[1][0]
    assert "rt_run(agent_id" in gen_system, "选中的表应带字段结构"
    assert "pf_setting(" not in gen_system, "未选中的表不应带字段结构"


def test_unknown_table_falls_back_to_full_schema(tmp_path: Path) -> None:
    """表名大小写不对:校验层放行(大小写不敏感),DB 报不存在 → 换全量 schema 重试。"""
    db = FakeDB([
        MySQLError("Table 'ainative_v2.WI_TICKET' doesn't exist", 1146),
        QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")]),
    ])
    llm = FakeLLM([TABLES_PICK,
                   _sql("SELECT DATE(gmt_create) AS d FROM WI_TICKET GROUP BY d"),
                   _sql("SELECT DATE(gmt_create) AS d FROM wi_ticket GROUP BY d"),
                   _ok_verdict()])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("每天新增多少")

    assert outcome.attempts == 2
    assert "全量表结构" in llm.calls[2][1]
    assert "执行失败" in llm.calls[2][1]


def test_sql_is_validated_before_execution(tmp_path: Path) -> None:
    """含注释的 SQL 必须在校验层被拦下,不能发往 DB。"""
    db = FakeDB([])
    llm = FakeLLM([TABLES_PICK, _sql("SELECT 1 -- 注释")])
    agent = ChatBIAgent(_config(tmp_path, max_retries=1), db, llm)

    with pytest.raises(ChatBIError):
        agent.ask("随便问个问题")

    business_queries = [
        q for q in db.queries
        if "information_schema" not in q and "wi_flow_state" not in q
    ]
    assert business_queries == [], "被拒绝的 SQL 不应该发往数据库"


# ------------------------------------------------------------------ 重试

def test_rejected_sql_is_retried_with_reason(tmp_path: Path) -> None:
    db = FakeDB([QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")])])
    llm = FakeLLM([
        TABLES_PICK,
        _sql("SELECT 1 -- 注释"),
        _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY d"),
        _ok_verdict(),
    ])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("每天新增多少")

    assert outcome.attempts == 2
    assert len(llm.calls) == 4  # 选表 + 两次生成 + 一次自检
    assert "被拒绝" in llm.calls[2][1]
    assert "注释" in llm.calls[2][1]


def test_execution_error_triggers_retry(tmp_path: Path) -> None:
    db = FakeDB([
        MySQLError("You have an error in your SQL syntax", 1064),
        QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")]),
    ])
    llm = FakeLLM([
        TABLES_PICK,
        _sql("SELECT foo FROM wi_ticket"),
        _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY d"),
        _ok_verdict(),
    ])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("每天新增多少")

    assert outcome.attempts == 2
    assert "执行失败" in llm.calls[2][1]


def test_retry_limit_is_respected(tmp_path: Path) -> None:
    db = FakeDB([])
    llm = FakeLLM([TABLES_PICK] + [_sql(f"SELECT {i} -- 注释") for i in range(10)])
    agent = ChatBIAgent(_config(tmp_path, max_retries=3), db, llm)

    with pytest.raises(ChatBIError) as excinfo:
        agent.ask("每天都失败")

    assert "3 次" in str(excinfo.value)
    assert len(llm.calls) == 4, "1 次选表 + 3 次生成,重试上限是 3"


def test_self_check_failure_feeds_problem_back(tmp_path: Path) -> None:
    db = FakeDB([
        QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")]),
        QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5"), ("2026-11-18", "0")]),
    ])
    llm = FakeLLM([
        TABLES_PICK,
        _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY d"),
        '{"ok": false, "problem": "漏了 GROUP BY,结果被折叠成一行", "conclusion": ""}',
        _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY DATE(gmt_create)"),
        _ok_verdict(),
    ])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("每天新增多少")

    assert outcome.attempts == 2
    assert "漏了 GROUP BY" in llm.calls[3][1]
    assert "自检不通过" in llm.calls[3][1]
    assert outcome.self_check_history == ["漏了 GROUP BY,结果被折叠成一行"]


def test_zero_rows_does_not_retry(tmp_path: Path) -> None:
    """拍板默认项:0 行不重试,直接出「无数据」结论。"""
    db = FakeDB([QueryResult(["d", "cnt"], [12, 8], [])])
    llm = FakeLLM([TABLES_PICK, _sql("SELECT DATE(gmt_create) AS d FROM wi_ticket WHERE 1=0 GROUP BY d")])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("没有数据的区间")

    assert outcome.row_count == 0
    assert outcome.attempts == 1
    assert len(llm.calls) == 2, "0 行不应触发自检重试"
    assert "没有符合条件的数据" in outcome.conclusion


def test_self_check_parse_failure_degrades_gracefully(tmp_path: Path) -> None:
    """自检输出不是合法 JSON 时不应把整轮查询打挂。"""
    db = FakeDB([QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")])])
    llm = FakeLLM([
        TABLES_PICK,
        _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY d"),
        "这不是 JSON",
        "查询返回 1 行。",
    ])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("每天新增多少")

    assert outcome.row_count == 1
    assert outcome.conclusion


def test_table_selection_failure_falls_back_to_core_tables(tmp_path: Path) -> None:
    """选表调用失败不能拖垮整轮,退回核心表。"""
    db = FakeDB([QueryResult(["d", "cnt"], [12, 8], [("2026-11-17", "5")])])

    class FailingSelectLLM(FakeLLM):
        def complete(self, system: str, user: str) -> str:
            self.calls.append((system, user))
            if "表选择器" in system:
                raise MySQLError("模拟端点故障")
            return super().complete(system, user)

    llm = FailingSelectLLM([
        _sql("SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket GROUP BY d"),
        _ok_verdict(),
    ])
    agent = ChatBIAgent(_config(tmp_path), db, llm)

    outcome = agent.ask("每天新增多少")

    assert outcome.row_count == 1
    assert "wi_ticket(" in llm.calls[1][0]
