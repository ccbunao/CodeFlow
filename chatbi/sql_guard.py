"""SQL 校验器(防线 A):在发往数据库**之前**拦掉一切非只读语句。

白名单 + 拒绝列表双闸门,任一条不满足即整条拒绝。
表名白名单实时读自 information_schema,不在代码里写死。
"""
from __future__ import annotations

import re

from .errors import SQLRejected

MAX_SQL_LENGTH = 8000

# 禁止的语句关键字。逐词匹配(\b),避免 OFFSET/SUBSET 之类的误伤。
FORBIDDEN_KEYWORDS = (
    "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE",
    "GRANT", "REVOKE", "CALL", "LOAD", "SET", "LOCK", "UNLOCK", "HANDLER",
    "PREPARE", "EXECUTE", "DEALLOCATE", "ANALYZE", "OPTIMIZE", "REPAIR",
    "KILL", "SHUTDOWN", "RENAME", "MERGE", "SAVEPOINT", "INTO",
)

# 禁止的函数(危险或有副作用)。
FORBIDDEN_FUNCTIONS = (
    "load_file", "sleep", "benchmark", "extractvalue", "updatexml",
    "get_lock", "release_lock", "master_pos_wait", "is_free_lock",
    "sys_exec", "sys_eval",
)

_KEYWORD_RE = re.compile(r"\b(?:%s)\b" % "|".join(FORBIDDEN_KEYWORDS), re.IGNORECASE)
_FUNCTION_RE = re.compile(r"\b(%s)\s*\(" % "|".join(FORBIDDEN_FUNCTIONS), re.IGNORECASE)
_OUTFILE_RE = re.compile(r"\binto\s+(?:outfile|dumpfile)\b", re.IGNORECASE)
_USER_VAR_RE = re.compile(r"\binto\s+@|\b@@", re.IGNORECASE)
_SELECT_START_RE = re.compile(r"^\s*(select|with)\b", re.IGNORECASE)
_IDENT_RE = re.compile(r"^[A-Za-z0-9_$]+$")
_SCHEMA_RE = re.compile(r"^[A-Za-z0-9_$]+$")
_TABLE_REF_RE = re.compile(
    r"\b(?:from|join)\s+(`?[A-Za-z0-9_$]+`?(?:\s*\.\s*`?[A-Za-z0-9_$]+`?)?)",
    re.IGNORECASE,
)
_CTE_RE = re.compile(r"\b([A-Za-z0-9_$]+)\s+as\s*\(", re.IGNORECASE)
_LITERAL_RE = re.compile(
    r"'(?:[^'\\]|\\.|'')*'"      # 单引号字符串
    r'|"(?:[^"\\]|\\.|"")*"'     # 双引号字符串
    r"|`(?:[^`]|``)*`",          # 反引号标识符
    re.IGNORECASE | re.DOTALL,
)

_COMMENT_MARKERS = ("--", "#", "/*", "*/")


def extract_sql(text: str) -> str:
    """从 LLM 输出里抽出 SQL(去掉 Markdown 代码块围栏与多余说明)。"""
    if not text:
        raise SQLRejected("LLM 没有返回任何内容")
    fenced = re.search(r"```(?:sql)?\s*(.+?)```", text, re.IGNORECASE | re.DOTALL)
    candidate = fenced.group(1) if fenced else text
    candidate = candidate.strip()
    if not candidate:
        raise SQLRejected("LLM 返回的 SQL 代码块是空的")
    return candidate


def fetch_allowed_tables(client, database: str) -> frozenset[str]:
    """实时从 information_schema 读取可查的基表白名单。"""
    if not _SCHEMA_RE.match(database):
        raise SQLRejected(f"库名 {database!r} 不是合法的标识符")
    result = client.query(
        "SELECT table_name FROM information_schema.tables "
        f"WHERE table_schema = '{database}' AND table_type = 'BASE TABLE'"
    )
    return frozenset(str(row[0]) for row in result.rows if row and row[0])


def _strip_literals(sql: str) -> str:
    """把字符串字面量与反引号标识符挖掉,只留语法骨架,便于做字符级检查。"""
    return _LITERAL_RE.sub(" ", sql)


def _check_identifiers(sql: str) -> None:
    for match in re.finditer(r"`([^`]*)`", sql):
        name = match.group(1).replace("``", "")
        if not _IDENT_RE.match(name):
            raise SQLRejected(f"反引号标识符 {name!r} 含非法字符", detail=sql)


class SqlGuard:
    """只读 SQL 校验器。"""

    def __init__(self, allowed_tables: frozenset[str], database: str,
                 max_length: int = MAX_SQL_LENGTH) -> None:
        self.allowed_tables = allowed_tables
        self.database = database
        self.max_length = max_length

    # ------------------------------------------------------------------ 主入口

    def check(self, sql: str) -> str:
        """校验通过返回规范化后的 SQL,否则抛 SQLRejected。"""
        if sql is None or not sql.strip():
            raise SQLRejected("SQL 为空")
        sql = sql.strip()
        if len(sql) > self.max_length:
            raise SQLRejected(f"SQL 长度 {len(sql)} 超过上限 {self.max_length}", detail=sql)
        if "\x00" in sql:
            raise SQLRejected("SQL 含有空字节", detail=sql)

        for marker in _COMMENT_MARKERS:
            if marker in sql:
                raise SQLRejected(f"SQL 含有注释标记 {marker!r},只允许无注释的单条查询", detail=sql)

        skeleton = _strip_literals(sql)

        if ";" in skeleton:
            stripped = skeleton.rstrip()
            if stripped.endswith(";"):
                stripped = stripped[:-1]
            if ";" in stripped:
                raise SQLRejected("只允许单条 SQL,检测到多语句(分号分隔)", detail=sql)

        if "@" in skeleton:
            raise SQLRejected("SQL 使用了 @ 变量或 @@ 系统变量,不允许", detail=sql)
        if "\\" in skeleton:
            raise SQLRejected("SQL 含反斜杠转义,不允许", detail=sql)
        if any(ord(ch) > 127 for ch in skeleton):
            raise SQLRejected("SQL 在字面量之外含有非 ASCII 字符", detail=sql)

        _check_identifiers(sql)

        if _OUTFILE_RE.search(skeleton):
            raise SQLRejected("SQL 含 INTO OUTFILE / DUMPFILE,不允许写文件", detail=sql)
        if _USER_VAR_RE.search(skeleton):
            raise SQLRejected("SQL 含用户变量赋值或系统变量,不允许", detail=sql)

        keyword = _KEYWORD_RE.search(skeleton)
        if keyword:
            raise SQLRejected(f"SQL 含被禁止的关键字 {keyword.group(0).upper()!r}", detail=sql)

        function = _FUNCTION_RE.search(skeleton)
        if function:
            raise SQLRejected(f"SQL 调用了被禁止的函数 {function.group(1).lower()!r}", detail=sql)

        if not _SELECT_START_RE.match(skeleton):
            head = skeleton.strip()[:24] or "(空)"
            raise SQLRejected(f"只允许 SELECT 或 WITH 开头的只读查询,当前以 {head!r} 开头", detail=sql)

        self._check_tables(sql, skeleton)
        return sql

    # ------------------------------------------------------------------ 表白名单

    def _check_tables(self, sql: str, skeleton: str) -> None:
        cte_names = {name.lower() for name in _CTE_RE.findall(skeleton)}
        for match in _TABLE_REF_RE.finditer(skeleton):
            raw = match.group(1)
            parts = [p.strip().strip("`") for p in raw.split(".")]
            if len(parts) == 1:
                table = parts[0]
            else:
                schema, table = parts[0], parts[-1]
                if schema.lower() != self.database.lower():
                    raise SQLRejected(
                        f"不允许跨库访问 {schema!r},只能查 {self.database!r}", detail=sql)
            if not _IDENT_RE.match(table):
                raise SQLRejected(f"表名 {table!r} 含非法字符", detail=sql)
            lowered = table.lower()
            if lowered in cte_names:
                continue
            if lowered in {t.lower() for t in self.allowed_tables}:
                continue
            raise SQLRejected(
                f"表 {table!r} 不在白名单内;可用表: {', '.join(sorted(self.allowed_tables))}",
                detail=sql,
            )
