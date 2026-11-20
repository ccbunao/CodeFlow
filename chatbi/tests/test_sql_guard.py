"""SQL 校验器用例——必须覆盖注入攻击样例(需求硬性要求)。"""
from __future__ import annotations

import pytest

from chatbi.errors import SQLRejected
from chatbi.sql_guard import SqlGuard, extract_sql

ALLOWED = frozenset({"wi_ticket", "rt_run", "ag_agent", "wi_journal", "wi_flow_state"})


@pytest.fixture()
def guard() -> SqlGuard:
    return SqlGuard(ALLOWED, "ainative_v2")


# ------------------------------------------------------------------ 合法 SQL 放行

LEGIT_SQL = [
    "SELECT COUNT(*) AS n FROM wi_ticket",
    "SELECT DATE(gmt_create) AS d, COUNT(*) AS cnt FROM wi_ticket "
    "WHERE type='REQ' AND workspace_id=2 GROUP BY DATE(gmt_create) ORDER BY d",
    "SELECT a.name, COUNT(r.id) AS total FROM rt_run r "
    "JOIN ag_agent a ON a.id = r.agent_id "
    "WHERE r.status IN ('SUCCEEDED','FAILED') GROUP BY a.id ORDER BY total DESC",
    "WITH x AS (SELECT id FROM wi_ticket) SELECT COUNT(*) FROM x",
    "WITH RECURSIVE x AS (SELECT 1 AS d UNION ALL SELECT d+1 FROM x WHERE d < 3) "
    "SELECT * FROM x",
    "SELECT * FROM wi_ticket LIMIT 5;",
    "SELECT j.ticket_id, MAX(j.gmt_create) AS done_at FROM wi_journal j "
    "WHERE j.event_type='STATUS_CHANGE' AND j.to_val='已发布' GROUP BY j.ticket_id",
    "SELECT AVG(TIMESTAMPDIFF(SECOND, t.gmt_create, j.gmt_create)) FROM wi_ticket t "
    "JOIN wi_journal j ON j.ticket_id = t.id",
]


@pytest.mark.parametrize("sql", LEGIT_SQL)
def test_legit_sql_passes(guard: SqlGuard, sql: str) -> None:
    assert guard.check(sql) == sql.strip()


def test_cte_name_is_not_treated_as_table(guard: SqlGuard) -> None:
    sql = "WITH daily AS (SELECT DATE(gmt_create) AS d FROM wi_ticket GROUP BY d) SELECT * FROM daily"
    guard.check(sql)


# ------------------------------------------------------------------ 注入攻击样例

INJECTION_SQL = [
    # 写操作
    ("UPDATE wi_ticket SET title='pwned' WHERE id=1", "UPDATE"),
    ("DELETE FROM wi_ticket", "DELETE"),
    ("INSERT INTO wi_ticket(title, type) VALUES('x','REQ')", "INSERT"),
    ("DROP TABLE wi_ticket", "DROP"),
    ("TRUNCATE TABLE wi_ticket", "TRUNCATE"),
    ("ALTER TABLE wi_ticket ADD COLUMN x INT", "ALTER"),
    ("REPLACE INTO wi_ticket(id,title) VALUES(1,'x')", "REPLACE"),
    ("CREATE TABLE evil (id INT)", "CREATE"),
    ("GRANT ALL ON *.* TO 'x'@'%'", "GRANT"),
    # 多语句
    ("SELECT 1; DROP TABLE wi_ticket", "多语句"),
    ("SELECT 1;UPDATE wi_ticket SET title='x'", "多语句"),
    ("SELECT 1 ; SELECT 2", "多语句"),
    # 注释注入
    ("SELECT * FROM wi_ticket -- AND 1=1", "注释"),
    ("SELECT * FROM wi_ticket # drop", "注释"),
    ("SELECT * FROM wi_ticket /* comment */ WHERE 1=1", "注释"),
    ("SELECT * FROM wi_ticket WHERE 1=1 /*! UNION SELECT */", "注释"),
    # 白名单外的表
    ("SELECT * FROM secret_table", "白名单"),
    ("SELECT * FROM id_user", "白名单"),
    ("SELECT * FROM mysql.user", "跨库"),
    ("SELECT * FROM information_schema.tables", "跨库"),
    ("SELECT * FROM otherdb.wi_ticket", "跨库"),
    # 危险函数与文件/变量
    ("SELECT LOAD_FILE('/etc/passwd')", "load_file"),
    ("SELECT SLEEP(10)", "sleep"),
    ("SELECT BENCHMARK(1000000, MD5('a'))", "benchmark"),
    ("SELECT * FROM wi_ticket INTO OUTFILE '/tmp/x.csv'", "OUTFILE"),
    ("SELECT * FROM wi_ticket INTO DUMPFILE '/tmp/x.bin'", "DUMPFILE"),
    ("SELECT @@version", "@@"),
    ("SELECT @secret", "@"),
    ("SELECT * FROM wi_ticket WHERE id = (SELECT SLEEP(3))", "sleep"),
    # 非 SELECT 开头
    ("SHOW TABLES", "SELECT"),
    ("EXPLAIN SELECT 1", "SELECT"),
    ("DESCRIBE wi_ticket", "SELECT"),
    # 长度超限
    ("SELECT " + ("a" * 9000), "长度"),
]


@pytest.mark.parametrize("sql,expect", INJECTION_SQL)
def test_injection_is_rejected(guard: SqlGuard, sql: str, expect: str) -> None:
    with pytest.raises(SQLRejected):
        guard.check(sql)


@pytest.mark.parametrize("sql,expect", INJECTION_SQL)
def test_rejection_reason_is_actionable(guard: SqlGuard, sql: str, expect: str) -> None:
    """拒绝原因要能回灌给 LLM,必须是人话且带线索。"""
    with pytest.raises(SQLRejected) as excinfo:
        guard.check(sql)
    reason = excinfo.value.reason
    assert reason, "拒绝原因不能为空"
    assert len(reason) > 8, f"拒绝原因太短,LLM 无法据此修正:{reason!r}"


def test_empty_sql_rejected(guard: SqlGuard) -> None:
    with pytest.raises(SQLRejected):
        guard.check("   ")


def test_null_byte_rejected(guard: SqlGuard) -> None:
    with pytest.raises(SQLRejected):
        guard.check("SELECT 1\x00; DROP TABLE wi_ticket")


# ------------------------------------------------------------------ 代码块抽取

def test_extract_sql_from_fenced_block() -> None:
    raw = "好的,这是查询:\n```sql\nSELECT COUNT(*) FROM wi_ticket\n```\n口径:按 gmt_create。"
    assert extract_sql(raw) == "SELECT COUNT(*) FROM wi_ticket"


def test_extract_sql_without_fence() -> None:
    assert extract_sql("SELECT 1") == "SELECT 1"


def test_extract_sql_empty_raises() -> None:
    with pytest.raises(SQLRejected):
        extract_sql("")
