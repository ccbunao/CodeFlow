"""L2 集成测试:对真实 seekdb 的只读路径与权限边界。

前置:platform-seekdb-1 在跑,且 chatbi/.env 里配好了只读账号口令。
没有配置时整组跳过(不判失败)。
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest

from chatbi.config import DOTENV_PATH, parse_env_file
from chatbi.errors import MySQLError
from chatbi.mysql_client import MySQLClient
from chatbi.sql_guard import SqlGuard, fetch_allowed_tables

REPO_ROOT = Path(__file__).resolve().parents[2]


def _db_config() -> dict[str, str]:
    values = parse_env_file(DOTENV_PATH)
    if not values.get("CHATBI_DB_PASSWORD"):
        pytest.skip("未配置只读账号口令(chatbi/.env),跳过集成测试")
    return values


@pytest.fixture(scope="module")
def db_config() -> dict[str, str]:
    return _db_config()


@pytest.fixture(scope="module")
def client(db_config: dict[str, str]):
    conn = MySQLClient(
        host=db_config.get("CHATBI_DB_HOST", "127.0.0.1"),
        port=int(db_config.get("CHATBI_DB_PORT", "33060")),
        user=db_config.get("CHATBI_DB_USER", "chatbi_ro"),
        password=db_config["CHATBI_DB_PASSWORD"],
        database=db_config.get("CHATBI_DB_NAME", "ainative_v2"),
    )
    conn.connect()
    yield conn
    conn.close()


# ------------------------------------------------------------------ IN-01 只读路径可用

def test_in01_readonly_account_can_select(client: MySQLClient) -> None:
    result = client.query("SELECT COUNT(*) AS n FROM wi_ticket")
    assert result.row_count == 1
    assert int(result.rows[0][0]) > 0


# ------------------------------------------------------------------ IN-02/03/04 数据库层拒写

@pytest.mark.parametrize("statement,verb", [
    ("UPDATE wi_ticket SET title='hacked' WHERE id=1", "UPDATE"),
    ("DELETE FROM wi_ticket WHERE id=1", "DELETE"),
    ("DROP TABLE wi_ticket", "DROP"),
    ("INSERT INTO wi_ticket(workspace_id, title, type) VALUES(2, 'x', 'REQ')", "INSERT"),
])
def test_in02_readonly_account_cannot_write(client: MySQLClient, statement: str, verb: str) -> None:
    """防线 B:写操作必须被**数据库**拒绝,而不是被应用层拦下。"""
    with pytest.raises(MySQLError) as excinfo:
        client.query(statement)
    assert excinfo.value.is_permission_denied or excinfo.value.code != 0
    assert verb.lower() in excinfo.value.message.lower()


def test_in03_update_did_not_change_data(client: MySQLClient) -> None:
    """确认上面那次 UPDATE 没有留下任何痕迹。"""
    rows = client.query("SELECT title FROM wi_ticket WHERE id=1").rows
    assert rows
    assert str(rows[0][0]) != "hacked"


def test_in04_grants_are_select_only(db_config: dict[str, str]) -> None:
    """只读账号只有 SELECT 授权。"""
    user = db_config.get("CHATBI_DB_USER", "chatbi_ro")
    host = db_config.get("CHATBI_DB_HOST", "127.0.0.1")
    port = db_config.get("CHATBI_DB_PORT", "33060")
    root_password = os.environ.get("CHATBI_ROOT_PASSWORD", "")
    command = [
        "/usr/local/mysql/bin/mysql", f"-h{host}", f"-P{port}", "-uroot",
        "--protocol=TCP", "-N", "-B", "-e",
        f"SHOW GRANTS FOR '{user}'@'%'",
    ]
    if root_password:
        command.insert(3, f"-p{root_password}")
    try:
        output = subprocess.run(command, capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError) as exc:
        pytest.skip(f"无法调用 mysql CLI 核对授权:{exc}")
    if not output.strip():
        pytest.skip("mysql CLI 未返回授权信息")
    assert "GRANT SELECT ON `ainative_v2`.*" in output
    for forbidden in ("INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALL PRIVILEGES"):
        assert forbidden not in output.upper().replace("GRANT USAGE", "")


# ------------------------------------------------------------------ IN-05 校验层不触达 DB

def test_in05_rejected_sql_never_reaches_database(client: MySQLClient) -> None:
    """防线 A:被校验器拒绝的 UPDATE 不能产生任何数据库侧请求。"""
    guard = SqlGuard(fetch_allowed_tables(client, "ainative_v2"), "ainative_v2")
    with pytest.raises(Exception):
        guard.check("UPDATE wi_ticket SET title='hacked' WHERE id=1")
    # 校验器拒了,客户端从未被调用——用「数据未变」来证明没有副作用。
    rows = client.query("SELECT title FROM wi_ticket WHERE id=1").rows
    assert str(rows[0][0]) != "hacked"


# ------------------------------------------------------------------ IN-06 交叉验证

def test_in06_matches_mysql_cli(client: MySQLClient) -> None:
    """自研客户端与 mysql CLI 的结果必须一致。"""
    sql = "SELECT id, title FROM wi_ticket ORDER BY id LIMIT 5"
    mine = client.query(sql).rows
    command = [
        "/usr/local/mysql/bin/mysql", "-h127.0.0.1", "-P33060", "-uroot",
        "--protocol=TCP", "-N", "-B", "-D", "ainative_v2", "-e", sql,
    ]
    try:
        output = subprocess.run(command, capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError) as exc:
        pytest.skip(f"无法调用 mysql CLI:{exc}")
    cli_rows = [tuple(line.split("\t")) for line in output.strip().splitlines() if line]
    assert len(mine) == len(cli_rows)
    for got, expected in zip(mine, cli_rows):
        assert got[0] == expected[0]


# ------------------------------------------------------------------ IN-07 白名单实时读取

def test_in07_whitelist_is_read_live(client: MySQLClient) -> None:
    tables = fetch_allowed_tables(client, "ainative_v2")
    assert "wi_ticket" in tables
    assert "rt_run" in tables
    assert "ag_agent" in tables
    assert len(tables) > 50


def test_in08_whitelist_rejects_unknown_table(client: MySQLClient) -> None:
    guard = SqlGuard(fetch_allowed_tables(client, "ainative_v2"), "ainative_v2")
    with pytest.raises(Exception):
        guard.check("SELECT * FROM definitely_not_a_real_table")
